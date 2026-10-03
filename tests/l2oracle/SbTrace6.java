import gnu.testlet.TestHarness;
import gnu.testlet.Testlet;

/**
 * First half of SbTraceFull (sequences 1 through 7a) with tags.
 * Distinguishes "dies in F1-F7a" from "needs F7b+".
 */
public class SbTrace6 implements Testlet {
    public void test(TestHarness h) {
        StringBuffer b1 = new StringBuffer();
        Object n = null;
        h.check(b1.append(n).toString().equals("null"), "s-1");
        System.out.println("S1");
        h.check(b1.append(new Integer(100)).toString().equals("null100"), "s-2");
        System.out.println("S2");
        StringBuffer b3 = new StringBuffer("hi");
        b3 = b3.append(" there");
        b3 = b3.append(" buddy");
        h.check(b3.toString().equals("hi there buddy"), "s-2b");
        System.out.println("S3");
        StringBuffer b4 = new StringBuffer();
        b4 = b4.append("sdljfksdjfklsdjflksdjflkjsdlkfjlsdkjflksdjfklsd");
        h.check(b4.toString().equals("sdljfksdjfklsdjflksdjflkjsdlkfjlsdkjflksdjfklsd"), "s-3");
        System.out.println("S4");
        Object nc = null;
        StringBuffer b6 = new StringBuffer();
        try {
            b6 = b6.append((char[]) nc);
            h.fail("s-4");
        } catch (NullPointerException e) {
        }
        System.out.println("S5");
        char[] hither = new char[]{'h', 'i', 't', 'h', 'e', 'r'};
        StringBuffer b8 = new StringBuffer("!");
        h.check(b8.append(hither).toString().equals("!hither"), "s-5");
        System.out.println("S6");
        try {
            b6 = b6.append((char[]) nc, 0, 3);
            h.fail("s-6a");
        } catch (NullPointerException e) {
        }
        System.out.println("S7a");
        h.check(true, "trace6-done");
    }
}
