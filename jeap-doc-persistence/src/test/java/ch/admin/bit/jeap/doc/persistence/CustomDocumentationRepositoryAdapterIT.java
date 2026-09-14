package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.custom.CustomDocumentation;
import ch.admin.bit.jeap.doc.domain.custom.CustomPage;
import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class CustomDocumentationRepositoryAdapterIT extends PostgresTestContainerBase {

    private static final Instant NOW = Instant.parse("2026-09-11T07:30:00Z");
    private static final String SHA256 = "6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b";

    @Autowired
    private CustomDocumentationRepository documentation;

    /** A site of its own per test, so the classes and the tests of this module do not read each other's sets. */
    private final String site = "site-" + UUID.randomUUID();

    private CustomSetKey systemKey() {
        return new CustomSetKey(site, SubjectKind.SYSTEM, "orders", null, SourceFormat.MARKDOWN, "arc42",
                null, null);
    }

    private CustomSetKey componentKey(String component) {
        return new CustomSetKey(site, SubjectKind.COMPONENT, "orders", component, SourceFormat.MARKDOWN,
                "arc42", null, null);
    }

    private static CustomSet setOf(CustomSetKey key, long revision, CustomPage... pages) {
        return new CustomSet(null, key, null, revision, "current/docs/" + revision + "/bundle.zip", SHA256,
                4096,
                new CustomProvenance("orders-docs", "main", "cafebabe", NOW, "1.2.3", NOW), List.of(pages));
    }

    private static CustomPage page(String chapter, String fileName, String title, int position) {
        return new CustomPage(chapter, fileName, title, position, false);
    }

    /** An HTML set, whose identity carries where it is embedded and whose label names it in the menu. */
    private CustomSetKey micrositeKey() {
        return new CustomSetKey(site, SubjectKind.COMPONENT, "orders", "orders-intake", SourceFormat.HTML,
                "arc42", "8-crosscutting-concepts", "configuration-reference");
    }

    private static CustomSet micrositeOf(CustomSetKey key, long revision, String label) {
        return new CustomSet(null, key, label, revision, "current/docs/" + revision + "/bundle.zip", SHA256,
                4096,
                new CustomProvenance("orders-docs", "main", "cafebabe", NOW, "1.2.3", NOW), List.of());
    }

    /**
     * The label is the one thing a microsite carries that neither its key nor its files hold: an HTML set has
     * no pages to take a title from, so without this the menu would have nothing to show.
     */
    @Test
    void replace_whenTheSetIsAMicrosite_thenItsLabelIsStoredAndReadBack() {
        CustomSetKey key = micrositeKey();

        documentation.replace(micrositeOf(key, 1, "Configuration Reference"));

        Optional<CustomSet> stored = documentation.find(key);
        assertThat(stored).isPresent();
        assertThat(stored.get().label()).isEqualTo("Configuration Reference");
    }

    /** A team may rename its microsite, and what the menu shows is what the last upload called it. */
    @Test
    void replace_whenTheMicrositeIsUploadedAgainUnderAnotherLabel_thenTheStoredLabelIsTheNewOne() {
        CustomSetKey key = micrositeKey();
        documentation.replace(micrositeOf(key, 1, "Configuration Reference"));

        documentation.replace(micrositeOf(key, 2, "Configuration"));

        assertThat(documentation.find(key).orElseThrow().label()).isEqualTo("Configuration");
    }

    /** Markdown has no label: every page of such a set carries its own title. */
    @Test
    void replace_whenTheSetIsMarkdown_thenItCarriesNoLabel() {
        CustomSetKey key = systemKey();

        documentation.replace(setOf(key, 1, page("1-intro", "why.md", "Why", 1)));

        assertThat(documentation.find(key).orElseThrow().label()).isNull();
    }

    @Test
    void replace_whenTheSetIsNew_thenStoredWithItsPages() {
        CustomDocumentationRepository.Replaced replaced = documentation.replace(setOf(systemKey(), 1,
                page("1-intro", "goals.md", "Goals", 1),
                page("2-constraints", "given.md", "What was given", 1),
                new CustomPage("2-constraints", "sketch.png", null, 0, true)));

        CustomSet stored = replaced.set();
        assertThat(replaced.previousObjectKey()).describedAs("a new set replaced nothing").isEmpty();
        assertThat(stored.id()).isNotNull();
        assertThat(stored.key()).isEqualTo(systemKey());
        assertThat(stored.pages()).hasSize(3);
        assertThat(documentation.find(systemKey())).isPresent();
        assertThat(documentation.find(systemKey()).orElseThrow().provenance().version()).isEqualTo("1.2.3");
    }

    @Test
    void replace_whenTheSetIsSmaller_thenWhatIsGoneIsGone() {
        documentation.replace(setOf(systemKey(), 1,
                page("1-intro", "goals.md", "Goals", 1),
                page("12-glossary", "terms.md", "Terms", 1)));

        CustomDocumentationRepository.Replaced result = documentation.replace(setOf(systemKey(), 2,
                page("1-intro", "goals.md", "Goals", 1)));

        CustomSet replaced = result.set();
        assertThat(result.previousObjectKey())
                .describedAs("the object the set stopped naming, which only this call can still name")
                .contains("current/docs/1/bundle.zip");
        assertThat(replaced.pages()).extracting(CustomPage::path).containsExactly("1-intro/goals.md");
        assertThat(replaced.revision()).isEqualTo(2);
        assertThat(replaced.objectKey()).describedAs("a replaced set is a new object").contains("/2/");
        CustomSet read = documentation.find(systemKey()).orElseThrow();
        assertThat(read.id()).describedAs("the same set, replaced rather than added").isEqualTo(replaced.id());
        assertThat(read.pages()).hasSize(1);
    }

    @Test
    void replace_whenOnlyTheTemplateDiffers_thenBothSetsStand() {
        CustomSetKey other = new CustomSetKey(site, SubjectKind.SYSTEM, "orders", null,
                SourceFormat.MARKDOWN, "something-else", null, null);

        documentation.replace(setOf(systemKey(), 1, page("1-intro", "goals.md", "Goals", 1)));
        documentation.replace(setOf(other, 2, page("1-intro", "goals.md", "Goals", 1)));

        assertThat(documentation.find(systemKey())).isPresent();
        assertThat(documentation.find(other)).isPresent();
        assertThat(documentation.of(site, "orders").sets())
                .describedAs("a template switch leaves the old set behind, which is why removal is a call")
                .hasSize(2);
    }

    @Test
    void theKey_comparesAMissingNameAsAnEmptyOne() {
        documentation.replace(setOf(systemKey(), 1, page("1-intro", "goals.md", "Goals", 1)));
        documentation.replace(setOf(componentKey("orders-intake"), 2, page("1-intro", "why.md", "Why", 1)));

        assertThat(documentation.find(systemKey())).describedAs("two null names would not conflict in "
                                                               + "PostgreSQL, so the index folds them")
                .isPresent();
        assertThat(documentation.find(componentKey("orders-intake"))).isPresent();
        assertThat(documentation.find(componentKey("orders-dispatch"))).isEmpty();
    }

    @Test
    void of_readsEverythingDocumentedForOneSystem() {
        documentation.replace(setOf(systemKey(), 1, page("1-intro", "goals.md", "Goals", 1)));
        documentation.replace(setOf(componentKey("orders-intake"), 2, page("1-intro", "why.md", "Why", 1)));
        documentation.replace(setOf(new CustomSetKey(site, SubjectKind.LIBRARY, "orders", "orders-client",
                SourceFormat.MARKDOWN, "arc42", null, null), 3, page("1-intro", "use.md", "Use", 1)));

        CustomDocumentation read = documentation.of(site, "orders");

        assertThat(read.documentsTheSystem()).isTrue();
        assertThat(read.documentedComponents()).extracting(CustomSubject::name).containsExactly("orders-intake");
        assertThat(read.libraries()).extracting(CustomSubject::name).containsExactly("orders-client");
        assertThat(read.chapterFoldersOf(componentKey("orders-intake").subject())).containsExactly("1-intro");
    }

    @Test
    void of_whenNothingIsDocumented_thenNothing() {
        assertThat(documentation.of(site, "orders").isEmpty()).isTrue();
    }

    @Test
    void subjectsOf_isEachSubjectOnce_whateverItsSets() {
        documentation.replace(setOf(systemKey(), 1, page("1-intro", "goals.md", "Goals", 1)));
        documentation.replace(setOf(new CustomSetKey(site, SubjectKind.SYSTEM, "orders", null,
                SourceFormat.MARKDOWN, "something-else", null, null), 2,
                page("1-intro", "goals.md", "Goals", 1)));
        documentation.replace(setOf(componentKey("orders-intake"), 3, page("1-intro", "why.md", "Why", 1)));

        assertThat(documentation.subjectsOf(site))
                .describedAs("a subject with two templates is one subject")
                .containsExactly(systemKey().subject(), componentKey("orders-intake").subject());
    }

    /**
     * <b>Two uploads of one set at once.</b> Replacing a set is a row and then its pages: reading the row and
     * writing it separately let both transactions delete the pages of the set and both insert their own, and
     * the set ended up carrying the pages of two uploads under one revision - or, where the two named one
     * file, failing at commit. The row is written in one statement now, which takes its lock, so the second
     * upload waits there and its delete then sees what the first one wrote.
     */
    @Test
    void replace_whenTwoUploadsOfOneSetRunAtOnce_thenTheSetCarriesOneOfThemWhole() throws Exception {
        documentation.replace(setOf(systemKey(), 1, page("1-intro", "goals.md", "Goals", 1)));
        CustomSet first = setOf(systemKey(), 2,
                page("1-intro", "goals.md", "Goals", 1),
                page("2-constraints", "given.md", "What was given", 2));
        CustomSet second = setOf(systemKey(), 3,
                page("1-intro", "goals.md", "Goals", 1),
                page("12-glossary", "terms.md", "Terms", 2));

        CyclicBarrier bothReady = new CyclicBarrier(2);
        ExecutorService uploads = Executors.newFixedThreadPool(2);
        try {
            List<Future<CustomDocumentationRepository.Replaced>> written = uploads.invokeAll(List.of(
                    replacing(first, bothReady), replacing(second, bothReady)));
            for (Future<CustomDocumentationRepository.Replaced> outcome : written) {
                outcome.get(30, TimeUnit.SECONDS);
            }
        } finally {
            uploads.shutdownNow();
        }

        CustomSet read = documentation.find(systemKey()).orElseThrow();
        assertThat(read.revision()).isIn(2L, 3L);
        assertThat(read.pages()).extracting(CustomPage::path)
                .describedAs("the pages of one upload, and not a mixture of the two")
                .containsExactlyInAnyOrderElementsOf(
                        (read.revision() == 2 ? first : second).pages().stream()
                                .map(CustomPage::path).toList());
        assertThat(documentation.of(site, "orders").sets())
                .describedAs("one set, replaced twice")
                .hasSize(1);
    }

    private Callable<CustomDocumentationRepository.Replaced> replacing(CustomSet set, CyclicBarrier ready) {
        return () -> {
            ready.await(30, TimeUnit.SECONDS);
            return documentation.replace(set);
        };
    }

    /**
     * A projection, because it is asked while a request is served: the site partition adds these subjects to
     * the systems of the architecture model. What is read back has to be the subjects and nothing else.
     */
    @Test
    void subjectsOf_isTheSubjectsOfThatSiteAndOfNoOther() {
        documentation.replace(setOf(systemKey(), 1, page("1-intro", "goals.md", "Goals", 1)));
        documentation.replace(setOf(componentKey("orders-intake"), 2, page("1-intro", "why.md", "Why", 1)));
        documentation.replace(setOf(new CustomSetKey("another-" + site, SubjectKind.SYSTEM, "shipping", null,
                SourceFormat.MARKDOWN, "arc42", null, null), 3, page("1-intro", "goals.md", "Goals", 1)));

        assertThat(documentation.subjectsOf(site))
                .describedAs("the system before its components, which a database sorting a null would not do")
                .containsExactly(systemKey().subject(), componentKey("orders-intake").subject());
    }

    /** A build of one landscape has to generate the same site twice, whatever the heap order happens to be. */
    @Test
    void of_thenTheSetsComeBackInAStableOrder() {
        documentation.replace(setOf(componentKey("orders-intake"), 1, page("1-intro", "why.md", "Why", 1)));
        documentation.replace(setOf(systemKey(), 2, page("1-intro", "goals.md", "Goals", 1)));
        documentation.replace(setOf(componentKey("orders-dispatch"), 3, page("1-intro", "why.md", "Why", 1)));
        // Replaced, which is what used to move a row to the end of the heap.
        documentation.replace(setOf(componentKey("orders-intake"), 4, page("1-intro", "why.md", "Why", 1)));

        List<Long> first = documentation.of(site, "orders").sets().stream().map(CustomSet::id).toList();

        assertThat(documentation.of(site, "orders").sets().stream().map(CustomSet::id)).isEqualTo(first);
    }

    @Test
    void remove_takesTheSetAndItsPages() {
        documentation.replace(setOf(systemKey(), 1, page("1-intro", "goals.md", "Goals", 1)));

        assertThat(documentation.remove(systemKey())).isTrue();
        assertThat(documentation.find(systemKey())).isEmpty();
        assertThat(documentation.remove(systemKey())).describedAs("gone already").isFalse();
    }

    @Test
    void removeSubject_takesEverySetOfIt() {
        documentation.replace(setOf(componentKey("orders-intake"), 1, page("1-intro", "why.md", "Why", 1)));
        documentation.replace(setOf(new CustomSetKey(site, SubjectKind.COMPONENT, "orders", "orders-intake",
                SourceFormat.HTML, "arc42", "6-runtime-view", "configuration-reference"), 2,
                page("6-runtime-view", "index.html", null, 0)));
        documentation.replace(setOf(systemKey(), 3, page("1-intro", "goals.md", "Goals", 1)));

        assertThat(documentation.removeSubject(componentKey("orders-intake").subject())).isEqualTo(2);
        assertThat(documentation.of(site, "orders").documentedComponents()).isEmpty();
        assertThat(documentation.find(systemKey())).describedAs("the system's own set is another subject")
                .isPresent();
    }

    /**
     * <b>What the search index run asks for.</b> A microsite's pages are in no content tree, so indexing a
     * site means asking for its microsites once - every subject's, and no markdown set among them.
     */
    @Test
    void micrositesOf_isEveryUploadedMicrositeOfTheSiteAndNothingElse() {
        documentation.replace(setOf(systemKey(), 50, page("1-intro", "goals.md", "Goals", 1)));
        documentation.replace(micrositeOf(micrositeKey(), 51, "Configuration Reference"));
        documentation.replace(micrositeOf(new CustomSetKey(site, SubjectKind.SYSTEM, "orders", null,
                SourceFormat.HTML, "arc42", "6-runtime-view", "traces"), 52, "Traces"));

        assertThat(documentation.micrositesOf(site))
                .extracting(set -> set.key().topic(), CustomSet::label)
                .containsExactly(tuple("configuration-reference", "Configuration Reference"),
                        tuple("traces", "Traces"));
        assertThat(documentation.micrositesOf("another-site")).isEmpty();
    }

    @Test
    void allObjectKeys_namesWhatTheSweepMayNotDelete() {
        documentation.replace(setOf(systemKey(), 42, page("1-intro", "goals.md", "Goals", 1)));

        assertThat(documentation.allObjectKeys()).contains("current/docs/42/bundle.zip");
    }

    @Test
    void aPagesOrder_isTheOneItWasStoredWith() {
        documentation.replace(setOf(systemKey(), 1,
                page("2-constraints", "zulu.md", "Alpha", 1),
                page("2-constraints", "alpha.md", "Zulu", 2)));

        Optional<CustomSet> read = documentation.find(systemKey());

        assertThat(read.orElseThrow().pagesOf("2-constraints"))
                .describedAs("the assigned position decides, not the file name")
                .extracting(CustomPage::fileName).containsExactly("zulu.md", "alpha.md");
    }
}
