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
import org.jnode.shell.syntax.AlternativesSyntax;
import org.jnode.shell.syntax.ArgumentSyntax;
import org.jnode.shell.syntax.EmptySyntax;
import org.jnode.shell.syntax.OptionalSyntax;
import org.jnode.shell.syntax.OptionSetSyntax;
import org.jnode.shell.syntax.OptionSyntax;
import org.jnode.shell.syntax.PowersetSyntax;
import org.jnode.shell.syntax.RepeatSyntax;
import org.jnode.shell.syntax.SequenceSyntax;
import org.jnode.shell.syntax.SymbolSyntax;
import org.jnode.shell.syntax.Syntax;
import org.jnode.shell.syntax.SyntaxBundle;
import org.jnode.shell.syntax.SyntaxFailureException;
import org.jnode.shell.syntax.SyntaxSpecAdapter;
import org.jnode.shell.syntax.SyntaxSpecLoader;
import org.jnode.shell.syntax.VerbSyntax;
import org.jnode.shell.syntax.XMLSyntaxSpecAdapter;
import org.junit.Assert;
import org.junit.Test;

public class SyntaxSpecLoaderTest {

    private final SyntaxSpecLoader loader = new SyntaxSpecLoader();

    private SyntaxBundle load(String xml) {
        XMLElement element = new XMLElement();
        element.parseString(xml);
        return loader.loadSyntax(new XMLSyntaxSpecAdapter(element));
    }

    private void loadFailure(String xml, String expectedMessage) {
        try {
            load(xml);
            Assert.fail("expected SyntaxFailureException: " + expectedMessage);
        } catch (SyntaxFailureException ex) {
            Assert.assertEquals(expectedMessage, ex.getMessage());
        }
    }

    @Test
    public void testEmptySpecGivesEmptyBundle() {
        SyntaxBundle bundle = load("<syntax alias='foo'/>");
        Assert.assertEquals("foo", bundle.getAlias());
        Assert.assertNull(bundle.getDescription());
        Assert.assertEquals(0, bundle.getSyntaxes().length);
    }

    @Test
    public void testDescriptionAndMultipleTopLevelSyntaxes() {
        SyntaxBundle bundle =
            load("<syntax alias='foo' description='the foo command'>"
                + "<symbol symbol='foo'/>" + "<argument argLabel='rest'/>" + "</syntax>");
        Assert.assertEquals("foo", bundle.getAlias());
        Assert.assertEquals("the foo command", bundle.getDescription());
        Syntax[] syntaxes = bundle.getSyntaxes();
        Assert.assertEquals(2, syntaxes.length);
        Assert.assertEquals(SymbolSyntax.class, syntaxes[0].getClass());
        Assert.assertEquals(ArgumentSyntax.class, syntaxes[1].getClass());
    }

    @Test
    public void testMissingAliasIsAFailure() {
        loadFailure("<syntax><argument argLabel='rest'/></syntax>",
                    "syntax element has no 'alias' attribute");
    }

    @Test
    public void testEmptyAliasIsTreatedAsMissingAlias() {
        loadFailure("<syntax alias=''><argument argLabel='rest'/></syntax>",
                    "syntax element has no 'alias' attribute");
    }

    @Test
    public void testUnknownElementIsAFailure() {
        loadFailure("<syntax alias='foo'><bogus/></syntax>",
                    "<bogus> element does not represent a known syntax");
    }

    @Test
    public void testUnknownAttributesAreIgnored() {
        SyntaxBundle bundle =
            load("<syntax alias='foo' bogus='whatever' other='x'>"
                + "<argument argLabel='rest' nonsense='y'/></syntax>");
        Assert.assertEquals("foo", bundle.getAlias());
        Syntax[] syntaxes = bundle.getSyntaxes();
        Assert.assertEquals(1, syntaxes.length);
        Assert.assertEquals(ArgumentSyntax.class, syntaxes[0].getClass());
        Assert.assertEquals("rest", ((ArgumentSyntax) syntaxes[0]).getArgName());
    }

