package ch.admin.bit.jeap.doc.domain.architecture;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A small landscape to compute against, written so a test reads as the situation it is about.
 */
public final class ArchitectureModelFixture {

    private ArchitectureModelFixture() {
    }

    public static DocumentedSystem system(String name, List<DocumentedComponent> components,
                                          List<SystemRelation> relations, List<DocumentedMessage> messages) {
        return new DocumentedSystem(name, name.toLowerCase(Locale.ROOT), name + " does things.",
                List.of(), new Team("Team " + name, "team@example.com", null, null),
                components, relations, messages);
    }

    public static DocumentedSystem system(String name, SystemRelation... relations) {
        return system(name, List.of(component(name + "-service")), Arrays.asList(relations), List.of());
    }

    public static DocumentedComponent component(String name) {
        return new DocumentedComponent(name, name.toLowerCase(Locale.ROOT), "Handles " + name,
                ComponentType.BACKEND_SERVICE,
                new Team("Team Blue", null, null, null), "DEPLOYMENT_LOG",
                ZonedDateTime.parse("2026-08-27T04:00:00Z"), List.of(), null, null);
    }

    /** An event published by a component of the producing system and consumed by one of the consuming one. */
    public static SystemRelation event(String name, String producerSystem, String producer,
                                       String consumerSystem, String consumer) {
        return new SystemRelation(RelationKind.EVENT, consumerSystem, consumer, producerSystem, producer,
                name, null, null, null);
    }

    /** A command sent by a component of the sending system to one of the receiving one. */
    public static SystemRelation command(String name, String senderSystem, String sender,
                                         String receiverSystem, String receiver) {
        return new SystemRelation(RelationKind.COMMAND, receiverSystem, receiver, senderSystem, sender,
                name, null, null, null);
    }

    /** A REST call from a component of the calling system to one of the providing one. */
    public static SystemRelation restApi(String method, String path, String consumerSystem, String consumer,
                                         String providerSystem, String provider) {
        return new SystemRelation(RelationKind.REST_API, consumerSystem, consumer, providerSystem, provider,
                null, method, path, null);
    }

    public static ArchitectureModel model(DocumentedSystem... systems) {
        return ArchitectureModel.of(Arrays.asList(systems));
    }
}
