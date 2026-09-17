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

import java.util.NoSuchElementException;

import org.jnode.shell.CommandLine;
import org.jnode.shell.DefaultInterpreter;
import org.jnode.shell.ShellException;
import org.jnode.shell.SymbolSource;
import org.junit.Assert;
import org.junit.Test;

public class DefaultTokenizerTest {

    // Expose methods for testing.
    private class MyDefaultInterpreter extends DefaultInterpreter {
        DefaultInterpreter.Tokenizer makeTokenizer(String line) throws ShellException {
            return new DefaultInterpreter.Tokenizer(line);
        }

        DefaultInterpreter.Tokenizer makeTokenizer(String line, int flags) throws ShellException {
            return new DefaultInterpreter.Tokenizer(line, flags);
        }
    }

    @Test
    public void testTokenizerEmpty() throws ShellException {
        SymbolSource<CommandLine.Token> t = new MyDefaultInterpreter().makeTokenizer("");
        Assert.assertEquals(false, t.hasNext());
        Assert.assertEquals(false, t.whitespaceAfterLast());
    }

    @Test
    public void testTokenizerSpaces() throws ShellException {
        SymbolSource<CommandLine.Token> t = new MyDefaultInterpreter().makeTokenizer("   ");
        Assert.assertEquals(false, t.hasNext());
        Assert.assertEquals(true, t.whitespaceAfterLast());
    }

    @Test
    public void testTokenizerSimple() throws ShellException {
        SymbolSource<CommandLine.Token> t = new MyDefaultInterpreter().makeTokenizer("a b c");
        Assert.assertEquals(true, t.hasNext());
        Assert.assertEquals(false, t.whitespaceAfterLast());
        CommandLine.Token s = t.next();
        Assert.assertEquals("a", s.text);
        Assert.assertEquals(0, s.start);
        Assert.assertEquals(1, s.end);
        s = t.next();
        Assert.assertEquals("b", s.text);
        Assert.assertEquals(2, s.start);
        Assert.assertEquals(3, s.end);
        s = t.next();
        Assert.assertEquals("c", s.text);
        Assert.assertEquals(4, s.start);
        Assert.assertEquals(5, s.end);
        Assert.assertEquals(false, t.hasNext());
        Assert.assertEquals(3, t.tell());
        try {
            t.next();
            Assert.fail("No exception");
        } catch (NoSuchElementException ex) {
            // expected
        }
        t.seek(0);
        Assert.assertEquals(true, t.hasNext());
        s = t.next();
        Assert.assertEquals("a", s.text);
        Assert.assertEquals(0, s.start);
        Assert.assertEquals(1, s.end);
        Assert.assertEquals(1, t.tell());
        try {
            t.seek(-1);
            Assert.fail("No exception");
        } catch (NoSuchElementException ex) {
            // expected
        }
        try {
            t.seek(4);
            Assert.fail("No exception");
        } catch (NoSuchElementException ex) {
            // expected
        }
    }

    @Test
    public void testTokenizerQuotes() throws ShellException {
        SymbolSource<CommandLine.Token> t = new MyDefaultInterpreter().makeTokenizer("'a' \"b c\"");
        Assert.assertEquals(true, t.hasNext());
        Assert.assertEquals(false, t.whitespaceAfterLast());
        CommandLine.Token s = t.next();
        Assert.assertEquals("a", s.text);
        Assert.assertEquals(0, s.start);
        Assert.assertEquals(3, s.end);
        s = t.next();
        Assert.assertEquals("b c", s.text);
        Assert.assertEquals(4, s.start);
        Assert.assertEquals(9, s.end);
        Assert.assertEquals(false, t.hasNext());
    }

    @Test
    public void testTokenizerBackslashes() throws ShellException {
        SymbolSource<CommandLine.Token> t =
                new MyDefaultInterpreter().makeTokenizer("\\'a  b\\ c\\\"");
        Assert.assertEquals(true, t.hasNext());
        Assert.assertEquals(false, t.whitespaceAfterLast());
        CommandLine.Token s = t.next();
        Assert.assertEquals("'a", s.text);
        Assert.assertEquals(0, s.start);
        Assert.assertEquals(3, s.end);
        s = t.next();
        Assert.assertEquals("b c\"", s.text);
        Assert.assertEquals(5, s.start);
        Assert.assertEquals(11, s.end);
        Assert.assertEquals(false, t.hasNext());
    }

