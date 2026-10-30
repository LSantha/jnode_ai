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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.jnode.plugin.ConfigurationElement;
import org.jnode.plugin.PluginDescriptor;
import org.jnode.shell.syntax.ArgumentSyntax;
import org.jnode.shell.syntax.PluginSyntaxSpecAdapter;
import org.jnode.shell.syntax.SymbolSyntax;
import org.jnode.shell.syntax.SyntaxBundle;
import org.jnode.shell.syntax.SyntaxFailureException;
import org.jnode.shell.syntax.SyntaxSpecAdapter;
import org.jnode.shell.syntax.SyntaxSpecLoader;
import org.junit.Assert;
import org.junit.Test;

public class PluginSyntaxSpecAdapterTest {

    @Test
    public void testGetName() {
        Assert.assertEquals("syntax", adapter(element("syntax", null)).getName());
    }

    @Test
    public void testGetAttribute() {
        SyntaxSpecAdapter adapter = adapter(element("syntax", attrs("alias", "foo")));
        Assert.assertEquals("foo", adapter.getAttribute("alias"));
        Assert.assertNull(adapter.getAttribute("bogus"));
    }

    @Test
    public void testGetAttributeReturnsNullForEmptyAttribute() {
        Assert.assertNull(adapter(element("syntax", attrs("alias", ""))).getAttribute("alias"));
    }

    @Test
    public void testGetNosChildrenAndGetChild() {
        SyntaxSpecAdapter adapter =
            adapter(element("syntax", attrs("alias", "foo"),
                             element("argument", attrs("argLabel", "a")),
                             element("argument", attrs("argLabel", "b"))));
        Assert.assertEquals(2, adapter.getNosChildren());
        Assert.assertEquals("argument", adapter.getChild(1).getName());
        Assert.assertEquals("b", adapter.getChild(1).getAttribute("argLabel"));
        Assert.assertEquals(0, adapter.getChild(1).getNosChildren());
    }

    @Test
    public void testEqualsAndHashCode() {
        ConfigurationElement root = element("syntax", attrs("alias", "foo"));
        SyntaxSpecAdapter adapter1 = adapter(root);
        SyntaxSpecAdapter adapter2 = adapter(root);
        Assert.assertEquals(adapter1, adapter2);
        Assert.assertEquals(adapter1.hashCode(), adapter2.hashCode());
        Assert.assertFalse(adapter1.equals(adapter(element("syntax", attrs("alias", "foo")))));
        Assert.assertFalse(adapter1.equals(null));
        Assert.assertFalse(adapter1.equals("syntax"));
    }

    @Test
    public void testLoadSyntaxFromPluginElements() {
        ConfigurationElement root =
            element("syntax", attrs("alias", "foo", "description", "the foo command"),
                    element("symbol", attrs("symbol", "foo")),
                    element("argument", attrs("argLabel", "rest")));
        SyntaxBundle bundle = new SyntaxSpecLoader().loadSyntax(adapter(root));
        Assert.assertEquals("foo", bundle.getAlias());
        Assert.assertEquals("the foo command", bundle.getDescription());
        Assert.assertEquals(2, bundle.getSyntaxes().length);
        Assert.assertEquals(SymbolSyntax.class, bundle.getSyntaxes()[0].getClass());
        Assert.assertEquals(ArgumentSyntax.class, bundle.getSyntaxes()[1].getClass());
        Assert.assertEquals("rest", ((ArgumentSyntax) bundle.getSyntaxes()[1]).getArgName());
    }

    @Test
    public void testMissingAliasIsAFailure() {
        try {
            new SyntaxSpecLoader().loadSyntax(
                adapter(element("syntax", null, element("symbol", attrs("symbol", "foo")))));
            Assert.fail("expected SyntaxFailureException");
        } catch (SyntaxFailureException ex) {
            Assert.assertEquals("syntax element has no 'alias' attribute", ex.getMessage());
        }
    }

    private static SyntaxSpecAdapter adapter(ConfigurationElement element) {
        return new PluginSyntaxSpecAdapter(element);
    }

    private static Map<String, String> attrs(String... nameValuePairs) {
        Map<String, String> attributes = new HashMap<String, String>();
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            attributes.put(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return attributes;
    }

    private static ConfigurationElement element(String name, Map<String, String> attributes,
                                                ConfigurationElement... children) {
        return new StubConfigurationElement(name, attributes, children);
    }

    private static class StubConfigurationElement implements ConfigurationElement {
        private final String name;
        private final Map<String, String> attributes;
        private final ConfigurationElement[] children;

        StubConfigurationElement(String name, Map<String, String> attributes,
                                 ConfigurationElement[] children) {
            this.name = name;
            this.attributes = (attributes == null)
                ? new HashMap<String, String>() : attributes;
            this.children = children;
        }

        public String getName() {
            return name;
        }

        public ConfigurationElement[] getElements() {
            return children;
        }

        public String getAttribute(String name) {
            return attributes.get(name);
        }

        public Set<String> attributeNames() {
            return new HashSet<String>(attributes.keySet());
        }

        public PluginDescriptor getDeclaringPluginDescriptor() {
            return null;
        }
    }
}
