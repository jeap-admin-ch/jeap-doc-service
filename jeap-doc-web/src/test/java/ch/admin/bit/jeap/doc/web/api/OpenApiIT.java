package ch.admin.bit.jeap.doc.web.api;

import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API documentation is served by the jEAP Swagger starter, which is switched on with
 * {@code jeap.swagger.status} - see the test configuration.
 */
class OpenApiIT extends DocServiceIntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocs_whenSwaggerIsOpen_thenDescribesTheUploadEndpoint() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("jEAP Doc Service API"))
                .andExpect(jsonPath("$.paths['/api/docs/uploads/{uploadId}'].put").exists());
    }
}
