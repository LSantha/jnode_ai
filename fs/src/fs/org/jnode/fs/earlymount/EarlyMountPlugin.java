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
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.jnode.fs.earlymount;

import java.io.IOException;
import java.util.Map;

import javax.naming.NameNotFoundException;

import org.apache.log4j.Logger;
import org.jnode.driver.Device;
import org.jnode.driver.DeviceManager;
import org.jnode.driver.DeviceNotFoundException;
import org.jnode.driver.DeviceUtils;
import org.jnode.fs.FileSystem;
import org.jnode.fs.FileSystemException;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.fs.service.def.EarlyMountClaims;
import org.jnode.java.io.VMFileSystemAPI;
import org.jnode.naming.InitialNaming;
import org.jnode.plugin.Plugin;
import org.jnode.plugin.PluginDescriptor;
import org.jnode.plugin.PluginException;

/**
 * Mounts one particular filesystem at a stable mount point, early in the boot
 * process.
 *
 * <p>
 * The device to mount is identified by its deterministic device id (for IDE
 * disks "hd" + (a|b|c|d) by channel and master/slave, plus the 0-based MBR
 * partition slot, e.g. {@code hda0}). The plugin deliberately does not rely on
 * the auto-mounting done by {@code FileSystemMounter}: that one only fires
 * from the {@code deviceStarted} listener, without replay or retry, so whether
 * a partition ends up mounted is timing dependent. Instead this plugin runs
 * synchronously from {@code startPlugin()} and mounts the filesystem itself
 * before it returns, so the mount is guaranteed to be in place before the
 * shell starts (and thus before {@code jnode.ini} runs).
 *
 * <p>
 * Device discovery is asynchronous: requiring {@code org.jnode.driver.finder}
 * only guarantees the finder plugin has run, not that devices exist: the PCI
 * scan itself runs on a WorkManager worker thread. This plugin therefore polls
 * for the device, with a bounded timeout (default 30 s, override with the
 * system property {@code jnode.earlymount.timeout} in seconds), instead of
 * assuming it is there. Blocking inside {@code startPlugin()} is safe: the
 * worker threads continue the scan while the boot thread waits.
 *
 * <p>
 * If the partition is already mounted by the {@code FileSystemMounter}, the
 * existing filesystem instance is reused and mounted at the configured mount
 * point as well. If a different driver (the legacy fat driver) claimed a
 * FAT12/16 partition, that instance is closed and replaced by a jfat
 * instance, so the result is deterministic.
 *
 * <p>
 * Interaction with {@code FileSystemMounter}: to make this plugin's mount
 * the first and only attempt, it claims the device id
 * ({@code EarlyMountClaims}) before the device can appear. The mounter
 * checks the claim and skips claimed devices, so its own attempt (which is
 * driven by the {@code deviceStarted} event on a worker thread and would
 * otherwise always run first) never fires for a claimed device. The claim is
 * released once this plugin has mounted or given up. When the filesystem
 * ends up mounted at two paths (for example both {@code /devices/hda0} and
 * the configured mount point), it is one instance: unmounting either path
 * closes it for both.
 *
 * <p>
 * Configuration (no configuration = no-op):
 * <ul>
 * <li>Kernel command line token: {@code earlymount=<device>[:<mountpoint>]}
 * (e.g. {@code earlymount=hda0:/data})</li>
 * <li>System property {@code jnode.earlymount.device} (overrides the
 * command line)</li>
 * <li>System property {@code jnode.earlymount.mountpoint} (overrides the
 * command line; defaults to {@code /mnt/<device>})</li>
 * <li>System property {@code jnode.earlymount.readonly} (default
 * {@code false})</li>
 * <li>System property {@code jnode.earlymount.required} (default
 * {@code false}; if {@code true}, a missing device or a failed mount fails
 * the plugin start, which is visible as an error in the boot log)</li>
 * </ul>
 *
 * @author JNode.org
 */
public class EarlyMountPlugin extends Plugin {

    /** My logger */
    private static final Logger log = Logger.getLogger(EarlyMountPlugin.class);

    /** Kernel command line token: earlymount=<device>[:<mountpoint>] */
    private static final String CMDLINE_KEY = "earlymount=";

    /** System property: device id (overrides the command line) */
    private static final String PROP_DEVICE = "jnode.earlymount.device";

    /** System property: mount point (overrides the command line) */
    private static final String PROP_MOUNT_POINT = "jnode.earlymount.mountpoint";

    /** System property: if true, a failure fails the plugin start */
    private static final String PROP_REQUIRED = "jnode.earlymount.required";

    /** System property: if true, mount read-only */
    private static final String PROP_READONLY = "jnode.earlymount.readonly";

    /**
     * System property: how long to wait for the device to appear, in seconds.
     * Device discovery is asynchronous: the finder plugin only starts the PCI
     * scan, which then runs on a WorkManager worker thread, so the device may
     * not exist yet when this plugin starts.
     */
    private static final String PROP_TIMEOUT = "jnode.earlymount.timeout";

