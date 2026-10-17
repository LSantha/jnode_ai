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
import org.jnode.shell.syntax.RepeatSyntax;
import org.jnode.shell.syntax.SequenceSyntax;
import org.jnode.shell.syntax.SyntaxBundle;

/**
 * This class implements the 'echo' built-in. It prints arguments separated by
 * spaces, with optional trailing newline based on the -n flag.
 * 
 * @author Auto-generated
 */
final class EchoBuiltin extends BjorneBuiltin {
    private static final SyntaxBundle SYNTAX = 
        new SyntaxBundle("echo", new SequenceSyntax(
                new FlagArgument("noNewline", 'n', Argument.OPTIONAL, "if set, no trailing newline"),
                new RepeatSyntax(new org.jnode.shell.syntax.ArgumentSyntax("arg"), 0, Integer.MAX_VALUE)));
    
    static final Factory FACTORY = new Factory() {
        public BjorneBuiltinCommandInfo buildCommandInfo(BjorneContext context) {
            return new BjorneBuiltinCommandInfo("echo", SYNTAX, new EchoBuiltin(context), context);
        }
    };
    
    private final FlagArgument flagNoNewline = new FlagArgument(
            "noNewline", 'n', Argument.OPTIONAL, "if set, no trailing newline");
    
    private final org.jnode.shell.syntax.ArgumentSyntax argArg = new org.jnode.shell.syntax.ArgumentSyntax(
            "arg", Argument.OPTIONAL + Argument.MULTIPLE, "arguments to echo");
    
    private final BjorneContext context;
    
    EchoBuiltin(BjorneContext context) {
        super("the bjorne 'echo' command prints arguments separated by spaces");
        this.context = context;
        registerArguments(flagNoNewline, argArg);
    }
    
    @Override
    public void execute() throws Exception {
        String[] args = argArg.getValues();
        if (args.length == 0) {
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        
        getOutput().getPrintWriter().print(sb.toString());
        if (!flagNoNewline.isSet()) {
            getOutput().getPrintWriter().println();
        }
    }
}