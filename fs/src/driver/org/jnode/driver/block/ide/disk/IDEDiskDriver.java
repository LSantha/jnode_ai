/*
 * $Id$
 *
 * Copyright (C) 2003-2015 JNode.org
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but 
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public 
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation, Inc., 
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
 
package org.jnode.driver.block.ide.disk;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import org.apache.log4j.Logger;
import org.jnode.bootlog.BootLogInstance;
import org.jnode.driver.Bus;
import org.jnode.driver.Device;
import org.jnode.driver.DeviceAlreadyRegisteredException;
import org.jnode.driver.DeviceManager;
import org.jnode.driver.Driver;
import org.jnode.driver.DriverException;
import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.driver.block.BlockDeviceAPIHelper;
import org.jnode.driver.bus.ide.IDEBus;
import org.jnode.driver.bus.ide.IDEConstants;
import org.jnode.driver.bus.ide.IDEDevice;
import org.jnode.driver.bus.ide.IDEDeviceAPI;
import org.jnode.driver.bus.ide.IDEDeviceFactory;
import org.jnode.driver.bus.ide.IDEDriveDescriptor;
import org.jnode.driver.bus.ide.IDEDriverUtils;
import org.jnode.driver.bus.ide.command.IDEFlushCacheCommand;
import org.jnode.driver.bus.ide.command.IDERWSectorsCommand;
import org.jnode.driver.bus.ide.command.IDEReadSectorsCommand;
import org.jnode.driver.bus.ide.command.IDESetMultipleModeCommand;
import org.jnode.driver.bus.ide.command.IDEWriteMultipleFUACommand;
import org.jnode.driver.bus.ide.command.IDEWriteSectorsCommand;
import org.jnode.naming.InitialNaming;
import org.jnode.partitions.ibm.IBMPartitionTable;
import org.jnode.partitions.ibm.IBMPartitionTableEntry;
import org.jnode.util.TimeoutException;
import org.jnode.vm.VmIOContext;

/**
 * Device driver for IDE disks.
 *
 * <h2>Write durability posture</h2>
 *
 * <p>This driver does not guarantee that a completed {@code write(2)} is
 * durable. Data may sit in the device's volatile write cache until a
 * barrier is issued. Durability is guaranteed only at the explicit
 * barriers:</p>
 * <ul>
 *   <li>{@link #flush()} - issues a FLUSH CACHE (or FLUSH CACHE EXT on
 *   48-bit devices) command;</li>
 *   <li>{@code FileHandleImpl.close()} - ends in {@code FSFile.flush()},
 *   which reaches {@code BlockDeviceAPI.flush()} and therefore a FLUSH
 *   CACHE command;</li>
 *   <li>{@code AbstractFileSystem.close()} and {@code FatFileSystem.flush()} -
 *   end in {@code BlockDeviceAPI.flush()};</li>
 *   <li>{@code sync} (org.jnode.fs.command.SyncCommand) - flushes every
 *   mounted filesystem and then calls {@code BlockDeviceAPI.flush()};</li>
 *   <li>{@code stopDevice()} - calls {@link #flush()} before the device is
 *   torn down.</li>
 * </ul>
 *
 * <p>Power loss at any other point may lose data that the filesystem has
 * handed to this driver. The window is exactly "everything since the last
 * barrier".</p>
 *
 * <p>Two properties control the posture (see jnode.properties):</p>
 * <ul>
 *   <li>{@code jnode.ide.writethrough} (default {@code false}): when
 *   {@code true}, every write is followed by a FLUSH CACHE command. This
 *   restores the pre-2026 behaviour (one flush per write) at the cost of
 *   roughly double the command count ("~2x commands, per-write
 *   durable").</li>
 *   <li>{@code jnode.ide.fua} (default {@code false}, opt-in): when
 *   {@code true} and the device declares FUA support (ATA device,
 *   48-bit addressing, IDENTIFY word 84 bit 14 set, word 84 bit 15 clear,
 *   word 84 bit 6 set), writes use WRITE MULTIPLE FUA EXT
 *   (0xCE, PIO), which forces the data to non-volatile media before
 *   command completion is reported. With {@code jnode.ide.writethrough=true}
 *   the per-write FLUSH CACHE is replaced by the FUA command (one queue
 *   turn instead of two). With {@code jnode.ide.writethrough=false} (the
 *   default) the FUA command provides the per-write durability guarantee
 *   without any separate cache flush: one command, durable at completion.
 *   A device that does not declare FUA support, or that rejects the
 *   SET MULTIPLE MODE command used to enable multiple mode, falls back to
 *   plain WRITE followed by FLUSH CACHE. Devices that silently ignore FUA
 *   would otherwise void this contract, so the gate fails closed.</li>
 * </ul>
 *
 * @author epr
 */
