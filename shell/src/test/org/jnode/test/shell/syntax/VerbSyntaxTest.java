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
import org.jnode.shell.AbstractCommand;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.ArgumentBundle;
import org.jnode.shell.syntax.FlagArgument;
import org.jnode.shell.syntax.MuBackReference;
import org.jnode.shell.syntax.MuSequence;
import org.jnode.shell.syntax.MuSyntax;
import org.jnode.shell.syntax.Syntax;
import org.jnode.shell.syntax.SyntaxBundle;
import org.jnode.shell.syntax.SyntaxSpecLoader;
import org.jnode.shell.syntax.VerbSyntax;
import org.jnode.shell.syntax.XMLSyntaxSpecAdapter;
import org.junit.Assert;

public class VerbSyntaxTest {

    public static class Test extends AbstractCommand {
        private final FlagArgument flagArg = new FlagArgument("flagArg", Argument.OPTIONAL +
                Argument.SINGLE);

        public Test() {
            registerArguments(flagArg);
        }

        public void execute() throws Exception {
        }
    }

    @org.junit.Test
    public void testConstructor() {
        new VerbSyntax(null, "run", "flagArg", null, null);
        try {
            new VerbSyntax(null, "", "flagArg", null, null);
            Assert.fail("no exception");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        try {
            new VerbSyntax(null, "run", "", null, null);
            Assert.fail("no exception");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    @org.junit.Test
    public void testToXMLRoundTrip() {
        VerbSyntax original = new VerbSyntax("v", "run", "flagArg", null, "desc");
        XMLElement root = new XMLElement();
        root.setName("syntax");
        root.setAttribute("alias", "cmd");
        root.addChild(original.toXML());

        SyntaxSpecLoader loader = new SyntaxSpecLoader();
        SyntaxBundle bundle = loader.loadSyntax(new XMLSyntaxSpecAdapter(root));
        Assert.assertEquals("cmd", bundle.getAlias());
        Assert.assertEquals(1, bundle.getSyntaxes().length);

        Syntax loaded = bundle.getSyntaxes()[0];
        Assert.assertTrue(loaded instanceof VerbSyntax);
        Assert.assertEquals("v", loaded.getLabel());
        Assert.assertEquals("desc", loaded.getDescription());
        Assert.assertEquals("flagArg", ((VerbSyntax) loaded).getArgName());
        Assert.assertEquals("run", loaded.toXML().getStringAttribute("symbol"));
    }

    @org.junit.Test
    public void testLoadLegacyArgNameAttribute() {
        XMLElement root = new XMLElement();
        root.setName("syntax");
        root.setAttribute("alias", "cmd");
        XMLElement verb = new XMLElement();
        verb.setName("verb");
        verb.setAttribute("symbol", "run");
        verb.setAttribute("label", "v");
        verb.setAttribute("argName", "flagArg");
        root.addChild(verb);

        SyntaxBundle bundle = new SyntaxSpecLoader().loadSyntax(new XMLSyntaxSpecAdapter(root));
        Assert.assertEquals(1, bundle.getSyntaxes().length);
        VerbSyntax loaded = (VerbSyntax) bundle.getSyntaxes()[0];
        Assert.assertEquals("v", loaded.getLabel());
        Assert.assertEquals("flagArg", loaded.getArgName());
        Assert.assertEquals("run", loaded.toXML().getStringAttribute("symbol"));
    }

    @org.junit.Test
    public void testToXMLEmitsArgLabel() {
        XMLElement element = new VerbSyntax(null, "run", "flagArg", null, null).toXML();
        Assert.assertEquals("verb", element.getName());
        Assert.assertEquals("flagArg", element.getStringAttribute("argLabel"));
        Assert.assertNull(element.getStringAttribute("argName"));
    }

    @org.junit.Test
    public void testPrepareCarriesLabel() throws Exception {
        Test test = new Test();
        ArgumentBundle bundle = test.getArgumentBundle();
        VerbSyntax verb = new VerbSyntax("v", "run", "flagArg", null, null);
        MuSyntax mu = verb.prepare(bundle);
        Assert.assertEquals("v", mu.getLabel());
        Assert.assertEquals("<v> ::= 'run' <<flagArg=true>>", mu.format());

        MuSyntax root = new MuSequence("root", mu, new MuBackReference("v"));
        root.resolveBackReferences();
    }
}
