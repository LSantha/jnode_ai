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

import java.util.SortedSet;
import java.util.TreeSet;

import javax.naming.NameNotFoundException;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.jnode.driver.console.CompletionInfo;
import org.jnode.shell.AbstractCommand;
import org.jnode.shell.Command;
import org.jnode.shell.CommandInfo;
import org.jnode.shell.CommandLine;
import org.jnode.shell.CommandLine.Token;
import org.jnode.shell.ShellUtils;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.ArgumentBundle;
import org.jnode.shell.syntax.ArgumentSyntax;
import org.jnode.shell.syntax.CommandSyntaxException;
import org.jnode.shell.syntax.Log4jLevelArgument;
import org.jnode.shell.syntax.Log4jLoggerArgument;
import org.jnode.shell.syntax.PropertyNameArgument;
import org.jnode.shell.syntax.ShellPropertyNameArgument;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.naming.InitialNaming;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class SystemArgumentTypesTest {

    /**
     * Some argument types (for example ShellPropertyNameArgument.doComplete) reach
     * InitialNaming, which NPEs on the host JDK until a name space is installed.
     * The shell suite only worked because HostArgumentTypesTest initialised it
     * earlier in the same JVM, so this class failed when run on its own.
     */
    @BeforeClass
    public static void setUpInitialNaming() {
        try {
            InitialNaming.nameSet();
        } catch (NullPointerException ex) {
            InitialNaming.setNameSpace(new BasicNameSpace());
        }
    }

    private static final String ALIAS = "command";

    private static final String ARG_NAME = "arg1";

    private static final String TEST_PROPERTY = "org.jnode.argumenttest.unused.property";

    private static final String TEST_PROPERTY_PREFIX = "org.jnode.argumenttest.unused";

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

    public abstract static class SingleArgumentCommand extends AbstractCommand {
        protected SingleArgumentCommand(Argument<?> arg) {
            registerArguments(arg);
        }

        public void execute() throws Exception {
        }
    }

    public static class LevelCommand extends SingleArgumentCommand {
        public LevelCommand() {
            super(new Log4jLevelArgument(ARG_NAME, 0, "a logging level"));
        }
    }

    public static class LoggerCommand extends SingleArgumentCommand {
        public LoggerCommand() {
            super(new Log4jLoggerArgument(ARG_NAME, 0, "a logger name"));
        }
    }

    public static class PropertyCommand extends SingleArgumentCommand {
        public PropertyCommand() {
            super(new PropertyNameArgument(ARG_NAME, 0, "a property name"));
        }
    }

    public static class ShellPropertyCommand extends SingleArgumentCommand {
        public ShellPropertyCommand() {
            super(new ShellPropertyNameArgument(ARG_NAME, 0, "a shell property name"));
        }
    }

    @Test
    public void testLog4jLevelArgument() throws Exception {
        assertLevel(Level.ALL, "all");
        assertLevel(Level.DEBUG, "debug");
        assertLevel(Level.INFO, "info");
        assertLevel(Level.WARN, "warn");
        assertLevel(Level.ERROR, "error");
        assertLevel(Level.FATAL, "fatal");
        assertLevel(Level.OFF, "off");
    }

    @Test
    public void testLog4jLevelArgumentTypeDescription() throws Exception {
        Argument<?> arg = parseArgument(LevelCommand.class, "debug");
        Assert.assertEquals("logging level", arg.getTypeDescription());
        Assert.assertEquals("a logging level", arg.getDescription());
    }

    @Test
    public void testLog4jLevelArgumentIsCaseInsensitive() throws Exception {
        assertLevel(Level.DEBUG, "DEBUG");
        assertLevel(Level.WARN, "WaRn");
        assertLevel(Level.OFF, "Off");
        Assert.assertEquals(parseValue(LevelCommand.class, "debug"),
            parseValue(LevelCommand.class, "DEBUG"));
        Assert.assertEquals(parseValue(LevelCommand.class, "warn"),
            parseValue(LevelCommand.class, "WaRn"));
    }

    @Test
    public void testLog4jLevelArgumentRejectsUnknownLevel() throws Exception {
        assertRejected(LevelCommand.class, "verbose");
        assertRejected(LevelCommand.class, "trace");
        assertRejected(LevelCommand.class, "");
    }

    @Test
    public void testLog4jLevelArgumentCompletion() throws Exception {
        SortedSet<String> completions =
            complete(new Log4jLevelArgument(ARG_NAME, 0, "a logging level"), "d");
        Assert.assertEquals(1, completions.size());
        Assert.assertTrue(completions.contains("debug"));
        Assert.assertEquals(7, complete(new Log4jLevelArgument(ARG_NAME, 0,
            "a logging level"), "").size());
    }

    @Test
    public void testLog4jLoggerArgument() throws Exception {
        Object value = parseValue(LoggerCommand.class, "org.jnode.test.dotted.logger");
        Assert.assertTrue(value instanceof Logger);
        Assert.assertEquals("org.jnode.test.dotted.logger", ((Logger) value).getName());
        Assert.assertSame(Logger.getLogger("org.jnode.test.dotted.logger"), value);
    }

    @Test
    public void testLog4jLoggerArgumentIsPermissive() throws Exception {
        assertLoggerName("", "");
        assertLoggerName("not a logger!", "not a logger!");
        assertLoggerName("42", "42");
    }

    @Test
    public void testLog4jLoggerArgumentTypeDescription() throws Exception {
        Assert.assertEquals("logger",
            parseArgument(LoggerCommand.class, "x").getTypeDescription());
    }

    @Test
    public void testLog4jLoggerArgumentCompletion() throws Exception {
        Logger.getLogger("org.jnode.test.completion.logger");
        SortedSet<String> completions =
            complete(new Log4jLoggerArgument(ARG_NAME, 0, "a logger name"),
                "org.jnode.test.completion");
        Assert.assertTrue(completions.contains("org.jnode.test.completion.logger"));
        Assert.assertTrue(complete(new Log4jLoggerArgument(ARG_NAME, 0, "a logger name"),
            "no.such.logger").isEmpty());
    }

    @Test
    public void testPropertyNameArgumentIsPermissive() throws Exception {
        Assert.assertEquals("no.such.property",
            parseValue(PropertyCommand.class, "no.such.property"));
        Assert.assertEquals("", parseValue(PropertyCommand.class, ""));
        Assert.assertEquals("anything at all",
            parseValue(PropertyCommand.class, "anything at all"));
    }

    @Test
    public void testPropertyNameArgumentTypeDescription() throws Exception {
        Assert.assertEquals("property",
            parseArgument(PropertyCommand.class, "x").getTypeDescription());
        Assert.assertEquals("a property name",
            parseArgument(PropertyCommand.class, "x").getDescription());
    }

    @Test
    public void testPropertyNameArgumentCompletion() throws Exception {
        String oldValue = System.getProperty(TEST_PROPERTY);
        try {
            System.setProperty(TEST_PROPERTY, "set-by-test");
            SortedSet<String> completions = complete(
                new PropertyNameArgument(ARG_NAME, 0, "a property name"),
                TEST_PROPERTY_PREFIX);
            Assert.assertEquals("only the test property was set",
                1, completions.size());
            Assert.assertTrue(completions.contains(TEST_PROPERTY));
        } finally {
            if (oldValue == null) {
                System.clearProperty(TEST_PROPERTY);
            } else {
                System.setProperty(TEST_PROPERTY, oldValue);
            }
        }
    }

    @Test
    public void testPropertyNameArgumentCompletionWithoutMatch() throws Exception {
        Assert.assertTrue(complete(new PropertyNameArgument(ARG_NAME, 0, "a property name"),
            "no.such.property.prefix.").isEmpty());
    }

    @Test
    public void testShellPropertyNameArgumentIsPermissive() throws Exception {
        Assert.assertEquals("no.such.shell.property",
            parseValue(ShellPropertyCommand.class, "no.such.shell.property"));
        Assert.assertEquals("", parseValue(ShellPropertyCommand.class, ""));
    }

    @Test
    public void testShellPropertyNameArgumentTypeDescription() throws Exception {
        Assert.assertEquals("property",
            parseArgument(ShellPropertyCommand.class, "x").getTypeDescription());
    }

    @Test
    public void testShellPropertyNameArgumentCompletionIsEmptyWithoutAShell() throws Exception {
        ShellPropertyNameArgument arg =
            new ShellPropertyNameArgument(ARG_NAME, 0, "a shell property name");
        ArgumentBundle bundle = new ArgumentBundle(arg);
        bundle.setStatus(ArgumentBundle.PARSE_SUCCEEDED);
        RawCompletions completions = new RawCompletions();
        arg.complete(completions, "no.such.shell.property", 0);
        if (!isShellBound()) {
            Assert.assertTrue("no shell is bound, so NameNotFoundException is swallowed",
                completions.getCompletions().isEmpty());
        } else {
            Assert.assertFalse(completions.getCompletions().contains(
                "no.such.shell.property"));
        }
    }

    private static boolean isShellBound() {
        try {
            ShellUtils.getCurrentShell();
            return true;
        } catch (NameNotFoundException ex) {
            return false;
        }
    }

    private static void assertLevel(Level expected, String token) throws Exception {
        Assert.assertSame(expected, parseValue(LevelCommand.class, token));
    }

    private static void assertLoggerName(String token, String expectedName) throws Exception {
        Object value = parseValue(LoggerCommand.class, token);
        Assert.assertTrue(value instanceof Logger);
        Assert.assertEquals(expectedName, ((Logger) value).getName());
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