    @Test
    public void testTokenizerBackslashes2() throws ShellException {
        SymbolSource<CommandLine.Token> t =
                new MyDefaultInterpreter().makeTokenizer("\\\\\\n\\r\\t\\b ");
        Assert.assertEquals(true, t.hasNext());
        Assert.assertEquals(true, t.whitespaceAfterLast());
        CommandLine.Token s = t.next();
        Assert.assertEquals("\\\n\r\t\b", s.text);
        Assert.assertEquals(0, s.start);
        Assert.assertEquals(10, s.end);
        Assert.assertEquals(false, t.hasNext());
    }

    @Test
    public void testTokenizerRedirects() throws ShellException {
        SymbolSource<CommandLine.Token> t =
                new MyDefaultInterpreter().makeTokenizer("a< >b c|d \"<\" \\< '<'",
                        MyDefaultInterpreter.REDIRECTS_FLAG);
        Assert.assertEquals(true, t.hasNext());
        Assert.assertEquals(false, t.whitespaceAfterLast());
        Assert.assertEquals("a", t.next().text);
        Assert.assertEquals("<", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.SPECIAL, t.last().tokenType);
        Assert.assertEquals(">", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.SPECIAL, t.last().tokenType);
        Assert.assertEquals("b", t.next().text);
        Assert.assertEquals("c", t.next().text);
        Assert.assertEquals("|", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.SPECIAL, t.last().tokenType);
        Assert.assertEquals("d", t.next().text);
        Assert.assertEquals("<", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.STRING | MyDefaultInterpreter.CLOSED,
                t.last().tokenType);
        Assert.assertEquals("<", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.LITERAL, t.last().tokenType);
        Assert.assertEquals("<", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.STRING | MyDefaultInterpreter.CLOSED,
                t.last().tokenType);
        Assert.assertEquals(false, t.hasNext());
    }

    @Test
    public void testTokenizerSequenceOperators() throws ShellException {
        SymbolSource<CommandLine.Token> t =
                new MyDefaultInterpreter().makeTokenizer("a&&b");
        Assert.assertEquals("a", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.LITERAL, t.last().tokenType);
        Assert.assertEquals("&&", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.SPECIAL, t.last().tokenType);
        Assert.assertEquals("b", t.next().text);
        Assert.assertEquals(false, t.hasNext());

        t = new MyDefaultInterpreter().makeTokenizer("a || b");
        Assert.assertEquals("a", t.next().text);
        Assert.assertEquals("||", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.SPECIAL, t.last().tokenType);
        Assert.assertEquals("b", t.next().text);
        Assert.assertEquals(false, t.hasNext());

        t = new MyDefaultInterpreter().makeTokenizer("a;b");
        Assert.assertEquals("a", t.next().text);
        Assert.assertEquals(";", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.SPECIAL, t.last().tokenType);
        Assert.assertEquals("b", t.next().text);
        Assert.assertEquals(false, t.hasNext());
    }

    @Test
    public void testTokenizerOrWithoutRedirectsFlag() throws ShellException {
        // '||' is an operator even when REDIRECTS_FLAG is not set.
        SymbolSource<CommandLine.Token> t =
                new MyDefaultInterpreter().makeTokenizer("a||b", 0);
        Assert.assertEquals("a", t.next().text);
        Assert.assertEquals("||", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.SPECIAL, t.last().tokenType);
        Assert.assertEquals("b", t.next().text);
        Assert.assertEquals(false, t.hasNext());
    }