    @Test
    public void testEmptyElement() {
        SyntaxBundle bundle =
            load("<syntax alias='foo'><empty label='lbl' description='desc'/></syntax>");
        Syntax syntax = bundle.getSyntaxes()[0];
        Assert.assertEquals(EmptySyntax.class, syntax.getClass());
        Assert.assertEquals("lbl", syntax.getLabel());
        Assert.assertEquals("desc", syntax.getDescription());
    }

    @Test
    public void testAlternativesElement() {
        SyntaxBundle bundle = load("<syntax alias='foo'>"
                                   + "<alternatives>"
                                   + "<symbol symbol='a'/><symbol symbol='b'/>"
                                   + "</alternatives></syntax>");
        Syntax syntax = bundle.getSyntaxes()[0];
        Assert.assertEquals(AlternativesSyntax.class, syntax.getClass());
        Syntax[] children = ((AlternativesSyntax) syntax).getChildren();
        Assert.assertEquals(2, children.length);
        Assert.assertEquals("a", children[0].format(null));
        Assert.assertEquals("b", children[1].format(null));
    }

    @Test
    public void testSequenceElement() {
        SyntaxBundle bundle = load("<syntax alias='foo'>"
                                   + "<sequence label='seq'>"
                                   + "<symbol symbol='a'/><symbol symbol='b'/>"
                                   + "</sequence></syntax>");
        Syntax syntax = bundle.getSyntaxes()[0];
        Assert.assertEquals(SequenceSyntax.class, syntax.getClass());
        Assert.assertEquals("seq", syntax.getLabel());
        Assert.assertEquals(2, ((SequenceSyntax) syntax).getChildren().length);
    }

    @Test
    public void testOptionalElement() {
        SyntaxBundle bundle = load("<syntax alias='foo'>"
                                   + "<optional><symbol symbol='a'/></optional></syntax>");
        Syntax syntax = bundle.getSyntaxes()[0];
        Assert.assertEquals(OptionalSyntax.class, syntax.getClass());
        Assert.assertEquals(1, ((OptionalSyntax) syntax).getChildren().length);
        Assert.assertNull(syntax.toXML().getStringAttribute("eager"));
    }

    @Test
    public void testPowersetElementIsLazyByDefault() {
        SyntaxBundle bundle = load("<syntax alias='foo'>"
                                   + "<powerset><symbol symbol='a'/></powerset></syntax>");
        Syntax syntax = bundle.getSyntaxes()[0];
        Assert.assertEquals(PowersetSyntax.class, syntax.getClass());
        Assert.assertNull(syntax.toXML().getStringAttribute("eager"));
    }

    @Test
    public void testEagerTrueFlagIsHonoured() {
        SyntaxBundle bundle = load("<syntax alias='foo'>"
                                   + "<powerset eager='true'><symbol symbol='a'/>"
                                   + "</powerset></syntax>");
        Assert.assertEquals("true", bundle.getSyntaxes()[0].toXML().getStringAttribute("eager"));
    }

    @Test
    public void testEagerFalseFlagCurrentlyBehavesLikeTrue() {
        SyntaxBundle bundle = load("<syntax alias='foo'>"
                                   + "<powerset eager='false'><symbol symbol='a'/>"
                                   + "</powerset></syntax>");
        Assert.assertEquals("true", bundle.getSyntaxes()[0].toXML().getStringAttribute("eager"));
    }

    @Test
    public void testNonBooleanFlagIsAFailure() {
        loadFailure("<syntax alias='foo'>"
                    + "<powerset eager='maybe'><symbol symbol='a'/></powerset></syntax>",
                    "'eager' attribute is not 'true' or 'false'");
    }

