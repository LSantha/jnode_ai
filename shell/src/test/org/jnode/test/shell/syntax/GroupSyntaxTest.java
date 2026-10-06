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

import org.jnode.nanoxml.XMLElement;
import org.jnode.shell.syntax.ArgumentBundle;
import org.jnode.shell.syntax.ArgumentSyntax;
import org.jnode.shell.syntax.GroupSyntax;
import org.jnode.shell.syntax.MuSyntax;
import org.jnode.shell.syntax.OptionalSyntax;
import org.jnode.shell.syntax.SequenceSyntax;
import org.jnode.shell.syntax.SymbolSyntax;
import org.jnode.shell.syntax.Syntax;
import org.junit.Assert;

public class GroupSyntaxTest {

    public static class ConcreteGroup extends GroupSyntax {

        public ConcreteGroup(String label, String description, Syntax... syntaxes) {
            super(label, description, syntaxes);
        }

        @Override
        public String format(ArgumentBundle bundle) {
            return "group";
        }

        @Override
        public MuSyntax prepare(ArgumentBundle bundle) {
            return null;
        }

        @Override
        public XMLElement toXML() {
            return basicElement("group");
        }
    }

    @org.junit.Test
    public void testGetChildrenReturnsComposedSyntaxes() {
        SymbolSyntax symbol = new SymbolSyntax("sl", "x", null);
        ArgumentSyntax arg = new ArgumentSyntax("al", "fileArg");
        GroupSyntax group = new ConcreteGroup("g", "desc", symbol, arg);
        Syntax[] children = group.getChildren();
        Assert.assertEquals(2, children.length);
        Assert.assertSame(symbol, children[0]);
        Assert.assertSame(arg, children[1]);
    }

    @org.junit.Test
    public void testGetChildrenOfEmptyGroup() {
        GroupSyntax group = new ConcreteGroup("g", null);
        Assert.assertEquals(0, group.getChildren().length);
    }

    @org.junit.Test
    public void testChildLabels() {
        SymbolSyntax symbol = new SymbolSyntax("sl", "x", null);
        GroupSyntax group = new ConcreteGroup("g", "desc", symbol, new ArgumentSyntax("al",
            "fileArg"));
        Assert.assertTrue(group.childLabels().isEmpty());
    }

    @org.junit.Test
    public void testChildLabelsIsEmptySetForZeroChildren() {
        GroupSyntax group = new ConcreteGroup("g", null);
        Assert.assertNotNull(group.childLabels());
        Assert.assertTrue(group.childLabels().isEmpty());
    }

    /**
     * A child label is only visible through childLabels() when the child itself is a
     * GroupSyntax that collects labels from its own children; a labelled leaf Syntax
     * reports an empty label set.
     */
    @org.junit.Test
    public void testChildLabelsDoesNotIncludeLeafLabels() {
        GroupSyntax inner = new SequenceSyntax("inner", new ArgumentSyntax("fileArg"));
        GroupSyntax outer = new ConcreteGroup("outer", null, inner);
        Assert.assertTrue(outer.childLabels().isEmpty());
    }

    @org.junit.Test
    public void testParentIsSetOnChildren() {
        SymbolSyntax symbol = new SymbolSyntax(null, "x", null);
        GroupSyntax group = new ConcreteGroup("g", null, symbol);
        Assert.assertSame(group, symbol.getParent());
        Assert.assertSame(group, symbol.getRoot());
    }

    @org.junit.Test
    public void testNestedChildRoot() {
        SymbolSyntax symbol = new SymbolSyntax(null, "x", null);
        GroupSyntax inner = new ConcreteGroup("inner", null, symbol);
        GroupSyntax outer = new ConcreteGroup("outer", null, inner);
        Assert.assertSame(outer, symbol.getRoot());
    }

    @org.junit.Test
    public void testBasicElementAddsChildren() {
        GroupSyntax group = new ConcreteGroup("g", "desc", new SymbolSyntax("sl", "x", "sd"),
            new ArgumentSyntax("al", "fileArg"));
        XMLElement element = group.toXML();
        Assert.assertEquals("group", element.getName());
        Assert.assertEquals("g", element.getStringAttribute("label"));
        Assert.assertEquals("desc", element.getStringAttribute("description"));
        Assert.assertEquals(2, element.getChildren().size());
        Assert.assertEquals("symbol", element.getChildren().get(0).getName());
        Assert.assertEquals("argument", element.getChildren().get(1).getName());
    }

    @org.junit.Test
    public void testBasicElementWithoutChildren() {
        XMLElement element = new ConcreteGroup("g", null).toXML();
        Assert.assertEquals(0, element.getChildren().size());
    }

    @org.junit.Test
    public void testToString() {
        String str = new ConcreteGroup("g", "desc", new SymbolSyntax(null, "x", null),
            new ArgumentSyntax("fileArg")).toString();
        Assert.assertTrue(str, str.startsWith("label='g', children=["));
        Assert.assertTrue(str, str.endsWith("]"));
        Assert.assertTrue(str, str.contains("symbol=x"));
        Assert.assertTrue(str, str.contains("argName=fileArg,flags=null"));
    }

    @org.junit.Test
    public void testToStringOfSingleChild() {
        String str = new ConcreteGroup("g", null, new SymbolSyntax(null, "x", null)).toString();
        Assert.assertTrue(str, str.startsWith("label='g', children=[SymbolSyntax{"));
        Assert.assertTrue(str, str.endsWith(",symbol=x}]"));
    }

    @org.junit.Test
    public void testToStringIsNotOverriddenByOptionalSyntaxBase() {
        GroupSyntax group = new OptionalSyntax("o", "desc", new SymbolSyntax(null, "x", null));
        String str = group.toString();
        Assert.assertTrue(str, str.startsWith("OptionalSyntax{"));
        Assert.assertTrue(str, str.contains("label='o'"));
        Assert.assertTrue(str, str.contains("symbol=x"));
    }
}