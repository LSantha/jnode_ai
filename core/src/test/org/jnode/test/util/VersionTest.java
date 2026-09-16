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

package org.jnode.test.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.jnode.util.Version;
import org.junit.Test;

public class VersionTest {
    @Test
    public void testToString1() throws Exception {
        Version v = new Version(1, 2);
        assertEquals("1.2", v.toString());
    }

    @Test
    public void testToString1b() throws Exception {
        Version v = new Version(1, 2, "dev");
        assertEquals("1.2-dev", v.toString());
    }

    @Test
    public void testToString2() throws Exception {
        Version v = new Version(1, 2, 3);
        assertEquals("1.2.3", v.toString());
    }

    @Test
    public void testToString2b() throws Exception {
        Version v = new Version(1, 2, 3, "foo");
        assertEquals("1.2.3-foo", v.toString());
    }

    @Test
    public void testToString3() throws Exception {
        Version v = new Version(1, 2, 3, 4);
        assertEquals("1.2.3.4", v.toString());
    }

    @Test
    public void testToString3b() throws Exception {
        Version v = new Version(1, 2, 3, 4, "foo");
        assertEquals("1.2.3.4-foo", v.toString());
    }

    @Test
    public void testToStringA() throws Exception {
        Version v = new Version("5");
        assertEquals("5", v.toString());
    }

    @Test
    public void testToStringAb() throws Exception {
        Version v = new Version("5-foo");
        assertEquals("5-foo", v.toString());
    }

    @Test
    public void testToStringB() throws Exception {
        Version v = new Version("5.7");
        assertEquals("5.7", v.toString());
    }

    @Test
    public void testToStringBb() throws Exception {
        Version v = new Version("5.7-foo");
        assertEquals("5.7-foo", v.toString());
    }

    @Test
    public void testToStringC() throws Exception {
        Version v = new Version("1.5.2");
        assertEquals("1.5.2", v.toString());
    }

    @Test
    public void testToStringCb() throws Exception {
        Version v = new Version("1.5.2-dev");
        assertEquals("1.5.2-dev", v.toString());
    }

    @Test
    public void testToStringD() throws Exception {
        Version v = new Version("4.3.2.1");
        assertEquals("4.3.2.1", v.toString());
    }

    @Test
    public void testToStringDb() throws Exception {
        Version v = new Version("4.3.2.1-rel");
        assertEquals("4.3.2.1-rel", v.toString());
    }

    @Test
    public void testCompare2a() throws Exception {
        Version a = new Version(1, 2);
        Version b = new Version(1, 2);
        assertEquals(0, a.compareTo(b));
    }

    @Test
    public void testCompare2b() throws Exception {
        Version a = new Version(2, 2);
        Version b = new Version(1, 2);
        assertEquals(1, a.compareTo(b));
    }

    @Test
    public void testCompare3a() throws Exception {
        Version a = new Version(1, 2, 3);
        Version b = new Version(1, 2, 3);
        assertEquals(0, a.compareTo(b));
    }

    @Test
    public void testCompare4a() throws Exception {
        Version a = new Version(1, 2, 3, 4);
        Version b = new Version(1, 2, 3, 5);
        assertEquals(-1, a.compareTo(b));
    }

    @Test
    public void testCompare4b() throws Exception {
        Version a = new Version(1, 2, 3, 5);
        Version b = new Version(1, 2, 3, 4);
        assertEquals(1, a.compareTo(b));
    }

    @Test
    public void testCompare5a() throws Exception {
        Version a = new Version(1, 2);
        Version b = new Version(1, 2, 3);
        assertEquals(-1, a.compareTo(b));
    }

    @Test
    public void testCompare5b() throws Exception {
        Version a = new Version(1, 2, 0);
        Version b = new Version(1, 2);
        assertEquals(1, a.compareTo(b));
    }

    @Test
    public void testCompare5c() throws Exception {
        Version a = new Version(1, 2, 3);
        Version b = new Version(1, 2, 3, 0);
        assertEquals(-1, a.compareTo(b));
    }

