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

package org.jnode.apps.nanocode.tool;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.naming.NameNotFoundException;

import org.jnode.apps.nanocode.config.Config;
import org.jnode.apps.nanocode.fs.FileOps;
import org.jnode.apps.nanocode.render.Console;
import org.jnode.shell.CommandLine;
import org.jnode.shell.CommandShell;
import org.jnode.shell.CommandThread;
import org.jnode.shell.ShellException;
import org.jnode.shell.ShellUtils;
import org.jnode.shell.ThreadExitListener;
import org.jnode.shell.io.CommandIO;
import org.jnode.shell.io.CommandInput;
import org.jnode.shell.io.CommandOutput;
import org.jnode.shell.io.NullInputStream;

/**
 * The {@code bash} tool for JNode, where there is no process model and
 * therefore no external {@code /bin/sh}. Instead it drives the JNode shell
 * itself: the command line is tokenized the way the shell tokenizes it
 * (quotes, escapes, {@code |}, {@code &&}, {@code ||}, {@code ;}, {@code <},
 * {@code >}, {@code >>}) and every stage is invoked through the shell's own
 * invoker, so alias resolution, the syntax parser and the whole registered
 * command set work exactly as in an interactive session.
 *
 * <p>Output is captured to a buffer and returned to the agent, while a poller
 * thread streams it live to the console so long runs (for example
 * {@code javac}) stay visible. stdin is {@code /dev/null} unless the line
 * redirects it from a file, so a command that reads stdin cannot swallow the
 * serial console.
 */
public final class JNodeShellTool extends AbstractTool {

    private static final int POLL_INTERVAL_MS = 200;

    public JNodeShellTool(Config config) {
        super(config);
    }

    public String name() {
        return "bash";
    }

    public String description() {
        return "Run a command in JNode's own shell (JNode has no process "
                + "model, so there is no external bash). It runs the shell's "
                + "built-in commands: ls, cat, cp, mv, mkdir, rm, echo, head, "
                + "tail, wc, find, grep, df, du, md5sum, env, date, more, "
                + "plugin, javac, java, ... Shell syntax is supported: "
                + "pipes (cmd1 | cmd2), && and ; sequencing, and < > >> "
                + "redirects. No background jobs and no shell globbing (use "
                + "the glob tool or find -name instead). Output is truncated "
                + "if very long.";
    }

