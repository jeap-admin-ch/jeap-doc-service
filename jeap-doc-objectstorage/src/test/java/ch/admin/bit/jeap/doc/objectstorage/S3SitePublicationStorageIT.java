package ch.admin.bit.jeap.doc.objectstorage;

import ch.admin.bit.jeap.doc.domain.port.PartPublication;
import ch.admin.bit.jeap.doc.domain.port.PublishedSite;
import ch.admin.bit.jeap.doc.domain.port.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Publishing a generated site into a real S3-compatible object storage.
 * <p>
 * The files go up several at a time, so this is where that is exercised: that every one of them arrives, that
 * the tree under the prefix is the tree on disk, and that a failure anywhere in the batch reaches the caller
 * rather than leaving a site half published and called successful.
 */
class S3SitePublicationStorageIT extends RustFsTestContainerBase {

    /** Where the files every part of a site emits identically go - see {@code SharedAssets}. */
    private static final String SHARED_PREFIX = "default/shared";

    @TempDir
    Path site;

    private DocObjectStorageProperties properties;
    private S3SitePublicationStorage storage;

    @BeforeEach
    void setUp() {
        properties = new DocObjectStorageProperties();
        properties.setBucket(TEST_BUCKET_NAME);
        storage = new S3SitePublicationStorage(S3_CLIENT, properties);
    }

    /**
     * Enough files that more than one thread is genuinely used, and nested, because a site is a tree.
     */
    @Test
    void publish_thenEveryFileArrivesWhereItsPathSaysItShould() throws IOException {
        int pages = 120;
        for (int page = 0; page < pages; page++) {
            write(site.resolve("docs").resolve("page-" + page).resolve("index.html"), "<h1>page " + page + "</h1>");
        }
        write(site.resolve("index.html"), "<h1>home</h1>");
        write(site.resolve("assets").resolve("main.js"), "console.log('hi')");

        PublishedSite published = storage.publish(under("default/42"), site);

        assertThat(published.prefix()).isEqualTo("default/42");
        // The part's own files: the pages and the root page, and *not* the shared bundle beside them. Every
        // part build writes the same assets/** to one prefix of the site, so counting them here made the size
        // of a fifty-two-part site fifty-one extra copies of that bundle.
        assertThat(published.fileCount()).isEqualTo(pages + 1);
        assertThat(published.sizeInBytes()).isPositive();

        assertThat(read("default/42", "index.html")).isEqualTo("<h1>home</h1>");
        assertThat(read("default/42", "docs/page-0/index.html")).isEqualTo("<h1>page 0</h1>");
        assertThat(read("default/42", "docs/page-119/index.html")).isEqualTo("<h1>page 119</h1>");
        // ...and the bundle under the site's shared prefix rather than under this build's, which is what lets
        // two parts of one site be served under one base URL - see SharedAssets.
        assertThat(read(SHARED_PREFIX, "assets/main.js")).isEqualTo("console.log('hi')");
        assertThat(storage.open("default/42", "assets/main.js"))
                .describedAs("a shared file is published once for the site, not once per part")
                .isEmpty();
    }

    /**
     * The split between what belongs to one part and what belongs to the whole site, which is the one thing a
     * publication does that a plain upload of a directory does not.
     */
    @Test
    void publish_thenTheSharedFilesGoToTheSitesPrefixAndTheRestToThePartsOwn() throws IOException {
        write(site.resolve("index.html"), "<h1>home</h1>");
        write(site.resolve("systems").resolve("orders").resolve("index.html"), "<h1>orders</h1>");
        write(site.resolve("assets").resolve("js").resolve("main.a1b2c3.js"), "console.log('hi')");
        write(site.resolve("img").resolve("logo.svg"), "<svg/>");

        storage.publish(under("default/43"), site);

        assertThat(read("default/43", "index.html")).isEqualTo("<h1>home</h1>");
        assertThat(read("default/43", "systems/orders/index.html")).isEqualTo("<h1>orders</h1>");
        assertThat(read(SHARED_PREFIX, "assets/js/main.a1b2c3.js")).isEqualTo("console.log('hi')");
        assertThat(read(SHARED_PREFIX, "img/logo.svg")).isEqualTo("<svg/>");
    }

