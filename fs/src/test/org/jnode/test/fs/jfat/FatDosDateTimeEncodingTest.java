/*
 * $Id$
 *
 * Copyright (C) 2003-2026 JNode.org
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

package org.jnode.test.fs.jfat;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import org.jnode.fs.jfat.FatUtils;
import org.jnode.util.TimeUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Spec vectors and Calendar-equivalence sweep for the integer DOS date/time
 * encoder. The sweep reproduces the pre-existing Calendar-based conversion and
 * asserts bit-identical output for the same input instant across several
 * default time zones (including half-hour offsets and 30-minute DST).
 */
public class FatDosDateTimeEncodingTest {

    private TimeZone originalZone;
    private Locale originalLocale;

    @Before
    public void setUp() {
        originalZone = TimeZone.getDefault();
        originalLocale = Locale.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Locale.setDefault(Locale.ROOT);
    }

    @After
    public void tearDown() {
        TimeZone.setDefault(originalZone);
        Locale.setDefault(originalLocale);
    }

    @Test
    public void testDosMinimumIs1980Jan1() {
        long min = FatUtils.getMinDateTime();
        assertEquals(TimeUtils.time2millis(1980, 1, 1, 0, 0, 0), min);
        assertEquals(33, FatUtils.encodeDate(min));
        assertEquals(0, FatUtils.encodeTime(min));
        assertEquals(0, FatUtils.encodeTenth(min));
    }

    @Test
    public void testDosMaximumIs2107Dec31_235958() {
        long max = FatUtils.getMaxDateTime();
        assertEquals(TimeUtils.time2millis(2107, 12, 31, 23, 59, 58), max);
        assertEquals(65439, FatUtils.encodeDate(max));
        assertEquals(49021, FatUtils.encodeTime(max));
        assertEquals(0, FatUtils.encodeTenth(max));
    }

    @Test
    public void testPre1980InputsClampToDosMinimum() {
        assertEquals(33, FatUtils.encodeDate(0L));
        assertEquals(0, FatUtils.encodeTime(0L));
        long pre = TimeUtils.time2millis(1965, 7, 4, 12, 0, 0);
        assertEquals(33, FatUtils.encodeDate(pre));
        assertEquals(0, FatUtils.encodeTime(pre));
    }

    @Test
    public void testPost2107InputsClampToDosMinimumViaCheckDateTime() {
        long post = TimeUtils.time2millis(2108, 6, 1, 0, 0, 0);
        assertEquals(33, FatUtils.encodeDate(post));
        assertEquals(0, FatUtils.encodeTime(post));
        assertEquals(33, FatUtils.encodeDate(Long.MAX_VALUE));
        assertEquals(0, FatUtils.encodeTime(Long.MAX_VALUE));
    }

    @Test
    public void test2107CeilingBoundaryInstantEncodesExactly() {
        long max = FatUtils.getMaxDateTime();
        assertEquals(65439, FatUtils.encodeDate(max));
        assertEquals(49021, FatUtils.encodeTime(max));
        long oneSecondOver = max + 1000L;
        assertEquals(33, FatUtils.encodeDate(oneSecondOver));
        assertEquals(0, FatUtils.encodeTime(oneSecondOver));
    }

    @Test
    public void testOddSecondsTruncateTo2SecondGranularity() {
        long t = TimeUtils.time2millis(2001, 5, 17, 13, 45, 57);
        assertEquals(10929, FatUtils.encodeDate(t));
        assertEquals(28092, FatUtils.encodeTime(t));
        long tOdd = TimeUtils.time2millis(1980, 6, 15, 12, 34, 57);
        assertEquals(207, FatUtils.encodeDate(tOdd));
        assertEquals(25692, FatUtils.encodeTime(tOdd));
    }

    @Test
    public void testTenthFieldEncodesSecondParityAndMilliseconds() {
        long evenSecond = TimeUtils.time2millis(1980, 6, 15, 12, 34, 56) + 750L;
        assertEquals(75, FatUtils.encodeTenth(evenSecond));
        long oddSecond = evenSecond + 1000L;
        assertEquals(175, FatUtils.encodeTenth(oddSecond));
        long maxTenth = TimeUtils.time2millis(2005, 3, 31, 0, 0, 1) + 990L;
        assertEquals(199, FatUtils.encodeTenth(maxTenth));
        long evenSecondMax = TimeUtils.time2millis(2005, 3, 31, 0, 0, 0) + 990L;
        assertEquals(99, FatUtils.encodeTenth(evenSecondMax));
    }

