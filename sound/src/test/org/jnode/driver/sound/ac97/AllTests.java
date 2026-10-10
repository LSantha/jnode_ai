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

package org.jnode.driver.sound.ac97;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * JUnit suite for the Intel AC'97 audio driver. Referenced by
 * <code>sound/build-tests.xml</code>, so it runs with
 * <code>sh build.sh tests</code> and with
 * <code>sh build.sh -f sound/build-tests.xml all-junit</code>.
 * <p/>
 * All tests are pure arithmetic and run on the build host; none of them need
 * a JNode instance or real hardware.
 *
 * @author JNode contributors
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    Ac97ConstantsTest.class,
    BufferDescriptorListTest.class,
    AC97CoreTest.class,
    ToneGeneratorTest.class
})
public class AllTests {

    /**
     * Suite marker class; the test classes are listed in the annotation.
     */
    public AllTests() {
    }
}
