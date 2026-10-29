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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.jnode.shell.CommandLine;
import org.jnode.shell.Completable;
import org.jnode.shell.CommandShell;
import org.jnode.shell.DefaultInterpreter;
import org.jnode.shell.RedirectingInterpreter;
import org.jnode.shell.ShellException;
import org.jnode.shell.ShellInvocationException;
import org.jnode.shell.ShellSyntaxException;
import org.jnode.shell.SymbolSource;
import org.jnode.test.shell.Cassowary;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for '&&amp;' / '||' / ';' sequencing in the built-in interpreters.
 */
public class DefaultInterpreterSequenceTest {

    private static class TestableDefault extends DefaultInterpreter {
        public int runLine(CommandShell shell, String line) throws ShellException {
            return interpret(shell, line);
        }

        public int parseSize(String line, boolean completing) throws ShellException {
            return parseSequence(new Tokenizer(line), completing).size();
        }

        public String commandNameAt(String line, int index) throws ShellException {
            return ((SequenceEntry) parseSequence(
                    new Tokenizer(line), false).get(index)).commandLine.getCommandName();
        }

        public String operatorAt(String line, int index) throws ShellException {
            return ((SequenceEntry) parseSequence(
                    new Tokenizer(line), false).get(index)).operatorBefore;
        }

        public void parseExpectError(String line) throws ShellException {
            parseSequence(new Tokenizer(line), false);
        }

        public SymbolSource<CommandLine.Token> tokenize(String line)
            throws ShellException {
            return new Tokenizer(line);
        }
    }

    private static class TestableRedirecting extends RedirectingInterpreter {
        public int runLine(CommandShell shell, String line) throws ShellException {
            return interpret(shell, line);
        }

        public SymbolSource<CommandLine.Token> tokenize(String line)
            throws ShellException {
            return new Tokenizer(line, REDIRECTS_FLAG);
        }
    }

    private static class StubShell extends TestShell {
        final Map<String, Integer> rcByCommand = new HashMap<String, Integer>();
        final Set<String> failing = new HashSet<String>();
        final List<String> executed = new ArrayList<String>();
        int diagnoseCount = 0;

        public int invoke(CommandLine cmdLine, Properties sysProps,
                          Map<String, String> env) throws ShellException {
            String name = cmdLine.getCommandName();
            executed.add(name);
            if (failing.contains(name)) {
                throw new ShellInvocationException("unknown command: " + name);
            }
            Integer rc = rcByCommand.get(name);
            return rc == null ? 0 : rc.intValue();
        }

        public void diagnose(Throwable ex, CommandLine cmdLine) {
            diagnoseCount++;
        }
    }

    private static class StopAfterCommandShell extends StubShell {
        public int invoke(CommandLine cmdLine, Properties sysProps,
                          Map<String, String> env) throws ShellException {
            int rc = super.invoke(cmdLine, sysProps, env);
            consoleClosed(null);
            return rc;
        }
    }

    private StubShell newStub() {
        StubShell shell = new StubShell();
        shell.rcByCommand.put("true", Integer.valueOf(0));
        shell.rcByCommand.put("false", Integer.valueOf(1));
        shell.rcByCommand.put("echo", Integer.valueOf(0));
        return shell;
    }

    @Test
    public void testParseSequenceOperators() throws Exception {
        TestableDefault interp = new TestableDefault();
        Assert.assertEquals(4, interp.parseSize("a && b || c ; d", false));
        Assert.assertEquals(null, interp.operatorAt("a && b || c ; d", 0));
        Assert.assertEquals("a", interp.commandNameAt("a && b || c ; d", 0));
        Assert.assertEquals("&&", interp.operatorAt("a && b || c ; d", 1));
        Assert.assertEquals("b", interp.commandNameAt("a && b || c ; d", 1));
        Assert.assertEquals("||", interp.operatorAt("a && b || c ; d", 2));
        Assert.assertEquals("c", interp.commandNameAt("a && b || c ; d", 2));
        Assert.assertEquals(";", interp.operatorAt("a && b || c ; d", 3));
        Assert.assertEquals("d", interp.commandNameAt("a && b || c ; d", 3));
    }

    @Test
    public void testAppendRedirectIsRejected() throws Exception {
        TestableRedirecting interp = new TestableRedirecting();
        StubShell shell = newStub();
        try {
            interp.runLine(shell, "echo 'HashProbe5' >> /tmp/mini.txt");
            Assert.fail("Expected unsupported append redirection");
        } catch (ShellSyntaxException ex) {
            Assert.assertEquals("unsupported '>>' redirection: use '>' instead", ex.getMessage());
        }
        Assert.assertEquals(0, shell.executed.size());
    }

