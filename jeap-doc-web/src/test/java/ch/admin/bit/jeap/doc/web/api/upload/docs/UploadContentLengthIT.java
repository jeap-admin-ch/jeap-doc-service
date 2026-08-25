package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.DocumentationUpload;
import ch.admin.bit.jeap.doc.domain.UploadState;
import ch.admin.bit.jeap.doc.domain.port.DocumentationUploadRepository;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import ch.admin.bit.jeap.doc.web.api.upload.UploadPaths;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationContext;
import ch.admin.bit.jeap.security.test.jws.JwsBuilder;
import ch.admin.bit.jeap.security.test.jws.JwsBuilderFactory;
import ch.admin.bit.jeap.security.test.resource.configuration.JeapOAuth2IntegrationTestResourceConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.SYSTEM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules of the request itself, over a real socket.
 * <p>
 * MockMvc derives the content length from the content it is given, so it can neither omit nor contradict it -
 * exactly the two cases this class is about. It therefore drives the running server over HTTP, with a token the
 * jEAP test support signs and the service accepts.
 */
// The tokens are signed by the jEAP test support, so the service has to expect the issuer it signs for; the
// keys are looked up in the JWKS endpoint the same support mocks inside the running service (see below).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "jeap.security.oauth2.resourceserver.authorization-server.issuer=" + JwsBuilder.DEFAULT_ISSUER)
@Import(JeapOAuth2IntegrationTestResourceConfiguration.class)
class UploadContentLengthIT extends DocServiceIntegrationTestBase {

    /**
     * The service listens on a port picked here rather than on a random one, because the keys of the tokens are
     * served by the running service itself: the address of that endpoint has to be configured before the service
     * starts, and a random port is only known afterwards.
     */
    private static final int PORT = reserveFreePort();

    @DynamicPropertySource
    static void jwksOfTheRunningService(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("jeap.security.oauth2.resourceserver.authorization-server.jwk-set-uri",
                () -> "http://localhost:" + PORT + "/.well-known/jwks.json");
    }

    private static int reserveFreePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Autowired
    private JwsBuilderFactory jwsBuilderFactory;

    @Autowired
    private DocumentationUploadRepository uploads;

    @Test
    void upload_whenTheSizeIsNotAnnounced_thenLengthRequired() throws Exception {
        HttpResponse<String> response = send(HttpRequest.BodyPublishers.ofInputStream(
                () -> new java.io.ByteArrayInputStream(DocumentationUploads.bundle("# a component"))));

        assertThat(response.statusCode()).isEqualTo(411);
        assertThat(response.body()).contains("LENGTH_REQUIRED");
    }

    /**
     * The upload announces more than it sends and then stops. Nothing may be published under that upload id, and
     * the caller has to be told what is wrong with its request rather than that the service failed - which is
     * what the whole chain from the storage through the domain to the problem document is for.
     */
    @Test
    void upload_whenTheBodyIsShorterThanAnnounced_thenRejectedAndRecordedAsTheCallersMistake() throws Exception {
        byte[] bundle = DocumentationUploads.bundle("# a component");
        UUID uploadId = UUID.randomUUID();

        String response = sendTruncated(uploadId, bundle, bundle.length + 512);

        // The request is rejected - by the servlet container, which notices the connection ending before the
        // announced length and answers with its own 400, so this answer carries no problem document.
        assertThat(response).startsWith("HTTP/1.1 400");
        // What the service does with it is the part that matters: the upload is recorded as failed, for the
        // reason it actually has, and nothing is published under it.
        DocumentationUpload recorded = uploads.findByUploadId(uploadId).orElseThrow();
        assertThat(recorded.state()).isEqualTo(UploadState.FAILED);
        assertThat(recorded.failureReason()).contains("Content-Length announced");
        assertThat(recorded.objectKey()).isNull();
    }

    /**
     * Sent over a bare socket: an HTTP client would refuse to send fewer bytes than it announced, and that is
     * exactly the request under test here. The output side is closed after the short body, which is what tells
     * the service that nothing more is coming.
     */
    private String sendTruncated(UUID uploadId, byte[] body, int announcedLength) throws IOException {
        try (Socket socket = new Socket("localhost", PORT)) {
            OutputStream out = socket.getOutputStream();
            out.write(("PUT " + UploadPaths.DOCS + "/" + uploadId + query() + " HTTP/1.1\r\n"
                       + "Host: localhost:" + PORT + "\r\n"
                       + "Authorization: Bearer " + token() + "\r\n"
                       + "Content-Type: application/zip\r\n"
                       + "Content-Length: " + announcedLength + "\r\n"
                       + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            socket.shutdownOutput();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String query() {
        StringBuilder query = new StringBuilder();
        DocumentationUploads.componentDocs().forEach((name, value) ->
                query.append(query.isEmpty() ? "?" : "&")
                        .append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                        .append("=")
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return query.toString();
    }

    private HttpResponse<String> send(HttpRequest.BodyPublisher body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + PORT + UploadPaths.DOCS + "/" + UUID.randomUUID() + query()))
                .header("Content-Type", "application/zip")
                .header("Authorization", "Bearer " + token())
                .PUT(body)
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private String token() {
        return jwsBuilderFactory.createValidForFixedLongPeriodBuilder("doc-pipeline", JeapAuthenticationContext.SYS)
                .withIssuer(JwsBuilder.DEFAULT_ISSUER)
                .withUserRoles(uploadsRole(SYSTEM, "write").toString())
                .build().serialize();
    }
}
