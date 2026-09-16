package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.custom.ChapterOrder;
import ch.admin.bit.jeap.doc.domain.custom.CustomDocumentation;
import ch.admin.bit.jeap.doc.domain.custom.CustomPage;
import ch.admin.bit.jeap.doc.domain.custom.CustomPages;
import ch.admin.bit.jeap.doc.domain.custom.CustomProperties;
import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.custom.Microsite;
import ch.admin.bit.jeap.doc.domain.custom.UploadedFrontMatter;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import ch.admin.bit.jeap.doc.domain.template.ReservedNames;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Writes the pages a team uploaded into the tree a build is generating.
 * <p>
 * One of these per build of one part. It holds the sets of that part open while the templates walk their
 * chapters and ask for what belongs in each: a set is one object, so it is fetched once and read many times
 * rather than once per page.
 * <p>
 * <b>What it decides is nothing about the structure.</b> Where a page goes is the template's answer - it
 * passes the directory - and what a page may be was decided when the set was received. This writes the file:
 * the body byte for byte, the front matter generated, and a name the template generates dropped rather than
 * published over it.
 */
@Slf4j
class CustomPagesWriter implements CustomPages, AutoCloseable {

    /** What a custom page says it is, against the {@code generated} of a page the service wrote itself. */
    static final String DOC_STATUS = "custom";

    /**
     * The most of one file that is ever held at once, whatever the budget of its set allows.
     * <p>
     * A byte array cannot be larger than an int anyway, and a single documentation file of even this size is
     * already absurd - the bound that decides is the set's, and this only keeps one entry from being the
     * whole of it.
     */
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;

    private final CustomDocumentation documentation;

    /**
     * Every set of this part, microsites included. The field above is narrowed to this template's markdown,
     * which is what the pages are read from - and would hide every microsite from this writer.
     */
    private final CustomDocumentation allDocumentation;

    private final CustomDocumentationStorage storage;
    private final StructureTemplate template;
    private final long maxUnpackedSize;

    /** The bundle of each set, opened when the set is first read from and closed with this writer. */
    private final Map<Long, CustomDocumentationStorage.OpenedBundle> opened = new HashMap<>();

    /**
     * How much each set has unpacked, against {@code max-unpacked-size} - the bytes, not what it claimed.
     * <p>
     * Per set, because that is what the property bounds and what the upload applies it to. One writer writes
     * the system's set, every component's and every library's, and a budget shared between them would abandon
     * the later ones over what the earlier ones are - and say so naming the wrong set.
     */
    private final Map<Long, Long> unpacked = new HashMap<>();

    /** The sets this writer has given up on, so a set past the bound is reported once and then skipped. */
    private final Set<Long> abandoned = new HashSet<>();

    CustomPagesWriter(CustomDocumentation documentation, CustomDocumentationStorage storage,
                      StructureTemplate template, CustomProperties properties) {
        // Narrowed here as well as by the caller, so this writes the pages of its own template whatever it is
        // handed: a subject may carry a second methodology and an HTML microsite beside its Markdown, and
        // asking for the set of a subject alone would answer with whichever of them came back first.
        this.documentation = documentation.publishedBy(template.id());
        this.allDocumentation = documentation;
        this.storage = storage;
        this.template = template;
        this.maxUnpackedSize = properties.getMaxUnpackedSize().toBytes();
    }

    @Override
    public int writeInto(CustomSubject subject, String chapterFolder, Path chapterDirectory) {
        Optional<CustomSet> set = documentation.setOf(subject);
        if (set.isEmpty()) {
            return 0;
        }
        List<CustomPage> pages = set.get().pagesOf(chapterFolder);
        // One order over everything in this chapter, so a microsite sits among the pages by its label
        // rather than after all of them. A chapter with no microsite comes out as the upload numbered it.
        ChapterOrder order = ChapterOrder.of(pages, micrositesOf(subject, chapterFolder));
        int written = 0;
        for (CustomPage page : pages) {
            if (write(set.get(), page, order.positionOf(page), chapterDirectory)) {
                written++;
            }
        }
        // The assets beside them. They are not pages and are not counted, but a page that shows one needs it
        // at the same place relative to itself.
        for (CustomPage asset : set.get().assetsOf(chapterFolder)) {
            write(set.get(), asset, 0, chapterDirectory);
        }
        return written;
    }

