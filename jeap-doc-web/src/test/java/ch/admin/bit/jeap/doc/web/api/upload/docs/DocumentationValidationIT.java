package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
     * The same request with its length withheld, as a chunked one arrives. MockMvc derives the content length
     * from the content it is given, so the request is built here rather than with the builder above.
     */
    private static RequestBuilder chunkedValidationOf(String body) {
        return context -> {
            MockHttpServletRequest request = new MockHttpServletRequest(context, "POST", PATH) {
                @Override
                public int getContentLength() {
                    return -1;
                }
            };
            request.setAsyncSupported(true);
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
            request.setParameter("type", "system-docs");
            request.setParameter("system", SYSTEM);
            request.setParameter("template", "arc42");
            request.setParameter("source-format", "markdown");
            return mayUpload().postProcessRequest(request);
        };
    }

    private static String tree(int paths, String path) {
        return "{\"paths\": [" + IntStream.range(0, paths)
                .mapToObj(index -> "\"" + path + "\"")
                .collect(Collectors.joining(",")) + "]}";
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
     * <b>Many tiny paths stay within the byte limit and are refused on their number.</b> Two hundred
     * thousand one-character paths are under a megabyte and two hundred thousand strings in the heap, so the
     * number has to be capped while the array is read - see {@code PathTreeReaderTest}.
     */
    @Test
    void aBodyOfVeryManyTinyPaths_isRefusedOnTheirNumber() throws Exception {
        mockMvc.perform(validationOf(tree(200_000, "a")).with(mayUpload()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("TOO_MANY_PATHS"));
    }

    /**
     * <b>A body that announces no length is bounded while it is read.</b> Chunked encoding carries no
     * {@code Content-Length}, so the interceptor has nothing to compare - and the caller here needs no more
     * than any doc pipeline's own token. Eleven megabytes in ten thousand paths passes the cap on the number
     * and is refused on the bytes, which is the bound that no announcement can get around.
     */
    @Test
    void aChunkedBodyLargerThanAnyListOfPaths_isCutWhileItIsRead() throws Exception {
        String path = "1-intro/" + "p".repeat(1088) + ".md";

        mockMvc.perform(chunkedValidationOf(tree(10_000, path)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("SIZE_LIMIT_EXCEEDED"));
    }

    /** And a chunked body that is a documentation set is answered like any other. */
    @Test
    void aChunkedBodyWithinTheLimit_isAnswered() throws Exception {
        mockMvc.perform(chunkedValidationOf("{\"paths\": [\"1-intro/goals.md\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pathsChecked").value(1));
    }

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

    /**
     * <b>Each answer carries the media type that matches it.</b> The report is JSON and the problem document
     * is a problem document, so a client that asks for {@code application/problem+json} by name is answered
     * rather than refused on content negotiation.
     */
    @Test
    void eachAnswer_carriesTheMediaTypeThatMatchesIt() throws Exception {
        mockMvc.perform(validationOf("{\"paths\": [\"1-intro/goals.md\"]}").with(mayUpload()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("application/json")));
        mockMvc.perform(validationOf("{\"paths\": [\"1-intro/index.md\"]}").with(mayUpload()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("application/problem+json")));
    }

    @Test
    void withAcceptOfTheProblemDocument_neitherAnswerIsRefused() throws Exception {
        mockMvc.perform(validationOf("{\"paths\": [\"1-intro/goals.md\"]}")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON).with(mayUpload()))
                .andExpect(status().isOk());
        mockMvc.perform(validationOf("{\"paths\": [\"1-intro/index.md\"]}")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON).with(mayUpload()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.findings[0].code").value("RESERVED_NAME"));
    }

    /**
     * <b>A body that is not a readable path tree is the endpoint's problem document too.</b> It is the most
     * likely mistake a workflow hand-building the JSON makes, and the answer has to carry the {@code code}
     * this endpoint's contract tells a pipeline to read.
     */
    @Test
    void aBodyThatIsNotAPathTree_isFourHundredWithTheUploadProblemDocument() throws Exception {
        mockMvc.perform(validationOf("{\"paths\":").with(mayUpload()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"))
                .andExpect(jsonPath("$.type").value(UploadProblems.PROBLEM_TYPE))
                .andExpect(jsonPath("$.detail").value("The request body is not a readable path tree."));
        mockMvc.perform(validationOf("").with(mayUpload()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"));
        mockMvc.perform(validationOf("{\"paths\": {}}").with(mayUpload()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"));
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