    /**
     * <b>A shared file already stored with the same bytes is not written again</b>, and one whose bytes have
     * changed is. The shared prefix belongs to the site rather than to a build, so every part of a publication
     * would otherwise write the same ninety-odd names into it - and a template change has to land there all
     * the same, which is why the entity tag decides rather than the key existing.
     */
    @Test
    void publish_whenASharedFileIsAlreadyStored_thenOnlyAChangedOneIsWrittenAgain() throws IOException {
        // Already in the bucket with the bytes the build is about to publish, and stored as something else -
        // so the content type says whether the publication wrote the object or left it alone.
        String key = properties.getSitePrefix() + "/" + SHARED_PREFIX + "/assets/theme.js";
        S3_CLIENT.putObject(request -> request.bucket(TEST_BUCKET_NAME).key(key).contentType("text/plain"),
                RequestBody.fromString("console.log('one')", StandardCharsets.UTF_8));
        write(site.resolve("index.html"), "<h1>home</h1>");
        write(site.resolve("assets").resolve("theme.js"), "console.log('one')");
        write(site.resolve("branding").resolve("logo.svg"), "<svg/>");

        storage.publish(under("default/50"), site);

        assertThat(tagsOf(key))
                .describedAs("the same bytes were already stored under that name, so nothing was written - "
                             + "and an object this publication wrote would carry its content tag")
                .isEmpty();
        assertThat(read(SHARED_PREFIX, "branding/logo.svg"))
                .describedAs("and a site's branding is shared, so it is written to the site's prefix rather "
                             + "than to each part's - where a request for it would never look")
                .isEqualTo("<svg/>");

        // A new version of the template writes a fixed-name file differently, and that has to land: what
        // decides is the entity tag, not that the key exists.
        write(site.resolve("assets").resolve("theme.js"), "console.log('two')");
        storage.publish(under("default/51"), site);

        assertThat(read(SHARED_PREFIX, "assets/theme.js")).isEqualTo("console.log('two')");
        assertThat(tagsOf(key)).describedAs("written this time, so it is tagged as published content")
                .containsEntry(S3DocumentationBundleStorage.CONTENT_TAG_KEY,
                        S3SitePublicationStorage.CONTENT_TAG_VALUE);
    }

    @Test
    void publish_thenEachFileIsServedAsWhatItIs() throws IOException {
        write(site.resolve("index.html"), "<h1>home</h1>");
        write(site.resolve("styles.css"), "body{}");
        write(site.resolve("logo.svg"), "<svg/>");

        storage.publish(under("default/43"), site);

        assertThat(open("default/43", "index.html").contentType()).isEqualTo("text/html;charset=UTF-8");
        assertThat(open("default/43", "styles.css").contentType()).isEqualTo("text/css;charset=UTF-8");
        assertThat(open("default/43", "logo.svg").contentType()).isEqualTo("image/svg+xml;charset=UTF-8");
    }

    /**
     * One file that cannot be read must not become a site that is published and served with a page missing.
     */
    @Test
    void publish_whenOneFileCannotBeRead_thenTheWholePublicationFails() throws IOException {
        write(site.resolve("index.html"), "<h1>home</h1>");
        Path unreadable = site.resolve("secret.html");
        write(unreadable, "<h1>secret</h1>");
        assertThat(unreadable.toFile().setReadable(false)).isTrue();

        try {
            assertThatThrownBy(() -> storage.publish(under("default/44"), site)).isInstanceOf(RuntimeException.class);
        } finally {
            assertThat(unreadable.toFile().setReadable(true)).isTrue();
        }
    }

    @Test
    void publish_whenThereIsNothingToPublish_thenItIsAnEmptySiteAndNotAFailure() {
        PublishedSite published = storage.publish(under("default/45"), site);

        assertThat(published.fileCount()).isZero();
        assertThat(published.sizeInBytes()).isZero();
    }