    /**
     * Default wait for the device, in seconds.
     *
     * <p>
     * The device is produced by the asynchronous PCI scan, which has been
     * running since the finder plugin started seconds before this plugin:
     * in practice the device appears within ~1-2 s of the wait starting
     * (both under QEMU TCG and VBox). 30 s covers even a slow scan with a
     * large margin; when the device is genuinely absent the boot only stalls
     * for this long before the plugin gives up.
     */
    private static final long DEFAULT_TIMEOUT_SECS = 30;

    /** Poll interval while waiting for the device, in milliseconds */
    private static final long POLL_INTERVAL_MS = 100;

    /** The name of the filesystem type this plugin forces */
    private static final String TYPE_JFAT = "JFAT";

    /**
     * Create a new instance
     */
    public EarlyMountPlugin(PluginDescriptor descriptor) {
        super(descriptor);
    }

    /**
     * @see org.jnode.plugin.Plugin#startPlugin()
     */
    @Override
    protected void startPlugin() throws PluginException {
        final Config cfg = Config.parse();
        if (cfg == null) {
            // Not configured: do nothing.
            return;
        }
        final boolean required = Boolean.parseBoolean(System.getProperty(PROP_REQUIRED));
        /*
         * Claim the device before the FileSystemMounter's deviceStarted
         * listener can attempt a mount of its own: the claim is registered
         * while the device does not even exist yet (the asynchronous PCI
         * scan takes seconds to produce it), and released again once this
         * plugin has mounted or given up.
         */
        EarlyMountClaims.claim(cfg.deviceId);
        try {
            doMount(cfg);
        } catch (DeviceNotFoundException ex) {
            // The device did not appear within the timeout. Note that device
            // discovery is asynchronous (the PCI scan runs on a WorkManager
            // worker thread), so this plugin polls for the device.
            fail(required, "device " + cfg.deviceId + " not found", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("EarlyMount: interrupted while waiting for " + cfg.deviceId);
        } catch (NameNotFoundException ex) {
            fail(required, "cannot look up a required service", ex);
        } catch (FileSystemException ex) {
            fail(required, "cannot create the " + TYPE_JFAT + " filesystem on "
                + cfg.deviceId, ex);
        } catch (IOException ex) {
            fail(required, "cannot mount " + cfg.deviceId + " on " + cfg.mountPoint, ex);
        } finally {
            /*
             * Release the claim: once this plugin has mounted or given up,
             * later device starts must fall back to the normal FileSystemMounter
             * behaviour (it will still skip the device while it is mounted here,
             * via the registered filesystem).
             */
            EarlyMountClaims.release(cfg.deviceId);
        }
    }

    /**
     * @see org.jnode.plugin.Plugin#stopPlugin()
     */
    @Override
    protected void stopPlugin() {
        // The mount stays active; unmounting on plugin stop would break
        // everything that started using the filesystem meanwhile.
    }

    /**
     * Mount the configured device at the configured mount point.
     */
    private void doMount(Config cfg)
        throws NameNotFoundException, DeviceNotFoundException, InterruptedException,
        FileSystemException, IOException {
        final DeviceManager dm = DeviceUtils.getDeviceManager();

        /*
         * Device discovery is asynchronous: the finder plugin only kicks off
         * the PCI scan, which runs on a WorkManager worker thread. Poll for
         * the device instead of assuming it exists; blocking here is safe and
         * keeps the happens-before: this plugin's startPlugin does not return
         * until the filesystem is mounted, so the shell (and jnode.ini) always
         * see it.
         */
        final long timeoutMs = getTimeoutMs();
        final long deadline = System.currentTimeMillis() + timeoutMs;
        Device dev = null;
        boolean firstAttempt = true;
        while (true) {
            try {
                dev = dm.getDevice(cfg.deviceId);
                break;
            } catch (DeviceNotFoundException ex) {
                if (firstAttempt) {
                    log.info("EarlyMount: waiting for device " + cfg.deviceId + " ...");
                    firstAttempt = false;
                }
                if (System.currentTimeMillis() >= deadline) {
                    throw ex;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
        }
        if (!firstAttempt) {
            log.info("EarlyMount: device " + cfg.deviceId + " appeared");
        }

        final FileSystemService fss = InitialNaming.lookup(FileSystemService.NAME);
        /*
         * The device object is the same monitor that
         * FileSystemMounter.tryToMount uses, so the
         * check-create-register-mount sequence here and the auto-mounter's
         * attempt can never interleave: exactly one filesystem instance is
         * created per device, no matter who gets there first.
         */
        synchronized (dev) {
            FileSystem<?> fs = fss.getFileSystem(dev);
            final String mountedAt = findMountPoint(fss, dev);

            if ((fs != null) && !TYPE_JFAT.equals(fs.getType().getName())) {
                // Another driver (the legacy fat driver) claimed this partition.
                // Close it and replace it with a jfat instance.
                if (mountedAt != null) {
                    try {
                        fss.unmount(mountedAt);
                    } catch (IllegalArgumentException ex) {
                        // Not a mount point; ignore.
                    } catch (IOException ex) {
                        log.warn("EarlyMount: cannot unmount " + mountedAt, ex);
                    }
                }
                fss.unregisterFileSystem(dev);
                fs = null;
            }

            if (fs == null) {
                final FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.class);
                fs = type.create(dev, cfg.readOnly);
                fss.registerFileSystem(fs);
            }

            if (cfg.mountPoint.equals(mountedAt)) {
                log.info("EarlyMount: " + cfg.deviceId + " already mounted at " + cfg.mountPoint);
                return;
            }

            mkdirs(fss.getApi(), getParentPath(cfg.mountPoint));
            fss.mount(cfg.mountPoint, fs, null);
            log.info("EarlyMount: mounted " + fs.getType().getName() + " (device " + cfg.deviceId
                + ") on " + cfg.mountPoint);
        }
    }

    /**
     * Get the path the given device is currently mounted at, or null.
     */
    private static String findMountPoint(FileSystemService fss, Device dev) {
        final Map<String, String> mounts = fss.getDeviceMountPoints();
        return mounts.get(dev.getId());
    }

    /**
     * Report a failure: fail the plugin start if required, otherwise log.
     */
    private static void fail(boolean required, String message, Exception ex)
        throws PluginException {
        if (required) {
            throw new PluginException("EarlyMount: " + message, ex);
        }
        log.error("EarlyMount: " + message + " (skipped)", ex);
    }

    /**
     * Create the given directory and all its missing parents.
     */
    private static void mkdirs(VMFileSystemAPI api, String dir) throws IOException {
        if ((dir == null) || (dir.length() == 0) || "/".equals(dir)) {
            return;
        }
        final StringBuilder path = new StringBuilder();
        final String[] parts = dir.split("/");
        for (String part : parts) {
            if (part.length() == 0) {
                continue;
            }
            path.append('/').append(part);
            // Returns false if it already exists, which is fine.
            api.mkDir(path.toString());
        }
    }

    /**
     * Get the parent path of the given absolute path.
     */
    private static String getParentPath(String path) {
        final int idx = path.lastIndexOf('/');
        return (idx <= 0) ? "/" : path.substring(0, idx);
    }

    /**
     * Get the value of the given token from the kernel command line, or null.
     */
    private static String cmdLineValue(String key) {
        final String cmdLine = System.getProperty("jnode.cmdline");
        if (cmdLine == null) {
            return null;
        }
        final String[] tokens = cmdLine.split("\\s+");
        for (String token : tokens) {
            if ((token.length() > key.length()) && token.startsWith(key)) {
                return token.substring(key.length());
            }
        }
        return null;
    }

    /**
     * Get the number of milliseconds to wait for the device to appear.
     */
    private static long getTimeoutMs() {
        final String value = System.getProperty(PROP_TIMEOUT);
        if (value != null) {
            try {
                final long secs = Long.parseLong(value);
                if (secs > 0) {
                    return secs * 1000L;
                }
            } catch (NumberFormatException ex) {
                log.warn("EarlyMount: invalid " + PROP_TIMEOUT + " '" + value
                    + "', using default", ex);
            }
        }
        return DEFAULT_TIMEOUT_SECS * 1000L;
    }

    /**
     * The configuration of this plugin.
     */
    private static final class Config {

        /** The device id to mount */
        final String deviceId;

        /** The mount point */
        final String mountPoint;

        /** Mount read-only? */
        final boolean readOnly;

        Config(String deviceId, String mountPoint, boolean readOnly) {
            this.deviceId = deviceId;
            this.mountPoint = mountPoint;
            this.readOnly = readOnly;
        }

        /**
         * Parse the configuration from the system properties and the kernel
         * command line.
         *
         * @return the configuration, or null if this plugin is not configured.
         */
        static Config parse() {
            String device = System.getProperty(PROP_DEVICE);
            String mountPoint = System.getProperty(PROP_MOUNT_POINT);

            if (device == null) {
                final String spec = cmdLineValue(CMDLINE_KEY);
                if (spec != null) {
                    final int idx = spec.indexOf(':');
                    if (idx >= 0) {
                        device = spec.substring(0, idx);
                        if (mountPoint == null) {
                            mountPoint = spec.substring(idx + 1);
                        }
                    } else {
                        device = spec;
                    }
                }
            }

            if ((device == null) || (device.length() == 0)) {
                return null;
            }
            if ((mountPoint == null) || (mountPoint.length() == 0)) {
                mountPoint = "/mnt/" + device;
            }
            mountPoint = normalize(mountPoint);

            final boolean readOnly = Boolean.parseBoolean(System.getProperty(PROP_READONLY));
            return new Config(device, mountPoint, readOnly);
        }

        /**
         * Make the given path absolute, without a trailing slash.
         */
        private static String normalize(String path) {
            String p = path;
            if (!p.startsWith("/")) {
                p = "/" + p;
            }
            while ((p.length() > 1) && p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            return p;
        }
    }
}