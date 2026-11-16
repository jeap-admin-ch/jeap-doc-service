package ch.admin.bit.jeap.doc.domain.template;

import java.util.Comparator;
import java.util.List;

/** Documentation destinations supplied by structure templates, never guessed by a graph viewer. */
public record ReactionDocumentationLinks(List<ComponentLink> componentLinks, List<InterfaceLink> interfaceLinks) {
    private static final Comparator<ComponentLink> COMPONENT_ORDER = Comparator
            .comparing(ComponentLink::system)
            .thenComparing(ComponentLink::component)
            .thenComparing(ComponentLink::href)
            .thenComparing(ComponentLink::templateId, Comparator.nullsFirst(String::compareTo));
    private static final Comparator<InterfaceLink> INTERFACE_ORDER = Comparator
            .comparing(InterfaceLink::kind)
            .thenComparing(InterfaceLink::name)
            .thenComparing(InterfaceLink::href)
            .thenComparing(InterfaceLink::templateId, Comparator.nullsFirst(String::compareTo));

    public ReactionDocumentationLinks {
        componentLinks = componentLinks.stream().distinct().sorted(COMPONENT_ORDER).toList();
        interfaceLinks = interfaceLinks.stream().distinct().sorted(INTERFACE_ORDER).toList();
    }

    public static ReactionDocumentationLinks empty() {
        return new ReactionDocumentationLinks(List.of(), List.of());
    }

    /** Preserve the template boundary so alternative methodologies are not mistaken for ambiguous ownership. */
    public ReactionDocumentationLinks forTemplate(String templateId) {
        return new ReactionDocumentationLinks(componentLinks.stream()
                .map(link -> new ComponentLink(link.system(), link.component(), link.href(), templateId)).toList(),
                interfaceLinks.stream()
                        .map(link -> new InterfaceLink(link.kind(), link.name(), link.href(), templateId)).toList());
    }

    public record ComponentLink(String system, String component, String href, String templateId) {
        public ComponentLink(String system, String component, String href) {
            this(system, component, href, null);
        }
    }

    public record InterfaceLink(String kind, String name, String href, String templateId) {
        public InterfaceLink(String kind, String name, String href) {
            this(kind, name, href, null);
        }
    }
}
