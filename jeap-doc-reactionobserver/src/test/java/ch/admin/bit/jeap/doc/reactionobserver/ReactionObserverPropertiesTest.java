package ch.admin.bit.jeap.doc.reactionobserver;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The configuration error that would otherwise be silent, exactly as for the architecture import.
 * <p>
 * A cap of zero makes every graph read as too large, and a graph that is too large is skipped - so every run
 * reports having found nothing to do, hour after hour, and the runtime views are simply never there. It fails
 * the deployment instead.
 */
class ReactionObserverPropertiesTest {

    private final ReactionObserverProperties properties = new ReactionObserverProperties();

    @Test
    void check_whenTheGraphCapIsZero_thenTheStartupFails() {
        properties.setMaxGraphSize(DataSize.ofBytes(0));

        assertThatThrownBy(properties::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-graph-size");
    }

    @Test
    void check_whenTheGraphCapIsNegative_thenTheStartupFails() {
        properties.setMaxGraphSize(DataSize.ofBytes(-1));

        assertThatThrownBy(properties::check).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void check_whenTheGraphCapIsMissing_thenTheStartupFails() {
        properties.setMaxGraphSize(null);

        assertThatThrownBy(properties::check).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void check_theDefaults_areLegal() {
        assertThatCode(properties::check).doesNotThrowAnyException();
    }
}