    @Test
    public void testRunRecoversPromptAfterAppendRedirect() throws Exception {
        Cassowary.initEnv();

        InputStream savedIn = System.in;
        PrintStream savedOut = System.out;
        PrintStream savedErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream testOut = new PrintStream(out);
        PrintStream testErr = new PrintStream(err);

        try {
            System.setIn(new ByteArrayInputStream("echo >>\naccepted\n".getBytes()));
            System.setOut(testOut);
            System.setErr(testErr);

            StopAfterCommandShell shell = new StopAfterCommandShell();
            String prompt = new RedirectingInterpreter().getPrompt(shell, false);
            shell.run();

            Assert.assertEquals(1, shell.diagnoseCount);
            Assert.assertEquals(1, shell.executed.size());
            Assert.assertEquals("accepted", shell.executed.get(0));
            Assert.assertEquals(prompt + prompt, out.toString());
        } finally {
            testOut.flush();
            testErr.flush();
            System.setIn(savedIn);
            System.setOut(savedOut);
            System.setErr(savedErr);
        }
    }

    @Test
    public void testParseSequenceAdjacentOperators() throws Exception {
        TestableDefault interp = new TestableDefault();
        Assert.assertEquals(2, interp.parseSize("a&&b", false));
        Assert.assertEquals("a", interp.commandNameAt("a&&b", 0));
        Assert.assertEquals("&&", interp.operatorAt("a&&b", 1));
        Assert.assertEquals("b", interp.commandNameAt("a&&b", 1));
    }

    @Test
    public void testAndShortCircuit() throws Exception {
        TestableDefault interp = new TestableDefault();
        StubShell shell = newStub();

        int rc = interp.runLine(shell, "true && echo one");
        Assert.assertEquals(0, rc);
        Assert.assertEquals(2, shell.executed.size());

        shell.executed.clear();
        rc = interp.runLine(shell, "false && echo two");
        Assert.assertEquals(1, rc);
        Assert.assertEquals(1, shell.executed.size());
        Assert.assertEquals("false", shell.executed.get(0));
    }

    @Test
    public void testAndChainStopsAtFirstFailure() throws Exception {
        TestableDefault interp = new TestableDefault();
        StubShell shell = newStub();
        shell.rcByCommand.put("a", Integer.valueOf(0));
        shell.rcByCommand.put("b", Integer.valueOf(1));
        shell.rcByCommand.put("c", Integer.valueOf(0));

        int rc = interp.runLine(shell, "a && b && c");
        Assert.assertEquals(1, rc);
        Assert.assertEquals(2, shell.executed.size());
        Assert.assertEquals("a", shell.executed.get(0));
        Assert.assertEquals("b", shell.executed.get(1));
    }

    @Test
    public void testOrShortCircuit() throws Exception {
        TestableDefault interp = new TestableDefault();
        StubShell shell = newStub();

        int rc = interp.runLine(shell, "false || echo ok");
        Assert.assertEquals(0, rc);
        Assert.assertEquals(2, shell.executed.size());

        shell.executed.clear();
        rc = interp.runLine(shell, "true || echo skipped");
        Assert.assertEquals(0, rc);
        Assert.assertEquals(1, shell.executed.size());
    }

    @Test
    public void testSemiAlwaysExecutes() throws Exception {
        TestableDefault interp = new TestableDefault();
        StubShell shell = newStub();

        int rc = interp.runLine(shell, "false ; echo ok");
        Assert.assertEquals(0, rc);
        Assert.assertEquals(2, shell.executed.size());

        shell.executed.clear();
        rc = interp.runLine(shell, "true ; false");
        Assert.assertEquals(1, rc);
        Assert.assertEquals(2, shell.executed.size());
    }

    @Test
    public void testLeftAssociativeMixed() throws Exception {
        TestableDefault interp = new TestableDefault();
        StubShell shell = newStub();
        // false && true is skipped, rc stays 1, so '|| true' runs.
        int rc = interp.runLine(shell, "false && true || true");
        Assert.assertEquals(0, rc);
        Assert.assertEquals(2, shell.executed.size());
        Assert.assertEquals("false", shell.executed.get(0));
        Assert.assertEquals("true", shell.executed.get(1));
    }

    @Test
    public void testOrAfterUnknownCommand() throws Exception {
        TestableDefault interp = new TestableDefault();
        StubShell shell = newStub();
        shell.failing.add("nosuch");

        int rc = interp.runLine(shell, "nosuch || echo ok");
        Assert.assertEquals(0, rc);
        Assert.assertEquals(2, shell.executed.size());
        Assert.assertEquals(1, shell.diagnoseCount);
    }

    @Test
    public void testAndAfterUnknownCommandSkips() throws Exception {
        TestableDefault interp = new TestableDefault();
        StubShell shell = newStub();
        shell.failing.add("nosuch");

        int rc = interp.runLine(shell, "nosuch && echo skipped");
        Assert.assertEquals(1, rc);
        Assert.assertEquals(1, shell.executed.size());
        Assert.assertEquals(1, shell.diagnoseCount);
    }

    @Test
    public void testSingleUnknownCommandRethrows() throws Exception {
        TestableDefault interp = new TestableDefault();
        StubShell shell = newStub();
        shell.failing.add("nosuch");
        try {
            interp.runLine(shell, "nosuch");
            Assert.fail("expected ShellException");
        } catch (ShellException ex) {
            // expected: single-command contract preserved
        }
        Assert.assertEquals(0, shell.diagnoseCount);
    }