    @Test
    public void testLeapDaysEncodes() {
        long leap1980 = TimeUtils.time2millis(1980, 2, 29, 12, 0, 0);
        assertEquals(93, FatUtils.encodeDate(leap1980));
        assertEquals(24576, FatUtils.encodeTime(leap1980));
        long leap2000 = TimeUtils.time2millis(2000, 2, 29, 12, 0, 0);
        assertEquals(10333, FatUtils.encodeDate(leap2000));
        assertEquals(24576, FatUtils.encodeTime(leap2000));
    }

    @Test
    public void testDecodeThenEncodeRoundTripInUtc() {
        long t = TimeUtils.time2millis(2003, 11, 20, 8, 30, 46);
        int date = FatUtils.encodeDate(t);
        int time = FatUtils.encodeTime(t);
        assertEquals(t, FatUtils.decodeDateTime(date, time));
    }

    @Test
    public void testEncodingMatchesCalendarAcrossTimeZones() {
        String[] zones = {
            "UTC",
            "America/New_York",
            "Asia/Kathmandu",
            "Australia/Eucla",
            "Australia/Lord_Howe"
        };
        long min = FatUtils.getMinDateTime();
        long max = FatUtils.getMaxDateTime();
        long[] interesting = {
            0L,
            min,
            max,
            max + 1,
            TimeUtils.time2millis(2000, 2, 29, 12, 0, 0),
            TimeUtils.time2millis(2000, 4, 2, 8, 0, 0),
            TimeUtils.time2millis(2000, 10, 29, 6, 30, 0),
            TimeUtils.time2millis(2021, 3, 14, 7, 30, 0),
            TimeUtils.time2millis(2021, 11, 7, 6, 30, 0),
            TimeUtils.time2millis(1980, 1, 1, 0, 0, 0) + 123L
        };
        for (int z = 0; z < zones.length; z++) {
            TimeZone.setDefault(TimeZone.getTimeZone(zones[z]));
            for (int i = 0; i < interesting.length; i++) {
                assertMatchesCalendar(zones[z], interesting[i]);
            }
            for (int i = 0; i < 500; i++) {
                long millis = min + (i * 1162201183L) % (max - min);
                assertMatchesCalendar(zones[z], millis);
            }
        }
    }

    @Test
    public void testEncodeIsLocaleIndependent() {
        long min = FatUtils.getMinDateTime();
        Locale[] locales = {
            Locale.ROOT,
            new Locale("th", "TH"),
            new Locale("ja", "JP", "JP"),
            new Locale("tr")
        };
        try {
            for (int i = 0; i < locales.length; i++) {
                Locale.setDefault(locales[i]);
                assertEquals("encodeDate under " + locales[i], 33, FatUtils.encodeDate(min));
                assertEquals("encodeTime under " + locales[i], 0, FatUtils.encodeTime(min));
                assertEquals("encodeTenth under " + locales[i], 0, FatUtils.encodeTenth(min));
            }
        } finally {
            Locale.setDefault(Locale.ROOT);
        }
    }

    private static void assertMatchesCalendar(String zone, long millis) {
        assertEquals("encodeDate mismatch in " + zone + " for " + millis,
            calendarEncodeDate(millis), FatUtils.encodeDate(millis));
        assertEquals("encodeTime mismatch in " + zone + " for " + millis,
            calendarEncodeTime(millis), FatUtils.encodeTime(millis));
        assertEquals("encodeTenth mismatch in " + zone + " for " + millis,
            calendarEncodeTenth(millis), FatUtils.encodeTenth(millis));
    }

    private static int calendarEncodeTime(long javaDateTime) {
        Calendar cal = Calendar.getInstance(Locale.ROOT);
        cal.setTimeInMillis(FatUtils.checkDateTime(javaDateTime));
        return 2048 * cal.get(Calendar.HOUR_OF_DAY) + 32 * cal.get(Calendar.MINUTE)
            + cal.get(Calendar.SECOND) / 2;
    }

    private static int calendarEncodeDate(long javaDateTime) {
        Calendar cal = Calendar.getInstance(Locale.ROOT);
        cal.setTimeInMillis(FatUtils.checkDateTime(javaDateTime));
        return 512 * (cal.get(Calendar.YEAR) - 1980) + 32 * (cal.get(Calendar.MONTH) + 1)
            + cal.get(Calendar.DATE);
    }

    private static int calendarEncodeTenth(long javaDateTime) {
        Calendar cal = Calendar.getInstance(Locale.ROOT);
        cal.setTimeInMillis(FatUtils.checkDateTime(javaDateTime));
        return 100 * (cal.get(Calendar.SECOND) % 2) + cal.get(Calendar.MILLISECOND) / 10;
    }
}
