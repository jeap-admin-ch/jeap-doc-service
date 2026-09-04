package ch.admin.bit.jeap.doc.archrepo;

import ch.admin.bit.jeap.doc.domain.architecture.imports.MessageVersionRef;
import ch.admin.bit.jeap.doc.domain.architecture.MessageVersionSchemas;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.SchemaFetch;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The message type schemas over the architecture repository's docs API.
 * <p>
 * What is worth asserting here beyond the mapping: that a version is fetched by the URL the index gave rather
 * than one built here - which is why this test gives the upstream a context path -, that a stored version is
 * revalidated with its tag rather than fetched again, that the names come back as the upstream stores them,
 * and that one bad entry costs one version rather than the run.
 */
class ArchRepoMessageSchemaUpstreamTest {

    private static final String ENVIRONMENT = "dev";
    /**
     * The upstream is given a context path on purpose. A version is fetched by the content URL the index gave,
     * and a path this adapter built from the ref's own names would not carry it - so every fetch here fails if
     * the content URL is ever ignored.
     */
    private static final String CONTEXT_PATH = "/archrepo";
    private static final String INDEX = CONTEXT_PATH + "/docs-api/message-types";
    private static final String VERSION_PATH =
            CONTEXT_PATH + "/docs-api/message-types/orders/OrdersPaidEvent/versions/2.0.0";

    private static final String ETAG = "\"sha256:abc\"";

    private WireMockServer archRepo;
    private ArchRepoMessageSchemaUpstream schemas;

    @BeforeEach
    void setUp() {
        archRepo = new WireMockServer(options().dynamicPort().gzipDisabled(true));
        archRepo.start();
        schemas = new ArchRepoMessageSchemaUpstream(
                TestClients.of(ENVIRONMENT, archRepo.baseUrl() + CONTEXT_PATH),
                new ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties());
    }

    @AfterEach
    void tearDown() {
        archRepo.stop();
    }

    @Test
    void index_thenOneEntryPerVersion() {
        stub(INDEX, 200, """
                {"messageTypes": [
                  {"system": "orders", "message": "OrdersPaidEvent", "kind": "EVENT", "versions": [
                    {"version": "1.0.0", "contentUrl": "/archrepo/docs-api/message-types/orders/OrdersPaidEvent/versions/1.0.0"},
                    {"version": "2.0.0", "contentUrl": "/archrepo/docs-api/message-types/orders/OrdersPaidEvent/versions/2.0.0"}]},
                  {"system": "shipping", "message": "ShippingSentEvent", "kind": "EVENT", "versions": [
                    {"version": "1.0.0", "contentUrl": "/archrepo/docs-api/message-types/shipping/ShippingSentEvent/versions/1.0.0"}]}
                ]}""");

        assertThat(schemas.index(ENVIRONMENT)).extracting(MessageVersionRef::identity)
                .containsExactly("orders OrdersPaidEvent 1.0.0", "orders OrdersPaidEvent 2.0.0",
                        "shipping ShippingSentEvent 1.0.0");
    }

    /**
     * The index is what a run diffs against its store, so it is asked plainly. There is no per-version tag in
     * it to compare and no question a conditional request would answer.
     */
    @Test
    void index_thenNoConditionalRequestIsMade() {
        stub(INDEX, 200, "{\"messageTypes\":[]}");

        schemas.index(ENVIRONMENT);

        archRepo.verify(getRequestedFor(urlEqualTo(INDEX)).withoutHeader("If-None-Match"));
    }

    @Test
    void index_whenAnEntryNamesNoSystemOrNoVersion_thenItIsLeftOutRatherThanStoredUnderNull() {
        stub(INDEX, 200, """
                {"messageTypes": [
                  {"message": "NoSystemEvent", "versions": [{"version": "1.0.0", "contentUrl": "/x"}]},
                  {"system": "orders", "message": "OrdersPaidEvent", "versions": [
                    {"contentUrl": "/no-version"},
                    {"version": "2.0.0", "contentUrl": "/archrepo/docs-api/x"}]}
                ]}""");

        assertThat(schemas.index(ENVIRONMENT)).extracting(MessageVersionRef::identity)
                .containsExactly("orders OrdersPaidEvent 2.0.0");
    }

