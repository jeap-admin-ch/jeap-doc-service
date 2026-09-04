package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationSubject;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationType;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationUpload;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationUploadDescriptor;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import ch.admin.bit.jeap.doc.domain.upload.UploadState;
import ch.admin.bit.jeap.doc.domain.port.DocumentationSubjectRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationUploadRepository;
import ch.admin.bit.jeap.doc.domain.port.StoredBundle;
import ch.admin.bit.jeap.doc.domain.port.UploadClaim;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationUploadRepositoryAdapterIT extends PostgresTestContainerBase {

    private static final Instant NOW = Instant.parse("2026-08-24T09:12:00Z");
    private static final Duration IN_PROGRESS_TIMEOUT = Duration.ofMinutes(2);
    private static final String BUNDLE_SHA256 = "6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b";

    @Autowired
    private DocumentationUploadRepository uploads;

    @Autowired
    private DocumentationSubjectRepository subjects;

    @Test
    void claim_whenTheUploadIsNew_thenRecordedWithAnIdOfItsOwn() {
        UUID uploadId = UUID.randomUUID();

        UploadClaim claim = claim(uploadId, descriptor().build(), NOW);

        assertThat(claim).isInstanceOf(UploadClaim.Claimed.class);
        DocumentationUpload upload = ((UploadClaim.Claimed) claim).upload();
        assertThat(upload.id()).isNotNull();
        assertThat(upload.state()).isEqualTo(UploadState.UPLOADING);
        assertThat(upload.attempt()).isEqualTo(1);
        assertThat(claim(UUID.randomUUID(), descriptor().build(), NOW))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(UploadClaim.Claimed.class))
                .satisfies(second -> assertThat(second.upload().id()).isGreaterThan(upload.id()));
    }

    @Test
    void findByUploadId_thenTheDescriptorIsTheOneThatWasRecorded() {
        UUID uploadId = UUID.randomUUID();
        DocumentationUploadDescriptor descriptor = descriptor().build();
        claim(uploadId, descriptor, NOW);

        DocumentationUpload found = uploads.findByUploadId(uploadId).orElseThrow();

        assertThat(found.descriptor()).isEqualTo(descriptor);
        assertThat(found.subject().kind()).isEqualTo(SubjectKind.COMPONENT);
        assertThat(found.subject().name()).isEqualTo(descriptor.component());
    }

    @Test
    void claim_whenAnotherAttemptIsInProgress_thenRefused() {
        UUID uploadId = UUID.randomUUID();
        claim(uploadId, descriptor().build(), NOW);

        assertThat(claim(uploadId, descriptor().build(), NOW.plusSeconds(30)))
                .isInstanceOf(UploadClaim.InProgress.class);
    }

    @Test
    void claim_whenTheAttemptInProgressIsAbandoned_thenTakenOver() {
        UUID uploadId = UUID.randomUUID();
        claim(uploadId, descriptor().build(), NOW);
        Instant later = NOW.plus(IN_PROGRESS_TIMEOUT).plusSeconds(1);

        UploadClaim claim = claim(uploadId, descriptor().build(), later);

        assertThat(claim).isInstanceOf(UploadClaim.Claimed.class);
        DocumentationUpload upload = ((UploadClaim.Claimed) claim).upload();
        assertThat(upload.attempt()).isEqualTo(2);
        assertThat(upload.receivedAt()).isEqualTo(later);
    }

    @Test
    void claim_whenThePreviousAttemptFailed_thenClaimedAgain() {
        UUID uploadId = UUID.randomUUID();
        DocumentationUpload claimed = claimed(uploadId);
        uploads.save(claimed.failed("the object storage did not answer"));

        UploadClaim claim = claim(uploadId, descriptor().build(), NOW.plusSeconds(5));

        assertThat(claim).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(UploadClaim.Claimed.class))
                .satisfies(taken -> {
                    assertThat(taken.upload().id()).isEqualTo(claimed.id());
                    assertThat(taken.upload().attempt()).isEqualTo(2);
                    assertThat(taken.upload().failureReason()).isNull();
                });
    }

    @Test
    void claim_whenTheUploadIsStored_thenReportedAsCompleted() {
        UUID uploadId = UUID.randomUUID();
        DocumentationUpload claimed = claimed(uploadId);
        uploads.save(claimed.completed(storedBundleOf(claimed), 4711, NOW));

        assertThat(claim(uploadId, descriptor().build(), NOW.plusSeconds(5)))
                .isInstanceOf(UploadClaim.AlreadyCompleted.class);
    }

    /**
     * Two attempts arriving at the same moment: the database decides, and exactly one of them may store a bundle.
     */
    @Test
    void claim_whenTwoAttemptsArriveAtOnce_thenOnlyOneOfThemClaimsTheUpload() throws Exception {
        UUID uploadId = UUID.randomUUID();
        Callable<UploadClaim> attempt = () -> claim(uploadId, descriptor().build(), NOW);

        List<UploadClaim> outcomes;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<UploadClaim>> futures = executor.invokeAll(List.of(attempt, attempt));
            outcomes = futures.stream().map(DocumentationUploadRepositoryAdapterIT::outcomeOf).toList();
        }

        assertThat(outcomes).filteredOn(UploadClaim.Claimed.class::isInstance).hasSize(1);
    }

    @Test
    void save_whenTheBundleIsStored_thenPendingWithItsKeyAndSize() {
        UUID uploadId = UUID.randomUUID();
        DocumentationUpload claimed = claimed(uploadId);

        uploads.save(claimed.completed(storedBundleOf(claimed), 4711, NOW));

        DocumentationUpload stored = uploads.findByUploadId(uploadId).orElseThrow();
        assertThat(stored.isPending()).isTrue();
        assertThat(stored.objectKey()).isEqualTo("uploads/docs/%d/1/bundle.zip".formatted(claimed.id()));
        assertThat(stored.bundleSha256()).isEqualTo(BUNDLE_SHA256);
        assertThat(stored.sizeInBytes()).isEqualTo(4711);
        assertThat(stored.completedAt()).isEqualTo(NOW);
    }

    /**
     * The attempt that was taken over finishes late: its outcome may not overwrite what the attempt that replaced
     * it recorded, or an upload whose bundle lies in the storage would end up marked as failed.
     */
    @Test
    void save_whenTheAttemptWasTakenOverMeanwhile_thenItsOutcomeIsNotRecorded() {
        UUID uploadId = UUID.randomUUID();
        DocumentationUpload first = claimed(uploadId);
        DocumentationUpload second = ((UploadClaim.Claimed) claim(uploadId, descriptor().build(),
                NOW.plus(IN_PROGRESS_TIMEOUT).plusSeconds(1))).upload();
        uploads.save(second.completed(storedBundleOf(second), 4711, NOW));

        DocumentationUpload afterTheStraggler = uploads.save(first.failed("the object storage did not answer"));

        assertThat(afterTheStraggler.state()).isEqualTo(UploadState.PENDING);
        assertThat(afterTheStraggler.attempt()).isEqualTo(2);
        assertThat(afterTheStraggler.failureReason()).isNull();
        assertThat(uploads.findByUploadId(uploadId).orElseThrow().isPending()).isTrue();
    }

    /**
     * The clean-up forgets what is old, whatever state it is in - a bundle that was never finished as much as one
     * the generator has long taken. What the uploads documented stays: that is the catalogue, not a leftover.
     */
    @Test
    void deleteReceivedBefore_thenOnlyWhatIsOlderIsRemovedAndTheSubjectsRemain() {
        UUID old = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        DocumentationUpload oldUpload = claimed(old, NOW.minus(Duration.ofDays(20)));
        uploads.save(oldUpload.completed(storedBundleOf(oldUpload), 4711, NOW.minus(Duration.ofDays(20))));
        claimed(recent, NOW.minus(Duration.ofDays(2)));

        int removed = uploads.deleteReceivedBefore(NOW.minus(Duration.ofDays(14)));

        assertThat(removed).isPositive();
        assertThat(uploads.findByUploadId(old)).isEmpty();
        assertThat(uploads.findByUploadId(recent)).isNotEmpty();
        assertThat(subjects.findOrCreate(DocumentationSubject.of(descriptor().build()), NOW).id()).isNotNull();
    }

    @Test
    void findOrCreate_whenTheSubjectIsRequestedTwice_thenCreatedOnce() {
        DocumentationSubject subject = new DocumentationSubject(null, "default", SubjectKind.SYSTEM,
                "orders-" + UUID.randomUUID().toString().substring(0, 8), null, null);

        DocumentationSubject created = subjects.findOrCreate(subject, NOW);
        DocumentationSubject foundAgain = subjects.findOrCreate(subject, NOW.plusSeconds(60));

        assertThat(created.id()).isNotNull();
        assertThat(foundAgain.id()).isEqualTo(created.id());
        assertThat(foundAgain.createdAt()).isEqualTo(created.createdAt());
    }

    private static UploadClaim outcomeOf(Future<UploadClaim> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static StoredBundle storedBundleOf(DocumentationUpload upload) {
        return new StoredBundle("uploads/docs/%d/%d/bundle.zip".formatted(upload.id(), upload.attempt()),
                BUNDLE_SHA256);
    }

    private DocumentationUpload claimed(UUID uploadId) {
        return claimed(uploadId, NOW);
    }

    private DocumentationUpload claimed(UUID uploadId, Instant receivedAt) {
        return ((UploadClaim.Claimed) claim(uploadId, descriptor().build(), receivedAt)).upload();
    }

    private UploadClaim claim(UUID uploadId, DocumentationUploadDescriptor descriptor, Instant now) {
        DocumentationSubject subject = subjects.findOrCreate(DocumentationSubject.of(descriptor), now);
        return uploads.claim(uploadId, subject, descriptor, now, now.minus(IN_PROGRESS_TIMEOUT));
    }

    private static DocumentationUploadDescriptor.DocumentationUploadDescriptorBuilder descriptor() {
        return DocumentationUploadDescriptor.builder()
                .type(DocumentationType.COMPONENT_DOCS)
                .system("orders")
                .component("foo-bar-scs")
                .version("1.4.0")
                .template("arc42")
                .sourceFormat(SourceFormat.MARKDOWN)
                .sourceRepository("ssh://git@bitbucket.example.ch/orders/foo-bar-scs.git")
                .sourceRevision("9a1c2f8")
                .sourceRef("main")
                .sourceTimestamp(Instant.parse("2026-08-21T07:12:00Z"))
                .buildUrl("https://github.com/orders/foo-bar-scs/actions/runs/1234567890");
    }
}
