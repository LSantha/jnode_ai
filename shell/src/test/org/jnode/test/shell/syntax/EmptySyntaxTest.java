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
import org.jnode.shell.syntax.AlternativesSyntax;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.CommandSyntaxException;
import org.jnode.shell.syntax.EmptySyntax;
import org.jnode.shell.syntax.FileArgument;
import org.jnode.shell.syntax.IntegerArgument;
import org.jnode.shell.syntax.SymbolSyntax;
import org.jnode.shell.syntax.Syntax;
import org.junit.Assert;

public class EmptySyntaxTest {

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
        EmptySyntax syntax = new EmptySyntax("el", "nothing at all");
        Assert.assertEquals("el", syntax.getLabel());
        Assert.assertEquals("nothing at all", syntax.getDescription());
        Assert.assertTrue(syntax.isLabelled());
        Assert.assertFalse(new EmptySyntax(null, null).isLabelled());
    }

    @org.junit.Test
    public void testFormat() {
        Test test = new Test();
        Assert.assertEquals("", new EmptySyntax(null, null).format(test.getArgumentBundle()));
        Assert.assertEquals("", new EmptySyntax("el", "desc").format(test.getArgumentBundle()));
    }

    @org.junit.Test
    public void testPrepare() {
        Assert.assertNull(new EmptySyntax("el", "desc").prepare(new Test().getArgumentBundle()));
    }

    @org.junit.Test
    public void testEmptyInputMatches() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.EmptySyntaxTest$Test");
        shell.addSyntax("cmd", new EmptySyntax("el", "desc"));

        CommandLine cl = new CommandLine(new Token("cmd"), new Token[] {}, null);
        CommandInfo cmdInfo = cl.parseCommandLine(shell);
        Command cmd = cmdInfo.createCommandInstance();
        Assert.assertEquals(0, cmd.getArgumentBundle().getArgument("fileArg").getValues().length);
        Assert.assertEquals(0, cmd.getArgumentBundle().getArgument("intArg").getValues().length);
    }

    @org.junit.Test
    public void testNonEmptyInputRejected() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.EmptySyntaxTest$Test");
        shell.addSyntax("cmd", new EmptySyntax("el", "desc"));

        try {
            new CommandLine(new Token("cmd"), new Token[] {new Token("41")}, null)
                .parseCommandLine(shell);
            Assert.fail("no exception");
        } catch (CommandSyntaxException ex) {
            Assert.assertEquals("No arguments expected for this command", ex.getMessage());
        }
    }

    @org.junit.Test
    public void testMatchesEmptyAlternative() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.EmptySyntaxTest$Test");
        shell.addSyntax("cmd", new AlternativesSyntax(new EmptySyntax(null, null),
            new SymbolSyntax(null, "run", null)));

        new CommandLine(new Token("cmd"), new Token[] {}, null).parseCommandLine(shell);

        CommandLine cl = new CommandLine(new Token("cmd"), new Token[] {new Token("run")}, null);
        CommandInfo cmdInfo = cl.parseCommandLine(shell);
        Command cmd = cmdInfo.createCommandInstance();
        Assert.assertEquals(0, cmd.getArgumentBundle().getArgument("intArg").getValues().length);
    }

    @org.junit.Test
    public void testToXML() {
        XMLElement element = new EmptySyntax("el", "desc").toXML();
        Assert.assertEquals("empty", element.getName());
        Assert.assertEquals("el", element.getStringAttribute("label"));
        Assert.assertEquals("desc", element.getStringAttribute("description"));
        Assert.assertEquals(0, element.getChildren().size());
    }

    @org.junit.Test
    public void testToXMLWithoutLabel() {
        XMLElement element = new EmptySyntax(null, null).toXML();
        Assert.assertEquals("empty", element.getName());
        Assert.assertNull(element.getStringAttribute("label"));
        Assert.assertNull(element.getStringAttribute("description"));
    }

    @org.junit.Test
    public void testToXMLRoundTrip() {
        Syntax syntax = new EmptySyntax("el", "desc");
        Syntax reloaded = TestSyntaxRoundTrip.roundTrip(syntax);
        Assert.assertEquals(EmptySyntax.class, reloaded.getClass());
        Assert.assertEquals("el", reloaded.getLabel());
        Assert.assertEquals("desc", reloaded.getDescription());
        Assert.assertNull(reloaded.prepare(new Test().getArgumentBundle()));
        Assert.assertEquals(syntax.toXML().toString(), reloaded.toXML().toString());
    }

    @org.junit.Test
    public void testToXMLRoundTripWithoutLabel() {
        Syntax syntax = new EmptySyntax(null, null);
        Syntax reloaded = TestSyntaxRoundTrip.roundTrip(syntax);
        Assert.assertEquals(EmptySyntax.class, reloaded.getClass());
        Assert.assertFalse(reloaded.isLabelled());
        Assert.assertEquals(syntax.toXML().toString(), reloaded.toXML().toString());
    }

    @org.junit.Test
    public void testToXMLRoundTripAsAlternative() {
        Syntax syntax = new AlternativesSyntax(new EmptySyntax("el", "desc"),
            new SymbolSyntax("sl", "run", "sd"));
        Syntax reloaded = TestSyntaxRoundTrip.roundTrip(syntax);
        Assert.assertEquals(2, reloaded.getChildren().length);
        Assert.assertEquals(EmptySyntax.class, reloaded.getChildren()[0].getClass());
        Assert.assertEquals("el", reloaded.getChildren()[0].getLabel());
        Assert.assertEquals("desc", reloaded.getChildren()[0].getDescription());
        Assert.assertEquals(SymbolSyntax.class, reloaded.getChildren()[1].getClass());
        Assert.assertEquals(syntax.toXML().toString(), reloaded.toXML().toString());
    }

    @org.junit.Test
    public void testToStringUsesDefaultSyntaxToString() {
        String str = new EmptySyntax("el", "desc").toString();
        Assert.assertTrue(str, str.startsWith("org.jnode.shell.syntax.EmptySyntax@"));
    }

    @org.junit.Test
    public void testIsNotAGroup() {
        EmptySyntax syntax = new EmptySyntax(null, null);
        Assert.assertEquals(0, syntax.getChildren().length);
        Assert.assertTrue(syntax.childLabels().isEmpty());
    }
}