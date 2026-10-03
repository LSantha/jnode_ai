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
import java.util.SortedSet;

import org.jnode.shell.CommandCompletions;
import org.jnode.shell.CommandLine;
import org.jnode.shell.CommandLine.Token;
import org.jnode.shell.syntax.ArgumentBundle;
import org.jnode.shell.syntax.ArgumentSyntax;
import org.jnode.shell.syntax.SyntaxBundle;
import org.jnode.shell.syntax.URLArgument;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the {@link URLArgument} completion behavior that depend on the host
 * file system. The test creates its own directory hierarchy to complete against
 * so that the expected completions don't depend on the content of any
 * particular system directory.
 *
 * @author crawley@jnode.org
 */
public class HostArgumentTypesTest {

    private static final String SUBDIR_NAME = "urltestsubdir";

    private File baseDir;
    private File subDir;

    @Before
    public void setUp() throws IOException {
        baseDir = File.createTempFile("jnodeurltest", "");
        Assert.assertTrue(baseDir.delete());
        Assert.assertTrue(baseDir.mkdir());
        subDir = new File(baseDir, SUBDIR_NAME);
        Assert.assertTrue(subDir.mkdir());
    }

    @After
    public void tearDown() {
        subDir.delete();
        baseDir.delete();
    }

    private String basePath() {
        return baseDir.getAbsolutePath();
    }

    private String subDirURL() {
        return "file:" + basePath() + File.separator + SUBDIR_NAME + File.separator;
    }

    private static void assertSingleCompletion(CommandCompletions completions, String expected) {
        SortedSet<String> set = completions.getCompletions();
        Assert.assertEquals("completions: " + set, 1, set.size());
        Assert.assertEquals(expected, set.first());
    }

    @Test
    public void testURLArgument() throws Exception {
        URLArgument arg = new URLArgument("arg1", 0);
        CommandCompletions completions = new CommandCompletions();
        arg.doComplete(completions, "file:" + basePath() + File.separator
            + SUBDIR_NAME.substring(0, SUBDIR_NAME.length() - 1), 0);
        assertSingleCompletion(completions, subDirURL());
    }

    @Test
    public void testURLArgumentCompletionWithNonExistentPath() throws Exception {
        URLArgument arg = new URLArgument("arg1", 0);
        CommandCompletions completions = new CommandCompletions();
        arg.doComplete(completions, "file:" + basePath() + "/nosuchdir/x", 0);
        Assert.assertTrue(completions.getCompletions().isEmpty());
    }

    @Test
    public void testURLArgumentCompletionWithNonFileProtocol() throws Exception {
        URLArgument arg = new URLArgument("arg1", 0);
        CommandCompletions completions = new CommandCompletions();
        arg.doComplete(completions, "http://www.jnode.org/" + SUBDIR_NAME, 0);
        Assert.assertTrue(completions.getCompletions().isEmpty());
    }

    @Test
    public void testURLArgumentCompletionWithAuthority() throws Exception {
        URLArgument arg = new URLArgument("arg1", 0);
        CommandCompletions completions = new CommandCompletions();
        arg.doComplete(completions, "file://localhost" + basePath() + File.separator
            + SUBDIR_NAME.substring(0, SUBDIR_NAME.length() - 1), 0);
        Assert.assertTrue(completions.getCompletions().isEmpty());
    }

    @Test
    public void testURLArgumentCompletionWithMalformedURL() throws Exception {
        URLArgument arg = new URLArgument("arg1", 0);
        CommandCompletions completions = new CommandCompletions();
        arg.doComplete(completions, "nosuchprotocol:/" + SUBDIR_NAME, 0);
        Assert.assertTrue(completions.getCompletions().isEmpty());
    }

    @Test
    public void testURLArgumentCompletionViaArgumentBundle() throws Exception {
        URLArgument arg = new URLArgument("arg1", 0);
        ArgumentBundle bundle = new ArgumentBundle("test", arg);
        CommandCompletions completions = new CommandCompletions();
        Token token =
            new Token("file:" + basePath() + File.separator
                + SUBDIR_NAME.substring(0, SUBDIR_NAME.length() - 1));
        CommandLine commandLine = new CommandLine(new Token("cmd"), new Token[] {token}, null);
        bundle.complete(commandLine, new SyntaxBundle("cmd", new ArgumentSyntax("arg1")),
            completions);
        assertSingleCompletion(completions, subDirURL());
    }
}
