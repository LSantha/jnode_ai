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

import org.jnode.shell.CommandLine;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.ArgumentBundle;
import org.jnode.shell.syntax.CommandSyntaxException;
import org.jnode.shell.syntax.IntegerArgument;
import org.jnode.shell.syntax.MuAlternation;
import org.jnode.shell.syntax.MuArgument;
import org.jnode.shell.syntax.MuBackReference;
import org.jnode.shell.syntax.MuParser;
import org.jnode.shell.syntax.MuPreset;
import org.jnode.shell.syntax.MuSequence;
import org.jnode.shell.syntax.MuSymbol;
import org.jnode.shell.syntax.MuSyntax;
import org.jnode.shell.syntax.SyntaxFailureException;
import org.junit.Assert;
import org.junit.Test;

@SuppressWarnings("deprecation")
public class MuPresetTest {

    @Test
    public void testUnlabelledConstructor() {
        MuPreset preset = new MuPreset("arg1", "val1");
        Assert.assertNull(preset.getLabel());
        Assert.assertEquals("arg1", preset.getArgName());
        Assert.assertEquals("val1", preset.getPreset());
        Assert.assertEquals(0, preset.getFlags());
        Assert.assertEquals(MuSyntax.MuSyntaxKind.PRESET, preset.getKind());
    }

    @Test
    public void testLabelledConstructor() {
        MuPreset preset = new MuPreset("l1", "arg1", "val1", Argument.MULTIPLE);
        Assert.assertEquals("l1", preset.getLabel());
        Assert.assertEquals("arg1", preset.getArgName());
        Assert.assertEquals("val1", preset.getPreset());
        Assert.assertEquals(Argument.MULTIPLE, preset.getFlags());
        Assert.assertEquals(MuSyntax.MuSyntaxKind.PRESET, preset.getKind());
    }

    @Test
    public void testEmptyPresetValueIsLegal() {
        MuPreset preset = new MuPreset("arg1", "");
        Assert.assertEquals("", preset.getPreset());

        preset = new MuPreset(null, "arg1", null, 0);
        Assert.assertNull(preset.getPreset());
    }

    @Test
    public void testConstructorRejectsBadArgNames() {
        try {
            new MuPreset("", "val1");
            Assert.fail("expected IAE");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("empty argName", ex.getMessage());
        }
        try {
            new MuPreset(null, "", "val1", 0);
            Assert.fail("expected IAE");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("empty argName", ex.getMessage());
        }
        try {
            new MuPreset(null, null, "val1", 0);
            Assert.fail("expected NPE");
        } catch (NullPointerException ex) {
            // expected
        }
    }

    @Test
    public void testConstructorRejectsEmptyLabel() {
        try {
            new MuPreset("", "arg1", "val1", 0);
            Assert.fail("expected IAE");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("empty label", ex.getMessage());
        }
    }

    @Test
    public void testFormat() {
        Assert.assertEquals("<*Start*> ::= <<arg1=val1>>", new MuPreset("arg1", "val1").format());
        Assert.assertEquals("<l1> ::= <<arg1=val1>>",
            new MuPreset("l1", "arg1", "val1", 0).format());
        Assert.assertEquals("<l1> ::= <l2>\n<l2> ::= <<arg2=2>>",
            new MuSequence("l1", new MuPreset("l2", "arg2", "2", 0)).format());
        Assert.assertEquals("<l1> ::= ( <<arg1=1>> | <l2> )\n<l2> ::= <<arg2=>>",
            new MuAlternation("l1", new MuPreset("arg1", "1"),
                new MuPreset("l2", "arg2", "", 0)).format());
    }

    @Test
    public void testResolveBackReferencesUsesPresetLabel() throws SyntaxFailureException {
        MuPreset preset = new MuPreset("p1", "arg1", "val1", 0);
        MuSyntax syntax = new MuSequence(preset, new MuBackReference("p1"));
        syntax.resolveBackReferences();

        MuSyntax[] elements = ((MuSequence) syntax).getElements();
        Assert.assertSame(preset, elements[0]);
        Assert.assertSame(preset, elements[1]);
        Assert.assertEquals("<*Start*> ::= <p1> <p1>\n<p1> ::= <<arg1=val1>>", syntax.format());
    }

    @Test
    public void testResolveBackReferencesUnlabelledPresetDefinesNothing()
        throws SyntaxFailureException {
        MuSyntax syntax =
            new MuSequence(new MuPreset("arg1", "val1"), new MuBackReference("arg1"));
        try {
            syntax.resolveBackReferences();
            Assert.fail("expected SFE");
        } catch (SyntaxFailureException ex) {
            Assert.assertEquals("Cannot resolve 'arg1'", ex.getMessage());
        }
    }

    @Test
    public void testResolveBackReferencesUndefinedName() {
        MuSyntax syntax =
            new MuSequence(new MuPreset("p1", "arg1", "val1", 0), new MuBackReference("nope"));
        try {
            syntax.resolveBackReferences();
            Assert.fail("expected SFE");
        } catch (SyntaxFailureException ex) {
            Assert.assertEquals("Cannot resolve 'nope'", ex.getMessage());
        }
    }

