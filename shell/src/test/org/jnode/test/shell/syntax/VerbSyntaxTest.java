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
import org.jnode.shell.syntax.FlagArgument;
import org.jnode.shell.syntax.IntegerArgument;
import org.jnode.shell.syntax.MuPreset;
import org.jnode.shell.syntax.MuSequence;
import org.jnode.shell.syntax.MuSymbol;
import org.jnode.shell.syntax.MuSyntax;
import org.jnode.shell.syntax.Syntax;
import org.jnode.shell.syntax.SyntaxFailureException;
import org.jnode.shell.syntax.VerbSyntax;
import org.junit.Assert;

public class VerbSyntaxTest {

    public static class Test extends AbstractCommand {
        private final IntegerArgument intArg = new IntegerArgument("intArg", Argument.OPTIONAL +
                Argument.MULTIPLE);
        private final FlagArgument flagArg = new FlagArgument("flagArg", Argument.OPTIONAL +
                Argument.MULTIPLE);

        public Test() {
            registerArguments(intArg, flagArg);
        }

        public void execute() throws Exception {
        }
    }

    @org.junit.Test
    public void testConstructor() {
        VerbSyntax syntax = new VerbSyntax("verb", "run", "flagArg", null, "run the thing");
        Assert.assertEquals("verb", syntax.getLabel());
        Assert.assertEquals("flagArg", syntax.getArgName());
        Assert.assertEquals("run the thing", syntax.getDescription());
        Assert.assertTrue(syntax.isLabelled());
        Assert.assertFalse(new VerbSyntax(null, "run", "flagArg", null, null).isLabelled());
    }

    @org.junit.Test
    public void testConstructorEmptySymbol() {
        try {
            new VerbSyntax(null, "", "flagArg", null, null);
            Assert.fail("no exception");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("empty symbol", ex.getMessage());
        }
    }

    @org.junit.Test
    public void testConstructorEmptyArgName() {
        try {
            new VerbSyntax(null, "run", "", null, null);
            Assert.fail("no exception");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("empty argName", ex.getMessage());
        }
    }

    @org.junit.Test
    public void testFormat() {
        Test test = new Test();
        Assert.assertEquals("run",
            new VerbSyntax(null, "run", "flagArg", null, null).format(test.getArgumentBundle()));
        Assert.assertEquals("run",
            new VerbSyntax("verb", "run", "flagArg", null, null).format(test.getArgumentBundle()));
    }

    @org.junit.Test
    public void testFormatIgnoresUnmatchedArgName() {
        Test test = new Test();
        Assert.assertEquals("run",
            new VerbSyntax(null, "run", "noSuchArg", null, null).format(test.getArgumentBundle()));
    }

    @org.junit.Test
    public void testPrepare() {
        Test test = new Test();
        MuSyntax muSyntax =
            new VerbSyntax("verb", "run", "flagArg", null, null).prepare(test.getArgumentBundle());
        Assert.assertTrue(muSyntax instanceof MuSequence);
        MuSequence sequence = (MuSequence) muSyntax;
        Assert.assertEquals(2, sequence.getElements().length);
        Assert.assertTrue(sequence.getElements()[0] instanceof MuSymbol);
        Assert.assertEquals("run", ((MuSymbol) sequence.getElements()[0]).getSymbol());
        Assert.assertTrue(sequence.getElements()[1] instanceof MuPreset);
        MuPreset preset = (MuPreset) sequence.getElements()[1];
        Assert.assertEquals("flagArg", preset.getArgName());
        Assert.assertEquals("true", preset.getPreset());
    }

    @org.junit.Test
    public void testPrepareUnmatchedArgName() {
        Test test = new Test();
        try {
            new VerbSyntax(null, "run", "noSuchArg", null, null).prepare(test.getArgumentBundle());
            Assert.fail("no exception");
        } catch (SyntaxFailureException ex) {
            Assert.assertEquals("No argument for syntax label 'noSuchArg'", ex.getMessage());
        }
    }

    @org.junit.Test
    public void testMatch() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.VerbSyntaxTest$Test");
        shell.addSyntax("cmd", new VerbSyntax(null, "run", "flagArg", null, null));

