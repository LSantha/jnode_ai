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
 
package org.jnode.test.shell;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;

import org.jnode.shell.PathnamePattern;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test key methods of the PathnamePattern class.
 *
 * @author crawley@jnode.org
 */
public class PathnamePatternTest {
    
    private static final int DF = PathnamePattern.DEFAULT_FLAGS;

    @Test
    public void testIsPattern() {
        Assert.assertTrue(PathnamePattern.isPattern("*"));
        Assert.assertTrue(PathnamePattern.isPattern("?"));
        Assert.assertTrue(PathnamePattern.isPattern("[abc]"));
        Assert.assertTrue(PathnamePattern.isPattern("\"hi\""));
        Assert.assertTrue(PathnamePattern.isPattern("\'hi\'"));
        Assert.assertTrue(PathnamePattern.isPattern("hi\\ there"));
        Assert.assertFalse(PathnamePattern.isPattern("hi there"));
        Assert.assertFalse(PathnamePattern.isPattern(""));
        Assert.assertFalse(PathnamePattern.isPattern(" "));
    }
    
    @Test
    public void testCompilePosixShellPattern() {
        Assert.assertEquals("abc", PathnamePattern.compilePosixShellPattern("abc", 0).toString());
        Assert.assertEquals("abc", PathnamePattern.compilePosixShellPattern("abc", DF).toString());
        
        Assert.assertEquals(".", PathnamePattern.compilePosixShellPattern("?", 0).toString());
        Assert.assertEquals("[^\\.]", PathnamePattern.compilePosixShellPattern("?", DF).toString());
        
        Assert.assertEquals(".*?", PathnamePattern.compilePosixShellPattern("*", 0).toString());
        Assert.assertEquals("(|[^\\.].*?)", PathnamePattern.compilePosixShellPattern("*", DF)
                .toString());

        Assert.assertEquals(".*?a.*?", PathnamePattern.compilePosixShellPattern("*a*", 0)
                .toString());
        Assert.assertEquals("(|[^\\.].*?)a.*?", PathnamePattern.compilePosixShellPattern("*a*", DF)
                .toString());

        Assert.assertEquals("a.*?a.*?a", PathnamePattern.compilePosixShellPattern("a*a*a", 0)
                .toString());
        Assert.assertEquals("a.*?a.*?a", PathnamePattern.compilePosixShellPattern("a*a*a", DF)
                .toString());

        Assert.assertEquals("\".*?a.*?\"", PathnamePattern.compilePosixShellPattern("\"*a*\"", 0)
                .toString());
        Assert.assertEquals("\\*a\\*", PathnamePattern.compilePosixShellPattern("\"*a*\"", DF)
                .toString());

        Assert.assertEquals("\'.*?a.*?\'", PathnamePattern.compilePosixShellPattern("\'*a*\'", 0)
                .toString());
        Assert.assertEquals("\\*a\\*", PathnamePattern.compilePosixShellPattern("\'*a*\'", DF)
                .toString());

        Assert.assertEquals("\\\\.*?a.*?", PathnamePattern.compilePosixShellPattern("\\*a*", 0)
                .toString());
        Assert.assertEquals("\\*a.*?", PathnamePattern.compilePosixShellPattern("\\*a*", DF)
                .toString());
    }

