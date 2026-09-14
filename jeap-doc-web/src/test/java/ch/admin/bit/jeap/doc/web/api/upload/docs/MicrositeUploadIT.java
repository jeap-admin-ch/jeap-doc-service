package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.MicrositePageText;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import ch.admin.bit.jeap.doc.domain.upload.validation.MicrositeRules;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.uploadOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What an upload of an HTML microsite leaves behind, from the request to the objects in the bucket.
 * <p>
 * A microsite is the one kind of set that is <b>unpacked</b>: it is served file by file to a browser, so its
 * row names the prefix its files lie under rather than one archive, and it records no pages at all - what
 * names it in the navigation is the label its upload carried.
 */
class MicrositeUploadIT extends DocServiceIntegrationTestBase {

    /** A chapter arc42 really has: a location that is not one is refused before anything is stored. */
    private static final String LOCATION = "5-building-block-view";
    private static final String TOPIC = "configuration-reference";
    private static final String LABEL = "Configuration Reference";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomDocumentationRepository documentation;

    @Autowired
    private CustomDocumentationStorage storage;

    /** The parameters of a component's microsite upload, as a build's doc workflow sends them. */
    private static Map<String, String> micrositeUpload() {
        Map<String, String> parameters = new LinkedHashMap<>(DocumentationUploads.componentDocs());
        parameters.put("source-format", "html");
        parameters.put("location", LOCATION);
        parameters.put("topic", TOPIC);
        parameters.put("label", LABEL);
        return parameters;
    }

    private static CustomSetKey key() {
        return new CustomSetKey("default", SubjectKind.COMPONENT, DocumentationUploads.SYSTEM,
                DocumentationUploads.COMPONENT, SourceFormat.HTML, "arc42", LOCATION, TOPIC);
    }

    /** A microsite as a build writes one: the entry point a frame opens, and an asset beside it. */
    private static byte[] micrositeBundle(String title) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            write(zip, "index.html", "<!doctype html><title>" + title + "</title>");
            write(zip, "assets/app.js", "console.log('" + title + "')");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void write(ZipOutputStream zip, String path, String content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        // A fixed time, so the same content is always the same bytes - see DocumentationUploads.
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void upload(byte[] bundle) throws Exception {
        mockMvc.perform(uploadOf(UUID.randomUUID(), micrositeUpload(), bundle)
                        .with(authentication(tokenWithRoles(
                                uploadsRole(DocumentationUploads.SYSTEM, "write")))))
                .andExpect(status().isCreated());
    }

    private static List<String> filesUnder(String prefix) {
        return S3_CLIENT.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(TEST_BUCKET_NAME).prefix(prefix).build())
                .contents().stream().map(S3Object::key).toList();
    }

    @Test
    void upload_ofAMicrosite_thenItsFilesAreCurrentUnderOnePrefix() throws Exception {
        upload(micrositeBundle("Configuration"));

        CustomSet set = documentation.find(key()).orElseThrow();
        assertThat(set.objectKey())
                .describedAs("a prefix rather than one archive, because a microsite is served file by file")
                .endsWith("/files/");
        assertThat(filesUnder(set.objectKey()))
                .describedAs("the files, and the text of its pages, which lies under the same prefix so that "
                             + "removing the set removes it too")
                .containsExactlyInAnyOrder(set.objectKey() + "index.html", set.objectKey() + "assets/app.js",
                        set.objectKey() + MicrositeRules.SEARCH_TEXT);
    }

    /**
     * <b>The text is read once, here.</b> A microsite is served file by file and no build writes its pages
     * into a content tree, so this is the only moment at which its content can reach the search index - and
     * doing it here means it is read once per upload rather than once per publication and environment.
     */
    @Test
    void upload_ofAMicrosite_thenTheTextOfItsPagesIsStoredBesideThem() throws Exception {
        upload(micrositeBundle("Configuration"));

        CustomSet set = documentation.find(key()).orElseThrow();
        assertThat(storage.readSearchText(set.objectKey()))
                .describedAs("the pages a reader reads; a script beside them is not one")
                .containsExactly(new MicrositePageText("index.html", "Configuration", ""));
    }

    @Test
    void upload_ofAMicrosite_thenTheSetCarriesItsLabelAndNoPages() throws Exception {
        upload(micrositeBundle("Configuration"));

        CustomSet set = documentation.find(key()).orElseThrow();
        assertThat(set.label()).describedAs("the only thing that can name it in the navigation")
                .isEqualTo(LABEL);
        assertThat(set.pages()).describedAs("nothing writes a page per file of a microsite").isEmpty();
    }

    /**
     * A team publishes its microsite again, and the revision it replaced is not left in the bucket: the row
     * names the new prefix, so nothing could ever read the old files again.
     */
    @Test
    void upload_ofAMicrositeThatReplacesOne_thenTheFilesOfTheOldRevisionAreGone() throws Exception {
        upload(micrositeBundle("First"));
        String replaced = documentation.find(key()).orElseThrow().objectKey();

        upload(micrositeBundle("Second"));

        String current = documentation.find(key()).orElseThrow().objectKey();
        assertThat(current).isNotEqualTo(replaced);
        assertThat(filesUnder(current)).isNotEmpty();
        assertThat(filesUnder(replaced)).describedAs("every file of it, not only its entry point").isEmpty();
    }
}
