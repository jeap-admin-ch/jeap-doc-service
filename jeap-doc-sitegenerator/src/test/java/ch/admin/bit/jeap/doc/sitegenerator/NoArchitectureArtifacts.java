package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.architecture.DatabaseSchema;
import ch.admin.bit.jeap.doc.domain.architecture.RestApiOverview;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifact;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifactRef;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactContent;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * An instance whose OpenAPI specifications and database schemas have never been replicated, which is what a
 * fresh one looks like.
 * <p>
 * The read side answers nothing and the write side throws, like the message schema double next to it. The
 * {@link ArchitectureArtifactContent} half throws on every call: with no artifact stored there is nothing to
 * parse, so a run that parsed anyway is a test to rewrite.
 */
final class NoArchitectureArtifacts implements ArchitectureArtifactRepository, ArchitectureArtifactContent {

    static final NoArchitectureArtifacts INSTANCE = new NoArchitectureArtifacts();

    private NoArchitectureArtifacts() {
    }

    @Override
    public List<ArchitectureArtifactRef> findRefs(String environment, ArchitectureImportKind kind) {
        return List.of();
    }

    @Override
    public Optional<ArchitectureArtifact> find(String environment, ArchitectureImportKind kind, String system,
                                               String component) {
        return Optional.empty();
    }

    @Override
    public void store(ArchitectureArtifact artifact) {
        throw new UnsupportedOperationException("This instance replicates no artifacts.");
    }

    @Override
    public void confirm(String environment, ArchitectureImportKind kind, String system, String component,
                        Instant checkedAt) {
        throw new UnsupportedOperationException("This instance replicates no artifacts.");
    }

    @Override
    public void remove(Collection<ArchitectureArtifactRef> artifacts) {
        throw new UnsupportedOperationException("This instance replicates no artifacts.");
    }

    @Override
    public int removeOrphans(String environment) {
        throw new UnsupportedOperationException("This instance replicates no artifacts.");
    }

    @Override
    public Optional<DatabaseSchema> databaseSchema(ArchitectureArtifact artifact) {
        throw new UnsupportedOperationException("There is no artifact to read.");
    }

    @Override
    public Optional<RestApiOverview> restApi(ArchitectureArtifact artifact) {
        throw new UnsupportedOperationException("There is no artifact to read.");
    }
}
