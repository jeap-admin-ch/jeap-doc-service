package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the generator reads: the architecture model as the last import stored it.
 * <p>
 * The composition of the two halves happens here rather than inside an adapter, because an adapter may not
 * depend on another adapter - the client of the architecture repository cannot read this service's database.
 * From the generator's point of view the store genuinely is where the model comes from, which is why the port
 * keeps its name.
 * <p>
 * <b>The landscape a build reads is held between builds</b> - see
 * {@link ArchitectureImportProperties#isCacheLandscape()} for what that is worth and why it cannot go stale.
 */
@Component
@RequiredArgsConstructor
class StoredArchitectureModel implements ArchitectureModelSource {

    private final ArchitectureModelUpstream upstream;
    private final ArchitectureModelRepository models;
    private final ArchitectureImportRepository imports;
    private final ArchitectureImportProperties properties;

    /**
     * The landscape of each environment as it was read, with the import it was read for. At most one entry per
     * configured environment, and every one of them is immutable: {@code ArchitectureModel} copies its systems
     * and everything below them is a record, so the readers of a held landscape cannot affect each other.
     */
    private final Map<String, HeldLandscape> held = new ConcurrentHashMap<>();

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

    /**
     * The landscape of an environment, read once per import rather than once per build.
     * <p>
     * <b>{@code compute} rather than a read followed by a write</b>: the parts of a site are built several at a
     * time, and three builds starting together would otherwise each read the whole landscape. Here the first
     * one reads it and the others wait for it - which is the same answer, three times cheaper. Only the
     * environment asked for is held up; the map locks per key.
     */
    @Override
    public ArchitectureSnapshot read(String environment) {
        if (!properties.isCacheLandscape()) {
            return models.read(environment);
        }
        Instant readFor = lastSuccessfulImportAt(environment).orElse(null);
        return held.compute(environment, (id, landscape) ->
                        landscape != null && Objects.equals(landscape.importedFor(), readFor)
                                ? landscape
                                : new HeldLandscape(readFor, models.read(id)))
                .snapshot();
    }

    /**
     * A landscape and the import it belongs to.
     *
     * @param importedFor when the architecture repository of that environment had last been read successfully
     *                    when this was read. An import moves that whether or not it changed anything, so
     *                    everything held is dropped by the import that could have changed it. Null where the
     *                    environment has never been imported, which is a landscape too - an empty one
     */
    private record HeldLandscape(Instant importedFor, ArchitectureSnapshot snapshot) {
    }

    @Override
    public java.util.List<String> systemSlugsOf(String environment) {
        return models.systemSlugsOf(environment);
    }
}
