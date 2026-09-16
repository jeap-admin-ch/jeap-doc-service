package ch.admin.bit.jeap.doc.domain.architecture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How two spellings of one path are compared. It is the architecture repository's own rule, and both callers -
 * the callers column of a REST API page and the relations left out of the views - share this definition.
 */
class RelationPathsTest {

    @Test
    void normalised_replacesEveryVariableNameAndDropsOneTrailingSlash() {
        assertThat(RelationPaths.normalised("/api/openapi/{systemComponentName}"))
                .isEqualTo(RelationPaths.normalised("/api/openapi/{componentName}/"))
                .isEqualTo("/api/openapi/{}");
        assertThat(RelationPaths.normalised("/api/v4/businesspartner/"))
                .isEqualTo("/api/v4/businesspartner");
        assertThat(RelationPaths.normalised("/api/{one}/and/{two}")).isEqualTo("/api/{}/and/{}");
    }

    /** The root path is the whole path, so its slash stays. */
    @Test
    void normalised_keepsTheSlashOfTheRootPath() {
        assertThat(RelationPaths.normalised("/")).isEqualTo("/");
    }

    @Test
    void normalised_ofNothing_isNothing() {
        assertThat(RelationPaths.normalised(null)).isEmpty();
        assertThat(RelationPaths.normalised("  ")).isEmpty();
    }
}
