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
 
package sun.misc;

/**
 * @author Levente S\u00e1ntha
 */
class NativeVM {
    /**
     * @see VM#getThreadStateValues(int[][], String[][])
     */
    private static void getThreadStateValues(int[][] vmThreadStateValues,
                                                    String[][] vmThreadStateNames){
        // HotSpot-compatible threadStatus values. JNode's VmThread mirrors its
        // scheduler state into java.lang.Thread.threadStatus using the same
        // values (see VmThread#toJavaThreadStatus).
        setState(vmThreadStateValues, vmThreadStateNames,
            Thread.State.NEW.ordinal(), 0, "NEW");
        setState(vmThreadStateValues, vmThreadStateNames,
            Thread.State.RUNNABLE.ordinal(), 1, "RUNNABLE");
        setState(vmThreadStateValues, vmThreadStateNames,
            Thread.State.BLOCKED.ordinal(), 2, "BLOCKED");
        setState(vmThreadStateValues, vmThreadStateNames,
            Thread.State.WAITING.ordinal(), 4, "WAITING");
        setState(vmThreadStateValues, vmThreadStateNames,
            Thread.State.TIMED_WAITING.ordinal(), 8, "TIMED_WAITING");
        setState(vmThreadStateValues, vmThreadStateNames,
            Thread.State.TERMINATED.ordinal(), 16, "TERMINATED");
    }

    private static void setState(int[][] values, String[][] names,
                                 int index, int value, String name) {
        values[index] = new int[]{value};
        names[index] = new String[]{name};
    }

    /**
     * @see sun.misc.VM#initialize() 
     */
    private static void initialize() {
        //throw new UnsupportedOperationException();
        //todo what to do here?
        VM.booted();
    }
}
