package ch.admin.bit.jeap.doc.sitegenerator;

import java.util.Collection;
import java.util.ArrayList;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.architecture.ComponentType;
import ch.admin.bit.jeap.doc.domain.architecture.DatabaseSchema;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.RestApiOverview;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifact;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifactRef;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactContent;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactRepository;
import ch.admin.bit.jeap.doc.domain.port.MessageSchemaRepository;
import ch.admin.bit.jeap.doc.domain.architecture.MessageVersionSchemas;
import ch.admin.bit.jeap.doc.domain.architecture.imports.MessageVersionRef;
import ch.admin.bit.jeap.doc.domain.architecture.MessageSchema;
import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.BuildMetrics;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplate;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the pages a system gets say, apart from what a template writes into them.
 */
class SystemPagesTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-08-28T06:05:02Z");

    /**
     * When the content of the landscape was imported. A week before the build, because the architecture
     * repository has not changed since - every import in between found it unchanged and wrote nothing.
     */
    private static final Instant CONTENT_IMPORTED_AT = Instant.parse("2026-08-21T05:45:00Z");

    /** When the architecture repository was last read successfully, which is a different question. */
    private static final Instant LAST_SUCCESSFUL_IMPORT = Instant.parse("2026-08-28T05:45:00Z");

    @TempDir
    Path directory;

    /**
     * A template is allowed to write nothing, and a link to a page nothing wrote fails the whole site build:
     * the site is generated with {@code onBrokenLinks: 'throw'}. So the landing page links to the subtrees
     * that exist rather than to the templates that are registered.
     */
    @Test
    void write_whenATemplateWritesNothing_thenTheLandingPageDoesNotLinkToIt() throws IOException {
        SystemPages pages = pagesWith(new SilentTemplate());

        pages.write("default", "prod", wholeSite(), "/", directory, GENERATED_AT);

        String landingPage = Files.readString(directory.resolve("systems").resolve("orders")
                .resolve("index.md"));
        assertThat(landingPage).doesNotContain("/systems/orders/silence/");
        assertThat(landingPage).describedAs("with nothing to link to there is no section either")
                .doesNotContain("## Documentation");
    }

    @Test
    void write_whenATemplateWritesASubtree_thenTheLandingPageLinksToIt() throws IOException {
        SystemPages pages = pagesWith(new WritingTemplate());

        pages.write("default", "prod", wholeSite(), "/", directory, GENERATED_AT);

        assertThat(Files.readString(directory.resolve("systems").resolve("orders").resolve("index.md")))
                .contains("## Documentation")
                .contains("/systems/orders/written/");
    }

    /**
     * What a generated page names as its source: the import <b>its content came from</b>, out of the same
     * snapshot as the content, and not the last import that ran. The two are different whenever the last run
     * found the landscape unchanged and wrote nothing - which, for a landscape nobody is changing, is every run
     * for weeks.
     */
    @Test
    void write_whenTheLastImportFoundTheLandscapeUnchanged_thenThePagesNameTheImportTheContentIsFrom()
            throws IOException {
        RecordingTemplate template = new RecordingTemplate();

        pagesWith(template).write("default", "prod", wholeSite(), "/", directory, GENERATED_AT);

        assertThat(template.context).isNotNull();
        assertThat(template.context.modelImportedAt()).isEqualTo(CONTENT_IMPORTED_AT);
        assertThat(template.context.generatedAt()).isEqualTo(GENERATED_AT);
    }

    /**
     * What the run counted, for the page that describes the documentation: the numbers come off the landscape
     * this build has just generated from, rather than from three queries for something already in hand.
     */
    @Test
    void write_thenItAnswersWhatTheEnvironmentsModelContributed() throws IOException {
        Optional<EnvironmentModel> model = pagesWith(new SilentTemplate())
                .write("default", "prod", wholeSite(), "/", directory, GENERATED_AT);

        assertThat(model).isPresent();
        assertThat(model.orElseThrow().systemCount()).isEqualTo(1);
        assertThat(model.orElseThrow().systems())
                .describedAs("the systems themselves, because the shell's sidebar lists them and they are "
                             + "built as parts of their own - the name as the model spells it, the path as "
                             + "the slug does")
                .extracting(EnvironmentModel.DocumentedSystemEntry::label,
                        EnvironmentModel.DocumentedSystemEntry::path)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("ORDERS", "/systems/orders/"));
        assertThat(model.orElseThrow().importedAt()).isEqualTo(CONTENT_IMPORTED_AT);
    }

    /**
     * An environment that reads no architecture model has nothing to say about how many systems there are, and
     * a zero would say the landscape is empty rather than that it was never looked at.
     */
    @Test
    void write_whenNoArchitectureRepositoryIsConfigured_thenNothingRatherThanZero() throws IOException {
        Optional<EnvironmentModel> model = NoArchitectureModel.systemPages(null)
                .write("default", "prod", wholeSite(), "/", directory, GENERATED_AT);

        assertThat(model).isEmpty();
    }

    /**
     * The schemas are read <b>per system</b> and joined onto the versions of that system's messages, so that a
     * landscape's renderings are never all held while the site generator runs.
     * <p>
     * The fixture spells the system {@code ORDERS} against a slug of {@code orders} and the stub answers with
     * {@code orderspaidevent} against a model that says {@code OrdersPaidEvent}: the two halves are keyed by two
     * different exports of the same upstream, so a join that matched exactly, or on the slug, would quietly
     * find nothing here - and every page would be written complete and without a schema on it.
     */
    @Test
    void write_thenEachSystemsSchemasAreReadForThatSystemAndJoinedOntoItsVersions() throws IOException {
        RecordingTemplate template = new RecordingTemplate();
        RecordingSchemas schemas = new RecordingSchemas();
        new SystemPages(new OneSystem(), schemas, NoArchitectureArtifacts.INSTANCE,
                NoArchitectureArtifacts.INSTANCE, new StructureTemplates(List.of(template)),
                new GeneratorProperties(), new ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties(),
                BuildMetrics.NONE, null).write("default", "prod", wholeSite(), "/", directory, GENERATED_AT);

        assertThat(schemas.asked).describedAs("one read, by the model's own spelling of the system name")
                .containsExactly("prod ORDERS");
        assertThat(template.system).isNotNull();
        assertThat(template.system.messages()).singleElement().satisfies(message ->
                assertThat(message.versions()).singleElement().satisfies(version -> {
                    assertThat(version.version()).isEqualTo("1.0.0");
                    assertThat(version.compatibilityMode()).isEqualTo("BACKWARD");
                    assertThat(version.value().resolvedSchema()).isEqualTo("string orderId;");
                }));
    }

    /** A version nothing was replicated for is left exactly as the model had it. */
    @Test
    void write_whenNothingWasReplicated_thenTheVersionsAreLeftAsTheyAre() throws IOException {
        RecordingTemplate template = new RecordingTemplate();
        new SystemPages(new OneSystem(), NoMessageSchemas.INSTANCE, NoArchitectureArtifacts.INSTANCE,
                NoArchitectureArtifacts.INSTANCE, new StructureTemplates(List.of(template)),
                new GeneratorProperties(), new ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties(),
                BuildMetrics.NONE, null).write("default", "prod", wholeSite(), "/", directory, GENERATED_AT);

        assertThat(template.system.messages()).singleElement().satisfies(message ->
                assertThat(message.versions()).singleElement().satisfies(version -> {
                    assertThat(version.version()).isEqualTo("1.0.0");
                    assertThat(version.hasSchemas()).isFalse();
                }));
    }

    /**
     * The artifacts are read <b>one component and one kind at a time</b> and joined onto that component, so
     * that neither a landscape's nor a system's worth of specifications is ever held at once. A specification
     * may be eight megabytes, and the model a build holds stays in memory until the generator has finished.
     * <p>
     * <b>Addressed by the model's own spellings</b>, {@code ORDERS} and {@code orders-intake}, which the
     * lookup then folds - the stub has the artifact under {@code ORDERS-INTAKE}, the way the replication
     * stored it. An address built from the slug, or a lookup that matched exactly, would find nothing, and
     * every page would come out complete and without a schema or an API on it.
     */
    @Test
    void write_thenTheArtifactsAreReadOneComponentAtATimeAndJoinedOntoIt() throws IOException {
        RecordingTemplate template = new RecordingTemplate();
        RecordingArtifacts artifacts = new RecordingArtifacts();

        new SystemPages(new OneSystem(), NoMessageSchemas.INSTANCE, artifacts, artifacts,
                new StructureTemplates(List.of(template)), new GeneratorProperties(),
                new ArchitectureImportProperties(), BuildMetrics.NONE, null)
                .write("default", "prod", wholeSite(), "/", directory, GENERATED_AT);

        assertThat(artifacts.asked)
                .describedAs("one lookup per component and kind, by the model's own spelling of both names")
                .containsExactly("prod DATABASE_SCHEMA ORDERS/orders-intake",
                        "prod OPENAPI_SPEC ORDERS/orders-intake");
        assertThat(template.system.components()).singleElement().satisfies(component -> {
            assertThat(component.schema()).isNotNull();
            assertThat(component.schema().name()).isEqualTo("orders_db");
            assertThat(component.api()).isNotNull();
            assertThat(component.api().version()).isEqualTo("2.4.0");
        });
    }

    /**
     * A component whose artifacts were never replicated - new, or missed by a run that hit its deadline -
     * is left as the model had it. It keeps its pages and carries no schema and no API, so a replication that
     * is behind never costs a page.
     */
    @Test
    void write_whenNoArtifactWasReplicated_thenTheComponentsAreLeftAsTheyAre() throws IOException {
        RecordingTemplate template = new RecordingTemplate();

        pagesWith(template).write("default", "prod", wholeSite(), "/", directory, GENERATED_AT);

        assertThat(template.system.components()).singleElement().satisfies(component -> {
            assertThat(component.artifacts()).isNull();
            assertThat(component.schema()).isNull();
            assertThat(component.api()).isNull();
        });
    }

    /**
     * An artifact that cannot be read costs its own page and nothing else. There is no {@code try} around
     * the generation of a site, so one that threw here would end the documentation of every system of the
     * environment.
     */
    @Test
    void write_whenAnArtifactCannotBeRead_thenTheComponentSimplyCarriesNone() throws IOException {
        RecordingTemplate template = new RecordingTemplate();
        RecordingArtifacts unreadable = new RecordingArtifacts();
        unreadable.readable = false;

        new SystemPages(new OneSystem(), NoMessageSchemas.INSTANCE, unreadable, unreadable,
                new StructureTemplates(List.of(template)), new GeneratorProperties(),
                new ArchitectureImportProperties(), BuildMetrics.NONE, null)
                .write("default", "prod", wholeSite(), "/", directory, GENERATED_AT);

        assertThat(template.system.components()).singleElement().satisfies(component -> {
            assertThat(component.name()).isEqualTo("orders-intake");
            assertThat(component.schema()).isNull();
            assertThat(component.api()).isNull();
        });
    }

    /**
     * A component the replication holds nothing for is left as the model had it - it keeps its pages and
     * simply carries no schema and no API, which is why a replication that is behind never costs a page.
     */
    @Test
    void write_whenTheReplicationHoldsNothingForTheComponent_thenItIsLeftAsItIs() throws IOException {
        RecordingTemplate template = new RecordingTemplate();
        RecordingArtifacts other = new RecordingArtifacts();
        other.stored = "orders-risk";

        new SystemPages(new OneSystem(), NoMessageSchemas.INSTANCE, other, other,
                new StructureTemplates(List.of(template)), new GeneratorProperties(),
                new ArchitectureImportProperties(), BuildMetrics.NONE, null)
                .write("default", "prod", wholeSite(), "/", directory, GENERATED_AT);

        assertThat(other.asked).describedAs("it is still asked for, once per kind")
                .containsExactly("prod DATABASE_SCHEMA ORDERS/orders-intake",
                        "prod OPENAPI_SPEC ORDERS/orders-intake");
        assertThat(template.system.components()).singleElement().satisfies(component -> {
            assertThat(component.artifacts()).isNull();
            assertThat(component.schema()).isNull();
            assertThat(component.api()).isNull();
        });
    }

    private SystemPages pagesWith(StructureTemplate template) {
        return new SystemPages(new OneSystem(), NoMessageSchemas.INSTANCE, NoArchitectureArtifacts.INSTANCE,
                NoArchitectureArtifacts.INSTANCE, new StructureTemplates(List.of(template)),
                new GeneratorProperties(), new ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties(),
                BuildMetrics.NONE, null);
    }

    /** A landscape of exactly one system, so the assertions are about the pages and not about the model. */
    private static final class OneSystem implements ArchitectureModelSource {

        @Override
        public java.util.Optional<java.time.Instant> lastSuccessfulImportAt(String environment) {
            return java.util.Optional.of(LAST_SUCCESSFUL_IMPORT);
        }

        @Override
        public boolean isConfiguredFor(String environment) {
            return true;
        }

        @Override
        public Optional<String> sourceUrlOf(String environment) {
            return Optional.of("https://archrepo");
        }

        @Override
        public java.util.List<String> systemSlugsOf(String environment) {
            return read(environment).model().systems().stream()
                    .map(ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem::slug).sorted().toList();
        }

        @Override
        public ArchitectureSnapshot read(String environment) {
            return new ArchitectureSnapshot(
                    ArchitectureModel.of(List.of(new DocumentedSystem("ORDERS", "orders", null, List.of(), null,
                            List.of(new DocumentedComponent("orders-intake", "orders-intake", null,
                                    ComponentType.BACKEND_SERVICE, null, null, null, List.of(), null, null,
                                    null)),
                            List.of(), List.of(new ch.admin.bit.jeap.doc.domain.architecture
                            .DocumentedMessage("OrdersPaidEvent", "orders-paid-event",
                            ch.admin.bit.jeap.doc.domain.architecture.MessageKind.EVENT, null, "orders.paid",
                            null, null, null,
                            List.of(ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessageVersion
                                    .of("1.0.0")),
                            List.of()))))),
                    CONTENT_IMPORTED_AT);
        }
    }

    /** A template with nothing to say about this system, which is a legitimate template. */
    private static class SilentTemplate implements StructureTemplate {
        @Override
        public java.util.Set<String> allowedFileExtensions() {
            return java.util.Set.of("md");
        }


        @Override
        public String id() {
            return "silence";
        }

        @Override
        public String systemPathSegment() {
            return "silence";
        }

        @Override
        public String systemLabel() {
            return "Silence";
        }

        @Override
        public String componentPathSegment() {
            return "silence";
        }

        @Override
        public String componentLabel() {
            return "Silence";
        }

        @Override
        public List<StructureChapter> chapters() {
            return List.of();
        }

        @Override
        public void writeSystem(DocumentedSystem system, GenerationContext context, Path systemDirectory)
                throws IOException {
            // Nothing to document, so nothing is written and no folder appears.
        }
    }

    private static final class WritingTemplate extends SilentTemplate {

        @Override
        public String id() {
            return "written";
        }

        @Override
        public String systemPathSegment() {
            return "written";
        }

        @Override
        public String systemLabel() {
            return "Written";
        }

        @Override
        public void writeSystem(DocumentedSystem system, GenerationContext context, Path systemDirectory)
                throws IOException {
            Path own = systemDirectory.resolve(systemPathSegment());
            Files.createDirectories(own);
            Files.writeString(own.resolve("index.md"), "# Written\n");
        }
    }

    /** A template that keeps the context it was handed, so the provenance of a page can be asserted. */
    private static final class RecordingTemplate extends SilentTemplate {

        private GenerationContext context;
        private DocumentedSystem system;

        @Override
        public void writeSystem(DocumentedSystem system, GenerationContext context, Path systemDirectory) {
            this.context = context;
            this.system = system;
        }
    }

    /** The replicated schemas of one system, and a note of which system was asked for. */
    private static final class RecordingSchemas implements MessageSchemaRepository {

        private final List<String> asked = new ArrayList<>();

        @Override
        public List<MessageVersionRef> findRefs(String environment) {
            return List.of();
        }

        /**
         * The row is spelled the way the <b>replication</b> stores it, never the way the caller asked - the two
         * halves come from two exports of the same upstream and need not agree on case. Echoing the argument
         * back would let this stub pass a join that matched on the wrong field, or on the wrong case.
         */
        @Override
        public List<MessageVersionSchemas> findAll(String environment, String system) {
            asked.add(environment + " " + system);
            return List.of(new MessageVersionSchemas(environment, "orders", "orderspaidevent", "1.0.0",
                    "BACKWARD", "0.9.0", null,
                    new MessageSchema("Value.avdl", "https://registry/Value.avdl", "string orderId;"),
                    "\"sha256:one\"", GENERATED_AT));
        }

        @Override
        public void store(MessageVersionSchemas schemas) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void confirm(String environment, String system, String message, String version,
                            Instant checkedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void remove(Collection<MessageVersionRef> versions) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * The replicated artifact of one component, and a note of what was asked for.
     * <p>
     * It stands in for both halves of the reading, so that a test about the join has one place to look. Like
     * the database it replaces it answers <b>one artifact per lookup</b> - a whole system's worth of
     * specifications is never in memory at once - and it folds the component name the way the unique index
     * does, because the model and these rows carry the spellings of two exports of one upstream.
     */
    private static final class RecordingArtifacts implements ArchitectureArtifactRepository,
            ArchitectureArtifactContent {

        private final List<String> asked = new ArrayList<>();

        /** Whether the bytes read back are what the format says, which decides whether a page gets them. */
        private boolean readable = true;

        /** The component the replication has an artifact for, spelled the way <b>it</b> stores it. */
        private String stored = "ORDERS-INTAKE";

        @Override
        public List<ArchitectureArtifactRef> findRefs(String environment, ArchitectureImportKind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ArchitectureArtifact> find(String environment, ArchitectureImportKind kind,
                                                   String system, String componentName) {
            asked.add(environment + " " + kind + " " + system + "/" + componentName);
            if (!stored.equalsIgnoreCase(componentName)) {
                return Optional.empty();
            }
            return Optional.of(new ArchitectureArtifact(environment, kind, "orders", stored, "1",
                    "\"sha256:one\"", new byte[]{'{', '}'}, 2, GENERATED_AT, GENERATED_AT));
        }

        @Override
        public void store(ArchitectureArtifact artifact) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void confirm(String environment, ArchitectureImportKind kind, String system,
                            String componentName, Instant checkedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void remove(Collection<ArchitectureArtifactRef> stored) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int removeOrphans(String environment) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<DatabaseSchema> databaseSchema(ArchitectureArtifact artifact) {
            return readable
                    ? Optional.of(new DatabaseSchema("orders_db", "1.2.3", List.of()))
                    : Optional.empty();
        }

        @Override
        public Optional<RestApiOverview> restApi(ArchitectureArtifact artifact) {
            return readable
                    ? Optional.of(new RestApiOverview("2.4.0", "https://orders.example.ch/api", List.of()))
                    : Optional.empty();
        }
    }

    /** The part that carries the whole of one environment, systems and all. */
    private static SitePart wholeSite() {
        return new SitePart(PartKey.shellOf("default"), "the site itself", "", true, List.of("prod"),
                List.of());
    }
}
