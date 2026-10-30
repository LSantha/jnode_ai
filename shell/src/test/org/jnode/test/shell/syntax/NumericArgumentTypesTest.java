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
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import org.jnode.driver.console.CompletionInfo;
import org.jnode.shell.AbstractCommand;
import org.jnode.shell.Command;
import org.jnode.shell.CommandInfo;
import org.jnode.shell.CommandLine;
import org.jnode.shell.CommandLine.Token;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.ArgumentBundle;
import org.jnode.shell.syntax.ArgumentSyntax;
import org.jnode.shell.syntax.CommandSyntaxException;
import org.jnode.shell.syntax.LongArgument;
import org.jnode.shell.syntax.MappedArgument;
import org.jnode.shell.syntax.PortNumberArgument;
import org.jnode.shell.syntax.SizeArgument;
import org.junit.Assert;
import org.junit.Test;

public class NumericArgumentTypesTest {

    private static final String ALIAS = "command";

    private static final String ARG_NAME = "arg1";

    public static class RawCompletions implements CompletionInfo {
        private final TreeSet<String> completions = new TreeSet<String>();

        private int completionStart = -1;

        public void addCompletion(String completion) {
            completions.add(completion);
        }

        public void addCompletion(String completion, boolean partial) {
            completions.add(completion);
        }

        public SortedSet<String> getCompletions() {
            return completions;
        }

        public int getCompletionStart() {
            return completionStart;
        }

        public void setCompletionStart(int completionStart) {
            this.completionStart = completionStart;
        }

        public String getCompletion() {
            return null;
        }
    }

    public static class TestMappedArgument extends MappedArgument<String> {
        public TestMappedArgument(String label, int flags, Map<String, String> valueMap,
            boolean caseInsensitive, String description) {
            super(label, flags, new String[0], valueMap, caseInsensitive, description);
        }

        @Override
        protected String argumentKind() {
            return "mapped";
        }
    }

    public abstract static class SingleArgumentCommand extends AbstractCommand {
        protected SingleArgumentCommand(Argument<?> arg) {
            registerArguments(arg);
        }

        public void execute() throws Exception {
        }
    }

    private static Map<String, String> lowerCaseMap() {
        Map<String, String> map = new HashMap<String, String>();
        map.put("alpha", "A");
        map.put("beta", "B");
        return map;
    }

    private static Map<String, String> upperCaseMap() {
        Map<String, String> map = new HashMap<String, String>();
        map.put("ALPHA", "A");
        map.put("BETA", "B");
        return map;
    }

    private static TestMappedArgument mappedArgument(boolean caseInsensitive) {
        return new TestMappedArgument(ARG_NAME, 0, lowerCaseMap(), caseInsensitive,
            "a mapped value");
    }

    private static TestMappedArgument upperCaseKeyArgument(boolean caseInsensitive) {
        return new TestMappedArgument(ARG_NAME, 0, upperCaseMap(), caseInsensitive,
            "a mapped value");
    }

    public static class LongCommand extends SingleArgumentCommand {
        public LongCommand() {
            super(new LongArgument(ARG_NAME, 0, "a long"));
        }
    }

    public static class UndescribedLongCommand extends SingleArgumentCommand {
        public UndescribedLongCommand() {
            super(new LongArgument(ARG_NAME, 0));
        }
    }

    public static class BoundedLongCommand extends SingleArgumentCommand {
        public BoundedLongCommand() {
            super(new LongArgument(ARG_NAME, 0, 10, 20, "a bounded long"));
        }
    }

    public static class BinarySizeCommand extends SingleArgumentCommand {
        public BinarySizeCommand() {
            super(new SizeArgument(ARG_NAME, 0, "a size"));
        }
    }

    public static class DecimalSizeCommand extends SingleArgumentCommand {
        public DecimalSizeCommand() {
            super(new SizeArgument(ARG_NAME, 0, false, "a size"));
        }
    }

    public static class PortCommand extends SingleArgumentCommand {
        public PortCommand() {
            super(new PortNumberArgument(ARG_NAME, 0, "a port"));
        }
    }

    public static class MappedCommand extends SingleArgumentCommand {
        public MappedCommand() {
            super(mappedArgument(false));
        }
    }

    public static class CaseInsensitiveMappedCommand extends SingleArgumentCommand {
        public CaseInsensitiveMappedCommand() {
            super(mappedArgument(true));
        }
    }

    public static class UpperCaseKeyMappedCommand extends SingleArgumentCommand {
        public UpperCaseKeyMappedCommand() {
            super(upperCaseKeyArgument(true));
        }
    }

