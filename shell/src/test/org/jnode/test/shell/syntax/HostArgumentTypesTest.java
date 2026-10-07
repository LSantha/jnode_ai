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

import java.io.File;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.naming.NameNotFoundException;

import org.jnode.bootlog.BootLog;
import org.jnode.driver.Device;
import org.jnode.driver.DeviceInfoAPI;
import org.jnode.driver.DeviceNotFoundException;
import org.jnode.driver.console.CompletionInfo;
import org.jnode.driver.input.KeyboardLayoutManager;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.naming.InitialNaming;
import org.jnode.plugin.PluginManager;
import org.jnode.shell.AbstractCommand;
import org.jnode.shell.Command;
import org.jnode.shell.CommandInfo;
import org.jnode.shell.CommandLine;
import org.jnode.shell.CommandLine.Token;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.ArgumentBundle;
import org.jnode.shell.syntax.ArgumentSyntax;
import org.jnode.shell.syntax.CountryArgument;
import org.jnode.shell.syntax.CommandSyntaxException;
import org.jnode.shell.syntax.DeviceArgument;
import org.jnode.shell.syntax.HostNameArgument;
import org.jnode.shell.syntax.KeyboardLayoutArgument;
import org.jnode.shell.syntax.LanguageArgument;
import org.jnode.shell.syntax.PluginArgument;
import org.jnode.shell.syntax.SyntaxFailureException;
import org.jnode.shell.syntax.ThreadNameArgument;
import org.jnode.shell.syntax.URLArgument;
import org.jnode.test.shell.DeviceManager;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class HostArgumentTypesTest {

    private static final String API_DEVICE_ID = "test-api-device";

    private static final String PLAIN_DEVICE_ID = "test-plain-device";

    private static final String ALIAS = "command";

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

    public static class CountryCommand extends SingleArgumentCommand {
        public CountryCommand() {
            super(new CountryArgument("arg1", 0, "a country"));
        }
    }

    public static class LanguageCommand extends SingleArgumentCommand {
        public LanguageCommand() {
            super(new LanguageArgument("arg1", 0, "a language"));
        }
    }

    public static class KeyboardLayoutCommand extends SingleArgumentCommand {
        public KeyboardLayoutCommand() {
            super(new KeyboardLayoutArgument("arg1", 0, "a keyboard layout"));
        }
    }

    public static class HostNameCommand extends SingleArgumentCommand {
        public HostNameCommand() {
            super(new HostNameArgument("arg1", 0, "a host"));
        }
    }

    public static class DeviceCommand extends SingleArgumentCommand {
        public DeviceCommand() {
            super(new DeviceArgument("arg1", 0, "a device"));
        }
    }

    public static class DeviceInfoCommand extends SingleArgumentCommand {
        public DeviceInfoCommand() {
            super(new DeviceArgument("arg1", 0, "a device", DeviceInfoAPI.class));
        }
    }

    public static class PluginCommand extends SingleArgumentCommand {
        public PluginCommand() {
            super(new PluginArgument("arg1", 0, "a plugin id"));
        }
    }

    public static class ThreadNameCommand extends SingleArgumentCommand {
        public ThreadNameCommand() {
            super(new ThreadNameArgument("arg1", 0, "a thread name"));
        }
    }

    public static class URLCommand extends SingleArgumentCommand {
        public URLCommand() {
            super(new URLArgument("arg1", 0, "a url"));
        }
    }

    public static class SilentBootLog implements BootLog {
        public void debug(String msg) {
        }

        public void debug(String msg, Throwable ex) {
        }

        public void error(String msg) {
        }

        public void error(String msg, Throwable ex) {
        }

        public void fatal(String msg) {
        }

        public void fatal(String msg, Throwable ex) {
        }

        public void info(String msg) {
        }

        public void info(String msg, Throwable ex) {
        }

        public void warn(String msg) {
        }

        public void warn(String msg, Throwable ex) {
        }

        public void setDebugOut(PrintStream out) {
        }
    }

    @BeforeClass
    public static void setUpDeviceManager() throws Exception {
        if (!namingInitialized()) {
            InitialNaming.setNameSpace(new BasicNameSpace());
        }
        if (!isBound(BootLog.class)) {
            InitialNaming.bind(BootLog.class, new SilentBootLog());
        }
        if (!isBound(DeviceManager.NAME)) {
            InitialNaming.bind(DeviceManager.NAME, DeviceManager.INSTANCE);
        }
        registerDevice(API_DEVICE_ID, true);
        registerDevice(PLAIN_DEVICE_ID, false);
    }

    @Test
    public void testCountryArgument() throws Exception {
        Assert.assertEquals("US", parseValue(CountryCommand.class, "US"));
        Assert.assertEquals("GB", parseValue(CountryCommand.class, "GB"));
        assertRejected(CountryCommand.class, "ZZ");
        assertRejected(CountryCommand.class, "United States");
        SortedSet<String> completions = complete(new CountryArgument("arg1", 0, "a country"), "U");
        Assert.assertTrue(completions.contains("US"));
        Assert.assertFalse(completions.contains("GB"));
    }

    @Test
    public void testLanguageArgument() throws Exception {
        Assert.assertEquals("en", parseValue(LanguageCommand.class, "en"));
        Assert.assertEquals("fr", parseValue(LanguageCommand.class, "fr"));
        assertRejected(LanguageCommand.class, "zz");
        assertRejected(LanguageCommand.class, "English");
        SortedSet<String> completions =
            complete(new LanguageArgument("arg1", 0, "a language"), "e");
        Assert.assertTrue(completions.contains("en"));
    }

    @Test
    public void testKeyboardLayoutArgument() throws Exception {
        Assert.assertEquals("US_en",
            parseValue(KeyboardLayoutCommand.class, "US_en"));
        Assert.assertEquals("anything",
            parseValue(KeyboardLayoutCommand.class, "anything"));
        Assert.assertEquals("", parseValue(KeyboardLayoutCommand.class, ""));
        Argument<?> arg = new KeyboardLayoutArgument("arg1", 0, "a keyboard layout");
        if (isBound(KeyboardLayoutManager.NAME)) {
            arg.complete(new RawCompletions(), "US_en", 0);
        } else {
            try {
                arg.complete(new RawCompletions(), "US_en", 0);
                Assert.fail("completion didn't fail");
            } catch (SyntaxFailureException ex) {
            }
        }
    }

    @Test
    public void testHostNameArgument() throws Exception {
        Assert.assertEquals("localhost", parseValue(HostNameCommand.class, "localhost"));
        Assert.assertEquals("127.0.0.1", parseValue(HostNameCommand.class, "127.0.0.1"));
        Assert.assertEquals("not a host!",
            parseValue(HostNameCommand.class, "not a host!"));
        Assert.assertEquals("", parseValue(HostNameCommand.class, ""));

        HostNameArgument ip =
            (HostNameArgument) parseArgument(HostNameCommand.class, "127.0.0.1");
        Assert.assertEquals(InetAddress.getByName("127.0.0.1"), ip.getAsInetAddress());

        HostNameArgument bogus =
            (HostNameArgument) parseArgument(HostNameCommand.class, "not a host!");
        try {
            bogus.getAsInetAddress();
            Assert.fail("resolution didn't fail");
        } catch (UnknownHostException ex) {
        }
    }

    @Test
    public void testDeviceArgument() throws Exception {
        Object dev = parseValue(DeviceCommand.class, API_DEVICE_ID);
        Assert.assertTrue(dev instanceof Device);
        Assert.assertEquals(API_DEVICE_ID, ((Device) dev).getId());
        assertRejected(DeviceCommand.class, "no-such-device");

        Object infoDev = parseValue(DeviceInfoCommand.class, API_DEVICE_ID);
        Assert.assertEquals(API_DEVICE_ID, ((Device) infoDev).getId());
        try {
            parseValue(DeviceInfoCommand.class, PLAIN_DEVICE_ID);
            Assert.fail("parse didn't fail");
        } catch (CommandSyntaxException ex) {
        }

        SortedSet<String> completions =
            complete(new DeviceArgument("arg1", 0, "a device"), "test-");
        Assert.assertTrue(completions.contains(API_DEVICE_ID));
        Assert.assertTrue(completions.contains(PLAIN_DEVICE_ID));

        SortedSet<String> apiCompletions =
            complete(new DeviceArgument("arg1", 0, "a device", DeviceInfoAPI.class), "test-");
        Assert.assertTrue(apiCompletions.contains(API_DEVICE_ID));
        Assert.assertFalse(apiCompletions.contains(PLAIN_DEVICE_ID));
    }

    @Test
    public void testPluginArgument() throws Exception {
        Assert.assertEquals("org.jnode.shell", parseValue(PluginCommand.class, "org.jnode.shell"));
        Assert.assertEquals("no-such-plugin", parseValue(PluginCommand.class, "no-such-plugin"));
        Assert.assertEquals("", parseValue(PluginCommand.class, ""));
        SortedSet<String> completions =
            complete(new PluginArgument("arg1", 0, "a plugin id"), "org.jnode");
        if (!isBound(PluginManager.NAME)) {
            Assert.assertTrue(completions.isEmpty());
        }
    }

    @Test
    public void testThreadNameArgument() throws Exception {
        Assert.assertEquals("worker", parseValue(ThreadNameCommand.class, "worker"));
        Assert.assertEquals("Thread[worker,5,main]",
            parseValue(ThreadNameCommand.class, "Thread[worker,5,main]"));
        Assert.assertEquals("", parseValue(ThreadNameCommand.class, ""));
        String current = Thread.currentThread().getName();
        SortedSet<String> completions =
            complete(new ThreadNameArgument("arg1", 0, "a thread name"), current);
        Assert.assertTrue(completions.contains(current));
    }

    @Test
    public void testURLArgument() throws Exception {
        Assert.assertEquals(new URL("http://www.jnode.org/index.html"),
            parseValue(URLCommand.class, "http://www.jnode.org/index.html"));
        Assert.assertEquals(new URL("file:/tmp/jnode-test.txt"),
            parseValue(URLCommand.class, "file:/tmp/jnode-test.txt"));
        assertRejected(URLCommand.class, "not a url");
        assertRejected(URLCommand.class, "");

        File dir = new File(System.getProperty("java.io.tmpdir"));
        Assert.assertTrue(dir.isDirectory());
        String prefix = "file:" + dir.getPath() + File.separatorChar;
        SortedSet<String> fileCompletions =
            complete(new URLArgument("arg1", 0, "a url"), prefix);
        Assert.assertFalse("file: URL completion must not fail", fileCompletions.isEmpty());
        for (String completion : fileCompletions) {
            Assert.assertTrue(completion, completion.startsWith("file:"));
        }

        SortedSet<String> httpCompletions =
            complete(new URLArgument("arg1", 0, "a url"), "http://www.jnode.org/");
        Assert.assertTrue(httpCompletions.isEmpty());
    }

    private static boolean namingInitialized() {
        try {
            InitialNaming.nameSet();
            return true;
        } catch (NullPointerException ex) {
            return false;
        }
    }

    private static boolean isBound(Class<?> name) {
        try {
            InitialNaming.lookup(name);
            return true;
        } catch (NameNotFoundException ex) {
            return false;
        }
    }

    private static void registerDevice(String id, boolean withApi) throws Exception {
        DeviceManager mgr = DeviceManager.INSTANCE;
        if (isRegistered(mgr, id)) {
            return;
        }
        Device device = new Device(null, id);
        if (withApi) {
            device.registerAPI(DeviceInfoAPI.class, new DeviceInfoAPI() {
                public void showInfo(PrintWriter out) {
                }
            });
        }
        mgr.register(device);
    }

    private static boolean isRegistered(DeviceManager mgr, String id) {
        try {
            mgr.getDevice(id);
            return true;
        } catch (DeviceNotFoundException ex) {
            return false;
        }
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
        return cmd.getArgumentBundle().getArgument("arg1");
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
        }
    }

    private static Command parse(Class<? extends AbstractCommand> commandClass, Token[] args)
        throws Exception {
        TestShell shell = new TestShell();
        shell.addAlias(ALIAS, commandClass.getName());
        shell.addSyntax(ALIAS, new ArgumentSyntax("arg1"));
        CommandLine cl = new CommandLine(new Token(ALIAS), args, null);
        CommandInfo cmdInfo = cl.parseCommandLine(shell);
        return cmdInfo.createCommandInstance();
    }

    /**
     * Regression for #715: DeviceArgument.state() dereferenced apiClass
     * unconditionally, so Argument.toString() threw NullPointerException for
     * every constructor that does not take an API filter.
     */
    @Test
    public void testDeviceArgumentToStringWithoutApiClass() {
        String str = new DeviceArgument("arg1", 0).toString();
        Assert.assertTrue(str, str.contains("apiClass=null"));
    }

    @Test
    public void testDeviceArgumentToStringWithApiClass() {
        String str = new DeviceArgument("arg1", 0, "desc", DeviceInfoAPI.class).toString();
        Assert.assertTrue(str, str.contains("apiClass=" + DeviceInfoAPI.class.getName()));
    }
}
