package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.CompletedPublication;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.PublicationTotals;
import ch.admin.bit.jeap.doc.domain.port.PublishedPart;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A build record that keeps nothing: no site has been published, no run was left behind, nothing is past its
 * retention. A test that is about what a pass <i>does</i> overrides only what it asks about.
 */
public class NoBuildHistory implements DocumentationBuildRepository {

    @Override
    public DocumentationBuild start(PartKey part, BuildTrigger trigger, String instance, Instant startedAt,
                                    Publication publication) {
        throw new UnsupportedOperationException("This test's build record does not start builds.");
    }

    @Override
    public DocumentationBuild succeeded(long id, String objectPrefix, int pageCount, long sizeInBytes,
                                        long docusaurusMillis,
                                        String contentDigest, Instant finishedAt) {
        return null;
    }

    @Override
    public DocumentationBuild failed(long id, String failureReason,
                                     Instant finishedAt) {
        return null;
    }

    @Override
    public DocumentationBuild skipped(long id, Instant finishedAt) {
        return null;
    }

    @Override
    public DocumentationBuild aborted(long id, String reason, Instant finishedAt) {
        return null;
    }

    @Override
    public List<DocumentationBuild> abandonRunning(PartKey part, Instant finishedAt) {
        return List.of();
    }

    @Override
    public Set<PartKey> partsWithRunningBuilds() {
        return Set.of();
    }

    @Override
    public List<DocumentationBuild> running() {
        return List.of();
    }

    @Override
    public List<DocumentationBuild> recent(String site, int limit) {
        return List.of();
    }

    @Override
    public List<DocumentationBuild> recentOf(PartKey part, int limit) {
        return List.of();
    }

    @Override
    public Optional<DocumentationBuild> find(String site, long id) {
        return Optional.empty();
    }

    @Override
    public Optional<DocumentationBuild> published(PartKey part) {
        return Optional.empty();
    }

    @Override
    public List<PublishedPart> publishedPartsOf(String site) {
        return List.of();
    }

    @Override
    public PublicationTotals publishedTotalsOf(String site) {
        return PublicationTotals.none();
    }

    @Override
    public Optional<Instant> lastSuccessAt(String site) {
        return Optional.empty();
    }

    @Override
    public Optional<Instant> lastCheckAt(String site) {
        return Optional.empty();
    }

    @Override
    public Optional<Instant> oldestPublicationAt(String site) {
        return Optional.empty();
    }

    @Override
    public Optional<CompletedPublication> lastCompletedPublicationOf(String site) {
        return Optional.empty();
    }

    @Override
    public List<String> prefixesBeyondRetention(PartKey part, int keep) {
        return List.of();
    }

    @Override
    public void forgetObjectPrefix(String objectPrefix) {
        // Nothing was published.
    }

    @Override
    public Set<Long> runningIds() {
        return Set.of();
    }

    @Override
    public int deleteFinishedBefore(Instant finishedBefore) {
        return 0;
    }
}