public class IDEDiskDriver extends Driver
    implements IDEDeviceAPI<IBMPartitionTableEntry>, IDEConstants {

    /**
     * My logger
     */
    private static final Logger log = Logger.getLogger(IDEDiskDriver.class);

    /**
     * Number of addressable sectors
     */
    private long maxSector;

    /** Has LBA support? */
    //private boolean lba;

    /** Has DMA support? */
    //private boolean dma;

    /**
     * Support 48-bit addressing?
     */
    private boolean is48bit;
    private IDEDiskBus diskBus;
    private IBMPartitionTable pt;
    private boolean writeThrough = false;
    private boolean fuaRequested;
    private boolean fuaEnabled;

    protected void startDevice() throws DriverException {
        final IDEDevice dev = (IDEDevice) getDevice();
        diskBus = new IDEDiskBus(dev);
        /* Register the IDEDevice API */
        dev.registerAPI(IDEDeviceAPI.class,
            new IDEDeviceBlockAlignmentSupport<IBMPartitionTableEntry>(this, SECTOR_SIZE));

        /* Get basic configuration */
        final IDEDriveDescriptor descr = dev.getDescriptor();
        //lba = descr.supportsLBA();
        //dma = descr.supportsDMA();
        is48bit = descr.supports48bitAddressing();
        maxSector = descr.getSectorsAddressable();

        writeThrough = "true".equals(
            VmIOContext.getGlobalProperties().getProperty("jnode.ide.writethrough"));
        fuaRequested = "true".equals(
            VmIOContext.getGlobalProperties().getProperty("jnode.ide.fua"));
        if (fuaRequested) {
            fuaEnabled = enableFua(dev, descr);
        } else {
            fuaEnabled = false;
        }

        // Look for partitions
        try {
            // Find the devicemanager
            DeviceManager devMan = InitialNaming.lookup(DeviceManager.NAME);
            // Read the bootsector
            final byte[] bs = new byte[SECTOR_SIZE];
            read(0, ByteBuffer.wrap(bs));

            IDEDeviceFactory factory;
            try {
                factory = IDEDriverUtils.getIDEDeviceFactory();
            } catch (NamingException ex) {
                throw new DriverException(ex);
            }
            log.debug("Creating partition table object on " + dev.getId());
            this.pt = factory.createIBMPartitionTable(bs, dev);
            log.debug("Created partition table object");

            int partIndex = 0;
            int i = 0;
            for (IBMPartitionTableEntry pte : pt) {
                log.debug("Processing partition " + i);
                if (pte == null) {
                    BootLogInstance.get().warn("PartitionTableEntry #" + i + " is null");
                } else if (pte.isValid()) {
                    log.debug("Partition " + i + " is valid");
                    registerPartition(devMan, dev, pte, partIndex);
                }
                partIndex++;
                i++;
            }
            if (!pt.getExtendedPartitions().isEmpty()) {
                // Create partition devices for the extended partition
                log.debug("Extended");
                partIndex = registerExtendedPartition(devMan, dev, partIndex);
            }
        } catch (DeviceAlreadyRegisteredException ex) {
            log.error("Partition device is already known");
            throw new DriverException("Partition device is already known???? Probably a bug", ex);
        } catch (IOException ex) {
            log.error("Cannot read partition table", ex);
            throw new DriverException("Cannot read partition table", ex);
        } catch (NameNotFoundException ex) {
            log.error("Cannot find DeviceManager", ex);
            throw new DriverException("Cannot find DeviceManager", ex);
        } catch (Throwable ex) {
            log.error("Unknown error", ex);
            throw new DriverException("Unknown error", ex);
        }
    }

    protected void stopDevice() throws DriverException {
        final IDEDevice dev = (IDEDevice) getDevice();
        // find mounted partitions on this device and unregister them !
        try {
            DeviceManager devMan = InitialNaming.lookup(DeviceManager.NAME);
            Collection<Device> devices = devMan.getDevices();
            final ArrayList<IDEDiskPartitionDevice> toStop = new ArrayList<IDEDiskPartitionDevice>();

            for (Device device : devices) {
                if (device instanceof IDEDiskPartitionDevice) {
                    IDEDiskPartitionDevice partition = (IDEDiskPartitionDevice) device;
                    if (partition.getParent() == dev) {
                        toStop.add(partition);
                    }
                }

            }

            for (IDEDiskPartitionDevice partition : toStop) {
                devMan.unregister(partition);
            }
        } catch (NameNotFoundException e) {
            throw new DriverException("Problem while stopping this IDE device", e);
        }

        try {
            flush();
        } catch (IOException e) {
            log.warn("Failed to flush device during stop", e);
        }

        dev.unregisterAPI(BlockDeviceAPI.class);
        this.pt = null;
    }

    /**
     * Resolve the opt-in FUA request against what the device actually
     * declares. Never assumes: when the device does not declare FUA
     * support, or rejects the SET MULTIPLE MODE command that multiple
     * mode requires, FUA is disabled and the caller falls back to a
     * plain FLUSH CACHE barrier.
     */
    private boolean enableFua(final IDEDevice dev, IDEDriveDescriptor descr) {
        if (!is48bit) {
            log.warn("jnode.ide.fua=true, but the device has no 48-bit addressing; "
                + "falling back to FLUSH CACHE");
            return false;
        }
        if (!descr.supportsFUA()) {
            log.warn("jnode.ide.fua=true, but the device does not declare FUA support "
                + "(IDENTIFY word 84 bit 6); falling back to FLUSH CACHE");
            return false;
        }
        if (descr.getMultipleSectorsPerBlock() != 0) {
            if (log.isDebugEnabled()) {
                log.debug("FUA enabled; device is already in multiple mode ("
                    + descr.getMultipleSectorsPerBlock() + " sectors per block)");
            }
            return true;
        }
        final IDEBus bus = (IDEBus) dev.getBus();
        IDESetMultipleModeCommand cmd =
            new IDESetMultipleModeCommand(dev.isPrimary(), dev.isMaster(), 1);
        try {
            bus.executeAndWait(cmd, IDE_DATA_XFER_TIMEOUT);
            if (cmd.hasError()) {
                log.warn("jnode.ide.fua=true, but SET MULTIPLE MODE was rejected (error="
                    + cmd.getError() + "); falling back to FLUSH CACHE");
                return false;
            }
            if (log.isDebugEnabled()) {
                log.debug("FUA enabled; multiple mode set to 1 sector per block");
            }
            return true;
        } catch (InterruptedException ex) {
            log.warn("jnode.ide.fua=true, but SET MULTIPLE MODE was interrupted; "
                + "falling back to FLUSH CACHE", ex);
            return false;
        } catch (TimeoutException ex) {
            log.warn("jnode.ide.fua=true, but SET MULTIPLE MODE timed out; "
                + "falling back to FLUSH CACHE", ex);
            return false;
        }
    }

    public void flush() throws IOException {
        final IDEDevice dev = (IDEDevice) getDevice();
        final IDEBus bus = (IDEBus) dev.getBus();
        IDEFlushCacheCommand cmd = new IDEFlushCacheCommand(
            dev.isPrimary(), dev.isMaster(), is48bit);
        try {
            bus.executeAndWait(cmd, IDE_DATA_XFER_TIMEOUT);
            if (cmd.hasError()) {
                throw new IOException("IDE flush error:" + cmd.getError());
            }
        } catch (InterruptedException ex) {
            throw new IOException("IDE flush interrupted", ex);
        } catch (org.jnode.util.TimeoutException ex) {
            throw new InterruptedIOException("IDE flush timeout: " + ex.getMessage());
        }
    }

    public long getLength() {
        return maxSector * SECTOR_SIZE;
    }

    public void read(long devOffset, ByteBuffer destBuf) throws IOException {
        transfer(devOffset, destBuf, false);
    }

    public void write(long devOffset, ByteBuffer srcBuf) throws IOException {
        transfer(devOffset, srcBuf, true);
    }

    protected void transfer(long devOffset, ByteBuffer buf, boolean isWrite) throws IOException {
//        int bufOffset = 0;
        int length = buf.remaining();
        if (length < SECTOR_SIZE && log.isDebugEnabled()) {
            log.debug("Transfer length=" + length + (isWrite ? " Wr " : " Rd "));
        }

        BlockDeviceAPIHelper.checkBounds(this, devOffset, length);
        BlockDeviceAPIHelper.checkAlignment(SECTOR_SIZE, this, devOffset, length);

        final long lbaStart = devOffset / SECTOR_SIZE;
        final int sectors = length / SECTOR_SIZE;

        final String errorSource = isWrite ? "write" : "read";
        if (lbaStart + sectors > this.maxSector) {
            throw new IOException(errorSource + " beyond device sectors");
        }

        final IDEDevice dev = (IDEDevice) getDevice();
        final IDEBus bus = (IDEBus) dev.getBus();
        final int maxSectorCount = is48bit ? MAX_SECTOR_COUNT_48 : MAX_SECTOR_COUNT_28;
        final boolean primary = dev.isPrimary();
        final boolean master = dev.isMaster();
        final boolean fuaWrite = isWrite && fuaEnabled;

        while (length > 0) {
            final long partLbaStart = devOffset / SECTOR_SIZE;
            final int partSectorCount = Math.min(length / SECTOR_SIZE, maxSectorCount);
            final int partLength = partSectorCount * SECTOR_SIZE;

            final IDERWSectorsCommand cmd = isWrite ?
                (fuaWrite ?
                    new IDEWriteMultipleFUACommand(primary, master, partLbaStart, partSectorCount, buf) :
                    new IDEWriteSectorsCommand(primary, master, is48bit, partLbaStart, partSectorCount, buf)) :
                new IDEReadSectorsCommand(primary, master, is48bit, partLbaStart, partSectorCount, buf);
            try {
                if (log.isDebugEnabled()) {
                    log.debug("bus.exAndWt" + (isWrite ? "W" : "R") + " dev=" + dev.getId() + " start=" + partLbaStart +
                        " sectors=" + partSectorCount + " len=" + partLength);
                }
                bus.executeAndWait(cmd, IDE_DATA_XFER_TIMEOUT);
            } catch (InterruptedException ex) {
                throw new IOException("IDE " + errorSource + " interrupted", ex);
            } catch (TimeoutException ex) {
                throw new InterruptedIOException("IDE timeout: " + ex.getMessage());
            }
            if (cmd.hasError()) {
                final int failed = cmd.getFailedSector();
                final String where = (failed >= 0)
                    ? (" at lba " + (partLbaStart + failed)) : "";
                throw new IOException("IDE " + errorSource + " error:" + cmd.getError() + where);
            }

            length -= partLength;
            devOffset += partLength;
        }

        // FUA writes land on non-volatile media before command completion,
        // which makes a separate FLUSH CACHE redundant. A requested but
        // unavailable FUA still gets the flush, so durability never fails
        // open.
        if (isWrite && !fuaWrite && (writeThrough || fuaRequested)) {
            flush();
        }
    }

    static class IDEDiskBus extends Bus {

        public IDEDiskBus(IDEDevice parent) {
            super(parent);
        }
    }

    /*
     * Register the given partition entry (maybe an extended partition entry)
     */
    private void registerPartition(DeviceManager devMan, IDEDevice dev,
                                   IBMPartitionTableEntry pte, int partIndex)
        throws DeviceAlreadyRegisteredException, DriverException {
        final String id = dev.getId() + partIndex;
        final IDEDiskPartitionDevice pdev =
            new IDEDiskPartitionDevice(
                diskBus,
                id,
                dev,
                pte,
                pte.getStartLba(),
                pte.getNrSectors());
        pdev.setDriver(new IDEDiskPartitionDriver());
        devMan.register(pdev);
    }

    /**
     * register all the partitions included in the extended partition
     *
     * @param devMan
     * @param dev
     * @param partIndex the first partition index to use
     * @return the next partition index
     * @throws DeviceAlreadyRegisteredException
     *
     * @throws DriverException
     */
    private int registerExtendedPartition(DeviceManager devMan, IDEDevice dev, int partIndex)
        throws DeviceAlreadyRegisteredException, DriverException {
        //now we should have an filled vector in the pt
        final List<IBMPartitionTableEntry> extendedPartitions = pt.getExtendedPartitions();
        log.info("Have " + extendedPartitions.size() + " Extended partitions found");

        for (int iPart = 0; iPart < extendedPartitions.size(); iPart++) {
            IBMPartitionTableEntry pteExt = extendedPartitions.get(iPart);
            registerPartition(devMan, dev, pteExt, partIndex);

            if (iPart < (extendedPartitions.size() - 1)) {
                partIndex++;
            }
        }
        return partIndex;
    }

    public int getSectorSize() throws IOException {
        return SECTOR_SIZE;
    }

    /**
     * Gets the partition table that this block device contains.
     *
     * @return {@code null} if no partition table is found.
     * @throws IOException
     */
    public IBMPartitionTable getPartitionTable() throws IOException {
        return pt;
    }
}
