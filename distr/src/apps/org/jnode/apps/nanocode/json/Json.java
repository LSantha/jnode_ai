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

package org.jnode.apps.nanocode.json;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON writer and parser. The JNode classlib ships no JSON library,
 * so this is a self-contained implementation.
 *
 * <p>The writer produces compact JSON. The parser is a recursive-descent
 * parser that returns {@code Map<String, Object>}, {@code List<Object>},
 * {@code String}, {@code Double}, {@code Boolean}, or {@code null}.
 */
public final class Json {

    private Json() {
    }

    // -------------------------------------------------------------- writer

    /**
     * Serialises an object to a JSON string.
     */
    public static String write(Object o) {
        StringBuilder b = new StringBuilder();
        writeValue(b, o);
        return b.toString();
    }

    private static void writeValue(StringBuilder b, Object o) {
        if (o == null) {
            b.append("null");
        } else if (o instanceof String) {
            writeString(b, (String) o);
        } else if (o instanceof Boolean) {
            b.append(((Boolean) o).booleanValue() ? "true" : "false");
        } else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                b.append(Long.toString((long) d));
            } else {
                b.append(Double.toString(d));
            }
        } else if (o instanceof Number) {
            b.append(o.toString());
        } else if (o instanceof Map) {
            writeObject(b, (Map<?, ?>) o);
        } else if (o instanceof List) {
            writeArray(b, (List<?>) o);
        } else {
            writeString(b, String.valueOf(o));
        }
    }

    private static void writeObject(StringBuilder b, Map<?, ?> map) {
        b.append('{');
        Iterator<?> it = map.entrySet().iterator();
        boolean first = true;
        while (it.hasNext()) {
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) it.next();
            if (!first) {
                b.append(',');
            }
            first = false;
            writeString(b, String.valueOf(e.getKey()));
            b.append(':');
            writeValue(b, e.getValue());
        }
        b.append('}');
    }

    private static void writeArray(StringBuilder b, List<?> list) {
        b.append('[');
        Iterator<?> it = list.iterator();
        boolean first = true;
        while (it.hasNext()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            writeValue(b, it.next());
        }
        b.append(']');
    }

    /**
     * Appends a JSON-escaped string (including surrounding quotes).
     */
    public static void writeString(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\b':
                    b.append("\\b");
                    break;
                case '\f':
                    b.append("\\f");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        b.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }

    // -------------------------------------------------------------- parser

    /**
     * Parses a JSON document. Returns a {@code Map}, {@code List},
     * {@code String}, {@code Double}, {@code Boolean}, or {@code null}.
     *
     * @throws IllegalArgumentException on malformed input
     */
    public static Object parse(String s) {
        Parser p = new Parser(s);
        Object o = p.parse();
        p.skipWhitespace();
        if (p.pos != s.length()) {
            throw new IllegalArgumentException("trailing junk at " + p.pos);
        }
        return o;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        Object parse() {
            skipWhitespace();
            if (pos >= s.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            char c = s.charAt(pos);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            return parseNumber();
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Map<String, Object> parseObject() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            pos++; // {
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWhitespace();
                String k = parseString();
                skipWhitespace();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw new IllegalArgumentException("expected ':' at " + pos);
                }
                pos++;
                m.put(k, parse());
                skipWhitespace();
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("unterminated object");
                }
                char c = s.charAt(pos++);
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' or '}' at " + (pos - 1));
                }
            }
        }

        List<Object> parseArray() {
            List<Object> l = new ArrayList<Object>();
            pos++; // [
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return l;
            }
            while (true) {
                l.add(parse());
                skipWhitespace();
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("unterminated array");
                }
                char c = s.charAt(pos++);
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' or ']' at " + (pos - 1));
                }
            }
        }

        String parseString() {
            if (pos >= s.length() || s.charAt(pos) != '"') {
                throw new IllegalArgumentException("expected string at " + pos);
            }
            pos++;
            StringBuilder b = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return b.toString();
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':
                        b.append('"');
                        break;
                    case '\\':
                        b.append('\\');
                        break;
                    case '/':
                        b.append('/');
                        break;
                    case 'b':
                        b.append('\b');
                        break;
                    case 'f':
                        b.append('\f');
                        break;
                    case 'n':
                        b.append('\n');
                        break;
                    case 'r':
                        b.append('\r');
                        break;
                    case 't':
                        b.append('\t');
                        break;
                    case 'u':
                        b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("bad escape \\" + e);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        Double parseNumber() {
            int start = pos;
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) {
                pos++;
            }
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                        || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new IllegalArgumentException("expected value at " + pos);
            }
            return Double.valueOf(Double.parseDouble(s.substring(start, pos)));
        }
    }
}