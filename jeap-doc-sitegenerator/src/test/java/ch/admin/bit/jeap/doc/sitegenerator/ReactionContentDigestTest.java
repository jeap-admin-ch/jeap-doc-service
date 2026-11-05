package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.PublicationProperties;
import ch.admin.bit.jeap.doc.domain.ReactionNetworkPublication;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.doc.domain.SystemSitePartition;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions.Identity;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions.ObservedReaction;
import ch.admin.bit.jeap.doc.domain.architecture.PublishedReactions;
import ch.admin.bit.jeap.doc.markdown.MarkdownWriter;
import ch.admin.bit.jeap.doc.markdown.Md;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static ch.admin.bit.jeap.doc.markdown.FrontMatter.frontMatter;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A newer environment snapshot must only rebuild the parts whose content changed. */
class ReactionContentDigestTest {
    private static final Instant GENERATED_AT = Instant.parse("2026-11-05T12:00:00Z");
    private static final Instant IMPORTED_AT = Instant.parse("2026-11-05T09:00:00Z");
    private static final Instant IMPORTED_LATER = Instant.parse("2026-11-05T10:00:00Z");

    @TempDir
    Path directory;

    private final ReactionNetworkPublication networks = mock(ReactionNetworkPublication.class);
    private final Site site = new DocumentationSites(new SiteProperties()).find("default").orElseThrow();
    private SiteSources sources;

    @BeforeEach
    void setUp() throws IOException {
        SystemPages pages = mock(SystemPages.class);
        when(pages.write(any(Site.class), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            SitePart part = invocation.getArgument(2);
            Path environmentDirectory = invocation.getArgument(3);
            Optional<PublishedReactions> snapshot = invocation.getArgument(5);
            if (part.carriesSystems() && snapshot.isPresent()) {
                writeReactionPage(environmentDirectory.resolve(part.tree()), snapshot.orElseThrow());
            }
            return Optional.empty();
        });
        PublicationProperties publication = new PublicationProperties();
        publication.setUrl("https://doc.example.ch");
        sources = new SiteSources(new SiteUrls(publication, ""), new DefaultResourceLoader(), pages,
                new DocumentationSites(new SiteProperties()),
                new SystemSitePartition(NoArchitectureModel.INSTANCE, new NoCustomDocumentation()),
                new BuildProperties(), TestProvenance.of(NoArchitectureModel.INSTANCE),
                new AboutThisDocumentation(), new ReactionNetworkFiles(networks));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void onlyTheReactionImportTimeChanges_thenTheSystemDigestStaysTheSame(String environment) throws IOException {
        SitePart part = systemPart(environment);
        String before = digestOf("before", part, snapshot(IMPORTED_AT, 1, 2));
        String after = digestOf("after", part, snapshot(IMPORTED_LATER, 1, 2));

        assertThat(Files.readString(directory.resolve("after/" + environment + "/systems/orders/reactions.md")))
                .contains(IMPORTED_LATER.toString(), DisplayTime.of(IMPORTED_LATER));
        assertThat(after).isEqualTo(before);
    }

    @Test
    void anotherSystemsReactionsChange_thenTheSystemDigestStaysTheSame() throws IOException {
        SitePart part = systemPart("prod");
        String before = digestOf("before", part, snapshot(IMPORTED_AT, 1, 2));
        String after = digestOf("after", part, snapshot(IMPORTED_LATER, 1, 9));

        assertThat(after).isEqualTo(before);
    }

    @Test
    void thisSystemsReactionsChange_thenTheSystemDigestChanges() throws IOException {
        SitePart part = systemPart("prod");
        String before = digestOf("before", part, snapshot(IMPORTED_AT, 1, 2));
        String after = digestOf("after", part, snapshot(IMPORTED_AT, 8, 2));

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void graphContentChangesAtTheSameImportTime_thenTheShellDigestChanges() throws IOException {
        SitePart shell = new SitePart(PartKey.shellOf(site.id()), "the site", "", false,
                List.of("prod"), List.of());
        String before = digestOf("before", shell, snapshot(IMPORTED_AT, 1, 2));
        String after = digestOf("after", shell, snapshot(IMPORTED_AT, 1, 9));

        assertThat(directory.resolve("after/static/reaction-graph.json")).exists();
        assertThat(after).isNotEqualTo(before);
    }

    private String digestOf(String folder, SitePart part, PublishedReactions snapshot) throws IOException {
        when(networks.read(part.environments().getFirst())).thenReturn(Optional.of(snapshot));
        Path content = directory.resolve(folder);
        WrittenContent written = sources.write(1, site, part, content, GENERATED_AT);
        return ContentDigest.of(content, written.volatileTimestamps(), "1.0.0");
    }

    private SitePart systemPart(String environment) {
        return new SitePart(PartKey.of(site.id(), "system-orders"), "orders", "systems/orders", true,
                List.of(environment), List.of("/systems/orders/"));
    }

    private static PublishedReactions snapshot(Instant importedAt, int ordersMedian, int shippingMedian) {
        ObservedReactions graph = new ObservedReactions(List.of(), List.of(
                new ObservedReaction(new Identity("REACTION", "orders"), "orders-api", BigDecimal.valueOf(ordersMedian)),
                new ObservedReaction(new Identity("REACTION", "shipping"), "shipping-api", BigDecimal.valueOf(shippingMedian))),
                List.of(), List.of());
        return new PublishedReactions(graph, importedAt, List.of(), List.of(), false);
    }

    /** A page writer exposes both timestamp formats and the content of one system to the digest. */
    private static void writeReactionPage(Path directory, PublishedReactions snapshot) throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(frontMatter().put("doc_reactions_imported_at", snapshot.importedAt().toString()))
                .paragraph(Md.sentence("Imported {}.", Md.text(DisplayTime.of(snapshot.importedAt()))))
                .table(List.of("Component", "Median"), snapshot.observations().reactions().stream()
                        .filter(reaction -> reaction.component().equals("orders-api"))
                        .map(reaction -> List.of(Md.code(reaction.component()), Md.text(reaction.median().toPlainString())))
                        .toList());
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("reactions.md"), page.text());
    }
}
