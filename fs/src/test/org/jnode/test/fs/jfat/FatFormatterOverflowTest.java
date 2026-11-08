/*
 * $Id$
 *
 * Copyright (C) 2003-2015 JNode.org
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
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

package org.jnode.test.fs.jfat;

import org.jnode.fs.jfat.FatFormatter;
import org.jnode.fs.jfat.FatUtils;

public class FatFormatterOverflowTest {

    @org.junit.Test
    public void testGetDiskSizeDoesNotWrap() {
        org.junit.Assert.assertEquals(1099511627264L,
                FatFormatter.getDiskSize(Integer.MAX_VALUE, 512));
        org.junit.Assert.assertEquals(1048576L, FatFormatter.getDiskSize(2048, 512));
        org.junit.Assert.assertEquals(0L, FatFormatter.getDiskSize(0, 512));
    }

    @org.junit.Test
    public void testGetDriveSerialNumberUsesUntruncatedEpoch() {
        long before = System.currentTimeMillis();
        int serial = FatFormatter.getDriveSerialNumber();
        long after = System.currentTimeMillis();
        boolean found = false;
        for (long t = before; t <= after && !found; t++) {
            if (expectedSerial(t) == serial) {
                found = true;
            }
        }
        org.junit.Assert.assertTrue(
                "serial " + serial + " not derived from epoch in [" + before + "," + after + "]",
                found);
    }

    private int expectedSerial(long time) {
        int year = FatUtils.getYear(time);
        int month = FatUtils.getMonth(time);
        int day = FatUtils.getDay(time);
        int hour = FatUtils.getHours(time);
        int minute = FatUtils.getMinutes(time);
        int second = FatUtils.getSeconds(time);
        int milliSecond = (int) time;
        int low = day + (month << 8);
        int tmp = (milliSecond / 10) + (second << 8);
        low += tmp;
        int high = minute + (hour << 8);
        high += year;
        return low + (high << 16);
    }
}
