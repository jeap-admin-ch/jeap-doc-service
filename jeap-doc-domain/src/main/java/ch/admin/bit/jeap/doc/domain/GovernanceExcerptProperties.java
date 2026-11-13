package ch.admin.bit.jeap.doc.domain;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the governance excerpts on the architecture pages link to.
 */
@Data
@ConfigurationProperties("jeap.doc.governance.excerpts")
public class GovernanceExcerptProperties {

    /**
     * The id of the site with {@code content: governance} the excerpts link into. Only needed where an
     * instance has more than one such site: with one, that is the site, and naming none of several fails the
     * startup rather than picking one.
     */
    private String site;
}
