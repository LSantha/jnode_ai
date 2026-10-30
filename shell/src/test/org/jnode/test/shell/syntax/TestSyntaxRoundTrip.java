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

import org.jnode.nanoxml.XMLElement;
import org.jnode.shell.syntax.Syntax;
import org.jnode.shell.syntax.SyntaxBundle;
import org.jnode.shell.syntax.SyntaxSpecLoader;
import org.jnode.shell.syntax.XMLSyntaxSpecAdapter;

/**
 * Support code for the Syntax <code>toXML()</code> round-trip tests. The Syntax tree
 * produced by <code>toXML()</code> is wrapped in a <code>&lt;syntax&gt;</code> root
 * element (as produced by the <code>syntax</code> command) and read back with
 * {@link SyntaxSpecLoader}.
 * 
 * @author crawley@jnode.org
 */
public class TestSyntaxRoundTrip {

    private TestSyntaxRoundTrip() {
    }

    /**
     * Serialize <code>syntax</code> with <code>toXML()</code> and re-parse it with a
     * fresh {@link SyntaxSpecLoader}.
     * 
     * @param syntax the syntax to round-trip
     * @return the re-parsed equivalent syntax
     */
    public static Syntax roundTrip(Syntax syntax) {
        return bundleRoundTrip(syntax).getSyntaxes()[0];
    }

    /**
     * Serialize <code>syntax</code> with <code>toXML()</code> and re-parse it with a
     * fresh {@link SyntaxSpecLoader}.
     * 
     * @param syntax the syntax to round-trip
     * @return the bundle holding the re-parsed equivalent syntax
     */
    public static SyntaxBundle bundleRoundTrip(Syntax syntax) {
        XMLElement root = new XMLElement();
        root.setName("syntax");
        root.setAttribute("alias", "cmd");
        root.addChild(syntax.toXML());
        SyntaxBundle bundle =
                new SyntaxSpecLoader().loadSyntax(new XMLSyntaxSpecAdapter(root));
        if (bundle.getSyntaxes().length != 1) {
            throw new IllegalStateException("expected exactly one re-parsed syntax");
        }
        return bundle;
    }
}