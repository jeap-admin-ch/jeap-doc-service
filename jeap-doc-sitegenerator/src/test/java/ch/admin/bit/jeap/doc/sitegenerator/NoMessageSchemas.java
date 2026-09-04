package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.architecture.imports.MessageVersionRef;
import ch.admin.bit.jeap.doc.domain.architecture.MessageVersionSchemas;
import ch.admin.bit.jeap.doc.domain.port.MessageSchemaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * An instance whose message schemas have never been replicated, which is what a fresh one looks like - and what
 * every test that is about the pages rather than about the schemas wants.
 */
final class NoMessageSchemas implements MessageSchemaRepository {

    static final NoMessageSchemas INSTANCE = new NoMessageSchemas();

    private NoMessageSchemas() {
    }

    @Override
    public List<MessageVersionRef> findRefs(String environment) {
        return List.of();
    }

    @Override
    public List<MessageVersionSchemas> findAll(String environment, String system) {
        return List.of();
    }

    @Override
    public void store(MessageVersionSchemas schemas) {
        throw new UnsupportedOperationException("This instance replicates no message schemas.");
    }

    @Override
    public void confirm(String environment, String system, String message, String version, Instant checkedAt) {
        throw new UnsupportedOperationException("This instance replicates no message schemas.");
    }

    @Override
    public void remove(Collection<MessageVersionRef> versions) {
        throw new UnsupportedOperationException("This instance replicates no message schemas.");
    }
}