    @Test
    public void testLongArgument() throws Exception {
        Assert.assertEquals(Long.valueOf(42L), parseValue(LongCommand.class, "42"));
        Assert.assertEquals(Long.valueOf(-7L), parseValue(LongCommand.class, "-7"));
        Assert.assertEquals(Long.valueOf(Long.MIN_VALUE),
            parseValue(LongCommand.class, "-9223372036854775808"));
        Assert.assertEquals(Long.valueOf(Long.MAX_VALUE),
            parseValue(LongCommand.class, "9223372036854775807"));

        Argument<?> arg = parseArgument(LongCommand.class, "42");
        Assert.assertEquals("long integer", arg.getTypeDescription());
        Assert.assertEquals("a long", arg.getDescription());

        assertRejected(LongCommand.class, "abc");
        assertRejected(LongCommand.class, "");
        assertRejected(LongCommand.class, "1.5");
        assertRejected(LongCommand.class, "0x10");
        assertRejected(LongCommand.class, "12x");
    }

    @Test
    public void testUndescribedLongArgument() throws Exception {
        Assert.assertEquals(Long.valueOf(5L), parseValue(UndescribedLongCommand.class, "5"));
        Assert.assertNull(parseArgument(UndescribedLongCommand.class, "5").getDescription());
    }

    @Test
    public void testBoundedLongArgument() throws Exception {
        Assert.assertEquals(Long.valueOf(15L), parseValue(BoundedLongCommand.class, "15"));
        Assert.assertEquals(Long.valueOf(10L), parseValue(BoundedLongCommand.class, "10"));
        Assert.assertEquals(Long.valueOf(20L), parseValue(BoundedLongCommand.class, "20"));

        assertRejectedWithMessage(BoundedLongCommand.class, "9", "number is out of range");
        assertRejectedWithMessage(BoundedLongCommand.class, "21", "number is out of range");
        assertRejectedWithMessage(BoundedLongCommand.class, "not a number", "invalid number");
    }

    @Test
    public void testLongArgumentMaxLessThanMin() throws Exception {
        try {
            new LongArgument(ARG_NAME, 0, 20, 10, "an illegal range");
            Assert.fail("constructor accepted max < min");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("max < min", ex.getMessage());
        }
    }

    @Test
    public void testLongArgumentCompletion() throws Exception {
        Assert.assertTrue(complete(new LongArgument(ARG_NAME, 0, "a long"), "1").isEmpty());
    }

    @Test
    public void testBinarySizeArgument() throws Exception {
        Assert.assertEquals(Long.valueOf(10L), parseValue(BinarySizeCommand.class, "10"));
        Assert.assertEquals(Long.valueOf(10240L), parseValue(BinarySizeCommand.class, "10K"));
        Assert.assertEquals(Long.valueOf(1048576L), parseValue(BinarySizeCommand.class, "1M"));
        Assert.assertEquals(Long.valueOf(1073741824L), parseValue(BinarySizeCommand.class, "1G"));
        Assert.assertEquals("size",
            parseArgument(BinarySizeCommand.class, "10").getTypeDescription());

        assertRejectedWithMessage(BinarySizeCommand.class, "10k", "invalid size");
        assertRejectedWithMessage(BinarySizeCommand.class, "10X", "invalid size");
        assertRejectedWithMessage(BinarySizeCommand.class, "abc", "invalid size");
        assertRejectedWithMessage(BinarySizeCommand.class, "", "invalid size");
    }

    @Test
    public void testDecimalSizeArgument() throws Exception {
        Assert.assertEquals(Long.valueOf(10L), parseValue(DecimalSizeCommand.class, "10"));
        Assert.assertEquals(Long.valueOf(10000L), parseValue(DecimalSizeCommand.class, "10k"));
        Assert.assertEquals(Long.valueOf(1000000L), parseValue(DecimalSizeCommand.class, "1M"));
        Assert.assertEquals(Long.valueOf(1000000000L), parseValue(DecimalSizeCommand.class, "1G"));
        Assert.assertEquals("size",
            parseArgument(DecimalSizeCommand.class, "10").getTypeDescription());

        assertRejectedWithMessage(DecimalSizeCommand.class, "10K", "invalid size");
        assertRejectedWithMessage(DecimalSizeCommand.class, "10X", "invalid size");
        assertRejectedWithMessage(DecimalSizeCommand.class, "abc", "invalid size");
        assertRejectedWithMessage(DecimalSizeCommand.class, "", "invalid size");
    }

    @Test
    public void testSizeArgumentScalingDiffersForTheSameToken() throws Exception {
        Assert.assertEquals(Long.valueOf(10240L), parseValue(BinarySizeCommand.class, "10K"));
        Assert.assertEquals(Long.valueOf(10000000L), parseValue(DecimalSizeCommand.class, "10M"));
        Assert.assertTrue(!parseValue(BinarySizeCommand.class, "1M")
            .equals(parseValue(DecimalSizeCommand.class, "1M")));
    }

    @Test
    public void testPortNumberArgument() throws Exception {
        Assert.assertEquals(Integer.valueOf(80), parseValue(PortCommand.class, "80"));
        Assert.assertEquals(Integer.valueOf(0), parseValue(PortCommand.class, "0"));
        Assert.assertEquals(Integer.valueOf(65535), parseValue(PortCommand.class, "65535"));
        Assert.assertEquals("port number",
            parseArgument(PortCommand.class, "80").getTypeDescription());

        assertRejectedWithMessage(PortCommand.class, "65536", "number is out of range");
        assertRejectedWithMessage(PortCommand.class, "-1", "number is out of range");
        assertRejectedWithMessage(PortCommand.class, "http", "invalid number");
        assertRejected(PortCommand.class, "");
    }