    @Test
    public void testSingleCommandFailurePropagates() throws Exception {
        TestableRedirecting interp = new TestableRedirecting();
        StubShell shell = newStub();
        shell.failing.add("nosuch");
        try {
            interp.runLine(shell, "nosuch");
            Assert.fail("expected ShellException");
        } catch (ShellException ex) {
            // expected
        }
        Assert.assertEquals(0, shell.diagnoseCount);
    }

    @Test
    public void testRedirectingSequenceWithoutPipes() throws Exception {
        TestableRedirecting interp = new TestableRedirecting();
        StubShell shell = newStub();
        int rc = interp.runLine(shell, "false || echo ok");
        Assert.assertEquals(0, rc);
        Assert.assertEquals(2, shell.executed.size());
    }

    @Test(expected = ShellSyntaxException.class)
    public void testTrailingOperatorIsError() throws Exception {
        new TestableDefault().parseExpectError("echo hi &&");
    }

    @Test(expected = ShellSyntaxException.class)
    public void testLeadingOperatorIsError() throws Exception {
        new TestableDefault().parseExpectError("&& echo hi");
    }

    @Test(expected = ShellSyntaxException.class)
    public void testTrailingOrIsError() throws Exception {
        new TestableDefault().parseExpectError("echo hi ||");
    }

    @Test(expected = ShellSyntaxException.class)
    public void testTrailingSemiIsError() throws Exception {
        new TestableDefault().parseExpectError("echo hi ;");
    }

    @Test(expected = ShellSyntaxException.class)
    public void testStandaloneAmpIsError() throws Exception {
        new TestableDefault().parseExpectError("echo a & echo b");
    }

    @Test(expected = ShellSyntaxException.class)
    public void testTripleAmpIsError() throws Exception {
        new TestableDefault().parseExpectError("a &&& b");
    }

    @Test
    public void testCompletionAfterTrailingOperatorIsEmpty() throws Exception {
        TestableDefault interp = new TestableDefault();
        StubShell shell = newStub();
        Completable c = interp.parsePartial(shell, "echo hi && ");
        Assert.assertTrue(c instanceof CommandLine);
        Assert.assertEquals("", ((CommandLine) c).getCommandName());

        c = interp.parsePartial(shell, "echo hi || ");
        Assert.assertEquals("", ((CommandLine) c).getCommandName());

        c = interp.parsePartial(shell, "echo hi ; ");
        Assert.assertEquals("", ((CommandLine) c).getCommandName());

        // No trailing operator: completion targets the last command.
        c = interp.parsePartial(shell, "echo hi && echo bye");
        Assert.assertEquals("echo", ((CommandLine) c).getCommandName());
    }

    @Test
    public void testRedirectingCompletionAfterTrailingOperatorIsEmpty()
        throws Exception {
        TestableRedirecting interp = new TestableRedirecting();
        StubShell shell = newStub();
        Completable c = interp.parsePartial(shell, "echo hi && ");
        Assert.assertTrue(c instanceof CommandLine);
        Assert.assertEquals("", ((CommandLine) c).getCommandName());
    }

    @Test
    public void testEscapeWordRoundTrip() throws Exception {
        TestableDefault interp = new TestableDefault();
        assertSingleLiteralToken(interp, interp, "a&&b");
        assertSingleLiteralToken(interp, interp, "a||b");
        assertSingleLiteralToken(interp, interp, "a;b");
        assertSingleLiteralToken(interp, interp, "a&b");
        assertSingleLiteralToken(interp, interp, "a|b");

        TestableRedirecting rinterp = new TestableRedirecting();
        assertSingleLiteralToken(rinterp, "a&&b");
        assertSingleLiteralToken(rinterp, "a||b");
        assertSingleLiteralToken(rinterp, "a;b");
    }

    private void assertSingleLiteralToken(TestableDefault interp,
                                          DefaultInterpreter escaper, String word)
        throws ShellException {
        String escaped = escaper.escapeWord(word);
        SymbolSource<CommandLine.Token> tokenizer = interp.tokenize(escaped);
        Assert.assertTrue("no token for '" + word + "'", tokenizer.hasNext());
        CommandLine.Token token = tokenizer.next();
        Assert.assertEquals(word, token.text);
        Assert.assertEquals(DefaultInterpreter.LITERAL, token.tokenType);
        Assert.assertEquals(false, tokenizer.hasNext());
    }

    private void assertSingleLiteralToken(TestableRedirecting interp, String word)
        throws ShellException {
        String escaped = interp.escapeWord(word);
        SymbolSource<CommandLine.Token> tokenizer = interp.tokenize(escaped);
        Assert.assertTrue("no token for '" + word + "'", tokenizer.hasNext());
        CommandLine.Token token = tokenizer.next();
        Assert.assertEquals(word, token.text);
        Assert.assertEquals(DefaultInterpreter.LITERAL, token.tokenType);
        Assert.assertEquals(false, tokenizer.hasNext());
    }
}
