package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationUploadRepository;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import ch.admin.bit.jeap.doc.domain.upload.UploadState;
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

import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.systemDocs;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.uploadOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A set that would not be published is refused, and refused before anything is stored.
 * <p>
 * The structure rules were written for the validation endpoint, which a pipeline may skip. This is the other
 * caller: what got past that endpoint, or never went through it, must not be able to put a page in a chapter
 * nothing serves.
 */
class UploadValidationEnforcementIT extends DocServiceIntegrationTestBase {

    /** Its own system, so that what this class refuses cannot be confused with another class's sets. */
    private static final String SYSTEM = "refusing-system";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentationUploadRepository uploads;

    @Autowired
    private CustomDocumentationRepository documentation;

    private static byte[] bundleOf(String... paths) {
        Map<String, String> content = new LinkedHashMap<>();
        for (String path : paths) {
            content.put(path, "# a page");
        }
        return bundleOf(content);
    }

    private static byte[] bundleOf(Map<String, String> pathsToContent) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : pathsToContent.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0L);
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private UUID refuse(String finding, String... paths) throws Exception {
        return refuse(finding, bundleOf(paths));
    }

    private UUID refuse(String finding, Map<String, String> pathsToContent) throws Exception {
        return refuse(finding, bundleOf(pathsToContent));
    }

    private UUID refuse(String finding, byte[] bundle) throws Exception {
        UUID uploadId = UUID.randomUUID();
        Map<String, String> parameters = systemDocs();
        parameters.put("system", SYSTEM);

        mockMvc.perform(uploadOf(uploadId, parameters, bundle)
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STRUCTURE_INVALID"))
                .andExpect(jsonPath("$.findings[0].code").value(finding));
        return uploadId;
    }

    @Test
    void upload_ofAPageInAChapterTheTemplateDoesNotHave_isRefused() throws Exception {
        refuse("UNKNOWN_CHAPTER", "13-appendix/extra.md");
    }

    @Test
    void upload_ofAPageAtTheRootOfTheSet_isRefused() throws Exception {
        refuse("FILE_OUTSIDE_CHAPTER", "README.md");
    }

    @Test
    void upload_ofAPageInAFolderInsideAChapter_isRefused() throws Exception {
        refuse("NESTED_FOLDER", "1-intro/deeper/page.md");
    }

    @Test
    void upload_ofAFileNoTemplateAccepts_isRefused() throws Exception {
        refuse("FORBIDDEN_EXTENSION", "1-intro/budget.xlsx");
    }

    /**
     * <b>Blocked server-side, whatever sent it.</b> {@code jeap-python-pipeline-lib} checks the shape of
     * {@code sidebar_position} too, but it is a client a caller can skip - this is the one check every upload
     * goes through, so a value this service cannot read as a number is refused here regardless.
     */
    @Test
    void upload_ofAPageWhoseSidebarPositionIsNotANumber_isRefused() throws Exception {
        refuse("INVALID_POSITION", Map.of("1-intro/goals.md",
                "---\nsidebar_position: banana\n---\n\n# Goals\n"));
    }

    @Test
    void upload_ofAPageNamingBothSidebarAfterAndSidebarBefore_isRefused() throws Exception {
        refuse("INVALID_REFERENCE", Map.of("1-intro/goals.md",
                "---\nsidebar_after: intro\nsidebar_before: outro\n---\n\n# Goals\n"));
    }

    @Test
    void upload_ofAPageWithoutAPlacement_isAccepted() throws Exception {
        UUID uploadId = UUID.randomUUID();
        Map<String, String> parameters = systemDocs();
        parameters.put("system", SYSTEM);

        mockMvc.perform(uploadOf(uploadId, parameters,
                        bundleOf(Map.of("1-intro/goals.md", "---\ntitle: Goals\n---\n\n# Goals\n")))
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isCreated());
    }

    /**
     * The point of refusing before storing: there is nothing to undo. No object, no set, and an upload a
     * retry can take over once the set is fixed.
     */
    @Test
    void upload_thatIsRefused_leavesNoSetAndNoStoredBundle() throws Exception {
        UUID uploadId = refuse("UNKNOWN_CHAPTER", "13-appendix/extra.md");

        assertThat(uploads.findByUploadId(uploadId)).get()
                .satisfies(upload -> {
                    assertThat(upload.state()).isEqualTo(UploadState.FAILED);
                    assertThat(upload.objectKey()).describedAs("nothing was stored").isNull();
                });
        assertThat(documentation.find(new CustomSetKey("default", SubjectKind.SYSTEM, SYSTEM, null,
                SourceFormat.MARKDOWN, "arc42", null, null))).isEmpty();
    }

    /**
     * <b>The half of the refusal contract that was untested.</b> The API promises a 422 leaves an upload a
     * retry can take over once the set is fixed, and nothing re-sent a corrected bundle under the same upload
     * id - the one path that runs through the same-upload check and a claim on a row that is FAILED.
     */
    @Test
    void upload_afterARefusal_canBeRetriedUnderTheSameId() throws Exception {
        UUID uploadId = refuse("UNKNOWN_CHAPTER", "13-appendix/extra.md");
        Map<String, String> parameters = systemDocs();
        parameters.put("system", SYSTEM);

        mockMvc.perform(uploadOf(uploadId, parameters, bundleOf("1-intro/goals.md"))
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isCreated());

        assertThat(uploads.findByUploadId(uploadId)).get()
                .satisfies(upload -> assertThat(upload.state()).isEqualTo(UploadState.PENDING));
        assertThat(documentation.find(new CustomSetKey("default", SubjectKind.SYSTEM, SYSTEM, null,
                SourceFormat.MARKDOWN, "arc42", null, null)))
                .describedAs("and the corrected set is what is published")
                .isPresent();
    }

    @Test
    void upload_thatIsRefused_saysWhichFileAndWhy() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.put("system", SYSTEM);

        mockMvc.perform(uploadOf(UUID.randomUUID(), parameters, bundleOf("13-appendix/extra.md"))
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.findings[0].path").value("13-appendix/extra.md"))
                .andExpect(jsonPath("$.allowedFolders[0]").value("1-intro"))
                .andExpect(jsonPath("$.detail").value(containsString("would not be published")));
    }

    /**
     * A bundle that is not an archive at all cannot be read, and is a different answer from a set that could
     * be read and breaks a rule.
     */
    @Test
    void upload_ofSomethingThatIsNotAnArchive_isRefusedAsSuch() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.put("system", SYSTEM);

        mockMvc.perform(uploadOf(UUID.randomUUID(), parameters,
                                "this is not a ZIP".getBytes(StandardCharsets.UTF_8))
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BUNDLE"));
    }
}
