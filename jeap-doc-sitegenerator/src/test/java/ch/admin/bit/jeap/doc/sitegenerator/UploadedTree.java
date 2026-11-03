package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.custom.CustomProperties;
import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.UploadedSet;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tree uploaded for a static site, held in memory: what the repository records of it and what its bundle
 * holds, as a {@link StaticSiteWriter} reads them.
 */
final class UploadedTree {

    static final Instant UPLOADED_AT = Instant.parse("2026-10-01T08:00:00Z");

    private final String site;
    private final Map<String, byte[]> files = new LinkedHashMap<>();

    UploadedTree(String site) {
        this.site = site;
    }

    UploadedTree page(String path, String text) {
        return file(path, text.getBytes(StandardCharsets.UTF_8));
    }

    UploadedTree file(String path, byte[] bytes) {
        files.put(path, bytes);
        return this;
    }

    /** The writer of a static site, reading this tree. */
    StaticSiteWriter writer() {
        return new StaticSiteWriter(documentation(), storage(), new CustomProperties());
    }

    NoCustomDocumentation documentation() {
        CustomSet set = new CustomSet(1L, CustomSetKey.ofSite(site), null, 1, "uploads/docs/1/1/bundle.zip",
                "0".repeat(64), files.values().stream().mapToLong(bytes -> bytes.length).sum(),
                new CustomProvenance("https://git.example.ch/handbook", "main", "abc123", UPLOADED_AT, null,
                        UPLOADED_AT),
                UploadedSet.staticPagesOf(List.copyOf(files.keySet())));
        return new NoCustomDocumentation() {
            @Override
            public Optional<CustomSet> find(CustomSetKey key) {
                return key.equals(CustomSetKey.ofSite(site)) ? Optional.of(set) : Optional.empty();
            }
        };
    }

    NoCustomStorage storage() {
        return new NoCustomStorage() {
            @Override
            public Optional<OpenedBundle> open(CustomSet set) {
                return Optional.of(new OpenedBundle() {
                    @Override
                    public Optional<InputStream> read(String path) {
                        return Optional.ofNullable(files.get(path)).map(ByteArrayInputStream::new);
                    }

                    @Override
                    public void close() {
                        // Nothing to release: the bytes are in memory.
                    }
                });
            }
        };
    }
}