    @Test
    public void testCompilePathPattern() {
        Assert.assertEquals("PathnamePattern{source='abc',absolute=false,patterns=['abc']}",
                PathnamePattern.compilePathPattern("abc", DF).toRegexString());

        Assert.assertEquals("PathnamePattern{source='?',absolute=false,patterns=['^[^\\.]$']}",
            PathnamePattern.compilePathPattern("?", DF).toRegexString());

        // The following (which matches an empty pathname component) is
        // suboptimal but
        // not incorrect. In practice, we should never encounter an empty
        // pathname component.
        Assert.assertEquals(
                "PathnamePattern{source='*',absolute=false,patterns=['^(|[^\\.].*)$']}",
                PathnamePattern.compilePathPattern("*", DF).toRegexString());

        Assert.assertEquals("PathnamePattern{source='\"*\"',absolute=false,patterns=['^\\*$']}",
                PathnamePattern.compilePathPattern("\"*\"", DF).toRegexString());

        Assert.assertEquals("PathnamePattern{source='a/b',absolute=false,patterns=['a','b']}",
                PathnamePattern.compilePathPattern("a/b", DF).toRegexString());

        Assert.assertEquals(
                "PathnamePattern{source='a/*',absolute=false,patterns=['a','^(|[^\\.].*)$']}",
                PathnamePattern.compilePathPattern("a/*", DF).toRegexString());

        Assert.assertEquals(
                "PathnamePattern{source='/a/*',absolute=true,patterns=['a','^(|[^\\.].*)$']}",
                PathnamePattern.compilePathPattern("/a/*", DF).toRegexString());

        Assert.assertEquals(
                "PathnamePattern{source='/a/\\*',absolute=true,patterns=['a','^\\*$']}",
                PathnamePattern.compilePathPattern("/a/\\*", DF).toRegexString());

        Assert.assertEquals(
                "PathnamePattern{source='a//\"*\"',absolute=false,patterns=['a','^\\*$']}",
                PathnamePattern.compilePathPattern("a//\"*\"", DF).toRegexString());

        Assert.assertEquals(
                "PathnamePattern{source='/a/\"*\"',absolute=true,patterns=['a','^\\*$']}",
                PathnamePattern.compilePathPattern("/a/\"*\"", DF).toRegexString());

        Assert.assertEquals(
                "PathnamePattern{source='\"/a/*\"',absolute=true,patterns=['a','^\\*$']}",
                PathnamePattern.compilePathPattern("\"/a/*\"", DF).toRegexString());

        Assert.assertEquals(
                "PathnamePattern{source='\"/a/*\"',absolute=true,patterns=['a','^\\*$']}",
                PathnamePattern.compilePathPattern("\"/a/*\"", DF).toRegexString());
        
        Assert.assertEquals("PathnamePattern{source='{print \\$1}',absolute=false,patterns=['^\\{print \\$1\\}$']}",
                PathnamePattern.compilePathPattern("{print \\$1}", DF).toRegexString());
    }
    
    @Test
    public void testExpand() throws Exception {
        // Use a controlled temporary directory rather than globbing the real
        // filesystem (e.g. /tmp may contain sockets or other special files
        // for which File.exists() returns false). This keeps the test immune
        // to unrelated contents of the host's temporary directories.
        File dir = createTempDir();
        try {
            String[] names = {"alpha", "beta", "gamma"};
            for (String name : names) {
                Assert.assertTrue("create " + name, new File(dir, name).createNewFile());
            }

            // Relative pattern expanded against the temp directory. The
            // expansion returns names relative to 'dir', so resolve them
            // against 'dir' when checking.
            PathnamePattern pat = PathnamePattern.compilePathPattern("*");
            LinkedList<String> list = pat.expand(dir);
            Assert.assertEquals("relative match count", names.length, list.size());
            for (String path : list) {
                File f = new File(dir, path);
                Assert.assertTrue("exists " + path, f.exists());
                Assert.assertTrue("is file " + path, f.isFile());
            }

            // Absolute pattern expanded against the temp directory.
            pat = PathnamePattern.compilePathPattern(dir.getCanonicalPath() + "/*");
            list = pat.expand(new File("."));
            Assert.assertEquals("absolute match count", names.length, list.size());
            for (String path : list) {
                Assert.assertTrue("exists " + path, new File(path).exists());
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    private static File createTempDir() throws IOException {
        File base = new File(System.getProperty("java.io.tmpdir", "."));
        File d = new File(base, "jnode-pp-test-" + System.currentTimeMillis() + "-"
                + Thread.currentThread().getId());
        Assert.assertTrue("mkdir " + d, d.mkdirs());
        return d;
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }
}
