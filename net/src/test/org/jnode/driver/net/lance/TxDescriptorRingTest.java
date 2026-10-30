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
 
package org.jnode.driver.net.lance;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.jnode.net.SocketBuffer;
import org.jnode.system.resource.MemoryResource;
import org.junit.Assert;
import org.junit.Test;
import org.vmmagic.unboxed.Address;

/**
 * Tests the ownership handling of the LANCE transmit descriptor ring.
 *
 * @author JNode.org
 */
public class TxDescriptorRingTest {

    private static final int RING_OFFSET = 0x100;
    private static final int DATA_OFFSET = 0x2000;
    private static final int RING_LENGTH = 16;
    private static final int BASE_ADDRESS = 0x10000;
    private static final int FRAME_SIZE = 4;

    private static int statusOffset(int index) {
        return RING_OFFSET + (index * Descriptor.MESSAGE_DESCRIPTOR_SIZE) + Descriptor.STATUS;
    }

    private static int bufferOffset(int index) {
        return DATA_OFFSET + (index * BufferManager.DATA_BUFFER_SIZE);
    }

    /**
     * Marks a descriptor as owned by the chip (OWN bit set in the status word).
     */
    private static void setChipOwned(byte[] mem, int index) {
        final int ptr = statusOffset(index);
        mem[ptr] |= (byte) (Descriptor.STATUS_OWN >> 8);
    }

    /**
     * Gives a descriptor back to the host (OWN bit cleared in the status word).
     */
    private static void setHostOwned(byte[] mem, int index) {
        final int ptr = statusOffset(index);
        mem[ptr] &= (byte) ~(Descriptor.STATUS_OWN >> 8);
    }

    private static short getStatus(byte[] mem, int index) {
        final int ptr = statusOffset(index);
        return (short) (((mem[ptr] & 0xFF) << 8) | (mem[ptr + 1] & 0xFF));
    }

    private static boolean isChipOwned(byte[] mem, int index) {
        return (getStatus(mem, index) & Descriptor.STATUS_OWN) != 0;
    }

    /**
     * Checks if a descriptor has been handed a complete frame.
     */
    private static boolean holdsFrame(byte[] mem, int index) {
        final short status = getStatus(mem, index);
        return (status & Descriptor.STATUS_STP) != 0 && (status & Descriptor.STATUS_ENP) != 0;
    }

    /**
     * Returns the frame stored in the data buffer of a descriptor.
     */
    private static byte[] getFrame(byte[] mem, int index) {
        final byte[] frame = new byte[FRAME_SIZE];
        System.arraycopy(mem, bufferOffset(index), frame, 0, FRAME_SIZE);
        return frame;
    }

    /**
     * Finds the descriptor that holds the frame created with the given marker.
     *
     * @return the descriptor index, or -1 if the frame is not in the ring.
     */
    private static int findFrame(byte[] mem, byte marker) {
        for (int i = 0; i < RING_LENGTH; i++) {
            if (holdsFrame(mem, i) && getFrame(mem, i)[0] == marker) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Creates a MemoryResource backed by a byte array.
     */
    private static MemoryResource createMemoryResource(final byte[] mem) {
        return (MemoryResource) Proxy.newProxyInstance(
            TxDescriptorRingTest.class.getClassLoader(),
            new Class[]{MemoryResource.class}, new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    final String name = method.getName();
                    if (name.equals("getAddress")) {
                        return Address.fromInt(BASE_ADDRESS);
                    }
                    if (name.equals("getByte")) {
                        return Byte.valueOf(mem[((Integer) args[0]).intValue()]);
                    }
                    if (name.equals("setByte")) {
                        mem[((Integer) args[0]).intValue()] = ((Byte) args[1]).byteValue();
                        return null;
                    }
                    if (name.equals("getShort")) {
                        final int ptr = ((Integer) args[0]).intValue();
                        return Short.valueOf(
                            (short) (((mem[ptr] & 0xFF) << 8) | (mem[ptr + 1] & 0xFF)));
                    }
                    if (name.equals("setShort")) {
                        final int ptr = ((Integer) args[0]).intValue();
                        final short value = ((Short) args[1]).shortValue();
                        mem[ptr] = (byte) (value >> 8);
                        mem[ptr + 1] = (byte) value;
                        return null;
                    }
                    if (name.equals("getInt")) {
                        final int ptr = ((Integer) args[0]).intValue();
                        return Integer.valueOf(((mem[ptr] & 0xFF) << 24) |
                            ((mem[ptr + 1] & 0xFF) << 16) | ((mem[ptr + 2] & 0xFF) << 8) |
                            (mem[ptr + 3] & 0xFF));
                    }
                    if (name.equals("setInt")) {
                        final int ptr = ((Integer) args[0]).intValue();
                        final int value = ((Integer) args[1]).intValue();
                        mem[ptr] = (byte) (value >> 24);
                        mem[ptr + 1] = (byte) (value >> 16);
                        mem[ptr + 2] = (byte) (value >> 8);
                        mem[ptr + 3] = (byte) value;
                        return null;
                    }
                    if (name.equals("getBytes")) {
                        System.arraycopy(mem, ((Integer) args[0]).intValue(), (byte[]) args[1],
                            ((Integer) args[2]).intValue(), ((Integer) args[3]).intValue());
                        return null;
                    }
                    if (name.equals("setBytes")) {
                        System.arraycopy((byte[]) args[0], ((Integer) args[1]).intValue(), mem,
                            ((Integer) args[2]).intValue(), ((Integer) args[3]).intValue());
                        return null;
                    }
                    if (name.equals("toString")) {
                        return "TestMemoryResource";
                    }
                    return null;
                }
            });
    }

