package ch.admin.bit.jeap.doc.archrepo;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * The payloads of the architecture repository's {@code /docs-api}, the read resources.
 * <p>
 * Field names are copied from the records the architecture repository ships. Everything that is an enum on
 * the other side arrives here as a string, so a kind this service has not heard of cannot fail the build.
 */
final class DocsApiDtos {

    private DocsApiDtos() {
    }

    record SystemListDto(List<SystemSummaryDto> systems) {
    }

    record SystemSummaryDto(String name, String description, List<String> aliases, TeamDto team) {
    }

    record SystemDetailDto(String name, String description, List<String> aliases, TeamDto team,
                           List<ComponentDto> components, List<RelationDto> relations) {
    }

    record TeamDto(String name, String contactAddress, String jiraLink, String confluenceLink) {
    }

    record ComponentDto(String name, String description, String type, TeamDto team, String importer,
                        ZonedDateTime lastSeen, List<RestApiDto> restApis,
                        OpenApiRefDto openApi, DatabaseSchemaRefDto databaseSchema) {
    }

    record RestApiDto(String method, String path) {
    }

    record OpenApiRefDto(String version, String serverUrl, String contentUrl, String swaggerUrl) {
    }

    record DatabaseSchemaRefDto(String schemaVersion, String contentUrl) {
    }

    record RelationDto(String type, String consumerSystem, String consumer, String providerSystem,
                       String provider, String method, String path, String pactUrl, String messageType) {
    }

    record MessageListDto(List<MessageDto> messages) {
    }

    record MessageDto(String name, String kind, String scope, String topic, String descriptorUrl,
                      String documentationUrl, String description, List<String> versions,
                      List<MessageContractDto> contracts) {
    }

    record MessageContractDto(String role, String component, String system, String topic,
                              List<String> versions) {
    }

    /**
     * The replication index of one kind of artifact. Both kinds answer this same shape, which is why the
     * replication is written once and parameterised by kind.
     */
    record ArtifactIndexDto(List<ArtifactIndexEntryDto> artifacts) {
    }

    /**
     * @param etag       byte-identical to the {@code ETag} header of the content resource, so that a consumer
     *                   can decide without a request whether it has to fetch
     * @param contentUrl the path of the content resource, relative to the service root, carrying the
     *                   architecture repository's context path
     */
    record ArtifactIndexEntryDto(String system, String component, String version, String etag,
                                 ZonedDateTime lastModifiedAt, String contentUrl) {
    }

    /**
     * The message type index: every message type of the model with the versions it has, and no schemas. What a
     * consumer diffs against what it has already replicated.
     */
    record MessageTypeIndexDto(List<MessageTypeIndexEntryDto> messageTypes) {
    }

    /**
     * @param kind     EVENT or COMMAND, which this service does not use - the model already says which a
     *                 message type is, and this index is read only to find out which versions exist
     * @param versions the versions of this message type, newest last
     */
    record MessageTypeIndexEntryDto(String system, String message, String kind,
                                    List<MessageTypeVersionRefDto> versions) {
    }

    /**
     * @param contentUrl the path of the version resource, relative to the service root, carrying the
     *                   architecture repository's context path
     */
    record MessageTypeVersionRefDto(String version, String contentUrl) {
    }

    /**
     * One version of one message type, with both Avro schemas.
     *
     * @param system            the system as the upstream stores it, which is not necessarily how it was
     *                          addressed - an alias resolves to it
     * @param message           the message type as the upstream stores it, for the same reason
     * @param compatibilityMode what this version declares against {@code compatibleVersion}, or null where the
     *                          descriptor declares none
     * @param key               the key schema, or null where the message type has none
     * @param value             the value schema. A version without one cannot be imported upstream
     */
    record MessageTypeVersionDto(String system, String message, String version, String compatibilityMode,
                                 String compatibleVersion, MessageSchemaDto key, MessageSchemaDto value) {
    }

    /**
     * @param resolvedSchema the schema as a person reads it - every import inlined, the base types dropped, the
     *                       namespaces and the enclosing braces removed. <b>Deliberately not valid Avro IDL</b>;
     *                       {@code schemaUrl} is where the file itself is
     */
    record MessageSchemaDto(String schemaName, String schemaUrl, String resolvedSchema) {
    }
}
