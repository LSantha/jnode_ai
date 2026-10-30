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
 
package org.jnode.test.shell.alias;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.jnode.plugin.Extension;
import org.jnode.plugin.ExtensionPoint;
import org.jnode.shell.alias.AliasManager;
import org.jnode.shell.alias.NoSuchAliasException;
import org.jnode.shell.alias.def.DefaultAliasManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the only production implementation of
 * {@link org.jnode.shell.alias.AliasManager}.
 */
public class DefaultAliasManagerTest {

    private static final String KNOWN_CLASS = "org.jnode.test.shell.AllTests";

    private static final String UNKNOWN_CLASS = "org.jnode.test.shell.NoSuchClassAnywhere";

    private ClassLoader savedContextClassLoader;

    private DefaultAliasManager system;

    private DefaultAliasManager child;

    @Before
    public void setUp() throws Exception {
        savedContextClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        system = newSystemAliasManager();
        child = new DefaultAliasManager(system);
    }

    @After
    public void tearDown() throws Exception {
        Thread.currentThread().setContextClassLoader(savedContextClassLoader);
        system = null;
        child = null;
    }

    @Test
    public void testAddOnChildIsVisible() throws Exception {
        child.add("foo", KNOWN_CLASS);
        assertEquals(KNOWN_CLASS, child.getAliasClassName("foo"));
    }

    @Test
    public void testAddOverwritesSilently() throws Exception {
        child.add("foo", KNOWN_CLASS);
        child.add("foo", UNKNOWN_CLASS);
        assertEquals(UNKNOWN_CLASS, child.getAliasClassName("foo"));
        assertEquals(1, child.aliases().size());
    }

    @Test
    public void testSystemAddIsUnsupported() throws Exception {
        try {
            system.add("foo", KNOWN_CLASS);
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertNotNull(expected.getMessage());
        }
        assertFalse(system.aliases().contains("foo"));
    }

    @Test
    public void testSystemRemoveIsUnsupported() throws Exception {
        try {
            system.remove("foo");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testUnknownAliasThrowsNoSuchAlias() throws Exception {
        try {
            child.getAliasClassName("nosuchalias");
            fail("expected NoSuchAliasException");
        } catch (NoSuchAliasException expected) {
            assertEquals("nosuchalias", expected.getMessage());
        }
    }

    @Test
    public void testParentChainResolution() throws Exception {
        final DefaultAliasManager grandChild = new DefaultAliasManager(child);
        child.add("parentalias", KNOWN_CLASS);
        grandChild.add("ownalias", KNOWN_CLASS);

        assertEquals(KNOWN_CLASS, grandChild.getAliasClassName("parentalias"));
        assertEquals(KNOWN_CLASS, grandChild.getAliasClassName("ownalias"));

        final Collection<String> all = grandChild.aliases();
        assertEquals(2, all.size());
        assertTrue(all.contains("parentalias"));
        assertTrue(all.contains("ownalias"));
        assertFalse(all.contains("foo"));
    }

    @Test
    public void testChildAliasesAreUnionOfParentAndOwn() throws Exception {
        child.add("childalias", KNOWN_CLASS);
        final Collection<String> all = child.aliases();
        assertEquals(1, all.size());
        assertTrue(all.contains("childalias"));

        final DefaultAliasManager grandChild = new DefaultAliasManager(child);
        grandChild.add("grandalias", KNOWN_CLASS);
        assertEquals(2, grandChild.aliases().size());
        assertEquals(1, child.aliases().size());
        assertEquals(0, system.aliases().size());
    }

    @Test
    public void testRemoveOnChildMakesAliasUnresolvable() throws Exception {
        child.add("foo", KNOWN_CLASS);
        assertEquals(KNOWN_CLASS, child.getAliasClassName("foo"));
        child.remove("foo");
        assertFalse(child.aliases().contains("foo"));
        try {
            child.getAliasClassName("foo");
            fail("expected NoSuchAliasException");
        } catch (NoSuchAliasException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testRemoveOfUnknownAliasIsANoop() throws Exception {
        child.add("foo", KNOWN_CLASS);
        child.remove("nosuchalias");
        assertEquals(KNOWN_CLASS, child.getAliasClassName("foo"));
    }

    @Test
    public void testSystemAliasesIsUnmodifiable() throws Exception {
        try {
            system.aliases().add("foo");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void testAliasIteratorMatchesAliases() throws Exception {
        child.add("childalias", KNOWN_CLASS);
        final DefaultAliasManager grandChild = new DefaultAliasManager(child);
        grandChild.add("grandalias", KNOWN_CLASS);

        final List<String> iterated = new ArrayList<String>();
        final Iterator<String> it = grandChild.aliasIterator();
        while (it.hasNext()) {
            iterated.add(it.next());
        }
        assertEquals(new ArrayList<String>(grandChild.aliases()), iterated);
        assertEquals(2, iterated.size());
    }

    @Test
    public void testGetAliasClassResolvesRealClass() throws Exception {
        child.add("suite", KNOWN_CLASS);
        assertSame(Class.forName(KNOWN_CLASS), child.getAliasClass("suite"));
        assertSame(child.getAliasClass("suite"), child.getAliasClass("suite"));
    }

    @Test
    public void testGetAliasClassThrowsForUnresolvableName() throws Exception {
        child.add("bogus", UNKNOWN_CLASS);
        try {
            child.getAliasClass("bogus");
            fail("expected ClassNotFoundException");
        } catch (ClassNotFoundException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testGetAliasClassThrowsForUnknownAlias() throws Exception {
        try {
            child.getAliasClass("nosuchalias");
            fail("expected NoSuchAliasException");
        } catch (NoSuchAliasException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testIsInternalIsFalseForAddedAliases() throws Exception {
        child.add("foo", KNOWN_CLASS);
        assertFalse(child.isInternal("foo"));
    }

    @Test
    public void testIsInternalThrowsForUnknownAlias() throws Exception {
        try {
            child.isInternal("nosuchalias");
            fail("expected NoSuchAliasException");
        } catch (NoSuchAliasException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testCreateAliasManagerReturnsMutableChildOfThis() throws Exception {
        final AliasManager created = system.createAliasManager();
        assertNotNull(created);
        assertTrue(created instanceof DefaultAliasManager);
        ((DefaultAliasManager) created).add("foo", KNOWN_CLASS);
        assertEquals(KNOWN_CLASS, created.getAliasClassName("foo"));
        assertFalse(system.aliases().contains("foo"));
        try {
            system.getAliasClassName("foo");
            fail("expected NoSuchAliasException");
        } catch (NoSuchAliasException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testNullParentIsRejected() throws Exception {
        try {
            new DefaultAliasManager((DefaultAliasManager) null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private DefaultAliasManager newSystemAliasManager() {
        final ExtensionPoint ep = mock(ExtensionPoint.class);
        when(ep.getExtensions()).thenReturn(new Extension[0]);
        return new DefaultAliasManager(ep);
    }
}
