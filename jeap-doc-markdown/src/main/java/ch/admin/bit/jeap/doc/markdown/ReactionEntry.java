package ch.admin.bit.jeap.doc.markdown;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Typed entry metadata; names remain text even when they contain Markdown or markup. */
public record ReactionEntry(Kind kind, String system, String component, String message, String variant,
                            List<ComponentLink> componentLinks, List<InterfaceLink> interfaceLinks) {
    public enum Kind { SYSTEM, COMPONENT, MESSAGE }

    public ReactionEntry {
        componentLinks = List.copyOf(componentLinks);
        interfaceLinks = List.copyOf(interfaceLinks);
    }

    public ReactionEntry(Kind kind, String system, String component, String message, String variant) {
        this(kind, system, component, message, variant, List.of(), List.of());
    }

    public ReactionEntry withComponentLinks(List<ComponentLink> links) {
        return new ReactionEntry(kind, system, component, message, variant, links, interfaceLinks);
    }

    public ReactionEntry withInterfaceLinks(List<InterfaceLink> links) {
        return new ReactionEntry(kind, system, component, message, variant, componentLinks, links);
    }

    public record ComponentLink(String system, String component, String href) {}
    public record InterfaceLink(String kind, String name, String href) {}

    String encoded() {
        StringBuilder value = new StringBuilder("kind=" + kind.name() + "&system=" + encode(system) + "&component=" + encode(component)
               + "&message=" + encode(message) + "&variant=" + encode(variant));
        for (ComponentLink link : componentLinks) {
            value.append("&componentLink=").append(encode("system=" + encode(link.system()) + "&component="
                    + encode(link.component()) + "&href=" + encode(link.href())));
        }
        for (InterfaceLink link : interfaceLinks) {
            value.append("&interfaceLink=").append(encode("kind=" + encode(link.kind()) + "&name="
                    + encode(link.name()) + "&href=" + encode(link.href())));
        }
        return value.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
