package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.custom.CustomPage;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.componentDocs;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.systemDocs;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.uploadOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What an upload leaves behind: a documentation set that is current, with its files and their order.
 * <p>
 * A subject the architecture model knows and one it does not are the same upload - the doc service does not
 * have to know about a system before its pipeline publishes documentation for it - and both are checked here,
 * because the difference between them shows up when a site is generated and not when a set is stored.
 */
class UploadTakesTheSetOverIT extends DocServiceIntegrationTestBase {

    private static final String SYSTEM = "orders";
    private static final String KNOWN_COMPONENT = "orders-intake";
    private static final String UNKNOWN_SYSTEM = "nobody-has-deployed-this";
    private static final String LIBRARY = "orders-client";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomDocumentationRepository documentation;

    private static CustomSetKey key(SubjectKind kind, String system, String name) {
        return new CustomSetKey("default", kind, system, name, SourceFormat.MARKDOWN, "arc42", null, null);
    }

    /** An archive of the given pages, each with the title its front matter carries. */
    private static byte[] bundleOf(Map<String, String> pagesAndTitles) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> page : pagesAndTitles.entrySet()) {
                ZipEntry entry = new ZipEntry(page.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(("---\ntitle: " + page.getValue() + "\n---\n\n# " + page.getValue() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private void upload(Map<String, String> parameters, byte[] bundle) throws Exception {
        mockMvc.perform(uploadOf(UUID.randomUUID(), parameters, bundle)
                        .with(authentication(tokenWithRoles(uploadsRole(parameters.get("system"), "write")))))
                .andExpect(status().isCreated());
    }

    @Test
    void upload_ofASystemTheModelKnows_thenItsSetIsCurrent() throws Exception {
        upload(systemDocs(), bundleOf(Map.of(
                "1-intro/goals.md", "Goals",
                "2-constraints/given.md", "What was given")));

        CustomSet set = documentation.find(key(SubjectKind.SYSTEM, SYSTEM, null)).orElseThrow();
        assertThat(set.objectKey()).startsWith("current/docs/default/system/orders/");
        assertThat(set.pages()).extracting(CustomPage::path)
                .containsExactlyInAnyOrder("1-intro/goals.md", "2-constraints/given.md");
        assertThat(set.provenance().sourceRef()).isEqualTo("main");
        assertThat(set.provenance().uploadedAt()).isNotNull();
    }

    @Test
    void upload_ofAComponent_thenItsSetIsItsOwn() throws Exception {
        Map<String, String> parameters = componentDocs();
        parameters.put("component", KNOWN_COMPONENT);
        parameters.put("version", "1.4.2");

        upload(parameters, bundleOf(Map.of("1-intro/why.md", "Why it exists")));

        CustomSet set = documentation.find(key(SubjectKind.COMPONENT, SYSTEM, KNOWN_COMPONENT)).orElseThrow();
        assertThat(set.provenance().version()).isEqualTo("1.4.2");
        assertThat(documentation.of("default", SYSTEM).documentedComponents())
                .extracting(CustomSubject::name).contains(KNOWN_COMPONENT);
    }

    /**
     * A docs repository can exist before anything is deployed, and the upload does not ask the architecture
     * model. What that costs on the way to a site is the generator's business, not this endpoint's.
     */
    @Test
    void upload_ofASystemNoArchitectureModelKnows_thenAcceptedAllTheSame() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.put("system", UNKNOWN_SYSTEM);

        upload(parameters, bundleOf(Map.of("1-intro/goals.md", "Goals")));

        assertThat(documentation.find(key(SubjectKind.SYSTEM, UNKNOWN_SYSTEM, null))).isPresent();
        assertThat(documentation.subjectsOf("default"))
                .extracting(CustomSubject::system).contains(UNKNOWN_SYSTEM);
    }

    @Test
    void upload_ofALibrary_thenItsSetIsKeyedAsALibrary() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.put("type", "library-docs");
        parameters.put("library", LIBRARY);
        parameters.put("version", "3.0.1");

        upload(parameters, bundleOf(Map.of(
                "1-intro/what-it-is.md", "What it is",
                "12-glossary/terms.md", "Terms")));

        CustomSet set = documentation.find(key(SubjectKind.LIBRARY, SYSTEM, LIBRARY)).orElseThrow();
        assertThat(set.key().kind()).isEqualTo(SubjectKind.LIBRARY);
        assertThat(documentation.of("default", SYSTEM).libraries())
                .extracting(CustomSubject::name).contains(LIBRARY);
    }

    /**
     * The rule an upload replaces a set by: whole. A page a team deleted is gone from the set, and nothing
     * has to notice that it went.
     */
    @Test
    void upload_ofASmallerSet_thenWhatIsGoneIsGone() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.put("system", "replacing-system");

        upload(parameters, bundleOf(Map.of(
                "1-intro/goals.md", "Goals",
                "12-glossary/terms.md", "Terms")));
        CustomSet first = documentation.find(key(SubjectKind.SYSTEM, "replacing-system", null)).orElseThrow();

        upload(parameters, bundleOf(Map.of("1-intro/goals.md", "Goals")));

        CustomSet replaced = documentation.find(key(SubjectKind.SYSTEM, "replacing-system", null)).orElseThrow();
        assertThat(replaced.id()).describedAs("the same set, replaced").isEqualTo(first.id());
        assertThat(replaced.pages()).extracting(CustomPage::path).containsExactly("1-intro/goals.md");
        assertThat(replaced.objectKey()).describedAs("and a new object, because the upload is in the key")
                .isNotEqualTo(first.objectKey());
    }

    @Test
    void upload_thenThePagesOfAChapterAreOrderedByTheirTitles() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.put("system", "ordering-system");
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("2-constraints/aaa.md", "Zulu");
        pages.put("2-constraints/zzz.md", "Alpha");

        upload(parameters, bundleOf(pages));

        CustomSet set = documentation.find(key(SubjectKind.SYSTEM, "ordering-system", null)).orElseThrow();
        assertThat(set.pagesOf("2-constraints"))
                .describedAs("the file names alone would have given the opposite order")
                .extracting(CustomPage::fileName).containsExactly("zzz.md", "aaa.md");
    }
}
