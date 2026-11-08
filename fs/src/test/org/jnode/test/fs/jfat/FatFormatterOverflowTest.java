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

import java.util.Calendar;

import org.jnode.fs.jfat.FatFormatter;
import org.jnode.fs.jfat.FatUtils;
import org.junit.Test;

import static org.junit.Assert.*;

public class FatFormatterOverflowTest {

    private static int expectedSerial(long time) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(time);
        int year = cal.get(Calendar.YEAR) - 1980;
        int month = cal.get(Calendar.MONTH);
        // Note: day mirrors FatUtils.getDay (day-of-week, not day-of-month).
        int day = cal.get(Calendar.DAY_OF_WEEK) - 1;
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int second = cal.get(Calendar.SECOND);
        int milliSecond = cal.get(Calendar.MILLISECOND);

        int low = day + (month << 8);
        int tmp = (milliSecond / 10) + (second << 8);
        low += tmp;

        int high = minute + (hour << 8);
        high += year;

        return low + (high << 16);
    }

    private static int expectedMilliSeconds(long time) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(time);
        return cal.get(Calendar.MILLISECOND);
    }

    @Test
    public void testGetDiskSizeDoesNotWrap() {
        assertEquals(1099511627264L, FatFormatter.getDiskSize(Integer.MAX_VALUE, 512));
        assertEquals(1048576L, FatFormatter.getDiskSize(2048, 512));
        assertEquals(0L, FatFormatter.getDiskSize(0, 512));
    }

    @Test
    public void testGetMilliSecondsDerivesFromArgument() {
        long[] times = new long[]{0L, 1L, 999L, 1000L, 1234567890123L, 3000000000L,
            315532800000L, 4102444800000L};
        for (int i = 0; i < times.length; i++) {
            assertEquals("getMilliSeconds(" + times[i] + ")",
                expectedMilliSeconds(times[i]), FatUtils.getMilliSeconds(times[i]));
        }
    }

    @Test
    public void testGetMilliSecondsIsDeterministic() {
        long past = 315532800123L;
        long future = 410244480456L;
        assertEquals(FatUtils.getMilliSeconds(past), FatUtils.getMilliSeconds(past));
        assertEquals(123L, FatUtils.getMilliSeconds(past) % 1000L);
        assertTrue(FatUtils.getMilliSeconds(past) != FatUtils.getMilliSeconds(future));
    }

    @Test
    public void testGetDriveSerialNumberUsesUntruncatedEpoch() {
        long[] times = new long[]{0L, 1L, 999L, 1000L, 1234567890123L, 3000000000L,
            315532800000L, 4102444800000L, 2147483647L, 2147483648L, 4294967295L, 4294967296L};
        for (int i = 0; i < times.length; i++) {
            assertEquals("computeDriveSerialNumber(" + times[i] + ")",
                expectedSerial(times[i]), FatFormatter.computeDriveSerialNumber(times[i]));
        }
    }

    @Test
    public void testComputeDriveSerialNumberIsDeterministic() {
        long time = 1234567890456L;
        int first = FatFormatter.computeDriveSerialNumber(time);
        int second = FatFormatter.computeDriveSerialNumber(time);
        assertEquals(first, second);
        assertTrue(first != FatFormatter.computeDriveSerialNumber(time + 1000L));
    }
}
