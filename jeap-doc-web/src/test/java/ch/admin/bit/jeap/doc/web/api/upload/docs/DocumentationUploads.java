package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.web.api.upload.UploadPaths;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import ch.admin.bit.jeap.doc.domain.port.UploadedBundles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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

    /**
     * The same archive as a received bundle, for a test that mocks the receive: it reads the entries out of
     * the bytes, so what a test asserts about is the archive it built rather than a made-up path list.
     */
    static UploadedBundles.ReceivedBundle received(byte[] bundle) {
        return new ReceivedBytes(bundle);
    }

    private record ReceivedBytes(byte[] bundle) implements UploadedBundles.ReceivedBundle {

        @Override
        public List<String> paths() {
            List<String> paths = new ArrayList<>();
            walk((entry, in) -> paths.add(entry.getName()));
            return paths;
        }

        @Override
        public long declaredUnpackedSize() {
            return bundle.length;
        }

        @Override
        public String sha256() {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bundle));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public long sizeInBytes() {
            return bundle.length;
        }

        @Override
        public byte[] head(String path, int maxBytes) {
            byte[][] found = {new byte[0]};
            walk((entry, in) -> {
                if (entry.getName().equals(path)) {
                    found[0] = in.readNBytes(maxBytes);
                }
            });
            return found[0];
        }

        @Override
        public void close() {
            // nothing to release: this bundle is a byte array.
        }

        private void walk(EntryReader reader) {
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bundle))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        reader.read(entry, zip);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private interface EntryReader {
        void read(ZipEntry entry, ZipInputStream in) throws IOException;
    }
}