    @Test
    public void testTokenizerSinglePipeWithoutFlagIsLiteral() throws ShellException {
        // A single '|' is literal without REDIRECTS_FLAG ...
        SymbolSource<CommandLine.Token> t =
                new MyDefaultInterpreter().makeTokenizer("a|b", 0);
        Assert.assertEquals(true, t.hasNext());
        Assert.assertEquals("a|b", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.LITERAL, t.last().tokenType);
        Assert.assertEquals(false, t.hasNext());

        t = new MyDefaultInterpreter().makeTokenizer("a | b", 0);
        Assert.assertEquals("a", t.next().text);
        Assert.assertEquals("|", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.LITERAL, t.last().tokenType);
        Assert.assertEquals("b", t.next().text);
        Assert.assertEquals(false, t.hasNext());

        // ... but special with REDIRECTS_FLAG.
        t = new MyDefaultInterpreter().makeTokenizer("a | b",
                MyDefaultInterpreter.REDIRECTS_FLAG);
        Assert.assertEquals("a", t.next().text);
        Assert.assertEquals("|", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.SPECIAL, t.last().tokenType);
        Assert.assertEquals("b", t.next().text);
        Assert.assertEquals(false, t.hasNext());
    }

    @Test
    public void testTokenizerQuotedOperators() throws ShellException {
        SymbolSource<CommandLine.Token> t =
                new MyDefaultInterpreter().makeTokenizer("'a&&b' \"c||d\" 'e;f'");
        CommandLine.Token s = t.next();
        Assert.assertEquals("a&&b", s.text);
        Assert.assertTrue((s.tokenType & MyDefaultInterpreter.STRING) != 0);
        s = t.next();
        Assert.assertEquals("c||d", s.text);
        Assert.assertTrue((s.tokenType & MyDefaultInterpreter.STRING) != 0);
        s = t.next();
        Assert.assertEquals("e;f", s.text);
        Assert.assertTrue((s.tokenType & MyDefaultInterpreter.STRING) != 0);
        Assert.assertEquals(false, t.hasNext());
    }

    @Test
    public void testTokenizerEscapedOperators() throws ShellException {
        SymbolSource<CommandLine.Token> t =
                new MyDefaultInterpreter().makeTokenizer("a\\&\\&b");
        Assert.assertEquals("a&&b", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.LITERAL, t.last().tokenType);
        Assert.assertEquals(false, t.hasNext());

        t = new MyDefaultInterpreter().makeTokenizer("a\\|\\|b");
        Assert.assertEquals("a||b", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.LITERAL, t.last().tokenType);
        Assert.assertEquals(false, t.hasNext());

        t = new MyDefaultInterpreter().makeTokenizer("a\\;b");
        Assert.assertEquals("a;b", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.LITERAL, t.last().tokenType);
        Assert.assertEquals(false, t.hasNext());
    }

    @Test
    public void testTokenizerCommentWithOperators() throws ShellException {
        SymbolSource<CommandLine.Token> t =
                new MyDefaultInterpreter().makeTokenizer("a && b # c && d");
        Assert.assertEquals("a", t.next().text);
        Assert.assertEquals("&&", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.SPECIAL, t.last().tokenType);
        Assert.assertEquals("b", t.next().text);
        // A trailing comment is ignored: any remaining token must be empty.
        while (t.hasNext()) {
            Assert.assertEquals("", t.next().text);
        }
    }

    @Test
    public void testTokenizerUrlWithAmp() throws ShellException {
        SymbolSource<CommandLine.Token> t =
                new MyDefaultInterpreter().makeTokenizer("http://x/?a=1&b=2");
        Assert.assertEquals("http://x/?a=1&b=2", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.LITERAL, t.last().tokenType);
        Assert.assertEquals(false, t.hasNext());
    }

    @Test
    public void testTokenizerStandaloneAmpIsSpecial() throws ShellException {
        SymbolSource<CommandLine.Token> t =
                new MyDefaultInterpreter().makeTokenizer("echo a & echo b");
        Assert.assertEquals("echo", t.next().text);
        Assert.assertEquals("a", t.next().text);
        Assert.assertEquals("&", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.SPECIAL, t.last().tokenType);
        Assert.assertEquals("echo", t.next().text);
        Assert.assertEquals("b", t.next().text);
        Assert.assertEquals(false, t.hasNext());

        t = new MyDefaultInterpreter().makeTokenizer("a &&& b");
        Assert.assertEquals("a", t.next().text);
        Assert.assertEquals("&&", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.SPECIAL, t.last().tokenType);
        Assert.assertEquals("&", t.next().text);
        Assert.assertEquals(MyDefaultInterpreter.SPECIAL, t.last().tokenType);
        Assert.assertEquals("b", t.next().text);
        Assert.assertEquals(false, t.hasNext());
    }
}
