package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.ContractRole;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageContract;
import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.RelationPaths;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Who is on the other side of what a component offers: the callers of a REST operation, and the components
 * that consume or publish a message.
 * <p>
 * It belongs beside {@link ComponentContext} for its reason: who talks to whom is a fact about the
 * architecture, and the slugs are resolved here so that a template reads a slug and never derives one. A slug
 * is also the evidence that a page exists - it is non-null only because this landscape documents that system,
 * and that system that component.
 */
public final class ComponentCounterparts {

    private ComponentCounterparts() {
    }

    /**
     * One counterpart, resolved against the landscape.
     *
     * @param component the component as the model spells it, or as the relation did where the model has no
     *                  such component
     * @param componentSlug the page's segment, or null where there is no page to link
     * @param system    the owning system, or null where the model does not say
     * @param pactUrl   the Pact contract of the relation, or null
     */
    public record Counterpart(String component, String componentSlug, String system, String systemSlug,
                              String pactUrl) {

        /** Whether a link may be written: both segments of the route have to be known. */
        public boolean isLinkable() {
            return systemSlug != null && componentSlug != null;
        }
    }

    /** One called operation, as the relations spell it, with the components that call it. */
    public record Operation(String method, String path, List<Counterpart> callers) {

        public Operation {
            callers = List.copyOf(callers);
        }

        /** The operation as a page names it. */
        public String label() {
            return ((method == null ? "" : method.strip()) + " " + (path == null ? "" : path.strip())).strip();
        }
    }

    /**
     * The callers of a component's REST operations.
     * <p>
     * <b>The key is the method and the path, normalised.</b> The operation table is written from the
     * replicated OpenAPI specification while a relation carries the path of the architecture repository's
     * {@code RestApi}: the specification says {@code POST /api/v4/businesspartner/} and the relation
     * {@code POST /api/v4/businesspartner}, and one side may name a path variable {@code {bpId}} where the
     * other names it {@code {businessPartnerId}}. See {@link RelationPaths#normalised}.
     * <p>
     * <b>Not a record, because it remembers.</b> A page asks for the callers of every operation it writes a
     * row for, and the architecture model knows called operations that the published specification does not
     * declare - their callers would simply vanish. So this notes what was asked for, and {@link #notLookedUp()}
     * answers the rest. One instance per page.
     */
    public static final class RestCallers {

        private final Map<String, Operation> byOperation;
        private final Set<String> lookedUp = new HashSet<>();

        private RestCallers(Map<String, Operation> byOperation) {
            this.byOperation = Map.copyOf(byOperation);
        }

        public static RestCallers none() {
            return new RestCallers(Map.of());
        }

        /** The callers of one operation, never null. */
        public List<Counterpart> of(String method, String path) {
            String key = keyOf(method, path);
            lookedUp.add(key);
            Operation operation = byOperation.get(key);
            return operation == null ? List.of() : operation.callers();
        }