    public Map<String, String> properties() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("cmd", "string");
        return m;
    }

    public String[] requiredProperties() {
        return new String[] { "cmd" };
    }

    public boolean isMutating() {
        return true;
    }

    public String execute(Map<String, Object> arguments) throws Exception {
        String command = getString(arguments, "cmd");
        if (command == null) {
            return "error: cmd is required";
        }
        if (command.trim().length() == 0) {
            return "error: empty command";
        }

        CommandShell shell;
        try {
            shell = (CommandShell) ShellUtils.getCurrentShell();
        } catch (NameNotFoundException e) {
            return "error: not running inside a JNode shell; this tool needs "
                    + "the JNode shell (it is installed automatically on JNode)";
        } catch (ClassCastException e) {
            return "error: the current shell is not a CommandShell";
        }

        List<String> tokens = tokenize(command);
        if (tokens.isEmpty()) {
            return "error: nothing to run";
        }
        List<Step> steps = splitSequence(tokens);

        StringBuilder sink = new StringBuilder();
        String op = null;
        int rc = 0;
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            boolean run;
            if (op == null) {
                run = true;
            } else if (op.equals("&&")) {
                run = rc == 0;
            } else if (op.equals("||")) {
                run = rc != 0;
            } else { // ";"
                run = true;
            }
            if (run) {
                rc = runPipeline(shell, step.stages, sink);
            }
            op = step.op;
        }

        String text = FileOps.trimTrailing(sink.toString());
        if (text.length() == 0) {
            text = "(no output)";
        }
        if (rc != 0) {
            text = text + "\n[exit " + rc + "]";
        }
        return text;
    }

    // ------------------------------------------------------------ execution

    /** One {@code |}-separated stage with its redirects. */
    private static final class Stage {
        String[] argv;
        String from;
        String to;
        boolean append;
        int next;
    }

    /** One sequencing step: a pipeline plus the operator that ends it. */
    private static final class Step {
        List<Stage> stages;
        String op;
    }

    private static List<Step> splitSequence(List<String> tokens) {
        List<Step> steps = new ArrayList<Step>();
        List<Stage> stages = null;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("&&") || t.equals("||") || t.equals(";")) {
                Step step = new Step();
                step.stages = stages;
                step.op = t;
                steps.add(step);
                stages = null;
            } else if (t.equals("|")) {
                // pipeline separator; the next token starts a new stage
                continue;
            } else {
                if (stages == null) {
                    stages = new ArrayList<Stage>();
                }
                Stage stage = parseStage(tokens, new int[] { i });
                stages.add(stage);
                // parseStage stopped on a separator; rewind so the loop
                // processes that token itself
                i = stage.next - 1;
            }
        }
        Step last = new Step();
        last.stages = stages == null ? new ArrayList<Stage>() : stages;
        last.op = null;
        steps.add(last);
        return steps;
    }

    /**
     * Parses the tokens for one pipeline stage: argv up to the next pipe, and
     * any {@code <}, {@code >} or {@code >>} redirects. The cursor in
     * {@code pos[0]} is advanced to the terminating token.
     */
    private static Stage parseStage(List<String> tokens, int[] pos) {
        Stage stage = new Stage();
        List<String> argv = new ArrayList<String>();
        int i = pos[0];
        for (; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("|")) {
                break;
            }
            if (t.equals("&&") || t.equals("||") || t.equals(";")) {
                break;
            }
            if (t.equals("<")) {
                i++;
                if (i < tokens.size()) {
                    stage.from = tokens.get(i);
                }
                continue;
            }
            if (t.equals(">") || t.equals(">>")) {
                boolean append = t.equals(">>");
                i++;
                if (i < tokens.size()) {
                    stage.to = tokens.get(i);
                    stage.append = append;
                }
                continue;
            }
            argv.add(t);
        }
        stage.argv = argv.toArray(new String[argv.size()]);
        stage.next = i;
        return stage;
    }

    /**
     * Runs one pipeline, appending the captured output to {@code sink} and
     * streaming it live to the console. Returns the exit code of the last
     * stage.
     */
    private int runPipeline(CommandShell shell, List<Stage> stages, StringBuilder sink)
            throws Exception {
        if (stages.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < stages.size(); i++) {
            String[] argv = stages.get(i).argv;
            if (argv.length == 0) {
                sink.append("error: missing command name\n");
                return 1;
            }
            if (argv[0].equals("nanocode")) {
                sink.append("error: refusing to run nanocode recursively\n");
                return 1;
            }
        }

        final ByteArrayOutputStream capture = new ByteArrayOutputStream();
        List<CommandIO> opened = new ArrayList<CommandIO>();
        List<CommandThread> threads = new ArrayList<CommandThread>();
        List<StageExitListener> listeners = new ArrayList<StageExitListener>();
        PipedHolder pipe = new PipedHolder();
        int rc = 0;
        final boolean[] streaming = new boolean[] { true };

        Thread streamer = new Thread(new Runnable() {
            public void run() {
                int printed = 0;
                String remainder = "";
                while (streaming[0]) {
                    if (capture.size() > printed) {
                        byte[] chunk = new byte[capture.size() - printed];
                        System.arraycopy(capture.toByteArray(), printed, chunk, 0,
                                chunk.length);
                        remainder += new String(chunk);
                        printed += chunk.length;
                        int nl;
                        while ((nl = remainder.indexOf('\n')) >= 0) {
                            System.out.println("  " + Console.DIM + "| "
                                    + remainder.substring(0, nl));
                            System.out.flush();
                            remainder = remainder.substring(nl + 1);
                        }
                    }
                    try {
                        Thread.sleep(POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (remainder.length() > 0) {
                    System.out.println("  " + Console.DIM + "| " + remainder);
                    System.out.flush();
                }
            }
        });
        streamer.setDaemon(true);
        streamer.start();

        try {
            int n = stages.size();
            for (int i = 0; i < n; i++) {
                Stage st = stages.get(i);
                boolean last = i == n - 1;

                // streams owned by this stage; they are closed when the
                // stage's thread exits so a downstream pipe stage sees EOF
                List<CommandIO> stageStreams = new ArrayList<CommandIO>();

                CommandIO in;
                if (st.from != null) {
                    CommandInput ci = new CommandInput(
                            new FileInputStream(resolvePath(st.from)));
                    in = ci;
                    opened.add(ci);
                    stageStreams.add(ci);
                } else if (pipe.out != null) {
                    PipedInputStream pin = new PipedInputStream();
                    pin.connect(pipe.out);
                    CommandInput ci = new CommandInput(pin);
                    in = ci;
                    opened.add(ci);
                    stageStreams.add(ci);
                    // the writer end stays open: the previous stage's own
                    // output stream is closed when its thread exits, which
                    // delivers EOF to this reader
                } else {
                    // A plain DEVNULL marker resolves to CommandInputOutput,
                    // which breaks commands that call getInput(); a real
                    // CommandInput over NullInputStream reads EOF at once.
                    CommandInput ci = new CommandInput(new NullInputStream());
                    in = ci;
                    opened.add(ci);
                    stageStreams.add(ci);
                }

                CommandIO out;
                if (last && st.to == null) {
                    CommandOutput co = new CommandOutput(capture);
                    out = co;
                    opened.add(co);
                    stageStreams.add(co);
                } else if (st.to != null) {
                    CommandOutput co = new CommandOutput(new FileOutputStream(
                            resolvePath(st.to), st.append));
                    out = co;
                    opened.add(co);
                    stageStreams.add(co);
                } else {
                    PipedOutputStream po = new PipedOutputStream();
                    CommandOutput co = new CommandOutput(new PrintStream(po));
                    out = co;
                    opened.add(co);
                    stageStreams.add(co);
                    pipe.out = po;
                }

                // stderr of every stage lands in the capture so the agent
                // sees error messages too
                CommandOutput err = new CommandOutput(capture);
                opened.add(err);
                stageStreams.add(err);

                CommandLine cl = new CommandLine(st.argv[0],
                        subarray(st.argv, 1, st.argv.length - 1));
                cl.setStreams(new CommandIO[] { in, out, err, err });

                // Always run through the async path. The synchronous
                // shell.invoke() stashes the command thread in the shell
                // invoker's threadProcess/blockingThread fields, which is
                // how Ctrl-C finds the command to kill; our own tool calls
                // would overwrite that bookkeeping with finished threads
                // and the user could no longer interrupt nanocode.
                CommandThread t = shell.invokeAsynchronous(cl);
                threads.add(t);
                listeners.add(new StageExitListener(stageStreams));
            }
            // Start every stage only after all pipes are wired, so a fast
            // producer cannot finish (and close the pipe) before the
            // consumer has connected.
            for (int i = 0; i < threads.size(); i++) {
                threads.get(i).start(listeners.get(i));
            }
            for (int i = 0; i < threads.size(); i++) {
                CommandThread t = threads.get(i);
                t.waitFor();
                if (i == threads.size() - 1) {
                    rc = t.getReturnCode();
                }
            }
        } catch (ShellException e) {
            sink.append("error: ").append(e.getMessage()).append('\n');
            return 1;
        } finally {
            streaming[0] = false;
            pipe.closeOut();
            for (int i = 0; i < opened.size(); i++) {
                try {
                    opened.get(i).close();
                } catch (Exception e) {
                    // squash, matching the shell's own pipeline teardown
                }
            }
            try {
                streamer.join(1000);
            } catch (InterruptedException e) {
                // tearing down anyway
            }
        }

        sink.append(new String(capture.toByteArray(), "ISO-8859-1"));
        if (sink.length() == 0) {
            Stage last = stages.get(stages.size() - 1);
            if (last.to != null) {
                sink.append("(output written to ").append(last.to).append(')');
            }
        }
        return rc;
    }

    private static final class PipedHolder {
        PipedOutputStream out;
        boolean closed;

        void closeOut() {
            if (out != null && !closed) {
                try {
                    out.close();
                } catch (Exception e) {
                    // squash
                }
                closed = true;
                out = null;
            }
        }
    }

    /**
     * Closes a pipeline stage's streams when its command thread exits. That
     * is what delivers end-of-file to the next stage's stdin; without it the
     * downstream command blocks forever.
     */
    private static final class StageExitListener implements ThreadExitListener {
        private final List<CommandIO> streams;

        StageExitListener(List<CommandIO> streams) {
            this.streams = streams;
        }

        public void notifyThreadExited(CommandThread thread) {
            for (int i = 0; i < streams.size(); i++) {
                try {
                    streams.get(i).close();
                } catch (Exception e) {
                    // squash, matching the shell's own pipeline teardown
                }
            }
        }
    }

    /** Java 6 has no Arrays.copyOfRange with primitive boxes; use the API array form. */
    private static String[] subarray(String[] src, int from, int to) {
        int len = to - from + 1;
        String[] dst = new String[len];
        for (int i = 0; i < len; i++) {
            dst[i] = src[from + i];
        }
        return dst;
    }

    // ------------------------------------------------------------ tokenizer

    /**
     * Splits a command line the way the shell tokenizer does: single quotes
     * are fully literal, double quotes protect whitespace and operators while
     * honouring {@code \"} and {@code \\}, and the operators {@code | || && ;}
     * {@code < > >>} become standalone tokens.
     */
    private static List<String> tokenize(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean inWord = false;
        final int NORMAL = 0;
        final int SINGLE = 1;
        final int DOUBLE = 2;
        int mode = NORMAL;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (mode == SINGLE) {
                if (c == '\'') {
                    mode = NORMAL;
                } else {
                    cur.append(c);
                }
                continue;
            }
            if (c == '\\') {
                if (i + 1 < line.length()) {
                    cur.append(line.charAt(++i));
                    inWord = true;
                }
                continue;
            }
            if (c == '\'') {
                mode = SINGLE;
                inWord = true;
                continue;
            }
            if (c == '"') {
                mode = DOUBLE;
                inWord = true;
                continue;
            }
            if (mode == NORMAL && (c == ' ' || c == '\t')) {
                if (inWord) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inWord = false;
                }
                continue;
            }
            if (mode == NORMAL && c == '#') {
                break;
            }
            if (mode == NORMAL) {
                String op = matchOp(line, i);
                if (op != null) {
                    if (inWord) {
                        out.add(cur.toString());
                        cur.setLength(0);
                        inWord = false;
                    }
                    out.add(op);
                    i += op.length() - 1;
                    continue;
                }
            }
            cur.append(c);
            inWord = true;
        }
        if (inWord) {
            out.add(cur.toString());
        }
        return out;
    }

    private static String matchOp(String s, int i) {
        if (s.startsWith("&&", i)) {
            return "&&";
        }
        if (s.startsWith("||", i)) {
            return "||";
        }
        if (s.startsWith(">>", i)) {
            return ">>";
        }
        char c = s.charAt(i);
        if (c == '|') {
            return "|";
        }
        if (c == ';') {
            return ";";
        }
        if (c == '<') {
            return "<";
        }
        if (c == '>') {
            return ">";
        }
        return null;
    }
}
