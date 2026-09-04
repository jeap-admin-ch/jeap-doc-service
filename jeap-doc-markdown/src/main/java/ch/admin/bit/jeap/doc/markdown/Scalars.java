package ch.admin.bit.jeap.doc.markdown;

/**
 * Quotes a string so it survives as a value in YAML front matter or in a JSON file.
 * <p>
 * Written by hand rather than with a JSON library, because this module has no dependencies. YAML and JSON
 * write a double-quoted scalar the same way, so one encoder serves both.
 */
final class Scalars {

    private Scalars() {
    }

    /** The value as a double-quoted scalar. A null becomes an empty string, not a {@code null} literal. */
    static String quoted(String value) {
        String text = value == null ? "" : value;
        StringBuilder quoted = new StringBuilder(text.length() + 8).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                case '\b' -> quoted.append("\\b");
                case '\f' -> quoted.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
