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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jnode.nanoxml.XMLElement;
import org.jnode.shell.syntax.ArgumentSyntax;
import org.jnode.shell.syntax.GroupSyntax;
import org.jnode.shell.syntax.OptionalSyntax;
import org.jnode.shell.syntax.SequenceSyntax;
import org.jnode.shell.syntax.SymbolSyntax;
import org.jnode.shell.syntax.Syntax;
import org.junit.Assert;

/**
 * Tests for the base class of syntaxes that compose other syntaxes.
 */
public class GroupSyntaxTest {

    @org.junit.Test
    public void testGetChildren() {
        Syntax arg = new ArgumentSyntax("fileArg");
        Syntax intArg = new ArgumentSyntax("intArg");
        GroupSyntax g = new SequenceSyntax(arg, intArg);
        Syntax[] children = g.getChildren();
        Assert.assertEquals(2, children.length);
        Assert.assertSame(arg, children[0]);
        Assert.assertSame(intArg, children[1]);
        Assert.assertEquals(0, new SequenceSyntax().getChildren().length);
    }

    @org.junit.Test
    public void testChildLabelsIncludesOwnChildren() {
        GroupSyntax g =
            new OptionalSyntax("outer", "d", new ArgumentSyntax("fileLabel", "fileArg"));
        Set<String> expected =
            new HashSet<String>(Arrays.asList(new String[] {"fileLabel"}));
        Assert.assertEquals(expected, g.childLabels());
    }

    @org.junit.Test
    public void testChildLabelsIncludesNestedChildren() {
        GroupSyntax inner = new SequenceSyntax("inner", new ArgumentSyntax("fileLabel", "fileArg"));
        GroupSyntax outer = new OptionalSyntax("outer", "d", inner);
        Set<String> expected =
            new HashSet<String>(Arrays.asList(new String[] {"inner", "fileLabel"}));
        Assert.assertEquals(expected, outer.childLabels());
    }

    @org.junit.Test
    public void testChildLabelsSkipsUnlabelledChildren() {
        GroupSyntax g = new SequenceSyntax(new ArgumentSyntax("fileArg"),
            new ArgumentSyntax("intLabel", "intArg"), new SymbolSyntax(null, "-x", null));
        Set<String> expected =
            new HashSet<String>(Arrays.asList(new String[] {"intLabel"}));
        Assert.assertEquals(expected, g.childLabels());
    }

    @org.junit.Test
    public void testChildLabelsOfLeafIsEmpty() {
        Assert.assertTrue(new ArgumentSyntax("fileArg").childLabels().isEmpty());
        Assert.assertTrue(new SequenceSyntax().childLabels().isEmpty());
    }

    @org.junit.Test
    public void testParentAndRootWiring() {
        Syntax arg = new ArgumentSyntax("fileArg");
        GroupSyntax inner = new SequenceSyntax("inner", arg);
        GroupSyntax outer = new OptionalSyntax("outer", "d", inner);
        Assert.assertSame(outer, inner.getParent());
        Assert.assertSame(inner, arg.getParent());
        Assert.assertSame(outer, arg.getRoot());
        Assert.assertNull(outer.getParent());
    }

    @org.junit.Test
    public void testNullChildIsTolerated() {
        Syntax sym = new SymbolSyntax("s", "-x", null);
        GroupSyntax g = new OptionalSyntax("o", new Syntax[] {sym, null});
        Assert.assertEquals(2, g.getChildren().length);
        Assert.assertSame(sym, g.getChildren()[0]);
        Assert.assertNull(g.getChildren()[1]);
        Assert.assertSame(g, sym.getParent());
        Set<String> expected = new HashSet<String>(Arrays.asList(new String[] {"s"}));
        Assert.assertEquals(expected, g.childLabels());
    }

    @org.junit.Test
    public void testBasicElementSerializesNullChildAsEmpty() {
        Syntax sym = new SymbolSyntax("s", "-x", null);
        GroupSyntax g = new OptionalSyntax("o", new Syntax[] {sym, null});
        XMLElement element = g.toXML();
        Assert.assertEquals("optional", element.getName());
        List<XMLElement> children = element.getChildren();
        Assert.assertEquals(2, children.size());
        Assert.assertEquals("symbol", children.get(0).getName());
        Assert.assertEquals("-x", children.get(0).getAttribute("symbol"));
        Assert.assertEquals("empty", children.get(1).getName());
    }

    @org.junit.Test
    public void testToString() {
        GroupSyntax g = new OptionalSyntax("outer", "d", new ArgumentSyntax("fileArg"));
        String str = g.toString();
        Assert.assertTrue(str, str.contains("label='outer'"));
        Assert.assertTrue(str, str.contains("fileArg"));
        Assert.assertTrue(str, str.contains("children=["));
        Assert.assertEquals("label='null', children=[]", new SequenceSyntax().toString());
    }

    @org.junit.Test
    public void testToStringOfSingleChild() {
        GroupSyntax g = new OptionalSyntax("outer", "d", new SequenceSyntax("inner",
            new ArgumentSyntax("fileArg")));
        String str = g.toString();
        Assert.assertTrue(str, str.contains("label='outer'"));
        Assert.assertTrue(str, str.contains("label='inner'"));
        Assert.assertTrue(str, str.contains("fileArg"));
    }
}