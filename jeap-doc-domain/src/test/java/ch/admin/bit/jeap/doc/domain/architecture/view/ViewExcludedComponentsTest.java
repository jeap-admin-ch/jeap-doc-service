package ch.admin.bit.jeap.doc.domain.architecture.view;

import org.junit.jupiter.api.Test;

import java.util.List;

import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViewExcludedComponentsTest {

    @Test
    void aPatternHasToMatchTheWholeName() {
        ViewExcludedComponents excluded = ViewExcludedComponents.excluding(List.of(".*-mock", "orders-fake"));

        assertThat(excluded.excludes("orders-mock")).isTrue();
        assertThat(excluded.excludes("orders-fake")).isTrue();
        assertThat(excluded.excludes("orders-mockery")).isFalse();
        assertThat(excluded.excludes("orders-fake-2")).isFalse();
        assertThat(excluded.excludes((String) null)).isFalse();
    }

    @Test
    void aRelationIsExcludedWhenEitherEndIs() {
        ViewExcludedComponents excluded = ViewExcludedComponents.excluding(List.of("orders-mock"));

        assertThat(excluded.excludes(event("E", "orders", "orders-mock", "shipping", "shipping-gateway"))).isTrue();
        assertThat(excluded.excludes(event("E", "shipping", "shipping-gateway", "orders", "orders-mock"))).isTrue();
        assertThat(excluded.excludes(event("E", "orders", "orders-intake", "shipping", "shipping-gateway")))
                .isFalse();
    }

    @Test
    void noPatterns_excludeNothing() {
        assertThat(ViewExcludedComponents.excluding(List.of())).isSameAs(ViewExcludedComponents.NONE);
        assertThat(ViewExcludedComponents.excluding(null).excludesNothing()).isTrue();
    }

    @Test
    void aPatternThatIsNotARegularExpression_isRefused() {
        assertThatThrownBy(() -> ViewExcludedComponents.excluding(List.of("orders-mock(")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orders-mock(");
    }
}
