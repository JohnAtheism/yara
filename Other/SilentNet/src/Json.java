package silentnet.stage1;

/**
 * Minimal JSON helpers matching the ad-hoc parser used by the original loader.
 *
 * The malware never used a JSON library. It built JSON by string concatenation
 * and pulled values back out with a hand-written scanner that only understands
 * quoted string values. These routines preserve that behaviour.
 */
final class Json {
    private Json() {
    }

    /** Escape a value the same way the original bdepuIdx/kvhx/bonunb helpers did. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    /**
     * Find {@code "key":"value"} and return the unescaped value.
     *
     * Returns null when the key is absent, the value is malformed, or the
     * literal value is the string "null" (the original treated that as absent).
     * The original did not implement backslash-u XXXX decoding; unknown escapes simply
     * dropped the backslash. That quirk is kept here.
     */
    static String extractString(String json, String key) {
        if (json == null || key == null) {
            return null;
        }
        String needle = "\"" + key + "\":\"";
        int start = json.indexOf(needle);
        if (start == -1) {
            return null;
        }
        int i = start + needle.length();
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    case '"':
                        out.append('"');
                        break;
                    default:
                        out.append(c);
                }
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                break;
            }
            out.append(c);
        }
        String value = out.toString();
        return value.equals("null") ? null : value;
    }
}
