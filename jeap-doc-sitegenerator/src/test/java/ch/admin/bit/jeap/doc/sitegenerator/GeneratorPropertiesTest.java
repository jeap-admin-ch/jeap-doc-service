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
}