    /**
     * <b>The kind is part of the upstream's grouping key and of nothing else.</b> A system that defines an
     * event and a command of one name gets two index entries, and the version resource is addressed by system,
     * message type and version - so both entries name the same resource and are the same version to this
     * service. Two refs would have the run store it twice and violate the unique index on those three, which
     * fails the replication of the whole environment for as long as the upstream keeps listing it.
     */
    @Test
    void index_whenAVersionIsListedUnderTwoKinds_thenItIsOneEntry() {
        stub(INDEX, 200, """
                {"messageTypes": [
                  {"system": "orders", "message": "OrdersPaid", "kind": "EVENT", "versions": [
                    {"version": "1.0.0", "contentUrl": "/archrepo/docs-api/message-types/orders/OrdersPaid/versions/1.0.0"}]},
                  {"system": "orders", "message": "OrdersPaid", "kind": "COMMAND", "versions": [
                    {"version": "1.0.0", "contentUrl": "/archrepo/docs-api/message-types/orders/OrdersPaid/versions/1.0.0"}]}
                ]}""");

        assertThat(schemas.index(ENVIRONMENT)).extracting(MessageVersionRef::identity)
                .containsExactly("orders OrdersPaid 1.0.0");
    }

    /**
     * An environment with no architecture repository is a configuration error, and it has to read as one. The
     * client is resolved before the request is attempted for exactly that reason - inside, its exception is
     * caught by the handler that wraps a failed call, and the run is told the upstream "could not be reached
     * at " with nothing after it.
     */
    @Test
    void version_whenTheEnvironmentHasNoArchitectureRepository_thenTheReasonIsTheConfiguration() {
        assertThatThrownBy(() -> schemas.version("nowhere", ref(VERSION_PATH), null))
                .isInstanceOf(ArchitectureModelUnavailableException.class)
                .hasMessageContaining("No architecture repository is configured");
    }

    @Test
    void index_whenTheUpstreamCannotBeRead_thenTheRunIsToldSo() {
        stub(INDEX, 503, "");

        assertThatThrownBy(() -> schemas.index(ENVIRONMENT))
                .isInstanceOf(ArchitectureModelUnavailableException.class);
    }

    @Test
    void version_thenBothSchemasAndTheCompatibility() {
        stub(VERSION_PATH, 200, """
                {"system": "orders", "message": "OrdersPaidEvent", "version": "2.0.0",
                 "compatibilityMode": "BACKWARD", "compatibleVersion": "1.0.0",
                 "key": {"schemaName": "Key.avdl", "schemaUrl": "https://registry/Key.avdl",
                         "resolvedSchema": "string orderId;"},
                 "value": {"schemaName": "Value.avdl", "schemaUrl": "https://registry/Value.avdl",
                           "resolvedSchema": "string orderId;\\nint total;"}}""");

        MessageVersionSchemas version = storedBy(schemas.version(ENVIRONMENT, ref(VERSION_PATH), null));

        assertThat(version.system()).isEqualTo("orders");
        assertThat(version.message()).isEqualTo("OrdersPaidEvent");
        assertThat(version.version()).isEqualTo("2.0.0");
        assertThat(version.compatibilityMode()).isEqualTo("BACKWARD");
        assertThat(version.compatibleVersion()).isEqualTo("1.0.0");
        assertThat(version.key().schemaName()).isEqualTo("Key.avdl");
        assertThat(version.value().resolvedSchema()).isEqualTo("string orderId;\nint total;");
        assertThat(version.replicatedAt()).isNotNull();
    }

    /**
     * The upstream answers with the system and the message type <b>as it stores them</b> - an alias or a
     * differently-cased path resolves to the stored spelling - and the model these rows are joined to by name
     * carries that spelling.
     */
    @Test
    void version_thenTheNamesComeFromThePayloadRatherThanFromTheIndexEntry() {
        stub(VERSION_PATH, 200, """
                {"system": "Orders", "message": "OrdersPaidEvent", "version": "2.0.0",
                 "value": {"resolvedSchema": "string a;"}}""");

        MessageVersionSchemas version = storedBy(schemas.version(ENVIRONMENT, ref(VERSION_PATH), null));

        assertThat(version.system()).isEqualTo("Orders");
    }

