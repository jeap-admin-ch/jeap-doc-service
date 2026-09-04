package ch.admin.bit.jeap.doc.domain.architecture.imports;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * One replicated artifact of a component: its OpenAPI specification or its database schema, as the
 * architecture repository of one environment publishes it.
 * <p>
 * The content is the bytes the upstream served, stored as they arrived. The entity tag names exactly those
 * bytes, so a round trip through a decoder would name different ones.
 *
 * @param environment  the environment whose architecture repository this came from
 * @param kind         which of the two artifacts it is
 * @param system       the system the component belongs to, by name
 * @param component    the component that published it, by name
 * @param version      the version the component declared, or null
 * @param etag         the entity tag verbatim, quotes and all
 * @param content      the bytes, as served
 * @param sizeInBytes  how many bytes, written rather than derived
 * @param modifiedAt   when the architecture repository last saw it change, or null
 * @param replicatedAt when this service last stored these bytes
 */
public record ArchitectureArtifact(
        String environment,
        ArchitectureImportKind kind,
        String system,
        String component,
        String version,
        String etag,
        byte[] content,
        long sizeInBytes,
        Instant modifiedAt,
        Instant replicatedAt) {

    /**
     * Value equality over the content too, which the generated one would not give for an array.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof ArchitectureArtifact artifact
               && Objects.equals(environment, artifact.environment)
               && kind == artifact.kind
               && Objects.equals(system, artifact.system)
               && Objects.equals(component, artifact.component)
               && Objects.equals(version, artifact.version)
               && Objects.equals(etag, artifact.etag)
               && Arrays.equals(content, artifact.content)
               && sizeInBytes == artifact.sizeInBytes
               && Objects.equals(modifiedAt, artifact.modifiedAt)
               && Objects.equals(replicatedAt, artifact.replicatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(environment, kind, system, component, version, etag, Arrays.hashCode(content),
                sizeInBytes, modifiedAt, replicatedAt);
    }

    /**
     * Without the content, so that a log line or a test failure does not print a megabyte of JSON.
     */
    @Override
    public String toString() {
        return "ArchitectureArtifact[%s %s %s/%s version=%s etag=%s %d bytes]"
                .formatted(environment, kind, system, component, version, etag, sizeInBytes);
    }
}
