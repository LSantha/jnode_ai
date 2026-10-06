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
 
package org.jnode.shell.syntax;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.Assert;
import org.junit.Test;

public class SharedStackTest {

    private static Deque<String> base(String... elements) {
        Deque<String> res = new LinkedList<String>();
        for (String element : elements) {
            res.addLast(element);
        }
        return res;
    }

    private static List<String> drain(Iterator<String> it) {
        List<String> res = new ArrayList<String>();
        while (it.hasNext()) {
            res.add(it.next());
        }
        return res;
    }

    @Test
    public void testEmptyBaseStack() {
        SharedStack<String> stack = new SharedStack<String>(base());
        Assert.assertEquals(0, stack.size());
        Assert.assertTrue(stack.isEmpty());
        Assert.assertTrue(stack.toArray().length == 0);
        Assert.assertFalse(stack.iterator().hasNext());
    }

    @Test
    public void testSizeIncludesBaseStack() {
        SharedStack<String> stack = new SharedStack<String>(base("a", "b", "c"));
        Assert.assertEquals(3, stack.size());
        Assert.assertFalse(stack.isEmpty());

        stack.push("d");
        Assert.assertEquals(4, stack.size());
        stack.pop();
        Assert.assertEquals(3, stack.size());
    }

    @Test
    public void testPushIsLifo() {
        SharedStack<String> stack = new SharedStack<String>(base("a", "b"));
        stack.push("x");
        stack.addFirst("y");
        stack.push("z");

        Assert.assertEquals(5, stack.size());
        Assert.assertEquals("z", stack.getFirst());
        Assert.assertEquals("z", stack.peek());
        Assert.assertEquals("z", stack.peekFirst());
        Assert.assertEquals("z", stack.pop());
        Assert.assertEquals("y", stack.pop());
    }

    @Test
    public void testPopIsFifoOverBaseStack() {
        SharedStack<String> stack = new SharedStack<String>(base("a", "b", "c"));
        stack.push("x");

        Assert.assertEquals("x", stack.pop());
        Assert.assertEquals("a", stack.pop());
        Assert.assertEquals("b", stack.pop());
        Assert.assertEquals("c", stack.pop());
        Assert.assertEquals(0, stack.size());
        Assert.assertTrue(stack.isEmpty());
    }

    @Test
    public void testPopUnderflow() {
        SharedStack<String> stack = new SharedStack<String>(base("a"));
        Assert.assertEquals("a", stack.pop());
        try {
            stack.pop();
            Assert.fail("expected NSEE");
        } catch (NoSuchElementException ex) {
            // expected
        }
        Assert.assertEquals(0, stack.size());
    }

    @Test
    public void testRemoveFirstIsSameAsPop() {
        SharedStack<String> stack = new SharedStack<String>(base("a"));
        stack.push("x");
        Assert.assertEquals("x", stack.removeFirst());
        Assert.assertEquals("a", stack.removeFirst());
        try {
            stack.removeFirst();
            Assert.fail("expected NSEE");
        } catch (NoSuchElementException ex) {
            // expected
        }
    }

    @Test
    public void testPeekIgnoresBaseStack() {
        SharedStack<String> stack = new SharedStack<String>(base("a", "b"));
        Assert.assertNull(stack.peek());
        Assert.assertNull(stack.peekFirst());
        try {
            stack.getFirst();
            Assert.fail("expected NSEE");
        } catch (NoSuchElementException ex) {
            // expected
        }
        stack.push("x");
        Assert.assertEquals("x", stack.peek());
    }

    @Test
    public void testIteratorOrder() {
        SharedStack<String> stack = new SharedStack<String>(base("a", "b"));
        stack.push("y");
        stack.push("x");
        Assert.assertEquals(Arrays.asList("x", "y", "a", "b"), drain(stack.iterator()));
    }

    @Test
    public void testIteratorAfterBaseStackDrained() {
        SharedStack<String> stack = new SharedStack<String>(base("a", "b"));
        Assert.assertEquals("a", stack.pop());
        Assert.assertEquals("b", stack.pop());
        Assert.assertTrue(drain(stack.iterator()).isEmpty());

        stack.push("x");
        Assert.assertEquals(Arrays.asList("x"), drain(stack.iterator()));
    }

    @Test
    public void testIteratorUnderflow() {
        SharedStack<String> stack = new SharedStack<String>(base("a"));
        Assert.assertEquals("a", stack.pop());

        Iterator<String> it = stack.iterator();
        Assert.assertFalse(it.hasNext());
        try {
            it.next();
            Assert.fail("expected NSEE");
        } catch (NoSuchElementException ex) {
            Assert.assertEquals("iterator is tired and emotional", ex.getMessage());
        }
    }