        /**
         * The called operations no row of the page has asked for, sorted - what the specification does not
         * declare, so that their callers are named rather than dropped.
         */
        public List<Operation> notLookedUp() {
            return byOperation.entrySet().stream()
                    .filter(entry -> !lookedUp.contains(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .sorted(Comparator.comparing(Operation::path,
                                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
                            .thenComparing(Operation::method,
                                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)))
                    .toList();
        }

        public boolean isEmpty() {
            return byOperation.isEmpty();
        }
    }

    /**
     * The callers of every REST operation the given component provides.
     * <p>
     * From the relations rather than from the contracts: a contract is about a message, and REST is not in
     * them at all. The Pact URL rides on the relation and has never been shown anywhere else.
     */
    public static RestCallers callersOf(ArchitectureModel model, DocumentedComponent component) {
        if (model == null || component == null) {
            return RestCallers.none();
        }
        Map<String, List<Counterpart>> found = new LinkedHashMap<>();
        Map<String, SystemRelation> spelling = new LinkedHashMap<>();
        for (SystemRelation relation : model.relations()) {
            // A relation with no path names no operation, so there is no row and no note for it either.
            if (relation.kind() != RelationKind.REST_API
                || !component.name().equalsIgnoreCase(relation.provider())
                || isBlank(relation.consumer())
                || isBlank(relation.path())) {
                continue;
            }
            String key = keyOf(relation.method(), relation.path());
            spelling.putIfAbsent(key, relation);
            found.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(counterpartOf(model, relation.consumerSystem(), relation.consumer(),
                            relation.pactUrl()));
        }
        Map<String, Operation> operations = new LinkedHashMap<>();
        found.forEach((key, callers) -> operations.put(key, new Operation(spelling.get(key).method(),
                spelling.get(key).path(), distinctAndSorted(callers))));
        return new RestCallers(operations);
    }

    /**
     * The components on the other side of a message: its consumers for a message the component produces, its
     * publishers for one it consumes.
     * <p>
     * From the message's own contracts, which is what the message's page lists its publishers and consumers
     * from - so the two pages cannot disagree, and the model is not scanned a second time.
     *
     * @param role          the role of the counterparts, not of the component whose page this is
     * @param ownComponent  the component whose page this is; its own contract is not a counterpart of itself
     */
    public static List<Counterpart> counterpartsOf(ArchitectureModel model, DocumentedMessage message,
                                                   ContractRole role, String ownComponent) {
        if (model == null || message == null || role == null) {
            return List.of();
        }
        List<Counterpart> counterparts = new ArrayList<>();
        for (MessageContract contract : message.contracts()) {
            if (contract.role() != role
                || isBlank(contract.component())
                || contract.component().equalsIgnoreCase(ownComponent)) {
                continue;
            }
            counterparts.add(counterpartOf(model, contract.system(), contract.component(), null));
        }
        return distinctAndSorted(counterparts);
    }

    /**
     * One end, resolved the way {@link ComponentContext} resolves a box: the system by name or alias, and by
     * unambiguous ownership where the relation names none - exactly one system of this landscape having a
     * component of that name means the export merely left the system out, while two mean nothing can be
     * concluded.
     */
    private static Counterpart counterpartOf(ArchitectureModel model, String systemName, String componentName,
                                             String pactUrl) {
        DocumentedSystem owner = ownerOf(model, systemName, componentName);
        if (owner == null) {
            return new Counterpart(componentName, null, blankToNull(systemName), null, blankToNull(pactUrl));
        }
        DocumentedComponent documented = owner.components().stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(componentName))
                .findFirst()
                .orElse(null);
        return new Counterpart(documented == null ? componentName : documented.name(),
                documented == null ? null : documented.slug(),
                owner.name(), owner.slug(), blankToNull(pactUrl));
    }

    private static DocumentedSystem ownerOf(ArchitectureModel model, String systemName, String componentName) {
        if (!isBlank(systemName)) {
            return model.systemNamed(systemName).orElse(null);
        }
        List<DocumentedSystem> owners = model.systemsOf(componentName);
        return owners.size() == 1 ? owners.getFirst() : null;
    }

    /**
     * One entry per component, sorted by name ignoring case, so that two runs over one model write the same
     * page. Where several relations name one caller and only some carry a Pact URL, the first that has one
     * wins.
     */
    private static List<Counterpart> distinctAndSorted(List<Counterpart> counterparts) {
        Map<String, Counterpart> byComponent = new TreeMap<>(Comparator.naturalOrder());
        for (Counterpart counterpart : counterparts) {
            String key = counterpart.component() == null ? "" : counterpart.component().toLowerCase(Locale.ROOT);
            Counterpart known = byComponent.get(key);
            if (known == null) {
                byComponent.put(key, counterpart);
            } else if (known.pactUrl() == null && counterpart.pactUrl() != null) {
                byComponent.put(key, new Counterpart(known.component(), known.componentSlug(), known.system(),
                        known.systemSlug(), counterpart.pactUrl()));
            }
        }
        return List.copyOf(byComponent.values());
    }

    /**
     * The method and the path as both sides of the join spell them.
     * <p>
     * The method ignoring case: the architecture repository compares it with {@code equals}, but there both
     * sides come from one importer and here they come from two different parsers.
     */
    private static String keyOf(String method, String path) {
        String verb = method == null ? "" : method.strip().toUpperCase(Locale.ROOT);
        return verb + " " + RelationPaths.normalised(path);
    }



    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }
}
