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

package org.jnode.shell.bjorne;

import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.FlagArgument;
import org.jnode.shell.syntax.OptionalSyntax;
import org.jnode.shell.syntax.RepeatSyntax;
import org.jnode.shell.syntax.SyntaxBundle;

/**
 * This class implements the 'echo' built-in.
 * 
 * @author crawley@jnode.org
 */
final class EchoBuiltin extends BjorneBuiltin {
    private static final SyntaxBundle SYNTAX = 
        new SyntaxBundle("echo", new OptionalSyntax(new FlagArgument("n", Argument.OPTIONAL, "if set, suppress trailing newline")),
                new RepeatSyntax(new ArgumentSyntax("arg"), 0, Integer.MAX_VALUE));

    static final Factory FACTORY = new Factory() {
        public BjorneBuiltinCommandInfo buildCommandInfo(BjorneContext context) {
            return new BjorneBuiltinCommandInfo("echo", SYNTAX, new EchoBuiltin(context), context);
        }
    };

    private final FlagArgument noNewlineArg = new FlagArgument(
            "n", Argument.OPTIONAL, "if set, suppress trailing newline");

    EchoBuiltin(BjorneContext context) {
        super("echo args");
        registerArguments(noNewlineArg);
    }

    public void execute() throws Exception {
        StringBuilder sb = new StringBuilder();
        String[] args = getArguments();
        boolean first = true;
        for (String arg : args) {
            if (!first) {
                sb.append(' ');
            }
            first = false;
            sb.append(arg);
        }
        if (sb.length() == 0) {
            return;
        }
        if (noNewlineArg.isSet()) {
            getOutput().print(sb.toString());
        } else {
            getOutput().println(sb.toString());
        }
    }
}