    @Test
    public void testResolveBackReferencesRedefinedName() throws SyntaxFailureException {
        MuPreset first = new MuPreset("dup", "arg1", "one", 0);
        MuPreset second = new MuPreset("dup", "arg2", "two", 0);
        MuSyntax syntax = new MuSequence(first, second, new MuBackReference("dup"));
        syntax.resolveBackReferences();

        MuSyntax[] elements = ((MuSequence) syntax).getElements();
        Assert.assertSame(first, elements[0]);
        Assert.assertSame(second, elements[1]);
        Assert.assertSame(second, elements[2]);
    }

    private static ArgumentBundle parse(MuSyntax syntax, IntegerArgument intArg, String... tokens)
        throws CommandSyntaxException, SyntaxFailureException {
        ArgumentBundle bundle = new ArgumentBundle(intArg);
        bundle.setStatus(ArgumentBundle.PARSING);
        new MuParser().parse(syntax, null, new CommandLine(tokens).tokenIterator(), bundle);
        bundle.setStatus(ArgumentBundle.PARSE_SUCCEEDED);
        return bundle;
    }

    @Test
    public void testPresetSuppliesArgumentWithoutTokens() throws Exception {
        IntegerArgument intArg = new IntegerArgument("intArg", Argument.MULTIPLE);
        parse(new MuPreset("intArg", "42"), intArg, new String[0]);
        Assert.assertTrue(intArg.isSet());
        Assert.assertEquals(new Integer(42), intArg.getValue());
    }

    @Test
    public void testPresetAppendsToExplicitArgumentValue() throws Exception {
        IntegerArgument intArg = new IntegerArgument("intArg", Argument.MULTIPLE);
        MuSyntax syntax = new MuSequence(new MuArgument("intArg"), new MuPreset("intArg", "42"));
        parse(syntax, intArg, new String[] {"7"});

        Integer[] values = intArg.getValues();
        Assert.assertEquals(2, values.length);
        Assert.assertEquals(new Integer(7), values[0]);
        Assert.assertEquals(new Integer(42), values[1]);
    }

    @Test
    public void testPresetConsumesNoTokens() throws Exception {
        IntegerArgument intArg = new IntegerArgument("intArg", Argument.MULTIPLE);
        MuSyntax syntax = new MuSequence(new MuPreset("intArg", "42"), new MuSymbol("a"));
        parse(syntax, intArg, new String[] {"a"});
        Assert.assertEquals(new Integer(42), intArg.getValue());

        try {
            parse(syntax, intArg, new String[] {"b"});
            Assert.fail("expected SEE");
        } catch (CommandSyntaxException ex) {
            // expected
        }
    }

    @Test
    public void testPresetForUnknownArgumentName() {
        IntegerArgument intArg = new IntegerArgument("intArg", Argument.MULTIPLE);
        ArgumentBundle bundle = new ArgumentBundle(intArg);
        bundle.setStatus(ArgumentBundle.PARSING);
        try {
            new MuParser().parse(new MuPreset("nosuchArg", "42"), null,
                new CommandLine(new String[0]).tokenIterator(), bundle);
            Assert.fail("expected SFE");
        } catch (SyntaxFailureException ex) {
            Assert.assertEquals("No argument for syntax label 'nosuchArg'", ex.getMessage());
        } catch (CommandSyntaxException ex) {
            Assert.fail("expected SFE");
        }
    }

    @Test
    public void testPresetValueIsRejectedByArgument() {
        IntegerArgument intArg = new IntegerArgument("intArg", Argument.MULTIPLE);
        ArgumentBundle bundle = new ArgumentBundle(intArg);
        bundle.setStatus(ArgumentBundle.PARSING);
        try {
            new MuParser().parse(new MuPreset("intArg", "notanumber"), null,
                new CommandLine(new String[0]).tokenIterator(), bundle);
            Assert.fail("expected SEE");
        } catch (CommandSyntaxException ex) {
            // expected
        }
        Assert.assertFalse(intArg.isSet());
    }

    @Test
    public void testPresetAndChoicePointTogether() throws Exception {
        // The alternation below forces MuParser to create a SharedStack choice point.
        // <root> ::= ( <<intArg=7>> 'a' ) | ( <<intArg=9>> 'b' )
        IntegerArgument intArg = new IntegerArgument("intArg", Argument.MULTIPLE);
        MuSyntax syntax =
            new MuAlternation(new MuSequence(new MuPreset("intArg", "7"), new MuSymbol("a")),
                new MuSequence(new MuPreset("intArg", "9"), new MuSymbol("b")));

        parse(syntax, intArg, new String[] {"b"});
        Assert.assertEquals(1, intArg.getValues().length);
        Assert.assertEquals(new Integer(9), intArg.getValue());

        parse(syntax, intArg, new String[] {"a"});
        Assert.assertEquals(1, intArg.getValues().length);
        Assert.assertEquals(new Integer(7), intArg.getValue());
    }

    @Test
    public void testPresetBacktrackIsUndone() throws Exception {
        // <root> ::= ( <<intArg=7>> <<intArg>> ) | ( <<intArg=9>> 'b' )
        IntegerArgument intArg = new IntegerArgument("intArg", Argument.MULTIPLE);
        MuSyntax syntax =
            new MuAlternation(new MuSequence(new MuPreset("intArg", "7"), new MuArgument("intArg")),
                new MuSequence(new MuPreset("intArg", "9"), new MuSymbol("b")));

        parse(syntax, intArg, new String[] {"b"});
        Assert.assertEquals(1, intArg.getValues().length);
        Assert.assertEquals(new Integer(9), intArg.getValue());
    }
}
