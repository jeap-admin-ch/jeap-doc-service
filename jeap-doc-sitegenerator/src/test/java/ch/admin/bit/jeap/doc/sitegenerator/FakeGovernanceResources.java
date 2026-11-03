package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceResource;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceKind;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceRef;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystemsIndex;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Stands in for the governance import's storage, keyed the way the real repository is. */
final class FakeGovernanceResources implements GovernanceResourceRepository {

    private final Map<String, GovernanceResource> byKey = new LinkedHashMap<>();

    void index(GovernanceSystemsIndex index) {
        // The content is never parsed here - FakeGovernanceContent answers from its own map - so any marker that
        // round-trips through the writer unexamined will do.
        byKey.put(keyOf(GovernanceResourceKind.SYSTEMS_INDEX, null),
                new GovernanceResource(GovernanceResourceKind.SYSTEMS_INDEX, null, "\"etag\"",
                        "index".getBytes(StandardCharsets.UTF_8), 5, Instant.EPOCH));
    }

    void rulesIndex() {
        byKey.put(keyOf(GovernanceResourceKind.RULES_INDEX, null),
                new GovernanceResource(GovernanceResourceKind.RULES_INDEX, null, "\"etag\"",
                        "rules-index".getBytes(StandardCharsets.UTF_8), 11, Instant.EPOCH));
    }

    @Override
    public List<GovernanceResourceRef> findRefs(GovernanceResourceKind kind) {
        throw new UnsupportedOperationException("the writer does not ask for a list of references");
    }

    @Override
    public Optional<GovernanceResource> find(GovernanceResourceKind kind, String key) {
        if (kind == GovernanceResourceKind.SYSTEM || kind == GovernanceResourceKind.RULE) {
            // The name or rule ID is the content, so FakeGovernanceContent can look the parsed fixture up by it.
            return Optional.of(new GovernanceResource(kind, key, "\"etag\"",
                    key.getBytes(StandardCharsets.UTF_8), key.length(), Instant.EPOCH));
        }
        return Optional.ofNullable(byKey.get(keyOf(kind, key)));
    }

    @Override
    public void store(GovernanceResource resource) {
        throw new UnsupportedOperationException("the writer never stores anything");
    }

    @Override
    public <T> T readConsistently(Supplier<T> reads) {
        return reads.get();
    }

    @Override
    public void replace(GovernanceResource index, Collection<GovernanceResource> resources,
                        Collection<GovernanceResourceRef> removed) {
        throw new UnsupportedOperationException("the writer never replaces anything");
    }

    @Override
    public void confirm(GovernanceResourceKind kind, String key, Instant checkedAt) {
        throw new UnsupportedOperationException("the writer never confirms anything");
    }

    @Override
    public void remove(Collection<GovernanceResourceRef> resources) {
        throw new UnsupportedOperationException("the writer never removes anything");
    }

    private static String keyOf(GovernanceResourceKind kind, String key) {
        return kind + "/" + key;
    }
}
