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
 
package org.jnode.shell;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.apache.log4j.Logger;
import org.jnode.shell.help.Help;
import org.jnode.shell.help.HelpException;
import org.jnode.shell.help.HelpFactory;
import org.jnode.shell.io.CommandIO;
import org.jnode.shell.io.CommandInput;
import org.jnode.shell.io.CommandOutput;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.FileArgument;

/**
 * This command interpreter supports simple input and output redirection and
 * pipelines.
 * 
 * @author crawley@jnode.org
 */
public class RedirectingInterpreter extends DefaultInterpreter implements
        ThreadExitListener {

    public static final Factory FACTORY = new Factory() {
        public CommandInterpreter create() {
            return new RedirectingInterpreter();
        }

        public String getName() {
            return "redirecting";
        }
    };

    private static final Logger LOG = Logger.getLogger(RedirectingInterpreter.class);

    @Override
    public String getName() {
        return "redirecting";
    }

    @Override
    protected int interpret(CommandShell shell, String line) throws ShellException {
        Tokenizer tokenizer = new Tokenizer(line, REDIRECTS_FLAG);
        List<SequenceDescriptor> sequence = new LinkedList<SequenceDescriptor>();
        parse(tokenizer, sequence, false);
        if (sequence.isEmpty()) {
            return 0; // empty command line.
        }
        if (sequence.size() == 1) {
            // Preserve the historical single-pipeline contract: ShellException
            // failures propagate to the caller.  Only multi-pipeline sequences
            // convert failures to rc == 1 for '&&' / '||' / ';' semantics.
            return runSequence(shell, sequence.get(0).pipeline);
        }
        int rc = 0;
        for (int i = 0; i < sequence.size(); i++) {
            SequenceDescriptor seq = sequence.get(i);
            if (i > 0) {
                String op = seq.operatorBefore;
                if (AND_IF.equals(op) && rc != 0) {
                    continue;
                } else if (OR_IF.equals(op) && rc == 0) {
                    continue;
                }
                // ';' always executes
            }
            try {
                rc = runSequence(shell, seq.pipeline);
            } catch (ShellControlException ex) {
                throw ex;
            } catch (ShellException ex) {
                // A per-pipeline failure (e.g. unknown command, bad redirection)
                // should not abort the whole '&&' / '||' / ';' sequence; record it
                // as a failure return code so that conditional execution works.
                shell.diagnose(ex, null);
                rc = 1;
            }
        }
        return rc;
    }

    private int runSequence(CommandShell shell, List<CommandDescriptor> commands)
        throws ShellException {
        int len = commands.size();
        if (len == 0) {
            return 0;
        } else if (len == 1) {
            return runCommand(shell, commands.get(0));
        } else {
            return runPipeline(shell, commands);
        }
    }

    @Override
    public Completable parsePartial(CommandShell shell, String line)
        throws ShellException {
        Tokenizer tokenizer = new Tokenizer(line, REDIRECTS_FLAG);
        List<SequenceDescriptor> sequence = new LinkedList<SequenceDescriptor>();
        return parse(tokenizer, sequence, true);
    }
    
    @Override
    public boolean help(CommandShell shell, String line, PrintWriter pw) throws ShellException {
        Tokenizer tokenizer = new Tokenizer(line, REDIRECTS_FLAG);
        List<SequenceDescriptor> sequence = new LinkedList<SequenceDescriptor>();
        parse(tokenizer, sequence, true);
        CommandLine cmd = getLastCommand(sequence);
        if (cmd == null) {
            return false;
        }
        // We'll show the help for the last command in the last pipeline.
        CommandInfo cmdInfo = cmd.getCommandInfo(shell);
        if (cmdInfo != null) {
            try {
                Help help = HelpFactory.getHelpFactory().getHelp(cmd.getCommandName(), cmdInfo);
                help.usage(pw);
                return true;
            } catch (HelpException ex) {
                LOG.info("Unexpected error while getting help for alias / class '" + 
                        cmd.getCommandName() + "': " + ex.getMessage(), ex);
            }
        }
        return false;
    }

    private CommandLine getLastCommand(List<SequenceDescriptor> sequence) {
        for (int i = sequence.size() - 1; i >= 0; i--) {
            List<CommandDescriptor> pipeline = sequence.get(i).pipeline;
            if (!pipeline.isEmpty()) {
                return pipeline.get(pipeline.size() - 1).commandLine;
            }
        }
        return null;
    }

    @Override
    public String escapeWord(String word) {
        return escapeWord(word, true);
    }

    /**
     * This method parses the shell input into command lines.  If we are completing,
     * then we return a Completable that will do completion for / after the last token
     * according to the parser's syntactic context.  (Normally the Completable is a 
     * CommandLine, but if we at / expecting a redirection filename, it will be a
     * Completer for the filename.)
     * <p>
     * The input is parsed as a sequence of pipelines separated by '&&', '||' and
     * ';' operators with POSIX shell compatible semantics.  Each pipeline is a
     * sequence of commands separated by '|' with optional '&lt;' / '&gt;'
     * redirections on each command.
     * 
     * @param tokenizer the source of shell input tokens
     * @param sequence a list for accumulating the parsed pipelines and their
     *        preceding '&&' / '||' / ';' operators
     * @param completing if <code>true</code> we are completing.
     * @return a Completer or <code>null</code>
     * @throws ShellSyntaxException
     */
    private Completable parse(Tokenizer tokenizer,
            List<SequenceDescriptor> sequence, boolean completing)
        throws ShellSyntaxException {
        boolean wspAfter = tokenizer.whitespaceAfterLast();
        List<CommandDescriptor> currentPipeline = new ArrayList<CommandDescriptor>();
        String pendingOp = null;
        boolean trailingPipe = false;
        boolean trailingSequenceOp = false;
        List<CommandLine.Token> args = new ArrayList<CommandLine.Token>();
        while (tokenizer.hasNext()) {
            trailingPipe = false;
            trailingSequenceOp = false;
            CommandLine.Token commandToken = tokenizer.next();
            if (commandToken.tokenType == SPECIAL) {
                throw new ShellSyntaxException("Misplaced '" +
                        commandToken.text + "': expected a command name");
            }
            
            CommandLine.Token from = null;
            CommandLine.Token to = null;
            boolean pipeTo = false;
            String pipelineTerminator = null;
            args.clear();
            while (tokenizer.hasNext()) {
                CommandLine.Token token = tokenizer.next();
                if (token.tokenType == SPECIAL) {
                    if (token.text.equals("<")) {
                        from = parseFileName(tokenizer, "<");
                        if (from == null && !completing) {
                            throw new ShellSyntaxException("no filename after '<'");
                        } else if (completing && 
                                (from == null || (!tokenizer.hasNext() && !wspAfter))) {
                            return new ArgumentCompleter(
                                    new FileArgument("?", Argument.MANDATORY, null), from);
                        }
                        continue;
                    } else if (token.text.equals(">")) {
                        to = parseFileName(tokenizer, ">");
                        if (to == null && !completing) {
                            throw new ShellSyntaxException("no filename after '>'");
                        } else if (completing && 
                                (to == null || (!tokenizer.hasNext() && !wspAfter))) {
                            return new ArgumentCompleter(
                                    new FileArgument("?", Argument.MANDATORY, null), to);
                        }
                        continue;
                    } else if (token.text.equals("|")) {
                        pipeTo = true;
                        break;
                    } else if (AND_IF.equals(token.text) || OR_IF.equals(token.text) ||
                            SEMI.equals(token.text)) {
                        pipelineTerminator = token.text;
                        break;
                    } else if (AMP.equals(token.text)) {
                        throw new ShellSyntaxException(
                                "unsupported '&': use '&&' for conditional execution");
                    } else {
                        throw new ShellSyntaxException(
                                "unrecognized symbol: '" + token.text + "'");
                    }
                } else {
                    args.add(token);
                }
            }
            CommandLine.Token[] argVec =
                    args.toArray(new CommandLine.Token[args.size()]);

            CommandLine cl = new CommandLine(commandToken, argVec, null);
            currentPipeline.add(new CommandDescriptor(cl, from, to, pipeTo));
            if (pipelineTerminator != null) {
                sequence.add(new SequenceDescriptor(currentPipeline, pendingOp));
                pendingOp = pipelineTerminator;
                currentPipeline = new ArrayList<CommandDescriptor>();
                if (!tokenizer.hasNext()) {
                    if (!completing) {
                        throw new ShellSyntaxException(
                                "no command after '" + pipelineTerminator + "'");
                    }
                    trailingSequenceOp = true;
                    break;
                }
                // else continue with the next pipeline
            } else if (pipeTo) {
                if (!tokenizer.hasNext()) {
                    if (!completing) {
                        throw new ShellSyntaxException("no command after '|'");
                    }
                    trailingPipe = true;
                    break;
                }
                // else continue with the next command in the same pipeline
            } else {
                // End of tokens: the current pipeline is complete.
                if (!tokenizer.hasNext()) {
                    break;
                }
            }
        }
        if (!currentPipeline.isEmpty()) {
            sequence.add(new SequenceDescriptor(currentPipeline, pendingOp));
        }
        if (completing) {
            if (trailingPipe || trailingSequenceOp || sequence.isEmpty()) {
                return new CommandLine("", null);
            } else {
                CommandLine res = getLastCommand(sequence);
                res.setArgumentAnticipated(wspAfter);
                return res;
            }
        } else {
            return null;
        }
    }

    private CommandLine.Token parseFileName(Tokenizer tokenizer, String special)
        throws ShellSyntaxException {
        if (!tokenizer.hasNext()) {
            return null;
        }
        CommandLine.Token token = tokenizer.next();
        if (token.tokenType == SPECIAL) {
            throw new ShellSyntaxException("misplaced '" + token + "'");
        }
        if (token.text.isEmpty()) {
            throw new ShellSyntaxException("empty '" + special + "' file name");
        }
        return token;
    }

    private int runCommand(CommandShell shell, CommandDescriptor desc)
        throws ShellException {
        CommandIO in = CommandLine.DEFAULT_STDIN;
        CommandIO out = CommandLine.DEFAULT_STDOUT;
        CommandIO err = CommandLine.DEFAULT_STDERR;
        try {
            try {
                if (desc.fromFileName != null) {
                    in = new CommandInput(new FileInputStream(desc.fromFileName.text));
                }
            } catch (IOException ex) {
                throw new ShellInvocationException("cannot open '" +
                        desc.fromFileName.text + "': " + ex.getMessage());
            }
            try {
                if (desc.toFileName != null) {
                    out = new CommandOutput(new FileOutputStream(desc.toFileName.text));
                }
            } catch (IOException ex) {
                throw new ShellInvocationException("cannot open '" +
                        desc.toFileName.text + "': " + ex.getMessage());
            }
            desc.commandLine.setStreams(new CommandIO[] {in, out, err, CommandLine.DEFAULT_STDERR});
            return shell.invoke(desc.commandLine, null, null);
        } finally {
            try {
                if (desc.fromFileName != null) {
                    in.close();
                }
            } catch (IOException ex) {
                // squash
            }
            try {
                if (desc.toFileName != null && out != null) {
                    out.close();
                }
            } catch (IOException ex) {
                // squash
            }
        }
    }

    private synchronized int runPipeline(CommandShell shell,
            List<CommandDescriptor> descs) throws ShellException {
        int nosStages = descs.size();
        try {
            // Create all the threads for the pipeline, wiring up their input
            // and output streams.
            int stageNo = 0;
            PipedOutputStream pipeOut = null;
            for (CommandDescriptor desc : descs) {
                CommandIO in = CommandLine.DEFAULT_STDIN;
                CommandIO out = CommandLine.DEFAULT_STDOUT;
                CommandIO err = CommandLine.DEFAULT_STDERR;
                desc.openedStreams = new ArrayList<CommandIO>(2);
                try {
                    // redirect from
                    if (desc.fromFileName != null) {
                        in = new CommandInput(new FileInputStream(desc.fromFileName.text));
                        desc.openedStreams.add(in);
                    }
                } catch (IOException ex) {
                    throw new ShellInvocationException("cannot open '" +
                            desc.fromFileName.text + "': " + ex.getMessage());
                }
                try {
                    // redirect to
                    if (desc.toFileName != null) {
                        out = new CommandOutput(new FileOutputStream(desc.toFileName.text));
                        desc.openedStreams.add(out);
                    }
                } catch (IOException ex) {
                    throw new ShellInvocationException("cannot open '" +
                            desc.toFileName + "': " + ex.getMessage());
                }
                if (stageNo > 0) {
                    // pipe from
                    if (pipeOut != null) {
                        // the previous stage is sending stdout to the pipe
                        if (in == CommandLine.DEFAULT_STDIN) {
                            // this stage is going to read from the pipe
                            PipedInputStream pipeIn = new PipedInputStream();
                            try {
                                pipeIn.connect(pipeOut);
                            } catch (IOException ex) {
                                throw new ShellInvocationException(
                                        "Problem connecting pipe", ex);
                            }
                            in = new CommandInput(pipeIn);
                            desc.openedStreams.add(in);
                        } else {
                            // this stage has redirected stdin from a file ...
                            // so go back and replace the previous stage's 
                            // pipeOut with devnull
                            CommandDescriptor prev = descs.get(stageNo - 1);
                            CommandIO[] prevIOs = prev.commandLine.getStreams();
                            try {
                                pipeOut.close();
                            } catch (IOException ex) {
                                // squash
                            }
                            prevIOs[Command.STD_OUT] = CommandLine.DEVNULL;
                        }
                    } else {
                        // the previous stage has explicitly redirected stdout
                        if (in == CommandLine.DEFAULT_STDIN) {
                            // this stage hasn't redirected stdin, so we need to
                            // give it a NullInputStream to suck on.
                            in = CommandLine.DEVNULL;
                        }
                    }
                }
                if (stageNo < nosStages - 1) {
                    // this stage is not the last one, and it hasn't redirected
                    // its stdout, so it will write to a pipe
                    if (out == CommandLine.DEFAULT_STDOUT) {
                        pipeOut = new PipedOutputStream();
                        out = new CommandOutput(new PrintStream(pipeOut));
                        desc.openedStreams.add(out);
                    }
                }
                desc.commandLine.setStreams(new CommandIO[] {in, out, err, CommandLine.DEFAULT_STDERR});
                try {
                    desc.thread = shell.invokeAsynchronous(desc.commandLine);
                } catch (UnsupportedOperationException ex) {
                    throw new ShellInvocationException(
                            "The current invoker does not support pipelines", ex);
                }
                stageNo++;
            }

            currentDescriptors = descs;
            threadsLeft = descs.size();

            // Start all threads.
            for (CommandDescriptor desc : descs) {
                desc.thread.start(this);
            }

            // Wait until they have finished
            while (threadsLeft > 0) {
                try {
                    wait();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            return descs.get(nosStages - 1).thread.getReturnCode();
        } finally {
            // Close any remaining streams.
            for (CommandDescriptor desc : descs) {
                if (desc.openedStreams != null) {
                    for (CommandIO stream : desc.openedStreams) {
                        try {
                            stream.close();
                        } catch (IOException ex) {
                            // squash
                        }
                    }
                }
            }
            // TODO deal with any left over threads
        }
    }

    private List<CommandDescriptor> currentDescriptors;
    private int threadsLeft;

    /**
     * Callback to deal with a thread that has exited.
     * 
     * @param thread
     */
    public synchronized void notifyThreadExited(CommandThread thread) {
        // If the thread owned any input or output streams, they need to
        // be closed. In particular, this will cause the next downstream
        // command in a pipeline to see an "end of file".
        for (CommandDescriptor desc : currentDescriptors) {
            if (thread == desc.thread) {
                if (desc.openedStreams == null) {
                    throw new ShellFailureException("bad thread exit callback");
                }
                for (CommandIO stream : desc.openedStreams) {
                    try {
                        stream.close();
                    } catch (IOException ex) {
                        // squash
                    }
                }
                desc.openedStreams = null;
                threadsLeft--;
                notify();
                break;
            }
        }
    }

    private static class CommandDescriptor {
        public final CommandLine commandLine;
        public final CommandLine.Token fromFileName;
        public final CommandLine.Token toFileName;
        public final boolean pipeTo;
        public List<CommandIO> openedStreams;
        public CommandThread thread;

        public CommandDescriptor(CommandLine commandLine, 
                CommandLine.Token fromFileName, CommandLine.Token toFileName,
                boolean pipeTo) {
            super();
            this.commandLine = commandLine;
            this.fromFileName = fromFileName;
            this.toFileName = toFileName;
            this.pipeTo = pipeTo;
        }
    }

    /**
     * A single pipeline in a '&&' / '||' / ';' sequence, together with the
     * operator that precedes it ({@code null} for the first pipeline).
     */
    private static class SequenceDescriptor {
        public final List<CommandDescriptor> pipeline;
        public final String operatorBefore;

        public SequenceDescriptor(List<CommandDescriptor> pipeline, String operatorBefore) {
            this.pipeline = pipeline;
            this.operatorBefore = operatorBefore;
        }
    }
}