    /** Whether a file may be written at its path: nothing the template generates, and inside its chapter. */
    private boolean mayLieWhereItIsWritten(CustomSet set, CustomPage page, Path chapterDirectory) {
        if (!page.asset() && isGeneratedByTheTemplate(set, page)) {
            log.warn("The uploaded page {} of {} is not published: {} generates a page of that name into "
                     + "that chapter, and a page has one source.",
                    page.path(), set.subject().slug(), template.id());
            return false;
        }
        if (page.asset() && liesInAFolderTheTemplateGenerates(set, page)) {
            log.warn("The uploaded file {} of {} is not published: its folder is one {} generates into that "
                     + "chapter, and what it generates is not written over.",
                    page.path(), set.subject().slug(), template.id());
            return false;
        }
        Path file = chapterDirectory.resolve(page.fileName()).normalize();
        if (!file.startsWith(chapterDirectory.normalize())) {
            // The upload refuses such a path; this is the backstop.
            log.warn("The uploaded file {} of {} is not published: it would lie outside its chapter.",
                    page.path(), set.subject().slug());
            return false;
        }
        if (isBothAFileAndAFolder(file, chapterDirectory.normalize())) {
            // The upload refuses a set that uses one name for both; this is the backstop for one stored before
            // it did, which would otherwise fail the build of the whole part on an I/O error.
            log.warn("The uploaded file {} of {} is not published: the same path is a file and a folder in "
                     + "that chapter.", page.path(), set.subject().slug());
            return false;
        }
        return true;
    }

