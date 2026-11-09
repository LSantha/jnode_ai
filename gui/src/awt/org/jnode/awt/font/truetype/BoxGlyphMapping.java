package org.jnode.awt.font.truetype;

/**
 * Maps the DOS/CP437 box-drawing code positions used by charva's ACS
 * table to the Unicode box-drawing characters available in most true
 * type fonts (DejaVu).
 *
 * <p>True type cmaps map code 218 to &Uacute; etc. (the Latin-1 letter),
 * so without this remap the charva commander panels would render letters
 * instead of box graphics.</p>
 */
final class BoxGlyphMapping {

    private static final int[] CP437_CODES = {
        218, 192, 191, 217, 195, 180, 193, 194, 196, 179, 197, 178, 254};
    private static final char[] UNICODE_BOX = {
        '\u250C', '\u2514', '\u2518', '\u2510', '\u251C', '\u2524',
        '\u2534', '\u252C', '\u2500', '\u2502', '\u253C', '\u2592',
        '\u25A0'};

    /**
     * Remaps a DOS code point to its Unicode box glyph, other characters
     * are returned unchanged.
     *
     * @param ch character code
     * @return the character to look up in the cmap
     */
    static char remap(char ch) {
        for (int i = 0; i < CP437_CODES.length; i++) {
            if (CP437_CODES[i] == ch) {
                return UNICODE_BOX[i];
            }
        }
        return ch;
    }

    /**
     * Is this character one of the box-drawing codes?
     *
     * @param ch character code
     * @return true for box-drawing codes
     */
    static boolean isBox(char ch) {
        for (int i = 0; i < CP437_CODES.length; i++) {
            if (CP437_CODES[i] == ch) {
                return true;
            }
        }
        return false;
    }

    private BoxGlyphMapping() {
    }
}
