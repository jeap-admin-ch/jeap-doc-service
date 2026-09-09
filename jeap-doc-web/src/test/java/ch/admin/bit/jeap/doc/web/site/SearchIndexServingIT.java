package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.SearchIndex;
import ch.admin.bit.jeap.doc.domain.SharedAssets;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.PartPublication;
import ch.admin.bit.jeap.doc.domain.port.SearchIndexRepository;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The search index as the reader's browser fetches it.
 * <p>
 * It is served like a page of the site - no token, same origin - and from a prefix of its own, because it
 * belongs to no part of the site and has a lifecycle none of them has.
 */
class SearchIndexServingIT extends DocServiceIntegrationTestBase {

    private static final String SITE = Site.DEFAULT_SITE;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentationBuildRepository builds;

    @Autowired
    private SearchIndexRepository indexes;

    @Autowired
    private SitePublicationStorage publication;

    @BeforeEach
    void publishASite(@TempDir Path site) throws IOException {
        publishShellOf(SITE, site);
    }

    /** A site with a front page and nothing else, so that it counts as published and serves its pages. */
    private void publishShellOf(String site, Path files) throws IOException {
        DocumentationBuild build =
                builds.start(PartKey.shellOf(site), BuildTrigger.IMPORT, "test", Instant.now(), null);
        Files.writeString(files.resolve("index.html"), "<html><body>Documentation</body></html>",
                StandardCharsets.UTF_8);
        Files.writeString(files.resolve("404.html"), "<html><body>Not found here</body></html>",
                StandardCharsets.UTF_8);
        String prefix = site + "/" + build.id();
        publication.publish(new PartPublication(prefix, SharedAssets.prefixOf(site)), files);
        builds.succeeded(build.id(), prefix, 1, 10, 1, "digest", Instant.now());
    }

    /** The five files a browser needs before it can answer anything, and the chunks it then asks for. */
    @Test
    void get_whenAnIndexIsPublished_thenItsFilesAreServedWithoutAToken(@TempDir Path bundle) throws Exception {
        publishIndex(bundle, "en_current");

        mockMvc.perform(get("/pagefind/pagefind-entry.json"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("en_current")));
        mockMvc.perform(get("/pagefind/pagefind.js")).andExpect(status().isOk());
        mockMvc.perform(get("/pagefind/index/en_current.pf_index")).andExpect(status().isOk());
        mockMvc.perform(get("/pagefind/fragment/en_current.pf_fragment")).andExpect(status().isOk());
    }

    /**
     * A site with no index answers nothing for it, and the search box hides itself rather than throwing - so
     * what a reader sees is a site without a search, not a broken one.
     * <p>
     * <b>It makes that state rather than assuming it.</b> An index is built at the end of the build pass that
     * published a site, so any test in this module that runs a pass gives the site it built an index - and
     * this module's tests share a database and nothing rolls back. Assuming a site had never been indexed is
     * therefore an assumption about which tests ran first, and it was one: this failed on CI as soon as
     * {@code DocumentationGenerationIT} happened to build the same site before it.
     */
    @Test
    void get_whenTheSiteHasNoIndex_thenItIsNotFoundAndThePagesStillAre(@TempDir Path site) throws Exception {
        publishShellOf("governance", site);
        forgetEveryIndexOf("governance");

        mockMvc.perform(get("/site/governance/pagefind/pagefind-entry.json")).andExpect(status().isNotFound());
        mockMvc.perform(get("/site/governance/")).andExpect(status().isOk());
    }

    /** Removes every published index of a site, so that "this site has no index" is a fact and not a hope. */
    private void forgetEveryIndexOf(String site) {
        // keep = 0: everything published is superseded by nothing, which is the whole list.
        for (ch.admin.bit.jeap.doc.domain.port.PublishedSearchIndex index : indexes.supersededOf(site, 0)) {
            publication.delete(index.objectPrefix());
            indexes.forget(index.id());
        }
    }

    /**
     * <b>The index a reader is already searching in outlives the one that replaced it.</b> Every file the
     * indexer writes is named by a content hash, so a browser that loaded the old manifest goes on asking for
     * chunks by names only the old prefix has. Publishing a new index must not answer those with 404.
     */
    @Test
    void get_whenANewIndexIsPublished_thenTheOldChunksAreStillThere(@TempDir Path older, @TempDir Path newer)
            throws Exception {
        long old = publishIndex(older, "en_old");
        publishIndex(newer, "en_new");

        mockMvc.perform(get("/pagefind/pagefind-entry.json"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("en_new")));
        // The old chunk is not reachable under /pagefind any more - that path now names the new index - but
        // its objects are still in the storage, which is what the retention is for and what a browser holding
        // the old manifest needs. Read directly, because the reader's URL always names the current index.
        org.assertj.core.api.Assertions
                .assertThat(publication.exists(SearchIndex.prefixOf(SITE, old), "pagefind/index/en_old.pf_index"))
                .describedAs("the superseded index's chunks are still in the storage").isTrue();
    }

    /** An index is not one of the shared files: those are a part's, and every part writes the same bytes. */
    @Test
    void get_whenAnIndexIsPublished_thenItDoesNotComeFromTheSharedPrefix(@TempDir Path bundle) throws Exception {
        long id = publishIndex(bundle, "en_current");

        org.assertj.core.api.Assertions
                .assertThat(publication.exists(SharedAssets.prefixOf(SITE), "pagefind/pagefind-entry.json"))
                .describedAs("nothing of the index is in the site's shared prefix").isFalse();
        org.assertj.core.api.Assertions
                .assertThat(publication.exists(SearchIndex.prefixOf(SITE, id), "pagefind/pagefind-entry.json"))
                .isTrue();
    }

    /**
     * Writes a bundle of the shape Pagefind produces and publishes it as the current index.
     *
     * @param hash what the content-hashed names carry, so that one index can be told from another
     */
    private long publishIndex(Path published, String hash) throws IOException {
        // What is published holds nothing but pagefind/, so an object's key is the path a reader asks for.
        Path bundle = published.resolve(SearchIndex.DIRECTORY);
        Files.createDirectories(bundle);
        Files.writeString(bundle.resolve("pagefind-entry.json"),
                "{\"version\":\"1.5.2\",\"languages\":{\"en\":{\"hash\":\"" + hash + "\"}}}",
                StandardCharsets.UTF_8);
        Files.writeString(bundle.resolve("pagefind.js"), "export const search = () => {};",
                StandardCharsets.UTF_8);
        Files.writeString(bundle.resolve("wasm.en.pagefind"), "wasm", StandardCharsets.UTF_8);
        Files.createDirectories(bundle.resolve("index"));
        Files.writeString(bundle.resolve("index/" + hash + ".pf_index"), "chunk", StandardCharsets.UTF_8);
        Files.createDirectories(bundle.resolve("fragment"));
        Files.writeString(bundle.resolve("fragment/" + hash + ".pf_fragment"), "{}", StandardCharsets.UTF_8);

        long id = indexes.start(SITE, "test", Instant.now());
        String prefix = SearchIndex.prefixOf(SITE, id);
        publication.publish(new PartPublication(prefix, prefix), published);
        indexes.published(id, prefix, 42, Instant.now());
        return id;
    }
}
