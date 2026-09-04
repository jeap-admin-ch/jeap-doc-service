package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.List;

/**
 * An event or a command defined by a system.
 * <p>
 * A message belongs to the system and not to the component that publishes it. Several components handle the
 * same contract, and the contract outlives all of them. So there is one page per message below the system's
 * building block view, and a component page only names the messages it handles.
 *
 * @param name             the message type name, e.g. {@code OrdersPaymentAcceptedEvent}
 * @param slug             the same name kebab-cased, e.g. {@code orders-payment-accepted-event}: the path
 *                         segment its page is served under. Derived and checked by the importer, like a
 *                         component's - see {@link ch.admin.bit.jeap.doc.domain.Slugs#toMessageSlug}
 * @param kind             event or command
 * @param scope            how far the message travels
 * @param topic            the topic it is published on
 * @param description      what it means, or null
 * @param descriptorUrl    where its descriptor is published, or null
 * @param documentationUrl further documentation, or null
 * @param versions         the versions that exist, with their schemas where a run has joined them in
 * @param contracts        who produces and who consumes it
 */
public record DocumentedMessage(
        String name,
        String slug,
        MessageKind kind,
        String scope,
        String topic,
        String description,
        String descriptorUrl,
        String documentationUrl,
        List<DocumentedMessageVersion> versions,
        List<MessageContract> contracts) {

    public DocumentedMessage {
        versions = versions == null ? List.of() : List.copyOf(versions);
        contracts = contracts == null ? List.of() : List.copyOf(contracts);
    }

    /**
     * The same message, addressed under the given slug. The upstream serves the name; how it becomes a path
     * segment is this service's decision, so the importer fills it in.
     */
    public DocumentedMessage withSlug(String slug) {
        return new DocumentedMessage(name, slug, kind, scope, topic, description, descriptorUrl,
                documentationUrl, versions, contracts);
    }

    /**
     * The same message with its versions joined to what was replicated about them. A generation run does this
     * per system - see the record of a version.
     */
    public DocumentedMessage withVersions(List<DocumentedMessageVersion> versions) {
        return new DocumentedMessage(name, slug, kind, scope, topic, description, descriptorUrl,
                documentationUrl, versions, contracts);
    }

    public List<MessageContract> producers() {
        return contracts.stream().filter(MessageContract::produces).toList();
    }

    public List<MessageContract> consumers() {
        return contracts.stream().filter(contract -> contract.role() == ContractRole.CONSUMES).toList();
    }

    /** Contracts whose role this service does not know, so that a page can show them rather than mis-file them. */
    public List<MessageContract> unknownContracts() {
        return contracts.stream().filter(contract -> contract.role() == ContractRole.UNKNOWN).toList();
    }
}
