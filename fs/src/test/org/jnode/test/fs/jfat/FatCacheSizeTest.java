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

import org.jnode.fs.jfat.FatCache;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for the volume/config-derived FAT cache sizing. These cover the
 * pure computation and clamping logic; the mount-time wiring lives in
 * {@code Fat} and is exercised by the file-backed integration tests.
 */
public class FatCacheSizeTest {

    @Test
    public void testComputeCacheSizeFloor() {
        assertEquals(FatCache.MIN_COMPUTED_CACHE_SIZE, FatCache.computeCacheSize(0));
        assertEquals(FatCache.MIN_COMPUTED_CACHE_SIZE, FatCache.computeCacheSize(2));
        assertEquals(FatCache.MIN_COMPUTED_CACHE_SIZE, FatCache.computeCacheSize(255));
    }

    @Test
    public void testComputeCacheSizeResidentForFatSectors() {
        assertEquals(256, FatCache.computeCacheSize(256));
        assertEquals(1024, FatCache.computeCacheSize(1024));
        assertEquals(2048, FatCache.computeCacheSize(2048));
        assertEquals(545, FatCache.computeCacheSize(545));
        assertEquals(8192, FatCache.computeCacheSize(8192));
    }

    @Test
    public void testComputeCacheSizeCappedAtDefault() {
        assertEquals(FatCache.DEFAULT_CACHE_SIZE, FatCache.computeCacheSize(8193));
        assertEquals(FatCache.DEFAULT_CACHE_SIZE, FatCache.computeCacheSize(32768));
        assertEquals(FatCache.DEFAULT_CACHE_SIZE, FatCache.computeCacheSize(65536));
        assertEquals(FatCache.DEFAULT_CACHE_SIZE, FatCache.computeCacheSize(Long.MAX_VALUE));
    }

    @Test
    public void testComputeCacheSizeNegativeSectorsPerFat() {
        assertEquals(FatCache.MIN_COMPUTED_CACHE_SIZE, FatCache.computeCacheSize(-1));
    }

    @Test
    public void testResolveCacheSizeDefaultsToComputed() {
        assertEquals(256, FatCache.resolveCacheSize(2, null));
        assertEquals(2048, FatCache.resolveCacheSize(2048, null));
        assertEquals(8192, FatCache.resolveCacheSize(65536, ""));
        assertEquals(8192, FatCache.resolveCacheSize(65536, "   "));
    }

    @Test
    public void testResolveCacheSizeHonoursOverride() {
        assertEquals(1024, FatCache.resolveCacheSize(2, "1024"));
        assertEquals(8192, FatCache.resolveCacheSize(2, "8192"));
        assertEquals(16, FatCache.resolveCacheSize(2, "16"));
        assertEquals(65536, FatCache.resolveCacheSize(2, "65536"));
    }

    @Test
    public void testResolveCacheSizeClampsAbsurdValues() {
        assertEquals(FatCache.MIN_CACHE_SIZE, FatCache.resolveCacheSize(2, "0"));
        assertEquals(FatCache.MIN_CACHE_SIZE, FatCache.resolveCacheSize(2, "-100"));
        assertEquals(FatCache.MIN_CACHE_SIZE, FatCache.resolveCacheSize(2, "15"));
        assertEquals(FatCache.MAX_CACHE_SIZE, FatCache.resolveCacheSize(2, "65537"));
        assertEquals(FatCache.MAX_CACHE_SIZE, FatCache.resolveCacheSize(2, "999999999"));
    }

    @Test
    public void testResolveCacheSizeIgnoresUnparseableOverride() {
        assertEquals(256, FatCache.resolveCacheSize(2, "abc"));
        assertEquals(2048, FatCache.resolveCacheSize(2048, "8.5"));
        assertEquals(8192, FatCache.resolveCacheSize(65536, "8192 elements"));
    }

    @Test
    public void testResolveCacheSizeTrimsWhitespace() {
        assertEquals(1024, FatCache.resolveCacheSize(2, " 1024 "));
    }
}
