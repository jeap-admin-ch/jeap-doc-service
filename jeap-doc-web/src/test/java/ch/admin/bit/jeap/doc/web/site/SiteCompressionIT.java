package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the documentation is actually served compressed.
 * <p>
 * Over a real socket, because that is the only way to see it: MockMvc never opens one, so every other test in
 * this module is blind to whether a response was compressed. It took a review to notice that the setting had
 * been switched on and could never fire, because the container refuses to compress a response carrying a strong
 * ETag - which is why the handler emits a weak one.
 */
class SiteCompressionIT extends DocServiceIntegrationTestBase {

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int port;

    @Autowired
    private DocumentationBuildRepository builds;

    @Autowired
    private SitePublicationStorage publication;

    @BeforeEach
    void publishACompressiblePage(@org.junit.jupiter.api.io.TempDir Path site) throws IOException {
        DocumentationBuild build = builds.start(Site.DEFAULT_SITE, BuildTrigger.SCHEDULE, "test", Instant.now());
        // Comfortably above the 1KB floor, and the kind of repetitive markup a documentation page is made of.
        Files.writeString(site.resolve("index.html"),
                "<html><body>" + "<p>The documentation of the system.</p>".repeat(200) + "</body></html>",
                StandardCharsets.UTF_8);
        String prefix = Site.DEFAULT_SITE + "/" + build.id();
        publication.publish(prefix, site);
        builds.succeeded(build.id(), prefix, 1, 8192, 10, null, Instant.now());
    }

    @Test
    void get_whenTheReaderAcceptsGzip_thenTheDocumentationIsCompressed() throws Exception {
        HttpResponse<byte[]> response = get("gzip");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");
        assertThat(new String(gunzip(response.body()), StandardCharsets.UTF_8))
                .contains("The documentation of the system.");
        // The point of it: the wire is far smaller than the page.
        assertThat(response.body().length).isLessThan(2000);
    }

    /**
     * The tag has to be weak, or the container refuses to compress - and it still has to identify the version,
     * because everything but the hashed assets revalidates on every request.
     */
    @Test
    void get_thenTheEntityTagIsWeakSoThatTheResponseMayBeCompressed() throws Exception {
        HttpResponse<byte[]> response = get("gzip");

        assertThat(response.headers().firstValue("ETag")).get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith("W/\"")
                .endsWith("\"");
    }

    @Test
    void get_whenTheReaderAcceptsNothing_thenItIsServedAsItIs() throws Exception {
        HttpResponse<byte[]> response = get("identity");

        assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .contains("The documentation of the system.");
    }

    private HttpResponse<byte[]> get(String acceptEncoding) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/"))
                    // The JDK client would otherwise not ask for compression at all.
                    .header("Accept-Encoding", acceptEncoding)
                    .build(), HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
            return in.readAllBytes();
        }
    }
}