    @Test
    void version_whenThereIsNoKeySchema_thenTheKeySideIsAbsent() {
        stub(VERSION_PATH, 200, """
                {"system": "orders", "message": "OrdersPaidEvent", "version": "2.0.0",
                 "value": {"schemaName": "Value.avdl", "resolvedSchema": "string a;"}}""");

        MessageVersionSchemas version = storedBy(schemas.version(ENVIRONMENT, ref(VERSION_PATH), null));

        assertThat(version.key()).isNull();
        assertThat(version.value().hasSource()).isTrue();
    }

    /** Withdrawn between the index and the fetch. One version lost, not a run - the next one is offered it. */
    @Test
    void version_whenItWentAwayAfterTheIndex_thenItIsSkippedRatherThanFailed() {
        stub(VERSION_PATH, 404, "");

        assertThat(schemas.version(ENVIRONMENT, ref(VERSION_PATH), null))
                .isInstanceOf(SchemaFetch.Skipped.class);
    }

    @Test
    void version_whenTheUpstreamCannotBeRead_thenTheRunIsToldSo() {
        stub(VERSION_PATH, 503, "");

        assertThatThrownBy(() -> schemas.version(ENVIRONMENT, ref(VERSION_PATH), null))
                .isInstanceOf(ArchitectureModelUnavailableException.class);
    }

    /** A content URL that is not on this upstream costs its own version and nothing else. */
    @Test
    void version_whenTheContentUrlCannotBeFetched_thenItIsSkipped() {
        SchemaFetch fetch = schemas.version(ENVIRONMENT, MessageVersionRef.listed(ENVIRONMENT, "orders",
                "OrdersPaidEvent", "2.0.0", "https://somewhere.else/docs-api/x"), null);

        assertThat(fetch).isInstanceOf(SchemaFetch.Skipped.class);
    }

    /**
     * <b>Why this adapter is conditional at all.</b> A version rarely moves, but it is not fixed:
     * {@code compatibleVersion} is derived upstream from the version list. The upstream tags every version, so
     * a run that holds one asks with the tag and is told "not modified" without a payload.
     */
    @Test
    void version_whenTheStoredTagIsStillCurrent_thenItIsConfirmedRatherThanFetched() {
        archRepo.stubFor(get(urlEqualTo(VERSION_PATH)).willReturn(aResponse().withStatus(304)));

        SchemaFetch fetch = schemas.version(ENVIRONMENT, ref(VERSION_PATH), ETAG);

        assertThat(fetch).isInstanceOf(SchemaFetch.Unchanged.class);
        archRepo.verify(getRequestedFor(urlEqualTo(VERSION_PATH)).withHeader("If-None-Match", equalTo(ETAG)));
    }

    /** Nothing is stored, so there is nothing for a "not modified" to confirm. It is left for the next run. */
    @Test
    void version_whenItAnswersNotModifiedToAnUnconditionalRequest_thenItIsSkipped() {
        archRepo.stubFor(get(urlEqualTo(VERSION_PATH)).willReturn(aResponse().withStatus(304)));

        assertThat(schemas.version(ENVIRONMENT, ref(VERSION_PATH), null))
                .isInstanceOf(SchemaFetch.Skipped.class);
    }

    /** The tag is what the next run revalidates with, so it is stored beside the schemas. */
    @Test
    void version_thenTheEntityTagIsKeptWithTheSchemas() {
        stub(VERSION_PATH, 200, """
                {"system": "orders", "message": "OrdersPaidEvent", "version": "2.0.0",
                 "value": {"resolvedSchema": "string a;"}}""");

        assertThat(storedBy(schemas.version(ENVIRONMENT, ref(VERSION_PATH), null)).etag()).isEqualTo(ETAG);
    }