    @Test
    public void testRepeatElementWithCounts() {
        SyntaxBundle bundle = load("<syntax alias='foo'>"
                                   + "<repeat minCount='1' maxCount='3'>"
                                   + "<symbol symbol='a'/></repeat></syntax>");
        Syntax syntax = bundle.getSyntaxes()[0];
        Assert.assertEquals(RepeatSyntax.class, syntax.getClass());
        Assert.assertNull(syntax.toXML().getStringAttribute("eager"));
        Assert.assertEquals("1", syntax.toXML().getStringAttribute("minCount"));
        Assert.assertEquals("3", syntax.toXML().getStringAttribute("maxCount"));
    }

    @Test
    public void testRepeatElementWithMultipleMembersMakesASequence() {
        SyntaxBundle bundle = load("<syntax alias='foo'>"
                                   + "<repeat><symbol symbol='a'/><symbol symbol='b'/>"
                                   + "</repeat></syntax>");
        Syntax syntax = ((RepeatSyntax) bundle.getSyntaxes()[0]).getChildren()[0];
        Assert.assertEquals(SequenceSyntax.class, syntax.getClass());
        Assert.assertEquals(2, ((SequenceSyntax) syntax).getChildren().length);
    }

    @Test
    public void testNonIntegerRepeatCountIsAFailure() {
        loadFailure("<syntax alias='foo'>"
                    + "<repeat maxCount='lots'><symbol symbol='a'/></repeat></syntax>",
                    "'maxCount' attribute is not an integer");
    }

    @Test
    public void testOptionElementWithShortNameOnly() {
        SyntaxBundle bundle =
            load("<syntax alias='foo'><option argLabel='verbose' shortName='v'/></syntax>");
        Syntax syntax = bundle.getSyntaxes()[0];
        Assert.assertEquals(OptionSyntax.class, syntax.getClass());
        Assert.assertEquals("verbose", ((OptionSyntax) syntax).getArgName());
        Assert.assertEquals("-v", ((OptionSyntax) syntax).getShortOptName());
        Assert.assertNull(((OptionSyntax) syntax).getLongOptName());
    }

    @Test
    public void testOptionElementWithLongNameOnly() {
        SyntaxBundle bundle =
            load("<syntax alias='foo'><option argLabel='verbose' longName='verbose'/></syntax>");
        OptionSyntax syntax = (OptionSyntax) bundle.getSyntaxes()[0];
        Assert.assertEquals("verbose", syntax.getArgName());
        Assert.assertEquals("--verbose", syntax.getLongOptName());
        Assert.assertNull(syntax.getShortOptName());
    }

    @Test
    public void testOptionElementWithBothNames() {
        SyntaxBundle bundle =
            load("<syntax alias='foo'>"
                + "<option argLabel='verbose' shortName='v' longName='verbose'/></syntax>");
        OptionSyntax syntax = (OptionSyntax) bundle.getSyntaxes()[0];
        Assert.assertEquals("-v", syntax.getShortOptName());
        Assert.assertEquals("--verbose", syntax.getLongOptName());
    }

    @Test
    public void testOptionElementWithoutArgLabelIsAFailure() {
        loadFailure("<syntax alias='foo'><option shortName='v'/></syntax>",
                    "<option> element has no 'argLabel' attribute");
    }

    @Test
    public void testOptionElementWithoutAnyNameIsAFailure() {
        loadFailure("<syntax alias='foo'><option argLabel='verbose'/></syntax>",
                    "<option> element has must have a 'shortName' or 'longName' attribute");
    }

    @Test
    public void testOptionElementWithMultiCharacterShortNameIsAFailure() {
        loadFailure("<syntax alias='foo'>"
                    + "<option argLabel='verbose' shortName='verb'/></syntax>",
                    "<option> elements 'shortName' attribute must be one character long");
    }

    @Test
    public void testOptionSetElement() {
        SyntaxBundle bundle = load("<syntax alias='foo'><optionSet label='opts'>"
                                   + "<option argLabel='a' shortName='a'/>"
                                   + "<option argLabel='b' shortName='b'/>"
                                   + "</optionSet></syntax>");
        Syntax syntax = bundle.getSyntaxes()[0];
        Assert.assertEquals(OptionSetSyntax.class, syntax.getClass());
        Assert.assertEquals("opts", syntax.getLabel());
        Assert.assertEquals(2, ((OptionSetSyntax) syntax).getChildren().length);
    }

