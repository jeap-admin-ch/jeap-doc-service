package ch.admin.bit.jeap.doc.domain.template;

import ch.admin.bit.jeap.doc.domain.architecture.ApiGroup;
import ch.admin.bit.jeap.doc.domain.architecture.ApiOperation;
import ch.admin.bit.jeap.doc.domain.architecture.RestApiOverview;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which paths of a specification a page describes, and which it leaves to the specification itself.
 * <p>
 * The default is the actuator: every jEAP service publishes the platform's operational endpoints, and on a
 * small service they outnumber the operations a reader came for.
 */
class DocumentedApiPathsTest {

    /** What the shipped default is, so that a change to it fails here rather than on a page. */
    private static final List<String> DEFAULT = List.of("/actuator(/.*)?");

    private static ApiOperation operation(String path) {
        return new ApiOperation("GET", path, "", false, List.of("Orders"));
    }

    @Test
    void theDefault_excludesTheActuatorAndNothingElse() {
        DocumentedApiPaths paths = DocumentedApiPaths.excluding(DEFAULT);

        assertThat(paths.documents("/actuator")).isFalse();
        assertThat(paths.documents("/actuator/health")).isFalse();
        assertThat(paths.documents("/actuator/health/readiness")).isFalse();
        assertThat(paths.documents("/api/orders")).isTrue();
        assertThat(paths.documents("/api/orders/{id}")).isTrue();
    }

    /**
     * <b>A pattern has to match the whole path.</b> A substring search would make an entry like
     * {@code /health} exclude {@code /api/health-reports} as well, which is not what a list of paths means.
     */
    @Test
    void aPatternMatchesTheWholePathAndNotAPartOfIt() {
        DocumentedApiPaths paths = DocumentedApiPaths.excluding(List.of("/health"));

        assertThat(paths.documents("/health")).isFalse();
        assertThat(paths.documents("/api/health")).isTrue();
        assertThat(paths.documents("/health-reports")).isTrue();
    }

    /** An instance that wants everything documented configures nothing, and nothing is a legal answer. */
    @Test
    void whenNothingIsExcluded_thenEveryPathIsDocumented() {
        assertThat(DocumentedApiPaths.ALL.documents("/actuator/health")).isTrue();
        assertThat(DocumentedApiPaths.ALL.excludesNothing()).isTrue();
        assertThat(DocumentedApiPaths.excluding(List.of()).excludesNothing()).isTrue();
        assertThat(DocumentedApiPaths.excluding(null).excludesNothing()).isTrue();
    }

    /**
     * A group the exclusions empty is gone, rather than a heading over an empty table: that would promise a
     * part of the API that is not documented at all.
     */
    @Test
    void documented_thenTheExcludedOperationsAndTheGroupsTheyEmptyAreGone() {
        RestApiOverview declared = new RestApiOverview("1.0", "https://orders.example.ch", List.of(
                new ApiGroup("Orders", "Everything about an order",
                        List.of(operation("/api/orders"), operation("/actuator/info"))),
                new ApiGroup("Actuator", "The platform's own",
                        List.of(operation("/actuator/health"), operation("/actuator/metrics")))));

        RestApiOverview documented = DocumentedApiPaths.excluding(DEFAULT).documented(declared);

        assertThat(documented.groups()).extracting(ApiGroup::name).containsExactly("Orders");
        assertThat(documented.operations()).extracting(ApiOperation::path).containsExactly("/api/orders");
        assertThat(documented.version()).describedAs("what the specification says about itself is untouched")
                .isEqualTo("1.0");
        assertThat(documented.serverUrl()).isEqualTo("https://orders.example.ch");
    }

    /** Nothing to do is nothing done: the same instance comes back, so a page pays nothing for the default. */
    @Test
    void documented_whenNothingIsExcluded_thenTheOverviewIsTheSameOne() {
        RestApiOverview declared = new RestApiOverview("1.0", null,
                List.of(new ApiGroup("Orders", null, List.of(operation("/actuator/health")))));

        assertThat(DocumentedApiPaths.ALL.documented(declared)).isSameAs(declared);
        assertThat(DocumentedApiPaths.excluding(DEFAULT).documented(null)).isNull();
    }

    /**
     * A pattern that is not a regular expression is a configuration error, and it has to stop the startup -
     * found on the first page with a REST API instead, it is a build failing an hour after the deployment.
     */
    @Test
    void excluding_whenAPatternIsNotARegularExpression_thenItIsRefused() {
        assertThatThrownBy(() -> DocumentedApiPaths.excluding(List.of("/actuator(")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/actuator(")
                .hasMessageContaining("not a regular expression");
    }
}