    private static SocketBuffer createFrame(byte marker) {
        final byte[] data = new byte[FRAME_SIZE];
        data[0] = marker;
        data[1] = (byte) ~marker;
        data[2] = marker;
        data[3] = (byte) ~marker;
        return new SocketBuffer(data, 0, data.length);
    }

    private static byte[] createMemory() {
        return new byte[DATA_OFFSET + (RING_LENGTH * BufferManager.DATA_BUFFER_SIZE)];
    }

    private static TxDescriptorRing createRing(byte[] mem) {
        return new TxDescriptorRing(createMemoryResource(mem), RING_OFFSET, RING_LENGTH,
            DATA_OFFSET);
    }

    @Test
    public void testFreshRingIsOwnedByHost() {
        final byte[] mem = createMemory();
        final TxDescriptorRing ring = createRing(mem);
        Assert.assertEquals(RING_LENGTH, ring.getLength());
        for (int i = 0; i < RING_LENGTH; i++) {
            Assert.assertFalse("descriptor " + i + " must start out owned by the host",
                isChipOwned(mem, i));
        }
    }

    @Test
    public void testTransmitUsesFirstDescriptor() {
        final byte[] mem = createMemory();
        final TxDescriptorRing ring = createRing(mem);

        ring.transmit(createFrame((byte) 0x42));

        Assert.assertEquals(0, findFrame(mem, (byte) 0x42));
        Assert.assertTrue("the used descriptor belongs to the chip", isChipOwned(mem, 0));
    }

    @Test
    public void testTransmitSkipsChipOwnedDescriptors() {
        final byte[] mem = createMemory();
        final TxDescriptorRing ring = createRing(mem);

        for (int i = 0; i < 5; i++) {
            setChipOwned(mem, i);
        }

        ring.transmit(createFrame((byte) 0x11));

        Assert.assertEquals("the first host owned descriptor must be used", 5,
            findFrame(mem, (byte) 0x11));
        for (int i = 0; i < 5; i++) {
            Assert.assertFalse("descriptor " + i + " is owned by the chip and must be untouched",
                holdsFrame(mem, i));
        }
    }

    @Test
    public void testTransmitUsesEveryDescriptorInTurn() {
        final byte[] mem = createMemory();
        final TxDescriptorRing ring = createRing(mem);

        for (int i = 0; i < RING_LENGTH; i++) {
            ring.transmit(createFrame((byte) i));
            Assert.assertEquals("frame " + i + " must use descriptor " + i, i,
                findFrame(mem, (byte) i));
        }
        for (int i = 0; i < RING_LENGTH; i++) {
            Assert.assertTrue("descriptor " + i + " must be owned by the chip",
                isChipOwned(mem, i));
        }
    }

    @Test
    public void testTransmitWrapsAroundTheRing() {
        final byte[] mem = createMemory();
        final TxDescriptorRing ring = createRing(mem);

        for (int i = 0; i < RING_LENGTH; i++) {
            ring.transmit(createFrame((byte) i));
        }
        for (int i = 0; i < RING_LENGTH; i++) {
            setHostOwned(mem, i);
        }

        ring.transmit(createFrame((byte) 0x7F));

        Assert.assertEquals("the ring pointer must wrap to the first descriptor", 0,
            findFrame(mem, (byte) 0x7F));
    }

    @Test
    public void testTransmitDropsFrameWhenRingIsFull() {
        final byte[] mem = createMemory();
        final TxDescriptorRing ring = createRing(mem);

        for (int i = 0; i < RING_LENGTH; i++) {
            setChipOwned(mem, i);
        }

        ring.transmit(createFrame((byte) 0x33));

        Assert.assertEquals("no descriptor is available", -1, findFrame(mem, (byte) 0x33));

        setHostOwned(mem, 3);

        ring.transmit(createFrame((byte) 0x44));

        Assert.assertEquals("transmission must resume on a returned descriptor", 3,
            findFrame(mem, (byte) 0x44));
    }
}