    /**
     * A rendering goes into an unbounded column that a build reads whole, per system, so it is capped exactly
     * as an artifact is - and by the same property, because a second one to keep in step would not be one.
     * <p>
     * The cap bounds the <b>read</b>: nothing past it comes off the wire, so an upstream offering a schema of a
     * gigabyte is refused rather than parsed into memory and only then measured.
     */
    @Test
    void version_whenTheAnswerIsLargerThanTheCap_thenItIsSkippedRatherThanStored() {
        // One line: a raw newline is not legal inside a JSON string, and the length is what is capped.
        String huge = "string orderId; ".repeat(600_000);
        stub(VERSION_PATH, 200, """
                {"system": "orders", "message": "OrdersPaidEvent", "version": "2.0.0",
                 "value": {"schemaName": "Value.avdl", "resolvedSchema": "%s"}}""".formatted(huge));

        assertThat(schemas.version(ENVIRONMENT, ref(VERSION_PATH), null))
                .isInstanceOf(SchemaFetch.Skipped.class);
    }

    /** No tag is not a reason to refuse a version: unlike an artifact, it is addressed by its three names. */
    @Test
    void version_whenTheUpstreamServesNoEntityTag_thenItIsStoredAllTheSame() {
        archRepo.stubFor(get(urlEqualTo(VERSION_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"system": "orders", "message": "OrdersPaidEvent", "version": "2.0.0",
                         "value": {"resolvedSchema": "string a;"}}""")));

        MessageVersionSchemas version = storedBy(schemas.version(ENVIRONMENT, ref(VERSION_PATH), null));

        assertThat(version.etag()).isNull();
        assertThat(version.value().resolvedSchema()).isEqualTo("string a;");
    }

    /**
     * <b>A redirect is not followed.</b> The origin of a content URL is checked before it is fetched, and a hop
     * off it would store whatever the Location named as this version's schemas.
     */
    @Test
    void version_whenTheUpstreamRedirects_thenItIsNotFollowedAndTheVersionIsSkipped() {
        archRepo.stubFor(get(urlEqualTo(VERSION_PATH)).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "https://elsewhere.example/steal")));

        assertThat(schemas.version(ENVIRONMENT, ref(VERSION_PATH), null))
                .isInstanceOfSatisfying(SchemaFetch.Skipped.class,
                        skipped -> assertThat(skipped.reason()).contains("redirect"));
    }

    /** A body that is not the JSON this expects costs one version, not the run. */
    @Test
    void version_whenTheBodyIsNotReadableJson_thenItIsSkippedRatherThanFailingTheRun() {
        stub(VERSION_PATH, 200, "<html>not json</html>");

        assertThat(schemas.version(ENVIRONMENT, ref(VERSION_PATH), null))
                .isInstanceOf(SchemaFetch.Skipped.class);
    }

    /**
     * An answer with <b>no list in it</b> is a failure and not an index of nothing: Spring hands out a null
     * body for any zero-length 200, and a run that lists nothing would report a success that replicated
     * nothing at all.
     */
    @Test
    void index_whenTheAnswerCarriesNoListAtAll_thenItFailsRatherThanReadingAsEmpty() {
        archRepo.stubFor(get(urlEqualTo(INDEX)).willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> schemas.index(ENVIRONMENT))
                .isInstanceOf(ArchitectureModelUnavailableException.class)
                .hasMessageContaining("without a list of message types");
    }

    @Test
    void index_whenTheListIsEmpty_thenItReadsAsAnEmptyIndex() {
        stub(INDEX, 200, "{\"messageTypes\":[]}");

        assertThat(schemas.index(ENVIRONMENT)).isEmpty();
    }

    private static MessageVersionRef ref(String contentUrl) {
        return MessageVersionRef.listed(ENVIRONMENT, "orders", "OrdersPaidEvent", "2.0.0", contentUrl);
    }

    /** The schemas of a fetch that stored something, or a failure naming what it answered instead. */
    private static MessageVersionSchemas storedBy(SchemaFetch fetch) {
        assertThat(fetch).isInstanceOf(SchemaFetch.Stored.class);
        return ((SchemaFetch.Stored) fetch).version();
    }

    private void stub(String path, int status, String body) {
        archRepo.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withHeader("ETag", ETAG).withBody(body)));
    }
}
