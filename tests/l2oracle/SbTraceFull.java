import gnu.testlet.TestHarness;
import gnu.testlet.Testlet;

/**
 * Faithful replica of StringBufferTest.test_append (all 13 sequences,
 * checks, fail()s and the 4 try/catch handlers with exact catch types),
 * plus streaming println tags. Last tag before a panic pinpoints the
 * crashing sequence in full-method context.
 */
public class SbTraceFull implements Testlet {
    public void test(TestHarness h) {
        StringBuffer b1 = new StringBuffer();
        Object n = null;
        h.check(b1.append(n).toString().equals("null"), "f-1");
        System.out.println("F1");
        h.check(b1.append(new Integer(100)).toString().equals("null100"), "f-2");
        System.out.println("F2");
        StringBuffer b3 = new StringBuffer("hi");
        b3 = b3.append(" there");
        b3 = b3.append(" buddy");
        h.check(b3.toString().equals("hi there buddy"), "f-2b");
        System.out.println("F3");
        StringBuffer b4 = new StringBuffer();
        b4 = b4.append("sdljfksdjfklsdjflksdjflkjsdlkfjlsdkjflksdjfklsd");
        h.check(b4.toString().equals("sdljfksdjfklsdjflksdjflkjsdlkfjlsdkjflksdjfklsd"), "f-3");
        System.out.println("F4");
        Object nc = null;
        StringBuffer b6 = new StringBuffer();
        try {
            b6 = b6.append((char[]) nc);
            h.fail("f-4");
        } catch (NullPointerException e) {
        }
        System.out.println("F5");
        char[] hither = new char[]{'h', 'i', 't', 'h', 'e', 'r'};
        StringBuffer b8 = new StringBuffer("!");
        h.check(b8.append(hither).toString().equals("!hither"), "f-5");
        System.out.println("F6");
        try {
            b6 = b6.append((char[]) nc, 0, 3);
            h.fail("f-6a");
        } catch (NullPointerException e) {
        }
        System.out.println("F7a");
        try {
            b6 = b6.append(hither, -1, 1);
            h.fail("f-6b");
        } catch (IndexOutOfBoundsException e) {
        }
        System.out.println("F7b");
        try {
            b6 = b6.append(hither, 0, 99);
            h.fail("f-6c");
        } catch (IndexOutOfBoundsException e) {
        }
        System.out.println("F7c");
        StringBuffer b8b = new StringBuffer("!");
        b8b = b8b.append(new char[]{'t', 'h', 'e'}, 0, 3);
        h.check(b8b.toString().equals("!the"), "f-7");
        System.out.println("F8");
        StringBuffer b9 = new StringBuffer();
        b9 = b9.append(true);
        h.check(b9.toString().equals("true"), "f-8");
        System.out.println("F9a");
        b9 = b9.append(false);
        h.check(b9.toString().equals("truefalse"), "f-9");
        System.out.println("F9b");
        b9 = b9.append(20);
        h.check(b9.toString().equals("truefalse20"), "f-10");
        System.out.println("F10");
        StringBuffer b11 = new StringBuffer();
        b11 = b11.append(2034L);
        h.check(b11.toString().equals("2034"), "f-11");
        System.out.println("F11");
        StringBuffer b12 = new StringBuffer();
        b12 = b12.append(34.45f);
        h.check(b12.toString().equals("34.45"), "f-12");
        System.out.println("F12");
        StringBuffer b13 = new StringBuffer();
        b13 = b13.append(34.46);
        h.check(b13.toString().equals("34.46"), "f-13");
        System.out.println("F13");
    }
}