    @Test
    public void testIteratorRemoveIsUnsupported() {
        SharedStack<String> stack = new SharedStack<String>(base("a", "b"));
        Iterator<String> it = stack.iterator();
        it.next();
        try {
            it.remove();
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
    }

    @Test
    public void testToArray() {
        SharedStack<String> stack = new SharedStack<String>(base("a", "b"));
        Assert.assertTrue(Arrays.equals(new String[] {"a", "b"}, stack.toArray()));

        stack.push("y");
        stack.push("x");
        Assert.assertTrue(Arrays.equals(new String[] {"x", "y", "a", "b"}, stack.toArray()));

        Assert.assertEquals("x", stack.pop());
        Assert.assertEquals("y", stack.pop());
        Assert.assertTrue(Arrays.equals(new String[] {"a", "b"}, stack.toArray()));

        Assert.assertEquals("a", stack.pop());
        Assert.assertEquals("b", stack.pop());
        Assert.assertTrue(Arrays.equals(new String[0], stack.toArray()));

        stack.push("z");
        Assert.assertTrue(Arrays.equals(new String[] {"z"}, stack.toArray()));
    }

    @Test
    public void testNestedStacks() {
        SharedStack<String> outer = new SharedStack<String>(base("root"));
        outer.push("o1");

        SharedStack<String> inner = new SharedStack<String>(outer);
        inner.push("i1");

        Assert.assertEquals(3, inner.size());
        Assert.assertEquals(Arrays.asList("i1", "o1", "root"), drain(inner.iterator()));

        Assert.assertEquals("i1", inner.pop());
        Assert.assertEquals("o1", inner.pop());
        Assert.assertEquals("root", inner.pop());
        Assert.assertTrue(inner.isEmpty());

        Assert.assertEquals(2, outer.size());
        Assert.assertEquals(Arrays.asList("o1", "root"), drain(outer.iterator()));
    }

    @Test
    public void testNestedStacksCopyOnWrite() {
        SharedStack<String> outer = new SharedStack<String>(base("root", "root2"));
        outer.push("o1");

        SharedStack<String> inner = new SharedStack<String>(outer);
        inner.push("i1");

        Assert.assertEquals("i1", inner.pop());
        Assert.assertEquals("o1", inner.pop());

        // inner now has its own copy of the outer stack's contents
        inner.push("i2");
        Assert.assertEquals(3, inner.size());
        Assert.assertEquals(Arrays.asList("i2", "root", "root2"), drain(inner.iterator()));

        Assert.assertEquals(3, outer.size());
        Assert.assertEquals(Arrays.asList("o1", "root", "root2"), drain(outer.iterator()));
    }

    @Test
    public void testMutatedBaseStackIsDetected() {
        Deque<String> base = base("a", "b");
        SharedStack<String> stack = new SharedStack<String>(base);
        base.addLast("c");
        try {
            stack.size();
            Assert.fail("expected AssertionError");
        } catch (AssertionError ex) {
            Assert.assertEquals("base stack has been updated!", ex.getMessage());
        }
        try {
            stack.push("x");
            Assert.fail("expected AssertionError");
        } catch (AssertionError ex) {
            Assert.assertEquals("base stack has been updated!", ex.getMessage());
        }
    }

    @Test
    public void testUnsupportedOperations() {
        SharedStack<String> stack = new SharedStack<String>(base("a"));
        stack.push("x");

        try {
            stack.add("x");
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.addLast("x");
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.addAll(Arrays.asList("y"));
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.clear();
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.contains("x");
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.containsAll(Arrays.asList("x"));
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.descendingIterator();
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.element();
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.getLast();
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.offer("x");
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.offerFirst("x");
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.offerLast("x");
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.peekLast();
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.poll();
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.pollFirst();
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.pollLast();
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.remove("x");
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.removeAll(Arrays.asList("x"));
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.removeFirstOccurrence("x");
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.removeLast();
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.removeLastOccurrence("x");
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.retainAll(Arrays.asList("x"));
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            stack.toArray(new String[0]);
            Assert.fail("expected UOE");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
    }

    @Test
    public void testRemoveIsSameAsPop() {
        SharedStack<String> stack = new SharedStack<String>(base("a", "b"));
        stack.push("x");

        Assert.assertEquals("x", stack.remove());
        Assert.assertEquals("a", stack.remove());
        Assert.assertEquals("b", stack.remove());
        try {
            stack.remove();
            Assert.fail("expected NSEE");
        } catch (NoSuchElementException ex) {
            // expected
        }
    }

    @Test
    public void testArrayDequeBaseStack() {
        Deque<String> base = new ArrayDeque<String>();
        base.addLast("a");
        base.addLast("b");
        SharedStack<String> stack = new SharedStack<String>(base);
        stack.push("x");

        Assert.assertEquals(3, stack.size());
        Assert.assertEquals(Arrays.asList("x", "a", "b"), drain(stack.iterator()));
        Assert.assertEquals("x", stack.pop());
        Assert.assertEquals("a", stack.pop());
        Assert.assertEquals("b", stack.pop());
        Assert.assertTrue(stack.isEmpty());
    }
}
