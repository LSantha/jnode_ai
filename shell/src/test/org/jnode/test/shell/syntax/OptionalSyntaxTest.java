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
import org.jnode.shell.CommandLine;
import org.jnode.shell.CommandLine.Token;
import org.jnode.shell.syntax.AlternativesSyntax;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.ArgumentSyntax;
import org.jnode.shell.syntax.CommandSyntaxException;
import org.jnode.shell.syntax.FileArgument;
import org.jnode.shell.syntax.IntegerArgument;
import org.jnode.shell.syntax.MuAlternation;
import org.jnode.shell.syntax.MuSyntax;
import org.jnode.shell.syntax.OptionalSyntax;
import org.jnode.shell.syntax.SymbolSyntax;
import org.jnode.shell.syntax.Syntax;
import org.junit.Assert;

public class OptionalSyntaxTest {

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
    public void testConstructors() {
        new OptionalSyntax();
        new OptionalSyntax(new SymbolSyntax(null, "x", null));
        new OptionalSyntax("o");
        new OptionalSyntax("o", new SymbolSyntax(null, "x", null));
        new OptionalSyntax("o", "desc", new SymbolSyntax(null, "x", null));
        new OptionalSyntax("o", "desc", true, new SymbolSyntax(null, "x", null));
        new OptionalSyntax("o", "desc", false, new SymbolSyntax(null, "x", null));
    }

    @org.junit.Test
    public void testFormat() {
        Test test = new Test();
        Assert.assertEquals("",
            new OptionalSyntax().format(test.getArgumentBundle()));
        Assert.assertEquals("[ <intArg> ]",
            new OptionalSyntax(new ArgumentSyntax("intArg")).format(test.getArgumentBundle()));
        Assert.assertEquals("[ x y ]",
            new OptionalSyntax(new SymbolSyntax(null, "x", null),
                new SymbolSyntax(null, "y", null)).format(test.getArgumentBundle()));
    }

    @org.junit.Test
    public void testFormatBracketsNestedGroup() {
        Test test = new Test();
        Assert.assertEquals("[ ( <intArg> | <fileArg> ) ]",
            new OptionalSyntax(new AlternativesSyntax(new ArgumentSyntax("intArg"),
                new ArgumentSyntax("fileArg"))).format(test.getArgumentBundle()));
    }

    @org.junit.Test
    public void testPrepareWithNoChildren() {
        Assert.assertNull(new OptionalSyntax("o", "desc").prepare(new Test().getArgumentBundle()));
    }

    @org.junit.Test
    public void testPrepareLazy() {
        MuSyntax muSyntax =
            new OptionalSyntax("o", "desc", false, new SymbolSyntax(null, "x", null))
                .prepare(new Test().getArgumentBundle());
        Assert.assertTrue(muSyntax instanceof MuAlternation);
        MuAlternation alternation = (MuAlternation) muSyntax;
        Assert.assertEquals(2, alternation.getAlternatives().length);
        Assert.assertNull(alternation.getAlternatives()[0]);
        Assert.assertNotNull(alternation.getAlternatives()[1]);
        Assert.assertEquals("<o> ::= (  | 'x' )", muSyntax.format());
    }

    @org.junit.Test
    public void testPrepareEager() {
        MuSyntax muSyntax = new OptionalSyntax("o", "desc", true, new SymbolSyntax(null, "x", null))
            .prepare(new Test().getArgumentBundle());
        Assert.assertTrue(muSyntax instanceof MuAlternation);
        MuAlternation alternation = (MuAlternation) muSyntax;
        Assert.assertEquals(2, alternation.getAlternatives().length);
        Assert.assertNotNull(alternation.getAlternatives()[0]);
        Assert.assertNull(alternation.getAlternatives()[1]);
        Assert.assertEquals("<o> ::= ( 'x' |  )", muSyntax.format());
    }

    @org.junit.Test
    public void testPrepareWithSeveralChildren() {
        MuSyntax muSyntax =
            new OptionalSyntax("o", "desc", false, new SymbolSyntax(null, "x", null),
                new SymbolSyntax(null, "y", null)).prepare(new Test().getArgumentBundle());
        Assert.assertEquals("<o> ::= (  | 'x' 'y' )", muSyntax.format());
    }

    @org.junit.Test
    public void testAbsentLazy() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.OptionalSyntaxTest$Test");
        shell.addSyntax("cmd", new OptionalSyntax("o", "desc", false,
            new ArgumentSyntax("intArg")));

