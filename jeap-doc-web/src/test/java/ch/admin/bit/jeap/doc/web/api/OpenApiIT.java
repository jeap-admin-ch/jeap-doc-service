package ch.admin.bit.jeap.doc.web.api;

import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API documentation is served by the jEAP Swagger starter, which is switched on with
 * {@code jeap.swagger.status} - see the test configuration.
 */
class OpenApiIT extends DocServiceIntegrationTestBase {

    private static final String UPLOAD_PARAMETERS = "$.paths['/api/uploads/docs/{uploadId}'].put.parameters[*].name";

    private static final String VALIDATION = "$.paths['/api/uploads/docs/validation'].post";

    private static final String VALIDATION_PARAMETERS = VALIDATION + ".parameters[*].name";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocs_whenSwaggerIsOpen_thenDescribesTheUploadEndpoint() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("jEAP Doc Service API"))
                .andExpect(jsonPath("$.paths['/api/uploads/docs/{uploadId}'].put").exists());
    }

    /**
     * The validation endpoint and the parameters it accepts - and, just as much, the ones it does not: a
     * pipeline reads this to know that a path tree is asked about with eight parameters and not with the
     * seventeen of an upload.
     */
    @Test
    void apiDocs_whenSwaggerIsOpen_thenDescribesTheStructureValidation() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/uploads/docs/validation'].post").exists())
                .andExpect(jsonPath(VALIDATION_PARAMETERS).value(hasItems(
                        "type", "system", "component", "library", "template", "source-format", "location",
                        "topic")))
                // One matcher per name, and not not(hasItems(a, b, c, d)): hasItems is every one of them, so
                // negating it holds as soon as a single one is absent - and the endpoint could start taking
                // the other three with this test still green.
                .andExpect(jsonPath(VALIDATION_PARAMETERS).value(not(hasItem("version"))))
                .andExpect(jsonPath(VALIDATION_PARAMETERS).value(not(hasItem("source-revision"))))
                .andExpect(jsonPath(VALIDATION_PARAMETERS).value(not(hasItem("build-url"))))
                .andExpect(jsonPath(VALIDATION_PARAMETERS).value(not(hasItem("site"))));
    }

    /**
     * <b>Both answers of the validation endpoint, with their schemas.</b> A pipeline is documented to branch
     * on the {@code 200} and the {@code 422}, so the report and the problem document have to be in the
     * specification rather than an untyped object - which is what a handler returning {@code ResponseEntity}
     * of anything leaves behind.
     */
    @Test
    void apiDocs_whenSwaggerIsOpen_thenDescribesBothAnswersOfTheStructureValidation() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(VALIDATION + ".requestBody.content['application/json'].schema['$ref']")
                        .value(containsString("PathTreeDto")))
                .andExpect(jsonPath(VALIDATION + ".responses['200'].content['application/json'].schema['$ref']")
                        .value(containsString("StructureReportDto")))
                .andExpect(jsonPath(VALIDATION + ".responses['422'].content['application/problem+json']")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.StructureReportDto.properties.findings").exists())
                .andExpect(jsonPath("$.components.schemas.StructureFindingDto.properties.code").exists());
    }

    /** And the architecture import, which is administered beside the sites rather than below one. */
    @Test
    void apiDocs_whenSwaggerIsOpen_thenDescribesTheArchitectureImports() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/architecture/imports'].post").exists())
                .andExpect(jsonPath("$.paths['/api/architecture/environments'].get").exists())
                .andExpect(jsonPath("$.paths['/api/architecture/environments/{environment}/imports'].post")
                        .exists());
    }

    @Test
    void apiDocs_whenSwaggerIsOpen_thenDescribesTheSiteAdministration() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/sites'].get").exists())
                .andExpect(jsonPath("$.paths['/api/sites/{site}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/sites/{site}/builds'].post").exists())
                .andExpect(jsonPath("$.paths['/api/sites/{site}/builds'].get").exists())
                .andExpect(jsonPath("$.paths['/api/sites/{site}/builds/{buildId}'].get").exists());
    }

    @Test
    void apiDocs_whenSwaggerIsOpen_thenNamesTheUploadParameters() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(UPLOAD_PARAMETERS).value(hasItems(
                        "uploadId", "site", "type", "system", "component", "library", "template", "source-format",
                        "location", "topic", "label", "source-repository", "source-revision", "source-ref",
                        "source-timestamp", "version", "build-url", "generated-at")));
    }
}
