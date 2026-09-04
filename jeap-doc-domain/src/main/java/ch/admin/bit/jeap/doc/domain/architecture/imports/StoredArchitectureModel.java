package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * What the generator reads: the architecture model as the last import stored it.
 * <p>
 * The composition of the two halves happens here rather than inside an adapter, because an adapter may not
 * depend on another adapter - the client of the architecture repository cannot read this service's database.
 * From the generator's point of view the store genuinely is where the model comes from, which is why the port
 * keeps its name.
 */
@Component
@RequiredArgsConstructor
class StoredArchitectureModel implements ArchitectureModelSource {

    private final ArchitectureModelUpstream upstream;
    private final ArchitectureModelRepository models;
    private final ArchitectureImportRepository imports;

    @Override
    public boolean isConfiguredFor(String environment) {
        return upstream.environments().contains(environment);
    }

    @Override
    public Optional<String> sourceUrlOf(String environment) {
        return upstream.urlOf(environment);
    }

    /**
     * Out of the state row rather than out of the landscape: it says when the architecture repository was last
     * read successfully, which is what the staleness warning and the readiness check ask about. A run that
     * found the landscape unchanged wrote nothing and still moved this.
     */
    @Override
    public Optional<Instant> lastSuccessfulImportAt(String environment) {
        return Optional.ofNullable(imports.state(environment, ArchitectureImportKind.MODEL).lastSuccessAt());
    }

    @Override
    public ArchitectureSnapshot read(String environment) {
        return models.read(environment);
    }
}
