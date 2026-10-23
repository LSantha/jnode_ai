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
 
package org.jnode.test.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.jnode.nanoxml.XMLElement;
import org.jnode.shell.CommandLine;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.ArgumentBundle;
import org.jnode.shell.syntax.CommandSyntaxException;
import org.jnode.shell.syntax.FlagArgument;
import org.jnode.shell.syntax.SyntaxBundle;
import org.jnode.shell.syntax.SyntaxSpecLoader;
import org.jnode.shell.syntax.XMLSyntaxSpecAdapter;
import org.junit.Before;
import org.junit.Test;

public class RouteSyntaxTest {

    private static final String DESCRIPTOR = "org.jnode.command.net.xml";

    private static final String[] SEARCH_PATHS = {
        "cli/descriptors", "descriptors", "all/build/descriptors"
    };

    private static class HostArgument extends Argument<String> {
        HostArgument(String label) {
            super(label, OPTIONAL, new String[0], null);
        }

        @Override
        protected String doAccept(CommandLine.Token value, int flags)
            throws CommandSyntaxException {
            final String[] parts = value.text.split("\\.");
            if (parts.length != 4) {
                throw new CommandSyntaxException("not an IPv4 address");
            }
            for (int i = 0; i < parts.length; i++) {
                try {
                    final int octet = Integer.parseInt(parts[i]);
                    if (octet < 0 || octet > 255) {
                        throw new CommandSyntaxException("not an IPv4 address");
                    }
                } catch (NumberFormatException ex) {
                    throw new CommandSyntaxException("not an IPv4 address");
                }
            }
            return value.text;
        }

        @Override
        protected String argumentKind() {
            return "IPv4 address";
        }
    }

    private static class DeviceStubArgument extends Argument<String> {
        DeviceStubArgument(String label) {
            super(label, OPTIONAL, new String[0], null);
        }

        @Override
        protected String doAccept(CommandLine.Token value, int flags)
            throws CommandSyntaxException {
            if (value.text.equals("eth-pci(0,3,0)") || value.text.equals("eth-pci(0,4,0)")) {
                return value.text;
            }
            throw new CommandSyntaxException("unknown device");
        }

        @Override
        protected String argumentKind() {
            return "device";
        }
    }

    private SyntaxBundle syntaxes;
    private ArgumentBundle bundle;
    private FlagArgument argAdd;
    private FlagArgument argDel;
    private HostArgument argTarget;
    private DeviceStubArgument argDevice;
    private HostArgument argGateway;

