package ch.admin.bit.jeap.doc.domain.upload;

import ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.SearchProperties;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.doc.domain.custom.CustomProperties;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import ch.admin.bit.jeap.doc.domain.port.HtmlText;
import ch.admin.bit.jeap.doc.domain.port.UploadedBundles;
import ch.admin.bit.jeap.doc.domain.upload.validation.FindingCode;
import ch.admin.bit.jeap.doc.domain.upload.validation.StructureFinding;
import ch.admin.bit.jeap.doc.domain.upload.validation.StructureReport;
import ch.admin.bit.jeap.doc.domain.upload.validation.StructureValidation;
import ch.admin.bit.jeap.doc.domain.port.DocumentationSubjectRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationUploadRepository;
import ch.admin.bit.jeap.doc.domain.port.StoredBundle;
import ch.admin.bit.jeap.doc.domain.port.UploadClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
    private UploadedBundles bundles;
    @Mock
    private CustomDocumentationRepository documentation;
    @Mock
    private CustomDocumentationStorage documentationStorage;
    @Mock
    private StructureValidation validation;
    @Mock
    private DocumentationBuildTrigger buildTrigger;
    @Mock
    private HtmlText htmlText;

    private DocumentationUploadService service;
    private RecordingUploadMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new RecordingUploadMetrics();
        service = new DocumentationUploadService(uploadRepository, subjectRepository, bundles, documentation,
                documentationStorage, validation, new UploadProperties(), new CustomProperties(),
                new DocumentationSites(new SiteProperties()), htmlText, new SearchProperties(), buildTrigger,
                metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * A bundle that reads as one page in one chapter, and a validation that accepts it. Every test about the
     * upload path needs both; what the rules are is {@code StructureValidationTest}'s.
     */
    /** A bundle that is read and a set that passes the structure rules. */
    private void acceptsTheSet() {
        when(bundles.receive(any(), anyLong(), any())).thenReturn(new ReceivedOnePage());
        when(validation.validate(any(), any()))
                .thenReturn(new StructureReport("arc42", 1, 0, List.of(), List.of(), List.of(),
                        List.of(), 0));
    }

    /**
     * And the set is taken over: the attempt still holds the upload, and the set it writes replaced nothing.
     * Separate from {@link #acceptsTheSet()}, because the tests about a bundle that is refused never get this
     * far.
     */
    private void takesTheSetOver() {
        when(uploadRepository.isHeldBy(UPLOAD_ID, 1)).thenReturn(true);
        when(documentation.replace(any())).thenAnswer(call ->
                CustomDocumentationRepository.Replaced.first(call.getArgument(0)));
    }

    /** The one page a received bundle holds in these tests. */
    private record ReceivedOnePage() implements UploadedBundles.ReceivedBundle {

        @Override
        public List<String> paths() {
            return List.of("1-intro/goals.md");
        }

        @Override
        public long declaredUnpackedSize() {
            return BUNDLE.length;
        }

        @Override
        public String sha256() {
            return STORED.sha256();
        }

        @Override
        public long sizeInBytes() {
            return BUNDLE.length;
        }

        @Override
        public byte[] head(String path, int maxBytes) {
            return "---\ntitle: Goals\n---\n".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            // nothing to release: this bundle is one page in memory.
        }
    }

    /**
     * A set that would not be published is refused, and refused before anything is stored: the paths are read
     * off the archive on the request thread, so there is no object and no set to undo.
     */
    @Test
    void receive_whenTheSetWouldNotBePublished_thenRefusedAndNothingIsStored() {
        DocumentationUpload claimed = claimed();
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(subjectRepository.findOrCreate(any(), eq(NOW))).thenAnswer(call -> call.getArgument(0));
        when(uploadRepository.claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any()))
                .thenReturn(new UploadClaim.Claimed(claimed));
        when(bundles.receive(any(), anyLong(), any())).thenReturn(new ReceivedOnePage());
        when(validation.validate(any(), any())).thenReturn(new StructureReport("arc42", 1, 0, List.of(),
                List.of(), List.of(), List.of(new StructureFinding(FindingCode.UNKNOWN_CHAPTER,
                "nowhere/page.md", "there is no such chapter")), 0));

        assertThatThrownBy(() -> service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length))
                .isInstanceOfSatisfying(InvalidUploadException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo(InvalidUploadException.Code.STRUCTURE_INVALID);
                    assertThat(refused.getReport()).describedAs("the caller gets the findings, not a sentence")
                            .isNotNull();
                });

        verify(bundles, never()).store(anyLong(), anyInt(), any());
        verifyNoInteractions(documentationStorage, documentation);
        verify(uploadRepository).save(argThat(upload -> upload.state() == UploadState.FAILED));
    }

    /**
     * What <i>taking the set over</i> is: the bundle is copied to the set's own key, and the files it holds
     * are recorded - both before the upload is completed, so an upload that is pending always has a set.
     */
    @Test
    void receive_whenTheSetIsAccepted_thenItIsCurrentBeforeTheUploadIsCompleted() {
        DocumentationUpload claimed = claimed();
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(subjectRepository.findOrCreate(any(), eq(NOW))).thenAnswer(call -> call.getArgument(0));
        when(uploadRepository.claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any()))
                .thenReturn(new UploadClaim.Claimed(claimed));
        acceptsTheSet();
        takesTheSetOver();
        when(bundles.store(eq(42L), eq(1), any())).thenReturn(STORED);
        when(documentationStorage.promote(eq(STORED), any(), eq(42L), eq(1)))
                .thenReturn("current/docs/x/42/1/bundle.zip");
        when(uploadRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length);

        InOrder inOrder = inOrder(bundles, documentationStorage, documentation, uploadRepository);
        inOrder.verify(bundles).store(eq(42L), eq(1), any());
        inOrder.verify(documentationStorage).promote(eq(STORED), any(), eq(42L), eq(1));
        inOrder.verify(documentation).replace(any());
        inOrder.verify(uploadRepository).save(any());
    }

    @Test
    void receive_whenTheSetIsAccepted_thenItCarriesWhereItCameFromAndItsPages() {
        DocumentationUpload claimed = claimed();
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(subjectRepository.findOrCreate(any(), eq(NOW))).thenAnswer(call -> call.getArgument(0));
        when(uploadRepository.claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any()))
                .thenReturn(new UploadClaim.Claimed(claimed));
        acceptsTheSet();
        takesTheSetOver();
        when(bundles.store(eq(42L), eq(1), any())).thenReturn(STORED);
        when(documentationStorage.promote(any(), any(), anyLong(), anyInt()))
                .thenReturn("current/docs/x/42/1/bundle.zip");
        when(uploadRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length);

        ArgumentCaptor<CustomSet> set = ArgumentCaptor.forClass(CustomSet.class);
        verify(documentation).replace(set.capture());
        assertThat(set.getValue().revision()).describedAs("the upload the set came from").isEqualTo(42L);
        assertThat(set.getValue().sha256()).isEqualTo(STORED.sha256());
        assertThat(set.getValue().provenance().sourceRevision()).isEqualTo("9a1c2f8");
        assertThat(set.getValue().provenance().uploadedAt()).isEqualTo(NOW);
        assertThat(set.getValue().pages()).singleElement().satisfies(page -> {
            assertThat(page.chapter()).isEqualTo("1-intro");
            assertThat(page.fileName()).isEqualTo("goals.md");
            assertThat(page.title()).isEqualTo("Goals");
        });
    }

    /**
     * <b>An attempt that was given up on must not publish over the one that took over.</b> Nothing can stop a
     * slow attempt: it keeps running, and by the time it gets here the attempt that replaced it may have made
     * its own bundle current. So the set is not written, and the object this attempt copied is left to the
     * sweep of what nothing references.
     */
    @Test
    void receive_whenTheAttemptWasTakenOverWhileItRan_thenItsSetIsNotMadeCurrent() {
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(subjectRepository.findOrCreate(any(), eq(NOW))).thenAnswer(call -> call.getArgument(0));
        when(uploadRepository.claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any()))
                .thenReturn(new UploadClaim.Claimed(claimed()));
        acceptsTheSet();
        when(uploadRepository.isHeldBy(UPLOAD_ID, 1)).thenReturn(false);
        when(bundles.store(eq(42L), eq(1), any())).thenReturn(STORED);
        when(documentationStorage.promote(any(), any(), anyLong(), anyInt()))
                .thenReturn("current/docs/x/42/1/bundle.zip");
        when(uploadRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length);

        verify(documentation, never()).replace(any());
        verify(documentationStorage, never()).delete(anyString());
    }

    /**
     * The object a replaced set used to lie in is deleted, and only the replacement knows which one it was:
     * the row is what names an object, so once it names the new one the old one cannot be found again. Every
     * re-upload would otherwise double a subject's stored bytes until the nightly sweep.
     */
    @Test
    void receive_whenTheSetReplacesAnother_thenThePredecessorsObjectIsRemoved() {
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(subjectRepository.findOrCreate(any(), eq(NOW))).thenAnswer(call -> call.getArgument(0));
        when(uploadRepository.claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any()))
                .thenReturn(new UploadClaim.Claimed(claimed()));
        acceptsTheSet();
        when(uploadRepository.isHeldBy(UPLOAD_ID, 1)).thenReturn(true);
        when(bundles.store(eq(42L), eq(1), any())).thenReturn(STORED);
        when(documentationStorage.promote(any(), any(), anyLong(), anyInt()))
                .thenReturn("current/docs/x/42/1/bundle.zip");
        when(documentation.replace(any())).thenAnswer(call ->
                new CustomDocumentationRepository.Replaced(call.getArgument(0),
                        Optional.of("current/docs/x/17/1/bundle.zip")));
        when(uploadRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        UploadReceipt receipt = service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length);

        verify(documentationStorage).delete("current/docs/x/17/1/bundle.zip");
        assertThat(receipt.stored()).isTrue();
    }

    /** And an object that will not go is the sweep's problem, not the upload's: the set is already current. */
    @Test
    void receive_whenThePredecessorsObjectCannotBeRemoved_thenTheUploadStillSucceeds() {
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(subjectRepository.findOrCreate(any(), eq(NOW))).thenAnswer(call -> call.getArgument(0));
        when(uploadRepository.claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any()))
                .thenReturn(new UploadClaim.Claimed(claimed()));
        acceptsTheSet();
        when(uploadRepository.isHeldBy(UPLOAD_ID, 1)).thenReturn(true);
        when(bundles.store(eq(42L), eq(1), any())).thenReturn(STORED);
        when(documentationStorage.promote(any(), any(), anyLong(), anyInt()))
                .thenReturn("current/docs/x/42/1/bundle.zip");
        when(documentation.replace(any())).thenAnswer(call ->
                new CustomDocumentationRepository.Replaced(call.getArgument(0),
                        Optional.of("current/docs/x/17/1/bundle.zip")));
        org.mockito.Mockito.doThrow(new IllegalStateException("the bucket went away"))
                .when(documentationStorage).delete(anyString());
        when(uploadRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        UploadReceipt receipt = service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length);

        assertThat(receipt.stored()).isTrue();
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
        acceptsTheSet();
        takesTheSetOver();
        when(bundles.store(eq(42L), eq(1), any())).thenReturn(STORED);
        when(uploadRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        UploadReceipt receipt = service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length);
        DocumentationUpload received = receipt.upload();

        InOrder inOrder = inOrder(uploadRepository, bundles);
        inOrder.verify(uploadRepository).claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any());
        inOrder.verify(bundles).store(eq(42L), eq(1), any());
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
        verifyNoInteractions(bundles, subjectRepository);
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
        verifyNoInteractions(bundles);
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
        verifyNoInteractions(bundles);
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
        acceptsTheSet();
        when(bundles.store(anyLong(), anyInt(), any())).thenThrow(new InvalidUploadException(
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
        acceptsTheSet();
        when(bundles.store(anyLong(), anyInt(), any())).thenThrow(new IllegalStateException());

        assertThatThrownBy(() -> service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length))
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.STORAGE_FAILED));

        verify(uploadRepository).save(claimed.failed("The bundle could not be stored."));
    }

    @Test
    void receive_whenTheUploadIdDescribesSomethingElse_thenRejected() {
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.of(claimed()));

        assertThatThrownBy(() -> service.receive(UPLOAD_ID, descriptor().buildUrl("https://github.com/orders/foo-bar-scs/actions/runs/1234567891").build(),
                bundle(), BUNDLE.length))
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.UPLOAD_ID_CONFLICT));
        verifyNoInteractions(bundles, subjectRepository);
    }

    @Test
    void receive_whenStoringFails_thenRecordedAsFailedAndAnswered() {
        DocumentationUpload claimed = claimed();
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(subjectRepository.findOrCreate(any(), eq(NOW))).thenAnswer(call -> call.getArgument(0));
        when(uploadRepository.claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any()))
                .thenReturn(new UploadClaim.Claimed(claimed));
        acceptsTheSet();
        when(bundles.store(anyLong(), anyInt(), any())).thenThrow(new IllegalStateException("no storage"));

        assertThatThrownBy(() -> service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length))
                .isInstanceOfSatisfying(InvalidUploadException.class,
                        e -> assertThat(e.getCode()).isEqualTo(InvalidUploadException.Code.STORAGE_FAILED));

        // What the storage said is in the log; what is recorded - and answered - are the service's own words.
        verify(uploadRepository).save(claimed.failed("The bundle could not be stored."));
    }

    /**
     * <b>A microsite that unpacks to more than it may is the uploader's to fix.</b> It is unpacked while it is
     * taken over, and that path used to answer every exception as a storage failure - a 500 telling a team to
     * retry an upload that can never succeed, and an error in the log for what is not the service's fault.
     */
    @Test
    void receive_whenAMicrositeUnpacksToTooMuch_thenItIsRefusedAsTooLargeAndNotAsAStorageFailure() {
        DocumentationUploadDescriptor microsite = descriptor().sourceFormat(SourceFormat.HTML)
                .location("8-crosscutting-concepts").topic("reference").label("Reference").build();
        DocumentationUpload claimed = new DocumentationUpload(42L, UPLOAD_ID, DocumentationSubject.of(microsite),
                microsite, UploadState.UPLOADING, null, null, 0, 1, NOW, null, null);
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(subjectRepository.findOrCreate(any(), eq(NOW))).thenAnswer(call -> call.getArgument(0));
        when(uploadRepository.claim(eq(UPLOAD_ID), any(), any(), eq(NOW), any()))
                .thenReturn(new UploadClaim.Claimed(claimed));
        acceptsTheSet();
        when(bundles.store(anyLong(), anyInt(), any())).thenReturn(STORED);
        InvalidUploadException tooLarge = new InvalidUploadException(
                InvalidUploadException.Code.UNPACKS_TO_TOO_MUCH, "The uploaded microsite unpacks to too much.");
        when(documentationStorage.promoteFiles(any(), any(), anyLong(), anyInt(), any())).thenThrow(tooLarge);

        assertThatThrownBy(() -> service.receive(UPLOAD_ID, microsite, bundle(), BUNDLE.length))
                .isInstanceOfSatisfying(InvalidUploadException.class, e -> assertThat(e.getCode())
                        .isEqualTo(InvalidUploadException.Code.UNPACKS_TO_TOO_MUCH));
        verify(uploadRepository).save(claimed.failed("The uploaded microsite unpacks to too much."));
    }

    @Test
    void statusOf_whenTheUploadBelongsToAnotherSystem_thenAnsweredAsUnknown() {
        DocumentationUpload stored = claimed().completed(STORED, BUNDLE.length, NOW);
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.of(stored));

        assertThat(service.statusOf(UPLOAD_ID, "orders")).contains(stored);
        assertThat(service.statusOf(UPLOAD_ID, "othersystem")).isEmpty();
    }

    @Test
    void statusOf_whenTheUploadIsUnknown_thenEmpty() {
        when(uploadRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());

        assertThat(service.statusOf(UPLOAD_ID, "orders")).isEmpty();
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
        acceptsTheSet();
        takesTheSetOver();
        when(bundles.store(eq(42L), eq(1), any())).thenReturn(STORED);
        when(uploadRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length);

        verify(buildTrigger).requestBecauseOfUpload(eq(Site.DEFAULT_SITE), anyString());
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
        verifyNoInteractions(bundles);
        verify(buildTrigger).requestBecauseOfUpload(eq(Site.DEFAULT_SITE), anyString());
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
        acceptsTheSet();
        takesTheSetOver();
        when(bundles.store(eq(42L), eq(1), any())).thenReturn(STORED);
        when(uploadRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doThrow(new IllegalStateException("the database went away"))
                .when(buildTrigger).requestBecauseOfUpload(anyString(), anyString());

        UploadReceipt receipt = service.receive(UPLOAD_ID, descriptor().build(), bundle(), BUNDLE.length);

        assertThat(receipt.stored()).isTrue();
        assertThat(metrics.results).containsExactly("stored:COMPONENT_DOCS:" + BUNDLE.length);
    }

    @Test
    void receive_whenTheSiteIsNotConfigured_thenNoBuildIsAskedForAndItIsCountedAsARefusal() {
        DocumentationUploadDescriptor unknownSite = descriptor().site("a-site-nobody-configured").build();

        InputStream bundle = bundle();

        assertThatThrownBy(() -> service.receive(UPLOAD_ID, unknownSite, bundle, BUNDLE.length))
                .isInstanceOf(InvalidUploadException.class);

        verify(buildTrigger, never()).requestBecauseOfUpload(anyString(), anyString());
        assertThat(metrics.results).containsExactly("failed:COMPONENT_DOCS:UNKNOWN_SITE");
    }

    private static InputStream bundle() {
        return new ByteArrayInputStream(BUNDLE);
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

    /**
     * Which sites exist is configuration. An upload naming anything else is refused rather than stored: a typo
     * in a doc workflow would otherwise be answered with a 2xx, put a bundle in the object storage and be
     * published nowhere - the failure nobody notices, because there is nothing to see.
     */
    @Test
    void receive_whenTheSiteIsNotConfigured_thenItIsRefusedAndNothingIsStored() {
        DocumentationUploadDescriptor unknownSite = descriptor().site("a-site-nobody-configured").build();

        InputStream bundle = bundle();

        assertThatThrownBy(() -> service.receive(UPLOAD_ID, unknownSite, bundle, BUNDLE.length))
                .isInstanceOf(InvalidUploadException.class)
                .extracting(failure -> ((InvalidUploadException) failure).getCode())
                .isEqualTo(InvalidUploadException.Code.UNKNOWN_SITE);

        verifyNoInteractions(bundles);
        verify(uploadRepository, never()).claim(any(), any(), any(), any(), any());
    }

    /**
     * The message has to say what does exist, or the pipeline that made the typo has nothing to go on.
     */
    @Test
    void receive_whenTheSiteIsNotConfigured_thenTheReasonNamesTheSitesThatAre() {
        DocumentationUploadDescriptor unknownSite = descriptor().site("a-site-nobody-configured").build();

        InputStream bundle = bundle();

        assertThatThrownBy(() -> service.receive(UPLOAD_ID, unknownSite, bundle, BUNDLE.length))
                .hasMessageContaining("a-site-nobody-configured")
                .hasMessageContaining(Site.DEFAULT_SITE);
    }
}
