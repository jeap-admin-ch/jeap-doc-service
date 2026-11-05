package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.content.StaticSiteContent;
import ch.admin.bit.jeap.doc.domain.content.StaticSiteRoutes;
import ch.admin.bit.jeap.doc.domain.custom.CustomPage;
import ch.admin.bit.jeap.doc.domain.custom.CustomProperties;
import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.UploadedFrontMatter;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage.OpenedBundle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Optional;

/**
 * Writes a static site: the tree uploaded for it, as it is, into the site's one tree. Its folders and
 * {@code _category_.json} files are the navigation, and nothing is generated into it - except a root page, where
 * the tree brings none of its own.
 * <p>
 * A page is stamped with where it came from, as every uploaded page is, and keeps only what an uploaded page may
 * say about itself. A file that cannot be written as it was uploaded is left out with a line saying why, and the
 * build goes on - one page missing is better than a site that cannot be published.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaticSiteWriter implements ContentWriter {

    private final CustomDocumentationRepository documentation;
    private final CustomDocumentationStorage storage;
    private final CustomProperties properties;

    @Override
    public String contentSource() {
        return StaticSiteContent.SOURCE_ID;
    }

    @Override
    public WrittenContent write(Site site, SitePart part, Path contentDirectory,
                                               Instant generatedAt) throws IOException {
        Path tree = contentDirectory.resolve(SiteSources.SINGLE_TREE);
        Files.createDirectories(tree);
        Optional<CustomSet> set = documentation.find(CustomSetKey.ofSite(site.id()));
        WrittenTree written = set.isPresent() ? writeTree(set.get(), tree) : WrittenTree.NOTHING;
        if (!written.rootPage()) {
            RootPage.write(site, null, tree, generatedAt, rootPageText(set.isPresent(), written), "");
        }
        return new WrittenContent(Map.of(), new LinkedHashSet<>());
    }

    /** What the generated root page says about the tree it stands in for. */
    private static String rootPageText(boolean uploaded, WrittenTree written) {
        if (!uploaded) {
            return "Nothing has been uploaded to it yet.";
        }
        if (written.files() == 0) {
            return "What was uploaded to it could not be published. The next upload repairs that.";
        }
        return "Its pages are in the navigation.";
    }

    private WrittenTree writeTree(CustomSet set, Path tree) throws IOException {
        Optional<OpenedBundle> opened = storage.open(set);
        if (opened.isEmpty()) {
            log.warn("The uploaded tree of the static site {} is recorded and its bundle is gone. The site is "
                     + "published without it; the next upload repairs it.", set.key().site());
            return WrittenTree.NOTHING;
        }
        try (OpenedBundle bundle = opened.get()) {
            return new TreeWriting(set, bundle, tree, properties.getMaxUnpackedSize().toBytes()).writeAll();
        }
    }

    /**
     * What of an uploaded tree was written: how many files, and whether one of them is the tree's own root page.
     */
    private record WrittenTree(int files, boolean rootPage) {
        static final WrittenTree NOTHING = new WrittenTree(0, false);
    }

    /**
     * One tree being written: its files one after another, against what is left of the bytes a documentation
     * set may unpack to.
     */
    @RequiredArgsConstructor
    private static final class TreeWriting {

        private final CustomSet set;
        private final OpenedBundle bundle;
        private final Path tree;
        private final long maxUnpackedBytes;

        private long left;
        private int files;
        private boolean rootPage;

        WrittenTree writeAll() throws IOException {
            left = maxUnpackedBytes;
            for (CustomPage page : set.pages()) {
                if (!write(page)) {
                    log.error("The uploaded tree of the static site {} unpacks to more than the {} bytes a "
                              + "documentation set may be, at {}. The rest of it is not published.",
                            site(), maxUnpackedBytes, page.path());
                    break;
                }
            }
            return new WrittenTree(files, rootPage);
        }

        /** Writes one file, or leaves it out saying why, and answers whether the tree is still within its bytes. */
        private boolean write(CustomPage page) throws IOException {
            Path file = tree.resolve(page.path()).normalize();
            if (!isAFileOfTheTree(file)) {
                // The upload refuses such a path; this is the backstop.
                log.warn("The uploaded file {} of the static site {} is not published: it would not be a file of "
                         + "its tree.", page.path(), site());
                return true;
            }
            Optional<byte[]> uploaded = read(page);
            if (uploaded.isEmpty()) {
                return true;
            }
            left -= uploaded.get().length;
            if (left < 0) {
                return false;
            }
            Optional<byte[]> published = publishedFormOf(page, uploaded.get());
            if (published.isPresent()) {
                Files.createDirectories(file.getParent());
                Files.write(file, published.get());
                files++;
                rootPage |= StaticSiteRoutes.isTheRootPage(page.path());
            }
            return true;
        }

        /**
         * Inside the tree, not a folder of it, and not below a file of it - the upload refuses a set that uses one
         * name for both, and a set stored before it did would otherwise fail the whole build.
         */
        private boolean isAFileOfTheTree(Path file) {
            if (!file.startsWith(tree) || Files.isDirectory(file)) {
                return false;
            }
            for (Path folder = file.getParent(); folder != null && !folder.equals(tree);
                 folder = folder.getParent()) {
                if (Files.exists(folder) && !Files.isDirectory(folder)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * The file as the bundle holds it - never more than what is left of the set's bytes, and one byte more to
         * tell reaching a bound from standing at it, see {@link CustomPagesWriter}, which reads an uploaded file
         * the same way. Nothing where the bundle does not hold it or it is larger than one file may be.
         */
        private Optional<byte[]> read(CustomPage page) throws IOException {
            Optional<InputStream> content = bundle.read(page.path());
            if (content.isEmpty()) {
                log.warn("The uploaded file {} of the static site {} is recorded and its bundle does not hold it.",
                        page.path(), site());
                return Optional.empty();
            }
            byte[] bytes;
            try (InputStream in = content.get()) {
                bytes = in.readNBytes((int) Math.min(Math.max(left, 0) + 1,
                        (long) CustomPagesWriter.MAX_FILE_BYTES + 1));
            }
            if (bytes.length > CustomPagesWriter.MAX_FILE_BYTES) {
                log.warn("The uploaded file {} of the static site {} is larger than the {} bytes this service "
                         + "reads of one file. It is not published.", page.path(), site(),
                        CustomPagesWriter.MAX_FILE_BYTES);
                return Optional.empty();
            }
            return Optional.of(bytes);
        }

        /**
         * What is written for an uploaded file: a folder's category with only what it may say, an asset as it is,
         * and a page stamped with where it came from - or nothing, where the file cannot be published.
         */
        private Optional<byte[]> publishedFormOf(CustomPage page, byte[] uploaded) {
            if (CategoryFile.isOne(page.path())) {
                return CategoryFile.publishable(uploaded, page.path(), site()).map(StaticSiteWriter::utf8);
            }
            if (page.asset()) {
                return Optional.of(uploaded);
            }
            return textOf(page, uploaded).map(text -> utf8(stamped(text)));
        }

        /** The page as text, or nothing where it is not UTF-8 - refused rather than repaired, as every upload is. */
        private Optional<String> textOf(CustomPage page, byte[] bytes) {
            try {
                return Optional.of(StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString());
            } catch (CharacterCodingException _) {
                log.warn("The uploaded page {} of the static site {} is not UTF-8, and is not published.",
                        page.path(), site());
                return Optional.empty();
            }
        }

        private String site() {
            return set.key().site();
        }

        /** The page with what it may say about itself, and where it came from. */
        private String stamped(String page) {
            CustomProvenance provenance = set.provenance();
            Map<String, Object> generated = UploadedFrontMatter.keys();
            generated.put("doc_status", CustomPagesWriter.DOC_STATUS);
            generated.put("doc_source", "upload");
            generated.put("doc_source_repository", provenance.sourceRepository());
            generated.put("doc_source_ref", provenance.sourceRef());
            generated.put("doc_source_revision", provenance.sourceRevision());
            generated.put("doc_uploaded_at", provenance.uploadedAt().toString());
            generated.put("doc_uploaded_at_display", DisplayTime.of(provenance.uploadedAt()));
            return UploadedFrontMatter.rewritten(page, generated);
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