    @Test
    public void testOptionSetElementWithNonOptionChildIsAFailure() {
        loadFailure("<syntax alias='foo'><optionSet>"
                    + "<symbol symbol='a'/></optionSet></syntax>",
                    "<optionSyntax> element can only contain <option> elements");
    }

    @Test
    public void testArgumentElementWithoutArgLabelIsAFailure() {
        loadFailure("<syntax alias='foo'><argument label='rest'/></syntax>",
                    "<argument> element has no 'argLabel' attribute");
    }

    @Test
    public void testVerbElement() {
        SyntaxBundle bundle = load("<syntax alias='foo'>"
                                   + "<verb label='cmd' symbol='go' argLabel='target'/>"
                                   + "</syntax>");
        Syntax syntax = bundle.getSyntaxes()[0];
        Assert.assertEquals(VerbSyntax.class, syntax.getClass());
        Assert.assertEquals("cmd", syntax.getLabel());
        Assert.assertEquals("target", ((VerbSyntax) syntax).getArgName());
        Assert.assertEquals("go", syntax.format(null));
    }

    @Test
    public void testVerbElementWithoutSymbolIsAFailure() {
        loadFailure("<syntax alias='foo'><verb argLabel='target'/></syntax>",
                    "<verb> element has no 'symbol' attribute");
    }

    @Test
    public void testVerbElementWithoutArgLabelIsAFailure() {
        loadFailure("<syntax alias='foo'><verb symbol='go'/></syntax>",
                    "<argument> element has no 'argLabel' attribute");
    }

    @Test
    public void testSymbolElement() {
        SyntaxBundle bundle =
            load("<syntax alias='foo'><symbol label='s' symbol='hello'/></syntax>");
        Syntax syntax = bundle.getSyntaxes()[0];
        Assert.assertEquals(SymbolSyntax.class, syntax.getClass());
        Assert.assertEquals("s", syntax.getLabel());
        Assert.assertEquals("hello", syntax.format(null));
    }

    @Test
    public void testSymbolElementWithoutSymbolIsAFailure() {
        loadFailure("<syntax alias='foo'><symbol label='s'/></syntax>",
                    "<symbol> element has no 'symbol' attribute");
    }

    @Test
    public void testFailureInsideNestedElementPropagates() {
        loadFailure("<syntax alias='foo'><sequence><symbol/></sequence></syntax>",
                    "<symbol> element has no 'symbol' attribute");
    }

    @Test
    public void testLoaderWorksWithAnyAdapterImplementation() {
        SyntaxSpecAdapter adapter =
            new StubSyntaxSpecAdapter("syntax", new String[] { "alias" },
                                      new String[] { "foo" }, new SyntaxSpecAdapter[0]);
        SyntaxBundle bundle = loader.loadSyntax(adapter);
        Assert.assertEquals("foo", bundle.getAlias());
        Assert.assertEquals(0, bundle.getSyntaxes().length);
    }

    private static class StubSyntaxSpecAdapter implements SyntaxSpecAdapter {
        private final String name;
        private final String[] attributeNames;
        private final String[] attributeValues;
        private final SyntaxSpecAdapter[] children;

        StubSyntaxSpecAdapter(String name, String[] attributeNames, String[] attributeValues,
                              SyntaxSpecAdapter[] children) {
            this.name = name;
            this.attributeNames = attributeNames;
            this.attributeValues = attributeValues;
            this.children = children;
        }

        public String getName() {
            return name;
        }

        public SyntaxSpecAdapter getChild(int childNo) {
            return children[childNo];
        }

        public int getNosChildren() {
            return children.length;
        }

        public String getAttribute(String name) {
            for (int i = 0; i < attributeNames.length; i++) {
                if (attributeNames[i].equals(name)) {
                    return attributeValues[i];
                }
            }
            return null;
        }
    }
}
