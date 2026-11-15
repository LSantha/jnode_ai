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
 
package org.jnode.fs.jfat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import org.jnode.util.TimeUtils;

/**
 * @author gvt
 * @author Tango
 */
public class FatUtils {
    private static final ThreadLocal<SimpleDateFormat> time = new ThreadLocal<SimpleDateFormat>() {
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.S");
        }
    };

    private static final ThreadLocal<SimpleDateFormat> date = new ThreadLocal<SimpleDateFormat>() {
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd");
        }
    };

    public static long decodeDateTime(final int dosDate, final int dosTime, final int dosTenth) {
        if (dosDate < 0 || dosDate > 0xFFFF)
            throw new IllegalArgumentException("dosDate is invalid: " + dosDate);

        if (dosTime < 0 || dosTime > 0xFFFF)
            throw new IllegalArgumentException("dosTime is invalid: " + dosTime);

        if (dosTenth < 0 || dosTenth > 199)
            throw new IllegalArgumentException("dosTime is invalid: " + dosTenth);

        int milliseconds = (dosTenth % 100) * 10;
        int seconds = (dosTime & 0x1f) * 2 + dosTenth / 100;
        int minutes = (dosTime >> 5) & 0x3f;
        int hours = dosTime >> 11;
        int days = dosDate & 0x1f;
        int months = ((dosDate >> 5) & 0x0f);
        int years = 1980 + (dosDate >> 9);

        return TimeUtils.time2millis(years, months, days, hours, minutes, seconds) + milliseconds;
    }

    public static long decodeDateTime(int dosDate, int dosTime) {
        return decodeDateTime(dosDate, dosTime, 0);
    }

    public static long getMinDateTime() {
        //dos Minimum DateTime is: January 1, 1980 00:00:00
        return TimeUtils.time2millis(1980, 1, 1, 0, 0, 0);
    }

    public static long getMaxDateTime() {
        //dos Maximum DateTime is: December 31, 2107 23:59:58
        return TimeUtils.time2millis(2107, 12, 31, 23, 59, 58);
    }

    public static long checkDateTime(long javaDateTime) {
        long dosMinDateTime = getMinDateTime();

        if (javaDateTime < dosMinDateTime || javaDateTime > getMaxDateTime())
            return dosMinDateTime;
        else
            return javaDateTime;
    }

    private static long localMillis(final long millis) {
        return millis + TimeZone.getDefault().getOffset(millis);
    }

    /*
     * Local-time civil decomposition of epoch milliseconds:
     * {year, month 1..12, day-of-month, hour, minute, second, millis}.
     * Package-visible for FatFormatter.getDriveSerialNumber; allocates one long[7].
     */
    static long[] localCivilMillis(final long javaDateTime) {
        return localCivil(localMillis(javaDateTime));
    }

    private static long[] localCivil(final long local) {
        long totalSeconds = local / 1000;
        long days = totalSeconds / 86400;
        int secOfDay = (int) (totalSeconds - days * 86400);
        long z = days + 719468;
        long era = z / 146097;
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp + (mp < 10 ? 3 : -9);
        long year = y + (m <= 2 ? 1 : 0);
        return new long[] {
            year, m, d,
            secOfDay / 3600, (secOfDay % 3600) / 60, secOfDay % 60,
            local % 1000
        };
    }

    public static int encodeTime(long javaDateTime) {
        long[] civil = localCivil(localMillis(checkDateTime(javaDateTime)));

        return (int) (2048 * civil[3] + 32 * civil[4] + civil[5] / 2);
    }

    public static int encodeTime(Calendar cal) {
        return 2048 * cal.get(Calendar.HOUR_OF_DAY) + 32 * cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 2;
    }

    public static int encodeDate(long javaDateTime) {
        long[] civil = localCivil(localMillis(checkDateTime(javaDateTime)));

        return (int) (512 * (civil[0] - 1980) + 32 * civil[1] + civil[2]);
    }

    public static int encodeDate(Calendar cal) {
        return 512 * (cal.get(Calendar.YEAR) - 1980) + 32 * (cal.get(Calendar.MONTH) + 1) + cal.get(Calendar.DATE);
    }

    public static int encodeTenth(long javaDateTime) {
        long[] civil = localCivil(localMillis(checkDateTime(javaDateTime)));

        return (int) (100 * (civil[5] % 2) + civil[6] / 10);
    }

    public static String fTime(long javaDateTime) {
        return time.get().format(javaDateTime);
    }

    public static String fDate(long javaDateTime) {
        return date.get().format(javaDateTime);
    }

    public static final boolean compareIgnoreCase(String s1, String s2) {
        return s1.equalsIgnoreCase(s2);
    }

    /**
     * Case-insensitive canonicalization kept for exported-API compatibility.
     *
     * @deprecated kept for exported-API compatibility; the fold is
     *   pinned to {@link Locale#ROOT} so it is no longer locale-dependent. New callers
     *   should use {@link #toUpperCase(String)}.
     */
    @Deprecated
    public static final String toIgnoreCase(String s) {
        return s.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    /**
     * This is THE case fold for JFAT names. The short-name basis generator
     * ({@code FatName.computePreTail}) encodes through this fold, the stored 8.3
     * bytes derive from it, and short-name lookups compare the stored bytes
     * directly ({@code FatDirectory.ShortNameKey}) - so generation and lookup agree.
     * The fold is pinned to {@link Locale#ROOT}: the FAT short-name rules are defined
     * on the OEM codepage, not on the JVM default locale (a Turkish default locale
     * would otherwise fold 'i' via the dotted-I rule). The earlier "Georgian alphabet"
     * upper-then-lower double fold is vestigial: the Unicode mappings for Mkhedruli,
     * Asomtavruli and Nuskhuri are stable and locale-independent, and Georgian
     * codepoints are unmappable in the FAT codepages so they reach the basis as the
     * lossy replacement byte anyway.
     */
    public static final String toUpperCase(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    public static final boolean isLowerCase(String s) {
        if (s.length() == 0)
            return false;

        char[] ca = s.toCharArray();

        for (int i = 0; i < ca.length; i++)
            if (!(Character.toLowerCase(ca[i]) == ca[i]))
                return false;

        return true;
    }

    public static final boolean isUpperCase(String s) {
        if (s.length() == 0)
            return false;

        char[] ca = s.toCharArray();

        for (int i = 0; i < ca.length; i++)
            if (!(Character.toUpperCase(ca[i]) == ca[i]))
                return false;

        return true;
    }

    public static final String longName(String name) {
        String lname = name.trim();
        lname = lname.replaceAll("\\.*$", "");
        return lname;
    }

    public static int getMonth(long time) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(time);
        return cal.get(Calendar.MONTH);
    }

    public static int getHours(long time) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(time);
        return cal.get(Calendar.HOUR_OF_DAY);
    }

    public static int getDay(long time) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(time);
        // For Calendar, Sunday is 1. For Date, Sunday is 0.
        return cal.get(Calendar.DAY_OF_WEEK) - 1;
    }

    public static int getYear(long time) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(time);
        return cal.get(Calendar.YEAR) - 1980;
    }

    public static int getMinutes(long time) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(time);
        return cal.get(Calendar.MINUTE);
    }

    public static int getSeconds(long time) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(time);
        return cal.get(Calendar.SECOND);
    }

    public static long getMilliSeconds(long time) {

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(time);
        return cal.get(Calendar.MILLISECOND);
    }

}
