package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The structure validation endpoint, over HTTP.
 * <p>
 * What the rules are is {@code StructureValidationTest}'s business. What is asserted here is the contract a
 * doc pipeline branches on: which status, which body shape, which parameters are accepted, and who may ask.
 */
class DocumentationValidationIT extends DocServiceIntegrationTestBase {

    private static final String SYSTEM = "orders";
    private static final String PATH = "/api/uploads/docs/validation";

    @Autowired
    private MockMvc mockMvc;

    private static MockHttpServletRequestBuilder validationOf(String body) {
        return post(PATH)
                .param("type", "system-docs")
                .param("system", SYSTEM)
                .param("template", "arc42")
                .param("source-format", "markdown")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static RequestPostProcessor mayUpload() {
        return authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")));
    }

    /**
     * A tree that follows the template is {@code 200}, and the report says what is allowed so that a workflow
     * can print it once rather than have the service repeat it in every message.
     */
    @Test
    void aTreeThatFollowsArc42_isAnsweredWithTwoHundred() throws Exception {
        mockMvc.perform(validationOf("""
                        {"paths": ["1-intro/goals.md", "5-building-block-view/design.md"]}""").with(mayUpload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template").value("arc42"))
                .andExpect(jsonPath("$.pathsChecked").value(2))
                .andExpect(jsonPath("$.pathsIgnored").value(0))
                .andExpect(jsonPath("$.findings").isEmpty())
                .andExpect(jsonPath("$.allowedFolders").value(hasItem("9-architecture-decision-records")))
                .andExpect(jsonPath("$.allowedExtensions").value(hasItem("md")));
    }

    /**
     * <b>A misfiled tree is 422, with the problem document the upload API already answers with.</b> The
     * verdict is the status line, so a pipeline branches on it: 200 publish, 422 print and stop, anything else
     * fail loudly because the endpoint or the token is wrong.
     */
    @Test
    void aMisfiledTree_isAnsweredWithFourTwentyTwoAndTheFindings() throws Exception {
        mockMvc.perform(validationOf("""
                        {"paths": ["4-runtime-view/reactions.md",
                                   "5-building-block-view/whitebox-view.md",
                                   "1-intro/_draft.md"]}""").with(mayUpload()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(DocumentationValidationController.PROBLEM_TYPE))
                .andExpect(jsonPath("$.title").value("The documentation structure is invalid"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("3 problems in 3 paths."))
                .andExpect(jsonPath("$.template").value("arc42"))
                .andExpect(jsonPath("$.findingsOmitted").value(0))
                .andExpect(jsonPath("$.findings[*].code")
                        .value(hasItem("UNKNOWN_CHAPTER")))
                .andExpect(jsonPath("$.findings[*].code").value(hasItem("RESERVED_NAME")))
                .andExpect(jsonPath("$.findings[*].code").value(hasItem("UNPUBLISHABLE_NAME")));
    }

    /** The files a ZIP carries that nobody wrote are dropped, and counted rather than hidden. */
    @Test
    void theToolingArtefacts_areIgnoredAndCounted() throws Exception {
        mockMvc.perform(validationOf("""
                        {"paths": ["1-intro/goals.md", "1-intro/.DS_Store", "__MACOSX/1-intro/goals.md"]}""")
                        .with(mayUpload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pathsChecked").value(1))
                .andExpect(jsonPath("$.pathsIgnored").value(2));
    }

    /** The gap this story closes: an upload naming a template nobody implements is accepted today. */
    @Test
    void aTemplateNobodyImplements_isRefused() throws Exception {
        mockMvc.perform(post(PATH)
                        .param("type", "system-docs").param("system", SYSTEM)
                        .param("template", "arc24").param("source-format", "markdown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paths": ["1-intro/goals.md"]}""")
                        .with(mayUpload()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.findings[0].code").value("UNKNOWN_TEMPLATE"))
                .andExpect(jsonPath("$.findings[0].message").value(
                        org.hamcrest.Matchers.containsString("arc42")));
    }

    @Test
    void aMissingParameter_isFourHundredWithTheUploadProblemDocument() throws Exception {
        mockMvc.perform(post(PATH)
                        .param("system", SYSTEM).param("template", "arc42")
                        .param("source-format", "markdown")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paths\": []}")
                        .with(mayUpload()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.type").value(UploadProblems.PROBLEM_TYPE));
    }

    /**
     * <b>A parameter this endpoint does not accept is a typo, and being told so is the point.</b> A path tree
     * does not depend on a commit hash, so the provenance parameters of an upload are refused here - which is
     * why the validation path carries an interceptor of its own.
     */
    @Test
    void aParameterOnlyAnUploadNeeds_isRefused() throws Exception {
        mockMvc.perform(validationOf("""
                        {"paths": ["1-intro/goals.md"]}""")
                        .param("source-revision", "0123456789abcdef")
                        .with(mayUpload()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PARAMETER"));
    }

    @Test
    void aTypeThatIsNotOneOfTheKnownOnes_isRefused() throws Exception {
        mockMvc.perform(post(PATH)
                        .param("type", "handbook-docs").param("system", SYSTEM)
                        .param("template", "arc42").param("source-format", "markdown")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paths\": []}")
                        .with(mayUpload()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"));
    }

    /** A tree of that size is a path pointing at more than the documentation, so it is refused. */
    @Test
    void morePathsThanTheLimit_isRefusedRatherThanAnswered() throws Exception {
        String paths = IntStream.rangeClosed(0, 10_000)
                .mapToObj(index -> "\"1-intro/page-%d.md\"".formatted(index))
                .collect(Collectors.joining(","));

        mockMvc.perform(validationOf("{\"paths\": [" + paths + "]}").with(mayUpload()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("TOO_MANY_PATHS"));
    }

    /**
     * <b>An oversized body is refused before it is read.</b> {@code max-paths} alone bounds nothing: it is
     * counted in the handler, and by then the whole array is in the heap - on a container that is also
     * running a Docusaurus build, that is the instance's memory.
     * <p>
     * The body here is small and its announced length is not: what is under test is that the length decides,
     * which is the only way to refuse a gigabyte without reading it.
     */
    /**
     * <b>An oversized body is refused on its length, not on its content.</b> This body carries more than
     * {@code max-paths} paths as well, so without the guard the same request would be answered
     * {@code TOO_MANY_PATHS} - by the handler, after Jackson had put all eleven megabytes in the heap. That
     * the code is {@code SIZE_LIMIT_EXCEEDED} is what says the guard ran first.
     * <p>
     * What makes it *before the body is read* rather than merely first is that the guard is a
     * {@code HandlerInterceptor}: {@code preHandle} runs before the argument resolvers, so nothing has asked
     * for the body yet. {@code ValidationBodySizeInterceptorTest} covers the arithmetic.
     */
    @Test
    void aBodyLargerThanAnyListOfPaths_isRefusedBeforeItsContentDecides() throws Exception {
        String path = "1-intro/" + "p".repeat(990) + ".md";
        String paths = IntStream.rangeClosed(0, 11_000)
                .mapToObj(index -> "\"" + path + "\"")
                .collect(Collectors.joining(","));

        mockMvc.perform(validationOf("{\"paths\": [" + paths + "]}").with(mayUpload()))
                .andExpect(status().isPayloadTooLarge())
                // The length decided, not the count: TOO_MANY_PATHS here would mean the body was parsed.
                .andExpect(jsonPath("$.code").value("SIZE_LIMIT_EXCEEDED"));
    }

    /** An unknown template is refused before a path is looked at, so the detail does not claim otherwise. */
    @Test
    void anUnknownTemplate_saysHowManyProblemsAndNotHowManyPaths() throws Exception {
        mockMvc.perform(post(PATH)
                        .param("type", "system-docs").param("system", SYSTEM)
                        .param("template", "arc24").param("source-format", "markdown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paths": ["1-intro/goals.md", "5-building-block-view/design.md"]}""")
                        .with(mayUpload()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("1 problem."))
                .andExpect(jsonPath("$.pathsChecked").value(0));
    }

    @Test
    void withoutAToken_theEndpointIsRefused() throws Exception {
        mockMvc.perform(validationOf("{\"paths\": []}")).andExpect(status().isUnauthorized());
    }

    /** The role is granted per system, and this endpoint is checked against the one the request names. */
    @Test
    void withTheRoleForAnotherSystem_theEndpointIsForbidden() throws Exception {
        mockMvc.perform(validationOf("{\"paths\": []}")
                        .with(authentication(tokenWithRoles(uploadsRole("shipping", "write")))))
                .andExpect(status().isForbidden());
    }

    /** Reading a site is not uploading to one: the validation is what a pipeline does before it uploads. */
    @Test
    void withOnlyTheReadRole_theEndpointIsForbidden() throws Exception {
        mockMvc.perform(validationOf("{\"paths\": []}")
                        .with(authentication(tokenWithRoles(sitesRole("read")))))
                .andExpect(status().isForbidden());
    }
}
