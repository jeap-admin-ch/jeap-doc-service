package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViewExclusionsTest {

    @Test
    void aPatternHasToMatchTheWholeName() {
        ViewExclusions excluded = ViewExclusions.excluding(List.of(".*-mock", "orders-fake"));

        assertThat(excluded.excludesComponent("orders-mock")).isTrue();
        assertThat(excluded.excludesComponent("orders-fake")).isTrue();
        assertThat(excluded.excludesComponent("orders-mockery")).isFalse();
        assertThat(excluded.excludesComponent("orders-fake-2")).isFalse();
        assertThat(excluded.excludesComponent(null)).isFalse();
    }

    @Test
    void aRelationIsExcludedWhenEitherEndIs() {
        ViewExclusions excluded = ViewExclusions.excluding(List.of("orders-mock"));

        assertThat(excluded.excludes(event("E", "orders", "orders-mock", "shipping", "shipping-gateway"))).isTrue();
        assertThat(excluded.excludes(event("E", "shipping", "shipping-gateway", "orders", "orders-mock"))).isTrue();
        assertThat(excluded.excludes(event("E", "orders", "orders-intake", "shipping", "shipping-gateway")))
                .isFalse();
    }

    @Test
    void noPatterns_excludeNothing() {
        assertThat(ViewExclusions.excluding(List.of())).isSameAs(ViewExclusions.NONE);
        assertThat(ViewExclusions.excluding(null).excludesNothing()).isTrue();
    }

    @Test
    void aPatternThatIsNotARegularExpression_isRefused() {
        assertThatThrownBy(() -> ViewExclusions.excluding(List.of("orders-mock(")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orders-mock(");
    }

    // ----------------------------------------------------------------------------------------------------
    // Leaving out a relation rather than a component.
    // ----------------------------------------------------------------------------------------------------

    /** An entry naming only one end takes everything between that component and anybody. */
    @Test
    void anEntryNamingOneEnd_takesEveryRelationThatReachesIt() {
        ViewExclusions excluded = exclusions(ExcludedRelation.of(null, "archrepo", null, null, null));

        assertThat(excluded.excludes(restApi("POST", "/api/dbschemas", "deploymentlog", "archrepo"))).isTrue();
        assertThat(excluded.excludes(event("E", "orders", "archrepo", "shipping", "shipping-gateway")))
                .describedAs("a message the same component publishes, since the entry names no kind")
                .isTrue();
        assertThat(excluded.excludes(restApi("GET", "/api/orders", "archrepo", "orders-intake")))
                .describedAs("and a call it makes to somebody else is another relation")
                .isFalse();
    }

    /** Every field an entry gives has to match; one it leaves out matches anything. */
    @Test
    void anEntryNamingSeveralFields_takesOnlyTheRelationThatMatchesAllOfThem() {
        ViewExclusions excluded =
                exclusions(ExcludedRelation.of(null, "archrepo", "post", "/api/dbschemas", null));

        assertThat(excluded.excludes(restApi("POST", "/api/dbschemas", "deploymentlog", "archrepo")))
                .describedAs("the method is compared ignoring case")
                .isTrue();
        assertThat(excluded.excludes(restApi("GET", "/api/dbschemas", "deploymentlog", "archrepo"))).isFalse();
        assertThat(excluded.excludes(restApi("POST", "/api/openapi", "deploymentlog", "archrepo"))).isFalse();
        assertThat(excluded.excludes(restApi("POST", "/api/dbschemas", "deploymentlog", "somebody-else")))
                .isFalse();
    }

    /**
     * <b>The case a regular expression cannot express.</b> {@code /api/openapi/&#123;systemComponentName&#125;}
     * does not compile as one - Java reads the brace as a repetition - and it is exactly what an operator
     * writes. It is compared literally after normalisation, which also joins the two spellings of a variable.
     */
    @Test
    void aPathIsMatchedLiterallyAfterNormalisation() {
        ViewExclusions excluded = exclusions(
                ExcludedRelation.of(null, "archrepo", null, "/api/openapi/{systemComponentName}", null));

        assertThat(excluded.excludes(restApi("POST", "/api/openapi/{systemComponentName}", "any", "archrepo")))
                .isTrue();
        assertThat(excluded.excludes(restApi("POST", "/api/openapi/{componentName}/", "any", "archrepo")))
                .describedAs("another name for the variable, and a trailing slash, are the same operation")
                .isTrue();
        assertThat(excluded.excludes(restApi("POST", "/api/openapi", "any", "archrepo"))).isFalse();
    }

    /** A message entry takes messages and no REST call, and a REST entry the other way round. */
    @Test
    void anEntryIsAboutARestCallOrAboutAMessage() {
        ViewExclusions messages = exclusions(ExcludedRelation.of(null, null, null, null, "Orders.*Event"));
        ViewExclusions calls = exclusions(ExcludedRelation.of(null, null, null, "/api/dbschemas", null));

        assertThat(messages.excludes(event("OrdersAcceptedEvent", "orders", "a", "shipping", "b"))).isTrue();
        assertThat(messages.excludes(event("ShippingDoneEvent", "orders", "a", "shipping", "b"))).isFalse();
        assertThat(messages.excludes(restApi("POST", "/api/dbschemas", "a", "b"))).isFalse();
        assertThat(calls.excludes(restApi("POST", "/api/dbschemas", "a", "b"))).isTrue();
        assertThat(calls.excludes(event("OrdersAcceptedEvent", "orders", "a", "shipping", "b"))).isFalse();
    }

    /** An entry with nothing in it would take the whole landscape, and one that can never match is a typo. */
    @Test
    void anEntryThatMatchesEverythingOrNothing_isRefused() {
        assertThatThrownBy(() -> ExcludedRelation.of(null, " ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every relation");
        assertThatThrownBy(() -> ExcludedRelation.of(null, "archrepo", null, "/api/dbschemas", "SomeEvent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no relation carries both");
        assertThatThrownBy(() -> ExcludedRelation.of("orders-mock(", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consumer");
    }

    /** The component's own page says that some of its relations are left out elsewhere. */
    @Test
    void excludesRelationsOf_namesBothEndsOfAnEntry() {
        ViewExclusions excluded =
                exclusions(ExcludedRelation.of("deployment.*", "archrepo", null, "/api/dbschemas", null));

        assertThat(excluded.excludesRelationsOf("archrepo")).isTrue();
        assertThat(excluded.excludesRelationsOf("deploymentlog")).isTrue();
        assertThat(excluded.excludesRelationsOf("orders-intake")).isFalse();
        assertThat(excluded.excludesComponent("archrepo"))
                .describedAs("its box stays: only the arrow goes")
                .isFalse();
        assertThat(exclusions(ExcludedRelation.of(null, null, null, "/api/dbschemas", null))
                .excludesRelationsOf("archrepo"))
                .describedAs("an entry naming no end names no component, so no page claims it")
                .isFalse();
    }

    private static ViewExclusions exclusions(ExcludedRelation... relations) {
        return ViewExclusions.excluding(List.of(), List.of(relations));
    }

    private static SystemRelation restApi(String method, String path, String consumer, String provider) {
        return new SystemRelation(RelationKind.REST_API, "consuming", consumer, "providing", provider, null,
                method, path, null);
    }
}
