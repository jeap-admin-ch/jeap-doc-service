package ch.admin.bit.jeap.doc.domain.architecture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the operations of a specification become the groups of a page.
 */
class RestApiOverviewTest {

    private static ApiOperation operation(String method, String path, String... tags) {
        return new ApiOperation(method, path, method + " " + path, false, List.of(tags));
    }

    private static RestApiOverview overviewOf(List<ApiOperation> operations,
                                              Map<String, String> tagDescriptions) {
        return RestApiOverview.of("2.4.0", "https://orders.example.ch/api", operations, tagDescriptions);
    }

    /**
     * An operation with several tags appears once, under the first. Listing it under each would make the
     * page longer than the API it describes.
     */
    @Test
    void of_groupsAnOperationByItsFirstTagOnly() {
        RestApiOverview overview = overviewOf(List.of(operation("POST", "/api/orders", "Orders", "Intake")),
                Map.of());

        assertThat(overview.groups()).extracting(ApiGroup::name).containsExactly("Orders");
        assertThat(overview.operations()).hasSize(1);
    }

    /** An operation with no tag is not left out: it is filed under one group named for that. */
    @Test
    void of_anOperationWithoutATagIsUngrouped() {
        RestApiOverview overview = overviewOf(List.of(operation("GET", "/api/health")), Map.of());

        assertThat(overview.groups()).singleElement().satisfies(group -> {
            assertThat(group.name()).isEqualTo(RestApiOverview.UNGROUPED);
            assertThat(group.description()).isNull();
            assertThat(group.operations()).hasSize(1);
        });
    }

    /**
     * A tag the specification declares and no operation uses is not a group: it would be an empty table
     * promising part of an API that is not there.
     */
    @Test
    void of_aDeclaredTagNothingUsesIsNotAGroup() {
        RestApiOverview overview = overviewOf(List.of(operation("GET", "/api/orders", "Orders")),
                Map.of("Orders", "Everything about an order", "Reports", "Nobody uses this"));

        assertThat(overview.groups()).extracting(ApiGroup::name).containsExactly("Orders");
        assertThat(overview.groups().getFirst().description()).isEqualTo("Everything about an order");
    }

    /**
     * The groups are alphabetical, with the ungrouped operations last because they are the remainder.
     */
    @Test
    void of_ordersTheGroupsAlphabeticallyWithTheRemainderLast() {
        RestApiOverview overview = overviewOf(List.of(
                operation("GET", "/api/health"),
                operation("GET", "/api/tariffs", "Tariffs"),
                operation("GET", "/api/orders", "Orders")), Map.of());

        assertThat(overview.groups()).extracting(ApiGroup::name)
                .containsExactly("Orders", "Tariffs", RestApiOverview.UNGROUPED);
    }

    /** Within a group the operations are sorted by path and then by method, so a page reads the same twice. */
    @Test
    void of_sortsTheOperationsOfAGroupByPathAndMethod() {
        RestApiOverview overview = overviewOf(List.of(
                operation("POST", "/api/orders", "Orders"),
                operation("GET", "/api/orders/{id}", "Orders"),
                operation("GET", "/api/orders", "Orders")), Map.of());

        assertThat(overview.groups().getFirst().operations())
                .extracting(operation -> operation.method() + " " + operation.path())
                .containsExactly("GET /api/orders", "POST /api/orders", "GET /api/orders/{id}");
    }

    /** A deprecated operation is shown and marked, because a caller of it wants to know. */
    @Test
    void of_keepsADeprecatedOperationAndItsMark() {
        RestApiOverview overview = overviewOf(
                List.of(new ApiOperation("GET", "/api/legacy", "The old way", true, List.of("Orders"))),
                Map.of());

        assertThat(overview.operations()).singleElement().satisfies(operation -> {
            assertThat(operation.deprecated()).isTrue();
            assertThat(operation.summary()).isEqualTo("The old way");
        });
    }

    /** A specification with no operation at all is an empty overview, and the page falls back to the model. */
    @Test
    void of_aSpecificationWithNoOperationIsEmpty() {
        RestApiOverview overview = overviewOf(List.of(), Map.of("Orders", "Declared and unused"));

        assertThat(overview.isEmpty()).isTrue();
        assertThat(overview.version()).isEqualTo("2.4.0");
        assertThat(overview.serverUrl()).isEqualTo("https://orders.example.ch/api");
    }

    /** Two runs over one specification produce the same overview, whatever order the paths arrived in. */
    @Test
    void of_isTheSameOverTwoRuns() {
        List<ApiOperation> operations = List.of(operation("GET", "/api/orders", "Orders"),
                operation("GET", "/api/tariffs", "Tariffs"), operation("GET", "/api/health"));

        assertThat(overviewOf(operations, Map.of())).isEqualTo(overviewOf(operations.reversed(), Map.of()));
    }
}