    /**
     * Writes one file, and answers whether it was written.
     * <p>
     * A file that is not written is never a failed build: a page that the archive does not hold, a name the
     * template generates, or a set that has grown past what a set may unpack to are all one page missing from
     * one part, and the log line says which.
     */
    private boolean write(CustomSet set, CustomPage page, int position, Path chapterDirectory) {
        if (abandoned.contains(set.id()) || !mayLieWhereItIsWritten(set, page, chapterDirectory)) {
            return false;
        }
        Path file = chapterDirectory.resolve(page.fileName()).normalize();
        Optional<CustomDocumentationStorage.OpenedBundle> bundle = bundleOf(set);
        if (bundle.isEmpty()) {
            // The object is gone - taken by a removal or by the upload that replaced this set. Its own log
            // line has been written; giving up on the set keeps this build from writing half of it.
            abandoned.add(set.id());
            return false;
        }
        Optional<InputStream> content = bundle.get().read(page.path());
        if (content.isEmpty()) {
            log.warn("The uploaded file {} of {} is recorded and its bundle does not hold it. The page is "
                     + "left out; the next upload of that set repairs it.", page.path(), set.subject().slug());
            return false;
        }
        try (InputStream in = content.get()) {
            // Never more than what is left of this set's budget, and one byte to tell "at the bound" from
            // "past it". A ZIP states the size of an entry and the uploader writes that statement, so the
            // bound the upload checked is the archive's word - this is the one that measures, and reading
            // the entry whole before measuring it is how a bundle of a few megabytes becomes gigabytes of
            // heap in the service that is generating the site.
            long left = maxUnpackedSize - unpacked.getOrDefault(set.id(), 0L);
            // One past each bound, so that reaching one is told from standing at it. Reading exactly the
            // per-file cap would make a file that is larger than it look like a file of exactly that size,
            // and the page or the image would be published cut in half.
            byte[] bytes = in.readNBytes((int) Math.min(left + 1, (long) MAX_FILE_BYTES + 1));
            if (bytes.length > MAX_FILE_BYTES) {
                log.warn("The uploaded file {} of {} is larger than the {} bytes this service reads of one "
                         + "file. It is not published: half a file is worse than none.",
                        page.path(), set.subject().slug(), MAX_FILE_BYTES);
                return false;
            }
            if (tooMuchUnpacked(set, page, bytes.length)) {
                return false;
            }
            Optional<String> uploaded = page.asset() ? Optional.of("") : textOf(set, page, bytes);
            if (uploaded.isEmpty()) {
                return false;
            }
            Files.createDirectories(file.getParent());
            if (page.asset()) {
                Files.write(file, bytes);
            } else {
                Files.writeString(file, pageOf(set, position, uploaded.get()), StandardCharsets.UTF_8);
            }
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Whether the template writes a page of this name into this chapter itself.
     * <p>
     * The upload API refuses such a set, so this is the backstop: a set stored before a rule existed, or a
     * template that starts generating a name it did not generate before. Without it the build fails on a
     * duplicate route twenty minutes in, naming a route rather than an upload.
     */
    private boolean isGeneratedByTheTemplate(CustomSet set, CustomPage page) {
        Optional<StructureChapter> chapter = template.chapterOfFolder(page.chapter());
        if (chapter.isEmpty()) {
            return false;
        }
        SubjectKind kind = set.key().kind();
        // Through the rule the upload applies, and not a comparison of its own: the landing-page names, the
        // folded case and the number prefix a document loses are all part of what is occupied, and a backstop
        // that knew only the last of them would let through exactly what it is there to catch.
        return ReservedNames.isTaken(template, chapter.get(), kind, page.fileName());
    }

    /** Whether the file is already a folder, or one of the folders it lies in below the chapter is a file. */
    private static boolean isBothAFileAndAFolder(Path file, Path chapterDirectory) {
        if (Files.isDirectory(file)) {
            return true;
        }
        for (Path folder = file.getParent(); folder != null && !folder.equals(chapterDirectory);
             folder = folder.getParent()) {
            if (Files.exists(folder) && !Files.isDirectory(folder)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an asset lies in a folder whose name the template writes into this chapter itself, such as the
     * {@code components} group of a system's building block view. The upload refuses such a set; this is the
     * backstop, by the same rule.
     */
    private boolean liesInAFolderTheTemplateGenerates(CustomSet set, CustomPage page) {
        int slash = page.fileName().indexOf('/');
        Optional<StructureChapter> chapter = template.chapterOfFolder(page.chapter());
        return slash > 0 && chapter.isPresent() && ReservedNames.isTaken(template, chapter.get(),
                set.key().kind(), page.fileName().substring(0, slash));
    }

    /**
     * Whether this set has now unpacked to more than a set may.
     * <p>
     * <b>Counted while writing, because the declared sizes are the uploader's to state.</b> The upload
     * refuses what an archive says it unpacks to; only this knows what it really does. Past the bound the set
     * is abandoned and the build goes on: half a set published is better than a part that cannot be built,
     * and the log line says which set to look at.
     */
    private boolean tooMuchUnpacked(CustomSet set, CustomPage page, int bytes) {
        long ofThisSet = unpacked.merge(set.id(), (long) bytes, Long::sum);
        if (ofThisSet <= maxUnpackedSize) {
            return false;
        }
        // Read short of the whole file, so what it really unpacks to is unknown - and does not need to be.
        abandoned.add(set.id());
        log.error("The uploaded documentation of {} unpacks to more than the {} bytes a documentation set may "
                  + "be, at {}. The rest of that set is not published.",
                set.subject().slug(), maxUnpackedSize, page.path());
        return true;
    }

    /**
     * The page as text, or nothing when it is not UTF-8.
     * <p>
     * <b>Refused rather than repaired.</b> A lenient decoding replaces every byte it cannot read with a
     * replacement character, which publishes a page nobody wrote and says nothing about it - and what this
     * service promises about an uploaded body is that it goes through unchanged. A page that cannot be read
     * is one page missing from one part, with a line saying which.
     */
    private Optional<String> textOf(CustomSet set, CustomPage page, byte[] bytes) {
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString());
        } catch (CharacterCodingException e) {
            log.warn("The uploaded page {} of {} is not published: it is not UTF-8 text, and a page this "
                     + "service cannot read is not a page it rewrites.", page.path(), set.subject().slug());
            return Optional.empty();
        }
    }

    /**
     * The page as it is written: the body as it was uploaded, and a front matter this service decides.
     * <p>
     * The values go in as the values they are - a position as a number, an instant as its text - and the
     * quoting is {@link UploadedFrontMatter}'s: a repository URL holds a colon, a title may hold anything,
     * and an instant written plainly would be read back as a date.
     */
    private String pageOf(CustomSet set, int position, String uploaded) {
        CustomProvenance provenance = set.provenance();
        Map<String, Object> generated = UploadedFrontMatter.keys();
        // Past whatever the template generates into this chapter: Docusaurus breaks a tie between two equal
        // positions by file name, which is the one thing assigning a position is meant to take out of it.
        generated.put("sidebar_position", template.firstCustomPagePosition() + position);
        generated.put("doc_status", DOC_STATUS);
        generated.put("doc_source", "upload");
        generated.put("doc_source_repository", provenance.sourceRepository());
        generated.put("doc_source_ref", provenance.sourceRef());
        generated.put("doc_source_revision", provenance.sourceRevision());
        if (provenance.version() != null) {
            generated.put("doc_version", provenance.version());
        }
        generated.put("doc_uploaded_at", provenance.uploadedAt().toString());
        generated.put("doc_uploaded_at_display", DisplayTime.of(provenance.uploadedAt()));
        return UploadedFrontMatter.rewritten(uploaded, generated);
    }

    @Override
    public int writeMicrositesInto(CustomSubject subject, String chapterFolder, Path chapterDirectory) {
        List<Microsite> microsites = micrositesOf(subject, chapterFolder);
        if (microsites.isEmpty()) {
            return 0;
        }
        ChapterOrder order = ChapterOrder.of(pagesOf(subject, chapterFolder), microsites);
        for (Microsite microsite : microsites) {
            writeMicrosite(microsite, order.positionOf(microsite), chapterDirectory);
        }
        return microsites.size();
    }

    private List<Microsite> micrositesOf(CustomSubject subject, String chapterFolder) {
        return allDocumentation.micrositesOf(subject, template.id(), chapterFolder);
    }

    private List<CustomPage> pagesOf(CustomSubject subject, String chapterFolder) {
        return documentation.setOf(subject).map(set -> set.pagesOf(chapterFolder)).orElseGet(List::of);
    }

    /**
     * The page that frames one microsite.
     * <p>
     * <b>Generated rather than copied.</b> There is no uploaded body here - the microsite's own files are
     * served from their own prefix, and this page only says where they are. The frame around them is the
     * site template's, which reads {@code doc_microsite_url} out of this front matter.
     */
    private void writeMicrosite(Microsite microsite, int position, Path chapterDirectory) {
        CustomProvenance provenance = microsite.provenance();
        Map<String, Object> generated = UploadedFrontMatter.keys();
        // A set stored before the label column existed carries none; its topic is what names it then.
        generated.put("title", microsite.label() == null ? microsite.topic() : microsite.label());
        generated.put("sidebar_position", template.firstCustomPagePosition() + position);
        // A namespace of its own under the chapter, so a microsite can never take the route of a page
        // beside it - and the topic the upload named is what a reader sees in the URL.
        generated.put("slug", "microsites/" + microsite.topic());
        // The frame is the page: a table of contents of one heading would only take width from it.
        generated.put("hide_table_of_contents", true);
        generated.put("className", "doc-microsite-page");
        generated.put("doc_status", DOC_STATUS);
        generated.put("doc_source", "upload");
        generated.put("doc_microsite_url", microsite.url());
        generated.put("doc_microsite_label", microsite.label());
        generated.put("doc_source_repository", provenance.sourceRepository());
        generated.put("doc_source_ref", provenance.sourceRef());
        generated.put("doc_source_revision", provenance.sourceRevision());
        if (provenance.version() != null) {
            generated.put("doc_version", provenance.version());
        }
        generated.put("doc_uploaded_at", provenance.uploadedAt().toString());
        generated.put("doc_uploaded_at_display", DisplayTime.of(provenance.uploadedAt()));
        String body = "The documentation below was published by the owning team.\n";
        try {
            Files.createDirectories(chapterDirectory);
            Files.writeString(chapterDirectory.resolve(microsite.fileName()),
                    UploadedFrontMatter.rewritten(body, generated), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The bundle of a set, opened once, or nothing where its object is no longer there. */
    private Optional<CustomDocumentationStorage.OpenedBundle> bundleOf(CustomSet set) {
        return Optional.ofNullable(
                opened.computeIfAbsent(set.id(), id -> storage.open(set).orElse(null)));
    }

    @Override
    public void close() {
        opened.values().stream().filter(java.util.Objects::nonNull)
                .forEach(CustomDocumentationStorage.OpenedBundle::close);
        opened.clear();
    }
}
