package ch.admin.bit.jeap.doc.markdown;

import java.util.List;
import java.util.stream.Collectors;

/**
 * What the live excerpts on a system's or a component's page are asked for: the placeholder the generator writes,
 * and the site template's excerpts component reads, in place of the excerpts themselves.
 * <p>
 * It holds nothing but what the architecture model says, so a deployment or a scoring run changes no page and no
 * part's content hash - the excerpts are read when the page is shown. Written as a fenced block of JSON, which
 * is inert text whatever a name contains.
 *
 * @param page        whether the page is a system's or a component's
 * @param system      the system's name in the architecture model
 * @param environment the id of the environment tree the page is in
 * @param components  on a system page the system's components in this tree's model, on a component page that one;
 *                    sorted by name
 */
public record ExcerptsEntry(Page page, String system, String environment, List<String> components) {

    /** The language of the fence, which the site template's code block hands to the excerpts component. */
    public static final String LANGUAGE = "jeap-excerpts";

    public enum Page {
        SYSTEM, COMPONENT;

        String json() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public ExcerptsEntry {
        components = components.stream().sorted().toList();
    }

    String encoded() {
        return "{\"page\": " + string(page.json())
               + ", \"system\": " + string(system)
               + ", \"environment\": " + string(environment)
               + ", \"components\": [" + components.stream().map(ExcerptsEntry::string)
                       .collect(Collectors.joining(", ")) + "]}";
    }

    /** A JSON string: quoted, with the quote, the backslash and every control character escaped. */
    static String string(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder json = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        json.append("\\u%04x".formatted((int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"').toString();
    }
}