    @Test
    public void testCompare5d() throws Exception {
        Version a = new Version(1, 2, 3, 0);
        Version b = new Version(1, 2, 3);
        assertEquals(1, a.compareTo(b));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testError2a() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version(-1, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testError2b() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version(1, -2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testError3a() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version(-1, 2, 3);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testError3b() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version(1, -2, 3);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testError3c() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version(1, 2, -3);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testError4a() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version(-1, 2, 3, 4);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testError4b() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version(1, -2, 3, 4);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testError4c() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version(1, 2, -3, 4);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testError4d() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version(1, 2, 3, -4);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testError5a() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version("-1.2");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testError5b() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version("1.-2");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testError5c() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version("1.2.-3");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testError5d() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version("1.2.3.-4");
    }

    @Test(expected = NumberFormatException.class)
    public void testError6a() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version("jon");
    }

    @Test(expected = NumberFormatException.class)
    public void testError6b() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version("1.jon");
    }

    @Test(expected = NumberFormatException.class)
    public void testError6c() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version("1.2.jon");
    }

    @Test(expected = NumberFormatException.class)
    public void testError6d() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version("1.2.3.jon");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testError7a() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version("1.2.3.4.5");
    }

    // ---- Expanded assertions: getters ----

    @Test
    public void testGettersFull4() throws Exception {
        Version v = new Version(1, 2, 3, 4);
        assertEquals(1, v.getMajor());
        assertEquals(2, v.getMinor());
        assertEquals(3, v.getBuild());
        assertEquals(4, v.getRevision());
        assertNull(v.getTag());
    }

    @Test
    public void testGettersFull4WithTag() throws Exception {
        Version v = new Version(1, 2, 3, 4, "rel");
        assertEquals(1, v.getMajor());
        assertEquals(2, v.getMinor());
        assertEquals(3, v.getBuild());
        assertEquals(4, v.getRevision());
        assertEquals("rel", v.getTag());
    }

    @Test
    public void testGetters3() throws Exception {
        Version v = new Version(1, 2, 3);
        assertEquals(1, v.getMajor());
        assertEquals(2, v.getMinor());
        assertEquals(3, v.getBuild());
        assertEquals(0, v.getRevision());
        assertNull(v.getTag());
    }

    @Test
    public void testGetters3WithTag() throws Exception {
        Version v = new Version(1, 2, 3, "foo");
        assertEquals(1, v.getMajor());
        assertEquals(2, v.getMinor());
        assertEquals(3, v.getBuild());
        assertEquals(0, v.getRevision());
        assertEquals("foo", v.getTag());
    }

    @Test
    public void testGetters2() throws Exception {
        Version v = new Version(1, 2);
        assertEquals(1, v.getMajor());
        assertEquals(2, v.getMinor());
        assertEquals(0, v.getBuild());
        assertEquals(0, v.getRevision());
        assertNull(v.getTag());
    }

    @Test
    public void testGetters2WithTag() throws Exception {
        Version v = new Version(1, 2, "dev");
        assertEquals(1, v.getMajor());
        assertEquals(2, v.getMinor());
        assertEquals(0, v.getBuild());
        assertEquals(0, v.getRevision());
        assertEquals("dev", v.getTag());
    }

    @Test
    public void testGettersStringSingle() throws Exception {
        Version v = new Version("5");
        assertEquals(5, v.getMajor());
        assertEquals(0, v.getMinor());
        assertEquals(0, v.getBuild());
        assertEquals(0, v.getRevision());
        assertNull(v.getTag());
    }

    @Test
    public void testGettersStringFull() throws Exception {
        Version v = new Version("4.3.2.1-rel");
        assertEquals(4, v.getMajor());
        assertEquals(3, v.getMinor());
        assertEquals(2, v.getBuild());
        assertEquals(1, v.getRevision());
        assertEquals("rel", v.getTag());
    }

    @Test
    public void testGettersUndefinedMapsToZero() throws Exception {
        Version two = new Version(1, 2);
        assertEquals(0, two.getBuild());
        assertEquals(0, two.getRevision());
        Version three = new Version(1, 2, 3);
        assertEquals(0, three.getRevision());
        Version one = new Version("5");
        assertEquals(0, one.getMinor());
        assertEquals(0, one.getBuild());
        assertEquals(0, one.getRevision());
    }

    @Test
    public void testGetTagNull() throws Exception {
        assertNull(new Version(1, 2).getTag());
        assertNull(new Version(1, 2, 3).getTag());
        assertNull(new Version(1, 2, 3, 4).getTag());
        assertNull(new Version("1.2.3").getTag());
    }

    @Test
    public void testGetTagNonNull() throws Exception {
        assertEquals("dev", new Version(1, 2, "dev").getTag());
        assertEquals("foo", new Version(1, 2, 3, "foo").getTag());
        assertEquals("foo", new Version(1, 2, 3, 4, "foo").getTag());
        assertEquals("foo", new Version("5-foo").getTag());
        assertEquals("rel", new Version("4.3.2.1-rel").getTag());
    }

    // ---- Expanded assertions: compareTo ----

    @Test
    public void testCompareMajorLess() throws Exception {
        Version a = new Version(1, 2, 3, 4);
        Version b = new Version(2, 2, 3, 4);
        assertEquals(-1, a.compareTo(b));
        assertEquals(1, b.compareTo(a));
    }

    @Test
    public void testCompareMajorGreater() throws Exception {
        Version a = new Version(3, 0);
        Version b = new Version(2, 9);
        assertEquals(1, a.compareTo(b));
        assertEquals(-1, b.compareTo(a));
    }

    @Test
    public void testCompareMinorLess() throws Exception {
        Version a = new Version(1, 1);
        Version b = new Version(1, 2);
        assertEquals(-1, a.compareTo(b));
        assertEquals(1, b.compareTo(a));
    }

    @Test
    public void testCompareMinorGreater() throws Exception {
        Version a = new Version(1, 3);
        Version b = new Version(1, 2);
        assertEquals(1, a.compareTo(b));
        assertEquals(-1, b.compareTo(a));
    }

    @Test
    public void testCompareBuildLess() throws Exception {
        Version a = new Version(1, 2, 2);
        Version b = new Version(1, 2, 3);
        assertEquals(-1, a.compareTo(b));
        assertEquals(1, b.compareTo(a));
    }

    @Test
    public void testCompareBuildGreater() throws Exception {
        Version a = new Version(1, 2, 4);
        Version b = new Version(1, 2, 3);
        assertEquals(1, a.compareTo(b));
        assertEquals(-1, b.compareTo(a));
    }

    @Test
    public void testCompareRevisionEqual() throws Exception {
        Version a = new Version(1, 2, 3, 4);
        Version b = new Version(1, 2, 3, 4);
        assertEquals(0, a.compareTo(b));
        assertEquals(0, b.compareTo(a));
        assertEquals(0, a.compareTo(a));
    }

    @Test
    public void testCompareTagNullVsTagged() throws Exception {
        Version plain = new Version(1, 2, 3);
        Version tagged = new Version(1, 2, 3, "foo");
        assertEquals(-1, plain.compareTo(tagged));
        assertEquals(-1, new Version("5").compareTo(new Version("5-foo")));
    }

    @Test
    public void testCompareTaggedVsNull() throws Exception {
        Version tagged = new Version(1, 2, 3, "foo");
        Version plain = new Version(1, 2, 3);
        assertEquals(1, tagged.compareTo(plain));
        assertEquals(1, new Version("5-foo").compareTo(new Version("5")));
    }

    @Test
    public void testCompareTagLexicalLess() throws Exception {
        Version a = new Version(1, 2, 3, "aaa");
        Version b = new Version(1, 2, 3, "zzz");
        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(a) > 0);
    }

    @Test
    public void testCompareTagLexicalGreater() throws Exception {
        Version a = new Version("5-foo");
        Version b = new Version("5-bar");
        assertTrue(a.compareTo(b) > 0);
        assertTrue(b.compareTo(a) < 0);
    }

    @Test
    public void testCompareTagEqual() throws Exception {
        Version a = new Version(1, 2, 3, "foo");
        Version b = new Version(1, 2, 3, "foo");
        assertEquals(0, a.compareTo(b));
        assertEquals(0, new Version("5-foo").compareTo(new Version("5-foo")));
    }

    @Test
    public void testCompareNumericDominatesTag() throws Exception {
        Version lowerTagged = new Version(1, 2, 3, "zzz");
        Version higherPlain = new Version(1, 2, 4, "aaa");
        assertEquals(-1, lowerTagged.compareTo(higherPlain));
        assertEquals(1, higherPlain.compareTo(lowerTagged));
    }

    @Test
    public void testCompareReflexive() throws Exception {
        Version a = new Version(1, 2, 3, 4, "foo");
        assertEquals(0, a.compareTo(a));
        Version b = new Version("5");
        assertEquals(0, b.compareTo(b));
    }

    @Test
    public void testCompareSymmetric() throws Exception {
        Version x = new Version(1, 2, 3, "foo");
        Version y = new Version(1, 2, 3, "bar");
        assertTrue(x.compareTo(y) > 0);
        assertTrue(y.compareTo(x) < 0);
        assertTrue(Integer.signum(x.compareTo(y)) == -Integer.signum(y.compareTo(x)));
    }

    @Test
    public void testCompareStringVsIntEquivalent() throws Exception {
        assertEquals(0, new Version("1.2.3").compareTo(new Version(1, 2, 3)));
        assertEquals(0, new Version("5.7").compareTo(new Version(5, 7)));
        assertEquals(0, new Version("4.3.2.1").compareTo(new Version(4, 3, 2, 1)));
        assertEquals(0, new Version("5").compareTo(new Version("5")));
    }

    @Test
    public void testCompareStringTagVsIntTag() throws Exception {
        assertEquals(0, new Version("1.2.3-foo").compareTo(new Version(1, 2, 3, "foo")));
        assertEquals(0, new Version("5.7-foo").compareTo(new Version(5, 7, "foo")));
        assertEquals(0, new Version("1.5.2-dev").compareTo(new Version(1, 5, 2, "dev")));
    }

    @Test
    public void testCompareSingleVsTwoPart() throws Exception {
        assertEquals(-1, new Version("5").compareTo(new Version("5.0")));
        assertEquals(1, new Version("5.0").compareTo(new Version("5")));
        assertEquals(0, new Version("5").compareTo(new Version("5")));
    }

    @Test
    public void testCompareZeroVersions() throws Exception {
        assertEquals(0, new Version(0, 0, 0, 0).compareTo(new Version(0, 0, 0, 0)));
        assertEquals(-1, new Version(0, 0, 0, 0).compareTo(new Version(0, 0, 0, 1)));
        assertEquals(1, new Version(0, 0, 0, 1).compareTo(new Version(0, 0, 0, 0)));
        assertEquals(-1, new Version(0, 0).compareTo(new Version(0, 0, 0)));
    }

    // ---- Expanded assertions: toString / parsing ----

    @Test
    public void testToStringZero() throws Exception {
        assertEquals("0.0.0.0", new Version(0, 0, 0, 0).toString());
        assertEquals("0.0", new Version(0, 0).toString());
        assertEquals("0.0.0.0", new Version("0.0.0.0").toString());
    }

    @Test
    public void testToStringRoundtrip() throws Exception {
        String[] samples = {"5", "5-foo", "5.7", "1.5.2-dev", "4.3.2.1", "4.3.2.1-rel",
            "0.0.0.0", "123.456.789.10", "1.2-foo-bar", "1.2.3-foo.bar"};
        for (String s : samples) {
            Version v = new Version(s);
            assertEquals(s, v.toString());
            assertEquals(0, new Version(v.toString()).compareTo(v));
        }
    }

    @Test
    public void testToStringTagWithHyphen() throws Exception {
        Version v = new Version("1.2-foo-bar");
        assertEquals("foo-bar", v.getTag());
        assertEquals("1.2-foo-bar", v.toString());
    }

    @Test
    public void testToStringTagWithDot() throws Exception {
        Version v = new Version("1.2.3-foo.bar");
        assertEquals("foo.bar", v.getTag());
        assertEquals("1.2.3-foo.bar", v.toString());
    }

    @Test
    public void testToStringLargeNumbers() throws Exception {
        Version v = new Version("123.456.789.10");
        assertEquals(123, v.getMajor());
        assertEquals(456, v.getMinor());
        assertEquals(789, v.getBuild());
        assertEquals(10, v.getRevision());
        assertEquals("123.456.789.10", v.toString());
    }

    @Test
    public void testParseZeroVersion() throws Exception {
        Version v = new Version("0.0.0.0");
        assertEquals(0, v.getMajor());
        assertEquals(0, v.getMinor());
        assertEquals(0, v.getBuild());
        assertEquals(0, v.getRevision());
        assertNull(v.getTag());
    }

    @Test
    public void testParseTagWithHyphen() throws Exception {
        Version v = new Version("1.2-foo-bar");
        assertEquals(1, v.getMajor());
        assertEquals(2, v.getMinor());
        assertEquals("foo-bar", v.getTag());
        assertEquals(0, v.compareTo(new Version(1, 2, "foo-bar")));
    }

    @Test
    public void testParseTagWithDot() throws Exception {
        Version v = new Version("1.2.3-foo.bar");
        assertEquals(1, v.getMajor());
        assertEquals(2, v.getMinor());
        assertEquals(3, v.getBuild());
        assertEquals("foo.bar", v.getTag());
    }

    // ---- Expanded assertions: hashCode / equals ----

    @Test
    public void testHashCodeConsistent() throws Exception {
        Version v = new Version(1, 2, 3, 4);
        assertEquals(v.hashCode(), v.hashCode());
        Version tagged = new Version(1, 2, 3, 4, "foo");
        assertEquals(tagged.hashCode(), tagged.hashCode());
    }

    @Test
    public void testHashCodeEqualVersions() throws Exception {
        Version a = new Version(1, 2, 3, 4);
        Version b = new Version(1, 2, 3, 4);
        assertEquals(a.hashCode(), b.hashCode());
        Version c = new Version(1, 2, 3, 4, "foo");
        Version d = new Version(1, 2, 3, 4, "foo");
        assertEquals(c.hashCode(), d.hashCode());
    }

    @Test
    public void testHashCodeStringVsInt() throws Exception {
        assertEquals(new Version("1.2.3.4").hashCode(), new Version(1, 2, 3, 4).hashCode());
        assertEquals(new Version("1.2").hashCode(), new Version(1, 2).hashCode());
        assertEquals(new Version("4.3.2.1-rel").hashCode(), new Version(4, 3, 2, 1, "rel").hashCode());
    }

    @Test
    public void testHashCodeTaggedVsUntagged() throws Exception {
        Version plain = new Version(1, 2, 3, 4);
        Version tagged = new Version(1, 2, 3, 4, "foo");
        assertFalse(plain.hashCode() == tagged.hashCode());
        assertNotNull(tagged.getTag());
        assertNull(plain.getTag());
    }

    @Test
    public void testHashCodeDifferentRevisions() throws Exception {
        Version a = new Version(1, 2, 3, 4);
        Version b = new Version(1, 2, 3, 5);
        assertFalse(a.hashCode() == b.hashCode());
        assertEquals(16909060, a.hashCode());
        assertEquals(16909061, b.hashCode());
    }

    @Test
    public void testEqualsIdentity() throws Exception {
        Version a = new Version(1, 2, 3);
        assertTrue(a.equals(a));
        assertTrue(a.equals((Object) a));
    }

    @Test
    public void testEqualsDistinctEqualValue() throws Exception {
        Version a = new Version(1, 2, 3);
        Version b = new Version(1, 2, 3);
        assertEquals(0, a.compareTo(b));
        assertFalse(a.equals(b));
    }

    @Test
    public void testEqualsNullAndOtherType() throws Exception {
        Version a = new Version(1, 2, 3);
        assertFalse(a.equals(null));
        assertFalse(a.equals("1.2.3"));
        assertFalse(a.equals(new Object()));
    }

    // ---- Expanded assertions: error cases ----

    @Test(expected = IllegalArgumentException.class)
    public void testErrorTagEmpty() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version("1.2-");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testErrorTagEmptySingle() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version("5-");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testErrorTagStartsWithDigit() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version("1.2-5foo");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testErrorTagStartsWithDigitFull() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version("1.2.3.4-9rel");
    }

    @Test(expected = NullPointerException.class)
    public void testErrorNullString() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version((String) null);
    }

    @Test
    public void testErrorIntWithTagNegative() throws Exception {
        try {
            new Version(-1, 2, "tag");
            fail("expected IllegalArgumentException for major");
        } catch (IllegalArgumentException e) {
            assertNotNull(e.getMessage());
        }
        try {
            new Version(1, -2, "tag");
            fail("expected IllegalArgumentException for minor");
        } catch (IllegalArgumentException e) {
            assertNotNull(e.getMessage());
        }
        try {
            new Version(1, 2, -3, "tag");
            fail("expected IllegalArgumentException for build");
        } catch (IllegalArgumentException e) {
            assertNotNull(e.getMessage());
        }
        try {
            new Version(1, 2, 3, -4, "tag");
            fail("expected IllegalArgumentException for revision");
        } catch (IllegalArgumentException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testErrorTooManyPartsWithTag() throws Exception {
        @SuppressWarnings("unused")
        Version a = new Version("1.2.3.4.5-foo");
    }

    @Test
    public void testIsCompatibleViaCompareTo() throws Exception {
        Version required = new Version(1, 2);
        Version newer = new Version(1, 5);
        Version older = new Version(1, 1);
        Version otherMajor = new Version(2, 2);
        assertTrue(newer.compareTo(required) > 0);
        assertTrue(older.compareTo(required) < 0);
        assertTrue(otherMajor.compareTo(required) > 0);
        assertEquals(0, new Version(1, 2).compareTo(required));
    }
}
