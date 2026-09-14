package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A microsite from the upload that published it to the bytes a reader's browser gets.
 * <p>
 * This is the whole path in one test: the doc workflow's {@code PUT}, the files unpacked into the bucket, and
 * every one of them served back under the microsite prefix - with the sandbox that contains it, the shim that
 * lets an application with an opaque origin start, and the uploaded bytes otherwise untouched.
 */
class MicrositeServingIT extends DocServiceIntegrationTestBase {

    private static final String SYSTEM = "orders";
    private static final String COMPONENT = "foo-bar-scs";
    private static final String LOCATION = "5-building-block-view";
    private static final String TOPIC = "configuration-reference";

    /** Where the reader finds it: the subject, the template, and where the microsite is embedded. */
    private static final String PREFIX = "/microsites/" + SYSTEM + "/components/" + COMPONENT
                                         + "/arc42/" + LOCATION + "/" + TOPIC;

    private static final String PAGE = "<!doctype html><html><head><title>Configuration</title></head>"
                                       + "<body>Every property</body></html>";
    private static final String SCRIPT = "console.log('configuration')";

    @Autowired
    private MockMvc mockMvc;

    private final MicrositeShim shim = new MicrositeShim();

    @BeforeEach
    void uploadTheMicrosite() throws Exception {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("type", "component-docs");
        parameters.put("system", SYSTEM);
        parameters.put("component", COMPONENT);
        parameters.put("version", "1.4.0");
        parameters.put("template", "arc42");
        parameters.put("source-format", "html");
        parameters.put("location", LOCATION);
        parameters.put("topic", TOPIC);
        parameters.put("label", "Configuration Reference");
        parameters.put("source-repository", "ssh://git@example.ch/orders/orders-config.git");
        parameters.put("source-revision", "9a1c2f8");
        parameters.put("source-ref", "main");
        parameters.put("source-timestamp", "2026-08-21T09:12:00+02:00");

        var request = put("/api/uploads/docs/{uploadId}", UUID.randomUUID())
                .contentType("application/zip")
                .content(bundle());
        parameters.forEach(request::param);
        mockMvc.perform(request.with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isCreated());
    }

    private static byte[] bundle() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            write(zip, "index.html", PAGE);
            write(zip, "assets/app.js", SCRIPT);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void write(ZipOutputStream zip, String path, String content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Test
    void get_theEntryPoint_thenThePageAsUploadedWithTheShimInIt() throws Exception {
        mockMvc.perform(get(PREFIX + "/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString(MicrositeShim.MARKER)))
                .andExpect(content().string(containsString("<title>Configuration</title>")))
                .andExpect(content().string(containsString("<body>Every property</body>")));
    }

    /** A microsite is opened at its entry point, so the topic itself serves the page. */
    @Test
    void get_theTopicWithoutAFile_thenTheEntryPoint() throws Exception {
        mockMvc.perform(get(PREFIX)).andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>Configuration</title>")));
        mockMvc.perform(get(PREFIX + "/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>Configuration</title>")));
    }

    /**
     * <b>Only a page is rewritten.</b> A script with a tag in it is a broken script, and the team's bytes are
     * what the browser has to run.
     */
    @Test
    void get_anAsset_thenExactlyTheUploadedBytes() throws Exception {
        mockMvc.perform(get(PREFIX + "/assets/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(SCRIPT))
                .andExpect(content().string(not(containsString(MicrositeShim.MARKER))))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment"));
    }

    /**
     * The sandbox is on the response and not only on the iframe, so a file opened directly is as contained as
     * a framed one - and it is the only thing this service answers cross-origin.
     */
    @Test
    void get_aMicrosite_thenItIsSandboxedAndAnsweredCrossOrigin() throws Exception {
        mockMvc.perform(get(PREFIX + "/index.html"))
                .andExpect(header().string("Content-Security-Policy", startsWith("sandbox allow-scripts")))
                .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "cross-origin"));

        mockMvc.perform(get("/index.html"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    /** The tag says which shim the page carries, so a new one reaches a reader who holds the old bytes. */
    @Test
    void get_aPage_thenItsTagSaysWhichShimItCarries() throws Exception {
        mockMvc.perform(get(PREFIX + "/index.html"))
                .andExpect(header().string(HttpHeaders.ETAG, containsString(shim.version())))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"));
    }

    /**
     * A reader who follows a dead deep link sees this <b>inside the frame</b>, where the site's own not-found
     * page would draw a second navbar into a box a few hundred pixels wide.
     */
    @Test
    void get_aFileTheMicrositeDoesNotHold_thenTheServicesOwnPage() throws Exception {
        mockMvc.perform(get(PREFIX + "/guide/missing.html"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("has no such file")))
                .andExpect(content().string(containsString("index.html")));
    }

    @Test
    void get_aMicrositeNobodyUploaded_thenTheSamePage() throws Exception {
        mockMvc.perform(get("/microsites/" + SYSTEM + "/arc42/" + LOCATION + "/nothing-here/index.html"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("has no such file")));
    }
}
