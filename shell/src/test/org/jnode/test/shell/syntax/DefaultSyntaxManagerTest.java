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
 
package org.jnode.test.shell.syntax;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.jnode.plugin.ConfigurationElement;
import org.jnode.plugin.Extension;
import org.jnode.plugin.ExtensionPoint;
import org.jnode.shell.CommandLine.Token;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.ArgumentBundle;
import org.jnode.shell.syntax.ArgumentSpecLoader;
import org.jnode.shell.syntax.ArgumentSpecLoader.ArgumentSpec;
import org.jnode.shell.syntax.CommandSyntaxException;
import org.jnode.shell.syntax.DefaultSyntaxManager;
import org.jnode.shell.syntax.IntegerArgument;
import org.jnode.shell.syntax.StringArgument;
import org.jnode.shell.syntax.SyntaxBundle;
import org.jnode.shell.syntax.SyntaxManager;
import org.jnode.shell.syntax.SyntaxSpecAdapter;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DefaultSyntaxManagerTest {

    private DefaultSyntaxManager system;

    @Before
    public void setUp() {
        system = new DefaultSyntaxManager(emptyExtensionPoint());
    }

    @Test
    public void testConstructorRegistersExtensionPointListener() {
        ExtensionPoint ep = emptyExtensionPoint();
        DefaultSyntaxManager mgr = new DefaultSyntaxManager(ep);
        verify(ep).addListener(mgr);
    }

    @Test
    public void testAddAndGetSyntaxBundle() {
        SyntaxManager child = system.createSyntaxManager();
        Assert.assertNotSame(system, child);
        SyntaxBundle bundle = new SyntaxBundle("ls");
        child.add(bundle);
        Assert.assertSame(bundle, child.getSyntaxBundle("ls"));
        Assert.assertEquals("ls", bundle.getAlias());
        Assert.assertNull(system.getSyntaxBundle("ls"));
    }

    @Test
    public void testAddNullSyntaxBundleIsIgnored() {
        SyntaxManager child = system.createSyntaxManager();
        child.add((SyntaxBundle) null);
        Assert.assertTrue(child.getKeys().isEmpty());
    }

    @Test
    public void testSystemManagerRejectsModification() {
        try {
            system.add(new SyntaxBundle("ls"));
            Assert.fail("add(SyntaxBundle) on the system manager should throw");
        } catch (UnsupportedOperationException ex) {
        }
        try {
            system.add("ls", new ArgumentSpec<?>[0]);
            Assert.fail("add(String, ArgumentSpec[]) on the system manager should throw");
        } catch (UnsupportedOperationException ex) {
        }
        try {
            system.remove("ls");
            Assert.fail("remove on the system manager should throw");
        } catch (UnsupportedOperationException ex) {
        }
    }

    @Test
    public void testUnknownAliasReturnsNull() {
        Assert.assertNull(system.getSyntaxBundle("nope"));
        Assert.assertNull(system.getArgumentBundle("nope"));
        Assert.assertTrue(system.getKeys().isEmpty());
        SyntaxManager child = system.createSyntaxManager();
        Assert.assertNull(child.getSyntaxBundle("nope"));
        Assert.assertNull(child.getArgumentBundle("nope"));
        Assert.assertTrue(child.getKeys().isEmpty());
    }

    @Test
    public void testAddArgumentBundle() {
        SyntaxManager child = system.createSyntaxManager();
        child.add("df", loadSpecs(argumentBundle("df", argument("file", StringArgument.class.getName()),
            argument("count", IntegerArgument.class.getName()))));
        ArgumentBundle bundle = child.getArgumentBundle("df");
        Assert.assertNotNull(bundle);
        Assert.assertEquals(2, countArguments(bundle));
        Argument<?> file = bundle.getArgument("file");
        Assert.assertEquals("file", file.getLabel());
        Assert.assertEquals(0, file.getFlags());
        Assert.assertTrue(file instanceof StringArgument);
        Argument<?> count = bundle.getArgument("count");
        Assert.assertEquals("count", count.getLabel());
        Assert.assertEquals(0, count.getFlags());
        Assert.assertTrue(count instanceof IntegerArgument);
        Assert.assertNull(system.getArgumentBundle("df"));
    }

    @Test
    public void testGetKeysExcludesArgumentOnlyAliases() {
        SyntaxManager child = system.createSyntaxManager();
        child.add(new SyntaxBundle("ls"));
        child.add("df", loadSpecs(argumentBundle("df", argument("file", StringArgument.class.getName()))));
        Collection<String> keys = child.getKeys();
        Assert.assertEquals(1, keys.size());
        Assert.assertTrue(keys.contains("ls"));
        Assert.assertFalse(keys.contains("df"));
        Assert.assertTrue(system.getKeys().isEmpty());
    }

    @Test
    public void testRemoveClearsSyntaxAndArguments() {
        SyntaxManager child = system.createSyntaxManager();
        SyntaxBundle bundle = new SyntaxBundle("ls");
        child.add(bundle);
        child.add("ls", loadSpecs(argumentBundle("ls", argument("file", StringArgument.class.getName()))));
        Assert.assertSame(bundle, child.getSyntaxBundle("ls"));
        Assert.assertNotNull(child.getArgumentBundle("ls"));
        Assert.assertSame(bundle, child.remove("ls"));
        Assert.assertNull(child.getSyntaxBundle("ls"));
        Assert.assertNull(child.getArgumentBundle("ls"));
        Assert.assertFalse(child.getKeys().contains("ls"));
        Assert.assertNull(child.remove("ls"));
    }

    @Test
    public void testParentChainLookup() {
        SyntaxManager child = system.createSyntaxManager();
        SyntaxManager grandChild = child.createSyntaxManager();
        SyntaxBundle bundle = new SyntaxBundle("ls");
        child.add(bundle);
        child.add("ls", loadSpecs(argumentBundle("ls", argument("file", StringArgument.class.getName()))));
        Assert.assertSame(bundle, grandChild.getSyntaxBundle("ls"));
        Assert.assertNotNull(grandChild.getArgumentBundle("ls"));
        Assert.assertTrue(grandChild.getKeys().contains("ls"));
        SyntaxBundle own = new SyntaxBundle("cat");
        grandChild.add(own);
        Assert.assertSame(own, grandChild.getSyntaxBundle("cat"));
        Assert.assertNull(child.getSyntaxBundle("cat"));
        Assert.assertEquals(1, child.getKeys().size());
        Assert.assertEquals(2, grandChild.getKeys().size());
    }

    @Test
    public void testChildShadowsParentSyntax() {
        SyntaxManager child = system.createSyntaxManager();
        SyntaxBundle parentBundle = new SyntaxBundle("ls");
        SyntaxBundle childBundle = new SyntaxBundle("ls");
        child.add(parentBundle);
        SyntaxManager grandChild = child.createSyntaxManager();
        grandChild.add(childBundle);
        Assert.assertSame(childBundle, grandChild.getSyntaxBundle("ls"));
        Assert.assertSame(parentBundle, child.getSyntaxBundle("ls"));
    }

    @Test
    public void testGetArgumentBundleReturnsNullWhenInstantiateFails() {
        SyntaxManager child = system.createSyntaxManager();
        child.add("bad", loadSpecs(argumentBundle("bad", argument("ok", StringArgument.class.getName()),
            argument("boom", ThrowingArgument.class.getName()))));
        Assert.assertNull(child.getArgumentBundle("bad"));
    }

    @Test
    public void testRefreshSyntaxesIgnoresUnknownElementNames() {
        ConfigurationElement bogus = mock(ConfigurationElement.class);
        when(bogus.getName()).thenReturn("not-a-syntax");
        when(bogus.getElements()).thenReturn(new ConfigurationElement[0]);
        Extension ext = extension(bogus);
        ExtensionPoint ep = mock(ExtensionPoint.class);
        when(ep.getExtensions()).thenReturn(new Extension[]{ext});
        DefaultSyntaxManager mgr = new DefaultSyntaxManager(ep);
        Assert.assertTrue(mgr.getKeys().isEmpty());
        Assert.assertNull(mgr.getArgumentBundle("df"));
    }

    @Test
    public void testRefreshSyntaxesLoadsArgumentBundleElements() {
        ConfigurationElement arg = mock(ConfigurationElement.class);
        when(arg.getName()).thenReturn("argument");
        when(arg.getAttribute("label")).thenReturn("file");
        when(arg.getAttribute("type")).thenReturn(StringArgument.class.getName());
        when(arg.getElements()).thenReturn(new ConfigurationElement[0]);
        ConfigurationElement argBundle = mock(ConfigurationElement.class);
        when(argBundle.getName()).thenReturn("argument-bundle");
        when(argBundle.getAttribute("alias")).thenReturn("df");
        when(argBundle.getElements()).thenReturn(new ConfigurationElement[]{arg});
        Extension ext = extension(argBundle);
        ExtensionPoint ep = mock(ExtensionPoint.class);
        when(ep.getExtensions()).thenReturn(new Extension[]{ext});
        DefaultSyntaxManager mgr = new DefaultSyntaxManager(ep);
        ArgumentBundle bundle = mgr.getArgumentBundle("df");
        Assert.assertNotNull(bundle);
        Assert.assertEquals(1, countArguments(bundle));
        Assert.assertEquals("file", bundle.getArgument("file").getLabel());
        Assert.assertTrue(mgr.getKeys().isEmpty());
        Extension empty = extension();
        when(ep.getExtensions()).thenReturn(new Extension[]{empty});
        mgr.extensionAdded(ep, empty);
        Assert.assertNull(mgr.getArgumentBundle("df"));
        Assert.assertTrue(mgr.getKeys().isEmpty());
    }

    private static int countArguments(ArgumentBundle bundle) {
        int count = 0;
        for (Iterator<Argument<?>> it = bundle.iterator(); it.hasNext(); it.next()) {
            count++;
        }
        return count;
    }

    private static ExtensionPoint emptyExtensionPoint() {
        ExtensionPoint ep = mock(ExtensionPoint.class);
        when(ep.getExtensions()).thenReturn(new Extension[0]);
        return ep;
    }

    private static Extension extension(ConfigurationElement... elements) {
        Extension ext = mock(Extension.class);
        when(ext.getConfigurationElements()).thenReturn(elements);
        return ext;
    }

    private static ArgumentSpec<?>[] loadSpecs(SyntaxSpecAdapter element) {
        return new ArgumentSpecLoader().loadArguments(element);
    }

    private static Node argument(String label, String type) {
        Node node = new Node("argument");
        node.setAttribute("label", label);
        node.setAttribute("type", type);
        return node;
    }

    private static Node argumentBundle(String alias, Node... args) {
        Node node = new Node("argument-bundle");
        node.setAttribute("alias", alias);
        for (int i = 0; i < args.length; i++) {
            node.addChild(args[i]);
        }
        return node;
    }

    private static class Node implements SyntaxSpecAdapter {
        private final String name;
        private final List<Node> children = new ArrayList<Node>();
        private final Map<String, String> attributes = new HashMap<String, String>();

        Node(String name) {
            this.name = name;
        }

        void setAttribute(String key, String value) {
            attributes.put(key, value);
        }

        void addChild(Node child) {
            children.add(child);
        }

        public String getName() {
            return name;
        }

        public SyntaxSpecAdapter getChild(int childNo) {
            return children.get(childNo);
        }

        public int getNosChildren() {
            return children.size();
        }

        public String getAttribute(String attrName) {
            return attributes.get(attrName);
        }
    }

    public static class ThrowingArgument extends Argument<Object> {
        public ThrowingArgument(String label, int flags) {
            super(label, flags, new Object[0], null);
            throw new IllegalStateException("cannot instantiate " + label);
        }

        @Override
        protected Object doAccept(Token value, int flags) throws CommandSyntaxException {
            return value.text;
        }

        @Override
        protected String argumentKind() {
            return "throwing";
        }
    }
}