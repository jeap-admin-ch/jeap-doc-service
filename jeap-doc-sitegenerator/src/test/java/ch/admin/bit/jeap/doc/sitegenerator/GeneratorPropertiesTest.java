package ch.admin.bit.jeap.doc.sitegenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A misconfigured instance has to fail while it starts, not on its first build an hour later.
 */
class GeneratorPropertiesTest {

    @Test
    void theDefaultIsADiagramAPersonCanRead() {
        assertThat(new GeneratorProperties().getMaxDiagramNodes()).isEqualTo(100);
        assertThat(new GeneratorProperties().getMaxEdgeLabels()).isEqualTo(4);
        assertThat(new GeneratorProperties().getMaxContextComponents()).isEqualTo(40);
        assertThat(new GeneratorProperties().getMaxSchemaTableDiagram()).isEqualTo(100);
        assertThat(new GeneratorProperties().getMaxSchemaTableList()).describedAs("a list can afford more "
                                                                                 + "than a picture")
                .isEqualTo(200);
    }

    /** What a template is handed is the five bounds in one value, so none of them can be lost on the way. */
    @Test
    void theLimitsHandedToATemplateAreTheConfiguredOnes() {
        GeneratorProperties properties = new GeneratorProperties();
        properties.setMaxDiagramNodes(7);
        properties.setMaxEdgeLabels(0);
        properties.setMaxContextComponents(9);
        properties.setMaxSchemaTableDiagram(11);
        properties.setMaxSchemaTableList(13);
        properties.setMaxDiagramEdges(15);
        properties.setMaxDetailedEdges(3);

        assertThat(properties.limits())
                .isEqualTo(new ch.admin.bit.jeap.doc.domain.template.DiagramLimits(7, 0, 9, 11, 13, 15, 3));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void aContextViewWithRoomForNothing_stopsTheStartup(int components) {
        GeneratorProperties properties = new GeneratorProperties();
        properties.setMaxContextComponents(components);

        assertThatThrownBy(properties::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.generator.max-context-components");
    }

    /**
     * A schema diagram with room for no table at all would be an empty diagram beside a full table list,
     * which is a typo rather than a choice - so it stops the deployment instead of the first build.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void anEntityRelationshipDiagramWithRoomForNothing_stopsTheStartup(int tables) {
        GeneratorProperties properties = new GeneratorProperties();
        properties.setMaxSchemaTableDiagram(tables);

        assertThatThrownBy(properties::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.generator.max-schema-table-diagram");
    }

    /**
     * A schema page with room for no table at all is a typo too. It is the bound that makes the page's cost
     * finite, so it must not be possible to set it to nothing by accident.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void aSchemaPageWithRoomForNoTable_stopsTheStartup(int entries) {
        GeneratorProperties properties = new GeneratorProperties();
        properties.setMaxSchemaTableList(entries);

        assertThatThrownBy(properties::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.generator.max-schema-table-list");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 40, 100, 500})
    void aDiagramWithRoomForTablesAndSiblings_isAccepted(int value) {
        GeneratorProperties properties = new GeneratorProperties();
        properties.setMaxContextComponents(value);
        properties.setMaxSchemaTableDiagram(value);
        properties.setMaxSchemaTableList(value);

        assertThatCode(properties::check).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, 50})
    void anArrowThatMayCarryNamesOrNone_isAccepted(int labels) {
        GeneratorProperties properties = new GeneratorProperties();
        properties.setMaxEdgeLabels(labels);

        assertThatCode(properties::check).doesNotThrowAnyException();
    }

    /**
     * Zero means an arrow always shows a count, which is a choice. Fewer than none is a typo, and it must not
     * wait until the first build an hour later to be noticed.
     */
    @Test
    void anArrowCarryingFewerThanNoNames_stopsTheStartup() {
        GeneratorProperties properties = new GeneratorProperties();
        properties.setMaxEdgeLabels(-1);

        assertThatThrownBy(properties::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.generator.max-edge-labels");
    }

    /**
     * The shipped default leaves the platform's own endpoints out of the documentation. It is asserted here
     * because it is the one default a reader notices on every page of every component.
     */
    @Test
    void theDefaultLeavesTheActuatorOutOfTheDocumentation() {
        GeneratorProperties properties = new GeneratorProperties();

        assertThat(properties.getRestApiExcludedPaths()).containsExactly("/actuator(/.*)?");
        assertThat(properties.apiPaths().documents("/actuator/health")).isFalse();
        assertThat(properties.apiPaths().documents("/api/orders")).isTrue();
    }

    @Test
    void whenTheExcludedPathsAreCleared_thenEveryPathIsDocumented() {
        GeneratorProperties properties = new GeneratorProperties();
        properties.setRestApiExcludedPaths(java.util.List.of());

        assertThatCode(properties::check).doesNotThrowAnyException();
        assertThat(properties.apiPaths().documents("/actuator/health")).isTrue();
    }

    /**
     * A pattern that is not a regular expression stops the deployment. Found on the first page with a REST
     * API instead, it would be a build failing an hour after the deployment that caused it.
     */
    @Test
    void anExcludedPathThatIsNotARegularExpression_stopsTheStartup() {
        GeneratorProperties properties = new GeneratorProperties();
        properties.setRestApiExcludedPaths(java.util.List.of("/actuator("));

        assertThatThrownBy(properties::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.generator.rest-api-excluded-paths")
                .hasMessageContaining("/actuator(");
    }

    @Test
    void byDefault_noComponentIsLeftOutOfTheViews() {
        assertThat(new GeneratorProperties().viewExclusions().excludesNothing()).isTrue();
    }

    @Test
    void aViewExcludedComponentThatIsNotARegularExpression_stopsTheStartup() {
        GeneratorProperties properties = new GeneratorProperties();
        properties.setViewExcludedComponents(java.util.List.of("orders-mock("));

        assertThatThrownBy(properties::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.generator.view-excluded-components")
                .hasMessageContaining("orders-mock(");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 60, 1000})
    void aDiagramWithRoomForAtLeastOneBox_isAccepted(int nodes) {
        GeneratorProperties properties = new GeneratorProperties();
        properties.setMaxDiagramNodes(nodes);

        assertThatCode(properties::check).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void aDiagramWithRoomForNothing_stopsTheStartup(int nodes) {
        GeneratorProperties properties = new GeneratorProperties();
        properties.setMaxDiagramNodes(nodes);

        assertThatThrownBy(properties::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.generator.max-diagram-nodes");
    }

    /** The two bounds on a whitebox picture: the shipped defaults, and what a reader would set by hand. */
    @Test
    void theBoundsOnAWhiteboxPicture_defaultToTwentyAndForty() {
        GeneratorProperties properties = new GeneratorProperties();

        assertThat(properties.limits().maxDetailedEdges()).isEqualTo(20);
        assertThat(properties.limits().maxDiagramEdges()).isEqualTo(40);
        assertThatCode(properties::check).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void aPictureWithRoomForNoRelation_stopsTheStartup(int edges) {
        GeneratorProperties dropped = new GeneratorProperties();
        dropped.setMaxDiagramEdges(edges);
        GeneratorProperties folded = new GeneratorProperties();
        folded.setMaxDetailedEdges(edges);

        assertThatThrownBy(dropped::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.generator.max-diagram-edges");
        assertThatThrownBy(folded::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.generator.max-detailed-edges");
    }

    /**
     * A bound that draws in full beyond the point where it draws nothing is a configuration nobody means: the
     * middle band would be empty, and every picture that is drawn at all would be drawn in full.
     */
    @Test
    void aPictureDrawnInFullBeyondWhereItIsNotDrawnAtAll_stopsTheStartup() {
        GeneratorProperties properties = new GeneratorProperties();
        properties.setMaxDiagramEdges(10);
        properties.setMaxDetailedEdges(11);

        assertThatThrownBy(properties::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.generator.max-detailed-edges")
                .hasMessageContaining("jeap.doc.generator.max-diagram-edges");
    }
}