        CommandLine cl =
            new CommandLine(new Token("cmd"), new Token[] {new Token("run")}, null);
        CommandInfo cmdInfo = cl.parseCommandLine(shell);
        Command cmd = cmdInfo.createCommandInstance();
        Assert.assertEquals(1, cmd.getArgumentBundle().getArgument("flagArg").getValues().length);
        Assert.assertEquals(Boolean.TRUE,
            cmd.getArgumentBundle().getArgument("flagArg").getValues()[0]);
        Assert.assertEquals(0, cmd.getArgumentBundle().getArgument("intArg").getValues().length);
    }

    @org.junit.Test
    public void testMissingVerb() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.VerbSyntaxTest$Test");
        shell.addSyntax("cmd", new VerbSyntax(null, "run", "flagArg", null, null));

        try {
            new CommandLine(new Token("cmd"), new Token[] {}, null).parseCommandLine(shell);
            Assert.fail("no exception");
        } catch (CommandSyntaxException ex) {
            Assert.assertEquals("ran out of alternatives", ex.getMessage());
        }
    }

    @org.junit.Test
    public void testNonMatchingVerb() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.VerbSyntaxTest$Test");
        shell.addSyntax("cmd", new VerbSyntax(null, "run", "flagArg", null, null));

        try {
            new CommandLine(new Token("cmd"), new Token[] {new Token("walk")}, null)
                .parseCommandLine(shell);
            Assert.fail("no exception");
        } catch (CommandSyntaxException ex) {
            Assert.assertEquals("ran out of alternatives", ex.getMessage());
        }
    }

    @org.junit.Test
    public void testTrailingTokenRejected() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.VerbSyntaxTest$Test");
        shell.addSyntax("cmd", new VerbSyntax(null, "run", "flagArg", null, null));

        try {
            new CommandLine(new Token("cmd"), new Token[] {new Token("run"), new Token("41")}, null)
                .parseCommandLine(shell);
            Assert.fail("no exception");
        } catch (CommandSyntaxException ex) {
            Assert.assertEquals("ran out of alternatives", ex.getMessage());
        }
    }

    @org.junit.Test
    public void testVerbOnNonFlagArgumentRejected() throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias("cmd", "org.jnode.test.shell.syntax.VerbSyntaxTest$Test");
        shell.addSyntax("cmd", new VerbSyntax(null, "run", "intArg", null, null));

        try {
            new CommandLine(new Token("cmd"), new Token[] {new Token("run")}, null)
                .parseCommandLine(shell);
            Assert.fail("no exception");
        } catch (CommandSyntaxException ex) {
            Assert.assertEquals("ran out of alternatives", ex.getMessage());
        }
    }

    @org.junit.Test
    public void testToXML() {
        XMLElement element = new VerbSyntax("verb", "run", "flagArg", null, "desc").toXML();
        Assert.assertEquals("verb", element.getName());
        Assert.assertEquals("run", element.getStringAttribute("symbol"));
        Assert.assertEquals("flagArg", element.getStringAttribute("argName"));
        Assert.assertEquals("verb", element.getStringAttribute("label"));
        Assert.assertEquals("desc", element.getStringAttribute("description"));
        Assert.assertEquals(0, element.getChildren().size());
    }

    /**
     * A VerbSyntax is serialized with an "argName" attribute, but SyntaxSpecLoader reads
     * the "argLabel" attribute for &lt;verb&gt; elements, so the round-trip currently fails
     * instead of preserving the syntax.
     */
    @org.junit.Test
    public void testToXMLRoundTripFailsOnArgNameAttribute() {
        Syntax syntax = new VerbSyntax("verb", "run", "flagArg", null, "desc");
        try {
            TestSyntaxRoundTrip.roundTrip(syntax);
            Assert.fail("no exception");
        } catch (SyntaxFailureException ex) {
            Assert.assertEquals("<argument> element has no 'argLabel' attribute", ex.getMessage());
        }
    }

    @org.junit.Test
    public void testToString() {
        String str = new VerbSyntax("verb", "run", "flagArg", "f", "desc").toString();
        Assert.assertTrue(str, str.startsWith("VerbSyntax{"));
        Assert.assertTrue(str, str.contains("symbol=run"));
        Assert.assertTrue(str, str.contains("argName=flagArg"));
        Assert.assertTrue(str, str.contains("flags=f"));
    }

    @org.junit.Test
    public void testIsNotAGroup() {
        VerbSyntax syntax = new VerbSyntax(null, "run", "flagArg", null, null);
        Assert.assertEquals(0, syntax.getChildren().length);
        Assert.assertTrue(syntax.childLabels().isEmpty());
    }
}