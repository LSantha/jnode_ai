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
import org.jnode.shell.Command;
import org.jnode.shell.CommandInfo;
import org.jnode.shell.CommandLine;
import org.jnode.shell.CommandLine.Token;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.CommandSyntaxException;
import org.jnode.shell.syntax.FileArgument;
import org.jnode.shell.syntax.IntegerArgument;
import org.jnode.shell.syntax.MuSymbol;
import org.jnode.shell.syntax.MuSyntax;
import org.jnode.shell.syntax.SymbolSyntax;
import org.jnode.shell.syntax.Syntax;
import org.junit.Assert;

public class SymbolSyntaxTest {

    public static class Test extends AbstractCommand {
        private final FileArgument fileArg = new FileArgument("fileArg", Argument.OPTIONAL +
                Argument.MULTIPLE);
        private final IntegerArgument intArg = new IntegerArgument("intArg", Argument.OPTIONAL +
                Argument.MULTIPLE);

        public Test() {
            registerArguments(fileArg, intArg);
        }

        public void execute() throws Exception {
        }
    }

    @org.junit.Test
    public void testConstructor() {
        SymbolSyntax syntax = new SymbolSyntax("sl", "run", "run it");
        Assert.assertEquals("sl", syntax.getLabel());
        Assert.assertEquals("run it", syntax.getDescription());
        Assert.assertTrue(syntax.isLabelled());
        Assert.assertFalse(new SymbolSyntax(null, "run", null).isLabelled());
    }

    @org.junit.Test
    public void testConstructorEmptySymbol() {
        try {
            new SymbolSyntax(null, "", null);
            Assert.fail("no exception");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("empty symbol", ex.getMessage());
        }
    }

    @org.junit.Test
    public void testFormat() {
        Test test = new Test();
        Assert.assertEquals("run",
            new SymbolSyntax(null, "run", null).format(test.getArgumentBundle()));
        Assert.assertEquals("run",
            new SymbolSyntax("sl", "run", "run it").format(test.getArgumentBundle()));
    }

    @org.junit.Test
    public void testPrepare() {
        MuSyntax muSyntax = new SymbolSyntax("sl", "run", null).prepare(new Test()
            .getArgumentBundle());
        Assert.assertTrue(muSyntax instanceof MuSymbol);
        MuSymbol symbol = (MuSymbol) muSyntax;
        Assert.assertEquals("run", symbol.getSymbol());
        Assert.assertEquals("sl", symbol.getLabel());
    }

    @org.junit.Test
    public void testPrepareWithoutLabel() {
        MuSyntax muSyntax = new SymbolSyntax(null, "run", null).prepare(new Test()
            .getArgumentBundle());
        Assert.assertNull(muSyntax.getLabel());
    }

    @org.junit.Test
    public void testMatch() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.SymbolSyntaxTest$Test");
        shell.addSyntax("cmd", new SymbolSyntax(null, "run", null));

        CommandLine cl = new CommandLine(new Token("cmd"), new Token[] {new Token("run")}, null);
        CommandInfo cmdInfo = cl.parseCommandLine(shell);
        Command cmd = cmdInfo.createCommandInstance();
        Assert.assertEquals(0, cmd.getArgumentBundle().getArgument("fileArg").getValues().length);
        Assert.assertEquals(0, cmd.getArgumentBundle().getArgument("intArg").getValues().length);
    }

    @org.junit.Test
    public void testMissingSymbol() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.SymbolSyntaxTest$Test");
        shell.addSyntax("cmd", new SymbolSyntax(null, "run", null));

        try {
            new CommandLine(new Token("cmd"), new Token[] {}, null).parseCommandLine(shell);
            Assert.fail("no exception");
        } catch (CommandSyntaxException ex) {
            Assert.assertEquals("ran out of alternatives", ex.getMessage());
        }
    }

    @org.junit.Test
    public void testNearMissRejected() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.SymbolSyntaxTest$Test");
        shell.addSyntax("cmd", new SymbolSyntax(null, "run", null));

        String[] nearMisses = new String[] {"ru", "runn", "RUN", "run1"};
        for (int i = 0; i < nearMisses.length; i++) {
            try {
                new CommandLine(new Token("cmd"), new Token[] {new Token(nearMisses[i])}, null)
                    .parseCommandLine(shell);
                Assert.fail("no exception for '" + nearMisses[i] + "'");
            } catch (CommandSyntaxException ex) {
                Assert.assertEquals("ran out of alternatives", ex.getMessage());
            }
        }
    }

    @org.junit.Test
    public void testTrailingTokenRejected() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.SymbolSyntaxTest$Test");
        shell.addSyntax("cmd", new SymbolSyntax(null, "run", null));

        try {
            new CommandLine(new Token("cmd"), new Token[] {new Token("run"), new Token("41")}, null)
                .parseCommandLine(shell);
            Assert.fail("no exception");
        } catch (CommandSyntaxException ex) {
            Assert.assertEquals("ran out of alternatives", ex.getMessage());
        }
    }

    @org.junit.Test
    public void testToXML() {
        XMLElement element = new SymbolSyntax("sl", "run", "desc").toXML();
        Assert.assertEquals("symbol", element.getName());
        Assert.assertEquals("run", element.getStringAttribute("symbol"));
        Assert.assertEquals("sl", element.getStringAttribute("label"));
        Assert.assertEquals("desc", element.getStringAttribute("description"));
        Assert.assertEquals(0, element.getChildren().size());
    }

    @org.junit.Test
    public void testToXMLWithoutLabel() {
        XMLElement element = new SymbolSyntax(null, "run", null).toXML();
        Assert.assertNull(element.getStringAttribute("label"));
        Assert.assertNull(element.getStringAttribute("description"));
    }

    @org.junit.Test
    public void testToXMLRoundTrip() {
        Syntax syntax = new SymbolSyntax("sl", "run", "desc");
        Syntax reloaded = TestSyntaxRoundTrip.roundTrip(syntax);
        Assert.assertEquals(SymbolSyntax.class, reloaded.getClass());
        Assert.assertEquals("sl", reloaded.getLabel());
        Assert.assertEquals("desc", reloaded.getDescription());
        Assert.assertEquals(syntax.prepare(new Test().getArgumentBundle()).format(),
            reloaded.prepare(new Test().getArgumentBundle()).format());
        Assert.assertEquals(syntax.toXML().toString(), reloaded.toXML().toString());
    }

    @org.junit.Test
    public void testToXMLRoundTripWithoutLabel() {
        Syntax syntax = new SymbolSyntax(null, "run", null);
        Syntax reloaded = TestSyntaxRoundTrip.roundTrip(syntax);
        Assert.assertEquals(SymbolSyntax.class, reloaded.getClass());
        Assert.assertFalse(reloaded.isLabelled());
        Assert.assertEquals(syntax.toXML().toString(), reloaded.toXML().toString());
    }

    @org.junit.Test
    public void testToString() {
        String str = new SymbolSyntax("sl", "run", "desc").toString();
        Assert.assertTrue(str, str.startsWith("SymbolSyntax{"));
        Assert.assertTrue(str, str.endsWith(",symbol=run}"));
    }

    @org.junit.Test
    public void testIsNotAGroup() {
        SymbolSyntax syntax = new SymbolSyntax(null, "run", null);
        Assert.assertEquals(0, syntax.getChildren().length);
        Assert.assertTrue(syntax.childLabels().isEmpty());
    }
}