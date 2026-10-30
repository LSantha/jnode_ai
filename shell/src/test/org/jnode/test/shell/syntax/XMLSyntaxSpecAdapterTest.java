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
import org.jnode.nanoxml.XMLParseException;
import org.jnode.shell.syntax.SyntaxBundle;
import org.jnode.shell.syntax.SyntaxSpecAdapter;
import org.jnode.shell.syntax.SyntaxSpecLoader;
import org.jnode.shell.syntax.XMLSyntaxSpecAdapter;
import org.junit.Assert;
import org.junit.Test;

public class XMLSyntaxSpecAdapterTest {

    private XMLSyntaxSpecAdapter adapter(String xml) {
        XMLElement element = new XMLElement();
        element.parseString(xml);
        return new XMLSyntaxSpecAdapter(element);
    }

    @Test
    public void testGetName() {
        Assert.assertEquals("syntax", adapter("<syntax alias='foo'/>").getName());
    }

    @Test
    public void testGetAttribute() {
        SyntaxSpecAdapter adapter = adapter("<syntax alias='foo' description='bar'/>");
        Assert.assertEquals("foo", adapter.getAttribute("alias"));
        Assert.assertEquals("bar", adapter.getAttribute("description"));
    }

    @Test
    public void testGetAttributeReturnsNullForUnknownAttribute() {
        Assert.assertNull(adapter("<syntax alias='foo'/>").getAttribute("bogus"));
    }

    @Test
    public void testGetAttributeReturnsNullForEmptyAttribute() {
        Assert.assertNull(adapter("<syntax alias='foo' empty=''/>").getAttribute("empty"));
    }

    @Test
    public void testGetNosChildrenAndGetChild() {
        SyntaxSpecAdapter adapter =
            adapter("<syntax alias='foo'><argument argLabel='a'/><argument argLabel='b'/>"
                    + "</syntax>");
        Assert.assertEquals(2, adapter.getNosChildren());
        Assert.assertEquals("argument", adapter.getChild(0).getName());
        Assert.assertEquals("a", adapter.getChild(0).getAttribute("argLabel"));
        Assert.assertEquals("b", adapter.getChild(1).getAttribute("argLabel"));
        Assert.assertEquals(0, adapter.getChild(0).getNosChildren());
    }

    @Test
    public void testGetNosChildrenOfEmptyElement() {
        Assert.assertEquals(0, adapter("<syntax alias='foo'/>").getNosChildren());
    }

    @Test
    public void testEqualsAndHashCode() {
        XMLElement element = new XMLElement();
        element.parseString("<syntax alias='foo'><argument argLabel='a'/></syntax>");
        XMLSyntaxSpecAdapter adapter1 = new XMLSyntaxSpecAdapter(element);
        XMLSyntaxSpecAdapter adapter2 = new XMLSyntaxSpecAdapter(element);
        XMLSyntaxSpecAdapter other = adapter("<syntax alias='foo'/>");
        Assert.assertEquals(adapter1, adapter2);
        Assert.assertEquals(adapter1.hashCode(), adapter2.hashCode());
        Assert.assertEquals(adapter1, adapter1);
        Assert.assertFalse(adapter1.equals(other));
        Assert.assertFalse(adapter1.equals(null));
        Assert.assertFalse(adapter1.equals("syntax"));
    }

    @Test
    public void testLoadSyntaxFromXMLElement() {
        XMLElement element = new XMLElement();
        element.parseString("<syntax><argument argLabel='rest'/></syntax>");
        element.setAttribute("alias", "foo");
        SyntaxBundle bundle = new SyntaxSpecLoader().loadSyntax(new XMLSyntaxSpecAdapter(element));
        Assert.assertEquals("foo", bundle.getAlias());
        Assert.assertEquals(1, bundle.getSyntaxes().length);
    }

    @Test
    public void testMalformedXMLFailsAtParseTime() {
        try {
            adapter("<syntax alias='foo'><argument argLabel='a'></syntax>");
            Assert.fail("expected XMLParseException");
        } catch (XMLParseException ex) {
            // expected
        }
    }

    @Test
    public void testUnclosedTagFailsAtParseTime() {
        try {
            adapter("<syntax alias='foo'>");
            Assert.fail("expected XMLParseException");
        } catch (XMLParseException ex) {
            // expected
        }
    }

    @Test
    public void testNonXMLElementTextFailsAtParseTime() {
        try {
            adapter("this is not xml");
            Assert.fail("expected XMLParseException");
        } catch (XMLParseException ex) {
            // expected
        }
    }

    @Test
    public void testMismatchedEndTagFailsAtParseTime() {
        try {
            adapter("<syntax alias='foo'></sequence>");
            Assert.fail("expected XMLParseException");
        } catch (XMLParseException ex) {
            // expected
        }
    }
}