    @Test
    public void testMappedArgument() throws Exception {
        Assert.assertEquals("A", parseValue(MappedCommand.class, "alpha"));
        Assert.assertEquals("B", parseValue(MappedCommand.class, "beta"));
        Assert.assertEquals("mapped",
            parseArgument(MappedCommand.class, "alpha").getTypeDescription());

        assertRejectedWithMessage(MappedCommand.class, "gamma", "not an acceptable <mapped>");
        assertRejectedWithMessage(MappedCommand.class, "ALPHA", "not an acceptable <mapped>");

        SortedSet<String> completions = complete(mappedArgument(false), "al");
        Assert.assertEquals(1, completions.size());
        Assert.assertTrue(completions.contains("alpha"));
        SortedSet<String> all = complete(mappedArgument(false), "");
        Assert.assertEquals(2, all.size());
        Assert.assertTrue(all.contains("alpha"));
        Assert.assertTrue(all.contains("beta"));
        Assert.assertTrue(complete(mappedArgument(false), "zzz").isEmpty());
    }

    @Test
    public void testMappedArgumentCaseInsensitive() throws Exception {
        Assert.assertEquals("A", parseValue(CaseInsensitiveMappedCommand.class, "alpha"));
        Assert.assertEquals("A", parseValue(CaseInsensitiveMappedCommand.class, "ALPHA"));
        Assert.assertEquals("B", parseValue(CaseInsensitiveMappedCommand.class, "BeTa"));

        SortedSet<String> completions = complete(mappedArgument(true), "AL");
        Assert.assertEquals(1, completions.size());
        Assert.assertTrue(completions.contains("alpha"));
    }

    @Test
    public void testMappedArgumentUpperCaseKeyNeverMatches() throws Exception {
        assertRejectedWithMessage(UpperCaseKeyMappedCommand.class, "ALPHA",
            "not an acceptable <mapped>");
        assertRejectedWithMessage(UpperCaseKeyMappedCommand.class, "alpha",
            "not an acceptable <mapped>");

        SortedSet<String> completions = complete(upperCaseKeyArgument(true), "AL");
        Assert.assertTrue(completions.isEmpty());
        Assert.assertEquals(2, complete(upperCaseKeyArgument(true), "").size());
    }

    private static SortedSet<String> complete(Argument<?> arg, String partial) {
        ArgumentBundle bundle = new ArgumentBundle(arg);
        bundle.setStatus(ArgumentBundle.PARSE_SUCCEEDED);
        RawCompletions completions = new RawCompletions();
        arg.complete(completions, partial, 0);
        return completions.getCompletions();
    }

    private static Argument<?> parseArgument(Class<? extends AbstractCommand> commandClass,
        String value) throws Exception {
        Command cmd = parse(commandClass, new Token[] {new Token(value)});
        return cmd.getArgumentBundle().getArgument(ARG_NAME);
    }

    private static Object parseValue(Class<? extends AbstractCommand> commandClass, String value)
        throws Exception {
        return parseArgument(commandClass, value).getValue();
    }

    private static void assertRejected(Class<? extends AbstractCommand> commandClass, String value)
        throws Exception {
        try {
            parse(commandClass, new Token[] {new Token(value)});
            Assert.fail("parse accepted '" + value + "'");
        } catch (CommandSyntaxException ex) {
            Assert.assertNotNull(ex.getMessage());
        }
    }

    private static void assertRejectedWithMessage(
        Class<? extends AbstractCommand> commandClass, String value, String message)
        throws Exception {
        try {
            parse(commandClass, new Token[] {new Token(value)});
            Assert.fail("parse accepted '" + value + "'");
        } catch (CommandSyntaxException ex) {
            Assert.assertEquals("ran out of alternatives", ex.getMessage());
            List<CommandSyntaxException.Context> argErrors = ex.getArgErrors();
            Assert.assertNotNull(argErrors);
            Assert.assertFalse("no argument failure was recorded for '" + value + "'",
                argErrors.isEmpty());
            boolean found = false;
            for (CommandSyntaxException.Context context : argErrors) {
                if (message.equals(context.exception.getMessage())) {
                    found = true;
                }
            }
            Assert.assertTrue("no argument failure said '" + message + "' for '" + value + "'",
                found);
        }
    }

    private static Command parse(Class<? extends AbstractCommand> commandClass, Token[] args)
        throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias(ALIAS, commandClass.getName());
        shell.addSyntax(ALIAS, new ArgumentSyntax(ARG_NAME));
        CommandLine cl = new CommandLine(new Token(ALIAS), args, null);
        CommandInfo cmdInfo = cl.parseCommandLine(shell);
        return cmdInfo.createCommandInstance();
    }
}
