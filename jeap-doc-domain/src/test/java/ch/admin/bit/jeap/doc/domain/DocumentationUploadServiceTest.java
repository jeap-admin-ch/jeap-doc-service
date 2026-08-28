package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationBundleStorage;
import ch.admin.bit.jeap.doc.domain.port.DocumentationSubjectRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationUploadRepository;
import ch.admin.bit.jeap.doc.domain.port.StoredBundle;
import ch.admin.bit.jeap.doc.domain.port.UploadClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentationUploadServiceTest {

    private static final UUID UPLOAD_ID = UUID.fromString("8f1c9a2e-6a1a-4a5f-9a5e-2b0f9a3c1d77");
    private static final Instant NOW = Instant.parse("2026-08-24T09:12:00Z");
    private static final byte[] BUNDLE = "a bundle".getBytes(StandardCharsets.UTF_8);
    private static final String OBJECT_KEY = "uploads/docs/42/1/bundle.zip";
    private static final StoredBundle STORED = new StoredBundle(OBJECT_KEY,
            "6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b");

    @Mock
    private DocumentationUploadRepository uploadRepository;
    @Mock
    private DocumentationSubjectRepository subjectRepository;
    @Mock
    private DocumentationBundleStorage bundleStorage;
    @Mock
    private DocumentationBuildTrigger buildTrigger;

    private DocumentationUploadService service;
    private RecordingUploadMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new RecordingUploadMetrics();
        service = new DocumentationUploadService(uploadRepository, subjectRepository, bundleStorage,
                new UploadProperties(), new DocumentationSites(new SiteProperties()), buildTrigger,
                metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * The order is the point: the upload is recorded before a byte of the bundle is read, so a bundle on its way
     * is a visible state rather than an object nobody knows about.
     */
    @Test
    void receive_whenTheUploadIsNew_thenRecordedBeforeItIsStoredAndPendingAfterwards() {
        DocumentationUpload claimed = claimed();
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(subjectRepository.findOrCreate(any(), eq(NOW))).thenAnswer(call -> call.getArgument(0));
        when(uploadRepository.claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any()))
                .thenReturn(new UploadClaim.Claimed(claimed));
        when(bundleStorage.store(eq(42L), eq(1), any(), anyLong())).thenReturn(STORED);
        when(uploadRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        UploadReceipt receipt = service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length);
        DocumentationUpload received = receipt.upload();

        InOrder inOrder = inOrder(uploadRepository, bundleStorage);
        inOrder.verify(uploadRepository).claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any());
        inOrder.verify(bundleStorage).store(eq(42L), eq(1), any(), eq((long) BUNDLE.length));
        inOrder.verify(uploadRepository).save(any());
        assertThat(receipt.stored()).isTrue();
        assertThat(received.isPending()).isTrue();
        assertThat(received.objectKey()).isEqualTo(OBJECT_KEY);
        assertThat(received.bundleSha256()).isEqualTo(STORED.sha256());
        assertThat(received.sizeInBytes()).isEqualTo(BUNDLE.length);
    }

    @Test
    void receive_whenTheUploadIsAlreadyStored_thenNothingIsWrittenAndTheStoredResultIsAnswered() {
        DocumentationUpload stored = claimed().completed(STORED, BUNDLE.length, NOW);
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.of(stored));
        InputStream bundle = bundle();

        UploadReceipt receipt = service.receive(UPLOAD_ID, descriptor().build(), bundle, BUNDLE.length);

        assertThat(receipt.stored()).isFalse();
        assertThat(receipt.upload()).isEqualTo(stored);
        verifyNoInteractions(bundleStorage, subjectRepository);
        verify(uploadRepository, never()).save(any());
        assertThat(bundle).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ByteArrayInputStream.class))
                .satisfies(drained -> assertThat(drained.available()).isZero());
    }

    @Test
    void receive_whenAnotherAttemptIsInFlight_thenRejectedWithHowLongToWait() {
        DocumentationUpload inFlight = claimed();
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.of(inFlight));
        when(subjectRepository.findOrCreate(any(), eq(NOW))).thenAnswer(call -> call.getArgument(0));
        when(uploadRepository.claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any()))
                .thenReturn(new UploadClaim.InProgress(inFlight));

        assertThatThrownBy(() -> service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length))
                .isInstanceOfSatisfying(InvalidUploadException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.UPLOAD_IN_PROGRESS);
                    assertThat(e.getRetryAfter()).isEqualTo(Duration.ofMinutes(2));
                });
        verifyNoInteractions(bundleStorage);
    }

    /**
     * The upload was not stored when it was looked up, but another attempt stored it before this one could claim
     * it. Nothing is written, and the caller is answered like any other repetition.
     */
    @Test
    void receive_whenAnotherAttemptStoresItFirst_thenAnsweredAsARepetition() {
        DocumentationUpload stored = claimed().completed(STORED, BUNDLE.length, NOW);
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(subjectRepository.findOrCreate(any(), eq(NOW))).thenAnswer(call -> call.getArgument(0));
        when(uploadRepository.claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any()))
                .thenReturn(new UploadClaim.AlreadyCompleted(stored));

        UploadReceipt receipt = service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length);

        assertThat(receipt.stored()).isFalse();
        assertThat(receipt.upload()).isEqualTo(stored);
        verifyNoInteractions(bundleStorage);
        verify(uploadRepository, never()).save(any());
    }

    /**
     * The attempt that holds the upload id started so long ago that its timeout has passed - the caller is still
     * told to come back, and not in a negative number of seconds.
     */
    @Test
    void receive_whenTheAttemptInFlightIsAlreadyPastItsTimeout_thenToldToRetryImmediately() {
        DocumentationUpload inFlight = new DocumentationUpload(42L, UPLOAD_ID, null, descriptor().build(),
                UploadState.UPLOADING, null, null, 0, 1, NOW.minus(Duration.ofHours(1)), null, null);
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(subjectRepository.findOrCreate(any(), eq(NOW))).thenAnswer(call -> call.getArgument(0));
        when(uploadRepository.claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any()))
                .thenReturn(new UploadClaim.InProgress(inFlight));

        assertThatThrownBy(() -> service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length))
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(1)));
    }

    /**
     * The bundle, not the service, is at fault - a body that is not as long as it announced, or longer than the
     * service accepts. The caller has to hear that reason, so it must not be reported as a failing service.
     */
    @Test
    void receive_whenTheBundleItselfIsRejected_thenTheReasonReachesTheCallerUnchanged() {
        DocumentationUpload claimed = claimed();
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(subjectRepository.findOrCreate(any(), eq(NOW))).thenAnswer(call -> call.getArgument(0));
        when(uploadRepository.claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any()))
                .thenReturn(new UploadClaim.Claimed(claimed));
        when(bundleStorage.store(anyLong(), anyInt(), any(), anyLong())).thenThrow(new InvalidUploadException(
                InvalidUploadException.Code.CONTENT_LENGTH_MISMATCH, "the bundle is shorter than announced"));

        assertThatThrownBy(() -> service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length))
                .isInstanceOfSatisfying(InvalidUploadException.class, e -> assertThat(e.getCode())
                        .isEqualTo(InvalidUploadException.Code.CONTENT_LENGTH_MISMATCH));

        verify(uploadRepository).save(claimed.failed("the bundle is shorter than announced"));
    }

    @Test
    void receive_whenStoringFailsWithoutAMessage_thenStillRecordedAsFailed() {
        DocumentationUpload claimed = claimed();
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(subjectRepository.findOrCreate(any(), eq(NOW))).thenAnswer(call -> call.getArgument(0));
        when(uploadRepository.claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any()))
                .thenReturn(new UploadClaim.Claimed(claimed));
        when(bundleStorage.store(anyLong(), anyInt(), any(), anyLong())).thenThrow(new IllegalStateException());

        assertThatThrownBy(() -> service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length))
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.STORAGE_FAILED));

        verify(uploadRepository).save(claimed.failed("The bundle could not be stored."));
    }

    @Test
    void receive_whenTheUploadIdDescribesSomethingElse_thenRejected() {
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.of(claimed()));

        assertThatThrownBy(() -> service.receive(UPLOAD_ID, descriptor().buildUrl("https://github.com/wvs/foo-bar-scs/actions/runs/1234567891").build(),
                bundle(), BUNDLE.length))
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.UPLOAD_ID_CONFLICT));
        verifyNoInteractions(bundleStorage, subjectRepository);
    }

    @Test
    void receive_whenStoringFails_thenRecordedAsFailedAndAnswered() {
        DocumentationUpload claimed = claimed();
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(subjectRepository.findOrCreate(any(), eq(NOW))).thenAnswer(call -> call.getArgument(0));
        when(uploadRepository.claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any()))
                .thenReturn(new UploadClaim.Claimed(claimed));
        when(bundleStorage.store(anyLong(), anyInt(), any(), anyLong())).thenThrow(new IllegalStateException("no storage"));

        assertThatThrownBy(() -> service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length))
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.STORAGE_FAILED));

        // What the storage said is in the log; what is recorded - and answered - are the service's own words.
        verify(uploadRepository).save(claimed.failed("The bundle could not be stored."));
    }

    @Test
    void statusOf_whenTheUploadBelongsToAnotherSystem_thenAnsweredAsUnknown() {
        DocumentationUpload stored = claimed().completed(STORED, BUNDLE.length, NOW);
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.of(stored));

        assertThat(service.statusOf(UPLOAD_ID, "wvs")).contains(stored);
        assertThat(service.statusOf(UPLOAD_ID, "othersystem")).isEmpty();
    }

    @Test
    void statusOf_whenTheUploadIsUnknown_thenEmpty() {
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());

        assertThat(service.statusOf(UPLOAD_ID, "wvs")).isEmpty();
    }

    private static DocumentationUpload claimed() {
        DocumentationUploadDescriptor descriptor = descriptor().build();
        return new DocumentationUpload(42L, UPLOAD_ID, DocumentationSubject.of(descriptor), descriptor,
                UploadState.UPLOADING, null, null, 0, 1, NOW, null, null);
    }

    /**
     * The one line that connects the upload API to publication. Without it every uploaded document is stored
     * and never published, and nothing anywhere says so - which is why it is asserted here rather than left to
     * the integration tests, where the runner is deliberately not ticking.
     */
    @Test
    void receive_whenTheUploadIsStored_thenABuildOfItsSiteIsAskedFor() {
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(uploadRepository.claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any()))
                .thenReturn(new UploadClaim.Claimed(claimed()));
        when(bundleStorage.store(eq(42L), eq(1), any(), anyLong())).thenReturn(STORED);
        when(uploadRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length);

        verify(buildTrigger).requestBecauseOfUpload(Site.DEFAULT_SITE);
        assertThat(metrics.results).containsExactly("stored:COMPONENT_DOCS:" + BUNDLE.length);
    }

    /**
     * A repetition writes nothing, but it does ask for a build. The request is one row per site however often
     * it is asked for, so this costs nothing - and it is what makes a retry repair a trigger that was lost
     * because the first attempt stored the bundle and then failed to ask for one.
     */
    @Test
    void receive_whenTheUploadIsARepetition_thenNothingIsWrittenButABuildIsStillAskedFor() {
        when(uploadRepository.findByUploadId(UPLOAD_ID))
                .thenReturn(Optional.of(claimed().completed(STORED, BUNDLE.length, NOW)));

        service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length);

        verify(uploadRepository, never()).save(any());
        verifyNoInteractions(bundleStorage);
        verify(buildTrigger).requestBecauseOfUpload(Site.DEFAULT_SITE);
        assertThat(metrics.results).containsExactly("repeated:COMPONENT_DOCS");
    }

    /**
     * The bundle is stored and the upload recorded before the build is asked for, so a failure there must not
     * become a 500 for an upload that worked - the client would retry, and the retry is what repairs it.
     */
    @Test
    void receive_whenAskingForABuildFails_thenTheUploadIsStillAnsweredAsStored() {
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(uploadRepository.claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any()))
                .thenReturn(new UploadClaim.Claimed(claimed()));
        when(bundleStorage.store(eq(42L), eq(1), any(), anyLong())).thenReturn(STORED);
        when(uploadRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doThrow(new IllegalStateException("the database went away"))
                .when(buildTrigger).requestBecauseOfUpload(anyString());

        UploadReceipt receipt = service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length);

        assertThat(receipt.stored()).isTrue();
        assertThat(metrics.results).containsExactly("stored:COMPONENT_DOCS:" + BUNDLE.length);
    }

    @Test
    void receive_whenTheSiteIsNotConfigured_thenNoBuildIsAskedForAndItIsCountedAsARefusal() {
        DocumentationUploadDescriptor unknownSite = descriptor().site("a-site-nobody-configured").build();

        assertThatThrownBy(() -> service.receive(UPLOAD_ID, unknownSite, bundle(), BUNDLE.length))
                .isInstanceOf(InvalidUploadException.class);

        verify(buildTrigger, never()).requestBecauseOfUpload(anyString());
        assertThat(metrics.results).containsExactly("failed:COMPONENT_DOCS:UNKNOWN_SITE");
    }

    private static InputStream bundle() {
        return new ByteArrayInputStream(BUNDLE);
    }

    private static DocumentationUploadDescriptor.DocumentationUploadDescriptorBuilder descriptor() {
        return DocumentationUploadDescriptor.builder()
                .type(DocumentationType.COMPONENT_DOCS)
                .system("wvs")
                .component("foo-bar-scs")
                .version("1.4.0")
                .template("arc42")
                .sourceFormat(SourceFormat.MARKDOWN)
                .sourceRepository("ssh://git@bitbucket.example.ch/wvs/foo-bar-scs.git")
                .sourceRevision("9a1c2f8")
                .sourceRef("main")
                .sourceTimestamp(Instant.parse("2026-08-21T07:12:00Z"))
                .buildUrl("https://github.com/wvs/foo-bar-scs/actions/runs/1234567890");
    }

    /**
     * Which sites exist is configuration. An upload naming anything else is refused rather than stored: a typo
     * in a doc workflow would otherwise be answered with a 2xx, put a bundle in the object storage and be
     * published nowhere - the failure nobody notices, because there is nothing to see.
     */
    @Test
    void receive_whenTheSiteIsNotConfigured_thenItIsRefusedAndNothingIsStored() {
        DocumentationUploadDescriptor unknownSite = descriptor().site("a-site-nobody-configured").build();

        assertThatThrownBy(() -> service.receive(UPLOAD_ID, unknownSite, bundle(), BUNDLE.length))
                .isInstanceOf(InvalidUploadException.class)
                .extracting(failure -> ((InvalidUploadException) failure).getCode())
                .isEqualTo(InvalidUploadException.Code.UNKNOWN_SITE);

        verifyNoInteractions(bundleStorage);
        verify(uploadRepository, never()).claim(any(), any(), any(), any(), any());
    }

    /**
     * The message has to say what does exist, or the pipeline that made the typo has nothing to go on.
     */
    @Test
    void receive_whenTheSiteIsNotConfigured_thenTheReasonNamesTheSitesThatAre() {
        DocumentationUploadDescriptor unknownSite = descriptor().site("a-site-nobody-configured").build();

        assertThatThrownBy(() -> service.receive(UPLOAD_ID, unknownSite, bundle(), BUNDLE.length))
                .hasMessageContaining("a-site-nobody-configured")
                .hasMessageContaining(Site.DEFAULT_SITE);
    }
}