    private static File findDescriptor() {
        final String configured = System.getProperty("jnode.descriptors.dir");
        if (configured != null) {
            final File candidate = new File(configured, DESCRIPTOR);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        File dir = new File(System.getProperty("user.dir"));
        while (dir != null) {
            for (int i = 0; i < SEARCH_PATHS.length; i++) {
                final File candidate = new File(dir, SEARCH_PATHS[i] + "/" + DESCRIPTOR);
                if (candidate.isFile()) {
                    return candidate;
                }
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    private static XMLElement findSyntax(XMLElement plugin, String alias) {
        final List<XMLElement> extensions = childrenNamed(plugin, "extension");
        for (int i = 0; i < extensions.size(); i++) {
            if (!"org.jnode.shell.syntaxes".equals(extensions.get(i).getStringAttribute("point"))) {
                continue;
            }
            final List<XMLElement> syntaxes = childrenNamed(extensions.get(i), "syntax");
            for (int j = 0; j < syntaxes.size(); j++) {
                if (alias.equals(syntaxes.get(j).getStringAttribute("alias"))) {
                    return syntaxes.get(j);
                }
            }
        }
        return null;
    }

    private static List<XMLElement> childrenNamed(XMLElement parent, String name) {
        final List<XMLElement> result = new ArrayList<XMLElement>();
        final List<XMLElement> children = parent.getChildren();
        for (int i = 0; i < children.size(); i++) {
            if (name.equals(children.get(i).getName())) {
                result.add(children.get(i));
            }
        }
        return result;
    }

    @Before
    public void setUp() throws Exception {
        final File descriptor = findDescriptor();
        assertTrue("cannot find " + DESCRIPTOR, descriptor != null);
        final XMLElement plugin = new XMLElement();
        final InputStream in = new FileInputStream(descriptor);
        try {
            plugin.parseFromReader(new InputStreamReader(in, "UTF-8"));
        } finally {
            in.close();
        }
        final XMLElement routeSyntax = findSyntax(plugin, "route");
        assertTrue("the descriptor has no 'route' syntax", routeSyntax != null);
        syntaxes = new SyntaxSpecLoader().loadSyntax(new XMLSyntaxSpecAdapter(routeSyntax));

        argAdd = new FlagArgument("add", Argument.OPTIONAL);
        argDel = new FlagArgument("del", Argument.OPTIONAL);
        argTarget = new HostArgument("target");
        argDevice = new DeviceStubArgument("device");
        argGateway = new HostArgument("gateway");
        bundle = new ArgumentBundle("route", argAdd, argDel, argTarget, argDevice, argGateway);
    }

    private void parse(String... words) throws CommandSyntaxException {
        bundle.setStatus(ArgumentBundle.UNPARSED);
        bundle.clear();
        bundle.parse(new CommandLine("route", words), syntaxes);
    }

    private void assertRejected(String... words) {
        try {
            parse(words);
            fail("expected CommandSyntaxException");
        } catch (CommandSyntaxException ex) {
            assertTrue(ex.getMessage() != null);
        }
    }

    @Test
    public void testNoArgumentsPrintsTheTable() throws Exception {
        parse();
        assertFalse(argAdd.isSet());
        assertFalse(argDel.isSet());
        assertFalse(argTarget.isSet());
        assertFalse(argDevice.isSet());
        assertFalse(argGateway.isSet());
    }

    @Test
    public void testDeviceOnly() throws Exception {
        parse("-a", "10.0.9.0", "eth-pci(0,4,0)");
        assertTrue(argAdd.isSet());
        assertEquals("10.0.9.0", argTarget.getValue());
        assertEquals("eth-pci(0,4,0)", argDevice.getValue());
        assertNull(argGateway.getValue());
    }

    @Test
    public void testGatewayAfterDevice() throws Exception {
        parse("-a", "10.0.9.0", "eth-pci(0,4,0)", "10.0.3.1");
        assertTrue(argAdd.isSet());
        assertEquals("10.0.9.0", argTarget.getValue());
        assertEquals("eth-pci(0,4,0)", argDevice.getValue());
        assertEquals("10.0.3.1", argGateway.getValue());
    }

    @Test
    public void testGatewayBeforeDevice() throws Exception {
        parse("-a", "10.0.9.0", "10.0.3.1", "eth-pci(0,4,0)");
        assertTrue(argAdd.isSet());
        assertEquals("10.0.9.0", argTarget.getValue());
        assertEquals("10.0.3.1", argGateway.getValue());
        assertEquals("eth-pci(0,4,0)", argDevice.getValue());
    }

    @Test
    public void testDeleteWithGatewayBeforeDevice() throws Exception {
        parse("--del", "10.0.9.0", "10.0.3.1", "eth-pci(0,4,0)");
        assertTrue(argDel.isSet());
        assertFalse(argAdd.isSet());
        assertEquals("10.0.3.1", argGateway.getValue());
        assertEquals("eth-pci(0,4,0)", argDevice.getValue());
    }

    @Test
    public void testDeviceIsMandatory() throws Exception {
        assertRejected("-a", "10.0.9.0", "10.0.3.1");
        assertRejected("-a", "10.0.9.0");
    }

    @Test
    public void testTwoDevicesAreRejected() throws Exception {
        assertRejected("-a", "10.0.9.0", "eth-pci(0,4,0)", "eth-pci(0,3,0)");
    }

    @Test
    public void testTwoGatewaysAreRejected() throws Exception {
        assertRejected("-a", "10.0.9.0", "10.0.3.1", "10.0.3.2", "eth-pci(0,4,0)");
    }

    @Test
    public void testThreeAddressesAreRejected() throws Exception {
        assertRejected("-a", "10.0.9.0", "10.0.3.1", "eth-pci(0,4,0)", "10.0.3.2");
    }
}