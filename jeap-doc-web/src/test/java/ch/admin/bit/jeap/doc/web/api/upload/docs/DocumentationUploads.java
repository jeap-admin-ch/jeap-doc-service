package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.web.api.upload.UploadPaths;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The uploads the tests send: the parameters of a doc workflow and a bundle that looks like one.
 */
final class DocumentationUploads {

    static final String SYSTEM = "orders";
    static final String COMPONENT = "foo-bar-scs";

    private DocumentationUploads() {
    }

    /**
     * The parameters of a system documentation upload, as the doc workflow of a repository sends them.
     */
    static Map<String, String> systemDocs() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("type", "system-docs");
        parameters.put("system", SYSTEM);
        parameters.put("template", "arc42");
        parameters.put("source-format", "markdown");
        parameters.put("source-repository", "ssh://git@bitbucket.example.ch/orders/orders-docs.git");
        parameters.put("source-revision", "9a1c2f8");
        parameters.put("source-ref", "main");
        parameters.put("source-timestamp", "2026-08-21T09:12:00+02:00");
        parameters.put("build-url", "https://github.com/orders/orders-docs/actions/runs/1234567890");
        parameters.put("generated-at", "2026-08-21T09:15:00+02:00");
        return parameters;
    }

    static Map<String, String> componentDocs() {
        Map<String, String> parameters = systemDocs();
        parameters.put("type", "component-docs");
        parameters.put("component", COMPONENT);
        parameters.put("version", "1.4.0");
        return parameters;
    }

    static MockHttpServletRequestBuilder uploadOf(UUID uploadId, Map<String, String> parameters, byte[] bundle) {
        MockHttpServletRequestBuilder request = put(UploadPaths.DOCS + "/{uploadId}", uploadId)
                .contentType("application/zip")
                .content(bundle);
        parameters.forEach(request::param);
        return request;
    }

    /**
     * A ZIP archive of a documentation folder, as a doc workflow packs it.
     */
    static byte[] bundle(String content) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry("1-intro/why-we-built-this.md");
            // A fixed time, so that the same content always produces the same bytes. A zip entry carries a DOS
            // timestamp with two-second granularity, so a test that built the same bundle twice - once to
            // upload and once to compare - failed whenever the two calls straddled a boundary.
            entry.setTime(0L);
            zip.putNextEntry(entry);
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