        CommandLine cl = new CommandLine(new Token("cmd"), new Token[] {}, null);
        cl.parseCommandLine(shell);
    }

    @org.junit.Test
    public void testPresentLazy() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.OptionalSyntaxTest$Test");
        shell.addSyntax("cmd", new OptionalSyntax("o", "desc", false,
            new ArgumentSyntax("intArg")));

        new CommandLine(new Token("cmd"), new Token[] {new Token("41")}, null)
            .parseCommandLine(shell);
    }

    @org.junit.Test
    public void testPresentLazyBindsArgument() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.OptionalSyntaxTest$Test");
        shell.addSyntax("cmd", new OptionalSyntax("o", "desc", false,
            new ArgumentSyntax("intArg")));

        CommandLine cl = new CommandLine(new Token("cmd"), new Token[] {new Token("41")}, null);
        Command cmd = cl.parseCommandLine(shell).createCommandInstance();
        Assert.assertEquals("41",
            cmd.getArgumentBundle().getArgument("intArg").getValues()[0].toString());
    }

    @org.junit.Test
    public void testAbsentEager() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.OptionalSyntaxTest$Test");
        shell.addSyntax("cmd", new OptionalSyntax("o", "desc", true,
            new ArgumentSyntax("intArg")));

        CommandLine cl = new CommandLine(new Token("cmd"), new Token[] {}, null);
        cl.parseCommandLine(shell);
    }

    @org.junit.Test
    public void testPresentEager() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.OptionalSyntaxTest$Test");
        shell.addSyntax("cmd", new OptionalSyntax("o", "desc", true,
            new ArgumentSyntax("intArg")));

        CommandLine cl = new CommandLine(new Token("cmd"), new Token[] {new Token("41")}, null);
        cl.parseCommandLine(shell);
    }

    @org.junit.Test
    public void testPartialMatchRejected() throws Exception {
        for (int i = 0; i < 2; i++) {
            TestShell shell = new TestShell();
            shell.addAlias("cmd", "org.jnode.test.shell.syntax.OptionalSyntaxTest$Test");
            shell.addSyntax("cmd",
                new OptionalSyntax("o", "desc", i == 1, new SymbolSyntax(null, "x", null),
                    new SymbolSyntax(null, "y", null)));
            try {
                new CommandLine(new Token("cmd"), new Token[] {new Token("x")}, null)
                    .parseCommandLine(shell);
                Assert.fail("no exception for eager=" + (i == 1));
            } catch (CommandSyntaxException ex) {
                Assert.assertEquals("ran out of alternatives", ex.getMessage());
            }
        }
    }

    @org.junit.Test
    public void testEmptyOptionalMatchesOnlyEmptyInput() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.OptionalSyntaxTest$Test");
        shell.addSyntax("cmd", new OptionalSyntax("o", "desc"));

        new CommandLine(new Token("cmd"), new Token[] {}, null).parseCommandLine(shell);
        try {
            new CommandLine(new Token("cmd"), new Token[] {new Token("41")}, null)
                .parseCommandLine(shell);
            Assert.fail("no exception");
        } catch (CommandSyntaxException ex) {
            Assert.assertEquals("No arguments expected for this command", ex.getMessage());
        }
    }

    @org.junit.Test
    public void testToXMLLazy() {
        XMLElement element = new OptionalSyntax("o", "desc", false,
            new SymbolSyntax("sl", "x", "sd")).toXML();
        Assert.assertEquals("optional", element.getName());
        Assert.assertEquals("o", element.getStringAttribute("label"));
        Assert.assertEquals("desc", element.getStringAttribute("description"));
        Assert.assertNull(element.getStringAttribute("eager"));
        Assert.assertEquals(1, element.getChildren().size());
        Assert.assertEquals("symbol", element.getChildren().get(0).getName());
    }

    @org.junit.Test
    public void testToXMLEager() {
        XMLElement element =
            new OptionalSyntax("o", "desc", true, new SymbolSyntax(null, "x", null)).toXML();
        Assert.assertEquals("true", element.getStringAttribute("eager"));
    }

    @org.junit.Test
    public void testToXMLRoundTripLazy() {
        Syntax syntax = new OptionalSyntax("o", "desc", false, new SymbolSyntax("sl", "x", "sd"),
            new ArgumentSyntax("al", "fileArg"));
        Syntax reloaded = TestSyntaxRoundTrip.roundTrip(syntax);
        Assert.assertEquals(OptionalSyntax.class, reloaded.getClass());
        Assert.assertEquals("o", reloaded.getLabel());
        Assert.assertEquals("desc", reloaded.getDescription());
        Assert.assertEquals(2, reloaded.getChildren().length);
        Assert.assertEquals(SymbolSyntax.class, reloaded.getChildren()[0].getClass());
        Assert.assertEquals("sl", reloaded.getChildren()[0].getLabel());
        Assert.assertEquals("sd", reloaded.getChildren()[0].getDescription());
        Assert.assertEquals(ArgumentSyntax.class, reloaded.getChildren()[1].getClass());
        Assert.assertEquals("al", reloaded.getChildren()[1].getLabel());
        Assert.assertEquals(syntax.toXML().toString(), reloaded.toXML().toString());
    }

    @org.junit.Test
    public void testToXMLRoundTripEager() {
        Syntax syntax = new OptionalSyntax("o", "desc", true, new SymbolSyntax("sl", "x", "sd"),
            new ArgumentSyntax("al", "fileArg"));
        Syntax reloaded = TestSyntaxRoundTrip.roundTrip(syntax);
        Assert.assertEquals(OptionalSyntax.class, reloaded.getClass());
        Assert.assertEquals("true", reloaded.toXML().getStringAttribute("eager"));
        Assert.assertEquals(syntax.toXML().toString(), reloaded.toXML().toString());
    }

    /**
     * The eager attribute is only serialized when it is true, so a lazy OptionalSyntax
     * round-trips as lazy; an explicit eager="false" attribute is mis-read by
     * SyntaxSpecLoader.getFlag() as true.
     */
    @org.junit.Test
    public void testToXMLRoundTripKeepsLazy() {
        Syntax syntax = new OptionalSyntax("o", "desc", false, new SymbolSyntax(null, "x", null));
        Syntax reloaded = TestSyntaxRoundTrip.roundTrip(syntax);
        Assert.assertNull(reloaded.toXML().getStringAttribute("eager"));
        Assert.assertEquals(syntax.toXML().toString(), reloaded.toXML().toString());
    }

    @org.junit.Test
    public void testToString() {
        String str =
            new OptionalSyntax("o", "desc", true, new SymbolSyntax(null, "x", null)).toString();
        Assert.assertTrue(str, str.startsWith("OptionalSyntax{"));
        Assert.assertTrue(str, str.contains("label='o'"));
        Assert.assertTrue(str, str.contains("symbol=x"));
        Assert.assertTrue(str, str.endsWith(",eager=true}"));
    }
}