    /**
     * The neighbours share the deleted prefix <b>as a string</b>, which is the whole point: a listing prefix of
     * {@code .../4} matches {@code 46} and {@code 47} too, and the one thing between this delete and taking
     * them with it is the trailing slash {@code keyOf} puts on. A pair like 46 and 47 would pass without it.
     */
    @Test
    void delete_thenTheWholeSiteIsGoneAndTheSitesWhoseIdItIsAPrefixOfAreNot() throws IOException {
        write(site.resolve("index.html"), "<h1>home</h1>");
        storage.publish(under("default/4"), site);
        storage.publish(under("default/46"), site);
        storage.publish(under("default/47"), site);

        storage.delete("default/4");

        assertThat(storage.open("default/4", "index.html")).isEmpty();
        assertThat(storage.open("default/46", "index.html")).isPresent();
        assertThat(storage.open("default/47", "index.html")).isPresent();
    }

    @Test
    void open_whenNothingIsThere_thenEmptyRatherThanAFailure() {
        assertThat(storage.open("default/does-not-exist", "index.html")).isEmpty();
    }

    /**
     * An instance may write the prefix with or without slashes around it; an object key wants neither.
     */
    @Test
    void publish_thenTheConfiguredSitePrefixIsUsedWhateverItsSlashes() throws IOException {
        write(site.resolve("index.html"), "<h1>home</h1>");
        properties.setSitePrefix("/somewhere/else/");

        storage.publish(under("default/48"), site);

        assertThat(keysUnder("somewhere/else/default/48")).contains("somewhere/else/default/48/index.html");
    }

    /**
     * What the trailing-slash redirect asks before it sends a reader anywhere. It exists because the obvious
     * way to ask - open() and look at the Optional - hands back a live connection that nobody closes, and a
     * pool of fifty is emptied by a crawler.
     */
    @Test
    void exists_thenItAnswersWithoutOpeningAnything() throws IOException {
        write(site.resolve("index.html"), "<h1>home</h1>");
        storage.publish(under("default/60"), site);

        assertThat(storage.exists("default/60", "index.html")).isTrue();
        assertThat(storage.exists("default/60", "nothing-here.html")).isFalse();
        assertThat(storage.exists("default/does-not-exist", "index.html")).isFalse();
    }

    /**
     * Asked far more often than the pool has connections. If it opened one per call this would block long
     * before the end - which is the failure it was introduced to remove.
     */
    @Test
    void exists_whenAskedManyTimes_thenItDoesNotRunOutOfConnections() throws IOException {
        write(site.resolve("index.html"), "<h1>home</h1>");
        storage.publish(under("default/61"), site);

        for (int attempt = 0; attempt < 200; attempt++) {
            assertThat(storage.exists("default/61", "index.html")).isTrue();
        }
    }

    private String read(String prefix, String path) throws IOException {
        try (InputStream content = open(prefix, path).content()) {
            return new String(content.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private StoredObject open(String prefix, String path) {
        Optional<StoredObject> object = storage.open(prefix, path);
        assertThat(object).describedAs("%s/%s should have been published", prefix, path).isPresent();
        return object.orElseThrow();
    }

    /** The tags of one object by its raw key, which is how "was this written" is asked without a timestamp. */
    private static Map<String, String> tagsOf(String key) {
        return S3_CLIENT.getObjectTagging(request -> request.bucket(TEST_BUCKET_NAME).key(key))
                .tagSet().stream()
                .collect(Collectors.toMap(Tag::key, Tag::value));
    }

    private List<String> keysUnder(String prefix) {
        return S3_CLIENT.listObjectsV2Paginator(builder -> builder.bucket(TEST_BUCKET_NAME).prefix(prefix))
                .contents().stream()
                .map(S3Object::key)
                .toList();
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * Where a part is published: its own prefix, and the site's shared one for the files every part emits
     * identically. What the split means is asserted in the test that has both.
     */
    private static PartPublication under(String prefix) {
        return new PartPublication(prefix, SHARED_PREFIX);
    }
}
