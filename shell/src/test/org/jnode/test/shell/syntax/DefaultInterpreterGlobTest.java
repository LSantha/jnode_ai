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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.jnode.shell.CommandLine;
import org.jnode.shell.CommandShell;
import org.jnode.shell.Completable;
import org.jnode.shell.DefaultInterpreter;
import org.jnode.shell.RedirectingInterpreter;
import org.jnode.shell.ShellException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for pathname pattern ('glob') expansion in the built-in interpreters.
 */
public class DefaultInterpreterGlobTest {

    private static class TestableDefault extends DefaultInterpreter {
        public int runLine(CommandShell shell, String line) throws ShellException {
            return interpret(shell, line);
        }

        public Completable parse(String line) throws ShellException {
            return parsePartial(null, line);
        }
    }

    private static class TestableRedirecting extends RedirectingInterpreter {
        public int runLine(CommandShell shell, String line) throws ShellException {
            return interpret(shell, line);
        }
    }

    private static class StubShell extends TestShell {
        final List<List<String>> invocations = new ArrayList<List<String>>();

        public int invoke(CommandLine cmdLine, Properties sysProps,
                          Map<String, String> env) throws ShellException {
            List<String> args = new ArrayList<String>();
            for (String arg : cmdLine.getArguments()) {
                args.add(arg);
            }
            invocations.add(args);
            return 0;
        }
    }

    private File dir;
    private StubShell shell;

    @Before
    public void setUp() throws Exception {
        dir = File.createTempFile("globtest", "");
        Assert.assertTrue(dir.delete());
        Assert.assertTrue(dir.mkdirs());
        shell = new StubShell();
    }

    private File touch(String name) throws IOException {
        File f = new File(dir, name);
        Assert.assertTrue(f.createNewFile());
        return f;
    }

    private List<String> argsOfLastCommand() {
        return shell.invocations.get(shell.invocations.size() - 1);
    }

    @Test
    public void testStarExpands() throws Exception {
        touch("one.b64");
        touch("two.b64");
        touch("three.java");

        int rc = new TestableDefault().runLine(shell,
                "rm " + dir.getPath() + "/*.b64");

        Assert.assertEquals(0, rc);
        Assert.assertEquals(1, shell.invocations.size());
        List<String> args = argsOfLastCommand();
        Assert.assertEquals(2, args.size());
        Assert.assertEquals(new File(dir, "one.b64").getPath(), args.get(0));
        Assert.assertEquals(new File(dir, "two.b64").getPath(), args.get(1));
    }

    @Test
    public void testQuestionMarkExpands() throws Exception {
        touch("c0");
        touch("c1");
        touch("cx");

        new TestableDefault().runLine(shell, "rm " + dir.getPath() + "/c?");

        List<String> args = argsOfLastCommand();
        Assert.assertEquals(3, args.size());
        Assert.assertEquals(new File(dir, "c0").getPath(), args.get(0));
        Assert.assertEquals(new File(dir, "c1").getPath(), args.get(1));
        Assert.assertEquals(new File(dir, "cx").getPath(), args.get(2));
    }

    @Test
    public void testCharacterClassExpands() throws Exception {
        touch("part0");
        touch("part9");
        touch("partx");

        new TestableDefault().runLine(shell, "rm " + dir.getPath() + "/part[0-9]");

        List<String> args = argsOfLastCommand();
        Assert.assertEquals(2, args.size());
        Assert.assertEquals(new File(dir, "part0").getPath(), args.get(0));
        Assert.assertEquals(new File(dir, "part9").getPath(), args.get(1));
    }

    @Test
    public void testNoMatchLeavesPatternVerbatim() throws Exception {
        touch("unrelated.txt");

        new TestableDefault().runLine(shell, "rm " + dir.getPath() + "/*.missing");

        List<String> args = argsOfLastCommand();
        Assert.assertEquals(1, args.size());
        Assert.assertEquals(dir.getPath() + "/*.missing", args.get(0));
    }

    @Test
    public void testQuotedPatternStaysLiteral() throws Exception {
        touch("star*file");

        new TestableDefault().runLine(shell, "echo \"*\"");

        Assert.assertEquals("*", argsOfLastCommand().get(0));
    }

    @Test
    public void testEscapedPatternStaysLiteral() throws Exception {
        touch("star*file");

        new TestableDefault().runLine(shell, "echo \\*");

        Assert.assertEquals("*", argsOfLastCommand().get(0));
    }

    @Test
    public void testMixedQuotedAndUnquotedArguments() throws Exception {
        touch("plain.txt");

        new TestableDefault().runLine(shell, "echo \"*\" " + dir.getPath() + "/*.txt");

        List<String> args = argsOfLastCommand();
        Assert.assertEquals(2, args.size());
        Assert.assertEquals("*", args.get(0));
        Assert.assertEquals(new File(dir, "plain.txt").getPath(), args.get(1));
    }

    @Test
    public void testExpansionInSequence() throws Exception {
        touch("seq.txt");

        new TestableDefault().runLine(shell, "true && rm " + dir.getPath() + "/*.txt");

        Assert.assertEquals(2, shell.invocations.size());
        Assert.assertEquals(new File(dir, "seq.txt").getPath(),
                argsOfLastCommand().get(0));
    }

    @Test
    public void testRedirectingInterpreterExpands() throws Exception {
        touch("redir1.txt");
        touch("redir2.txt");

        int rc = new TestableRedirecting().runLine(shell, "rm " + dir.getPath() + "/redir?.txt");

        Assert.assertEquals(0, rc);
        Assert.assertEquals(2, argsOfLastCommand().size());
    }

    @Test
    public void testCompletionIsNotExpanded() throws Exception {
        touch("complete.txt");

        Completable c = new TestableDefault().parse("ls " + dir.getPath() + "/*.txt");

        Assert.assertTrue(c instanceof CommandLine);
        String[] args = ((CommandLine) c).getArguments();
        Assert.assertEquals(1, args.length);
        Assert.assertEquals(dir.getPath() + "/*.txt", args[0]);
    }
}