package ch.admin.bit.jeap.doc.domain;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How the generated sites are published and served. Cross-site, like {@link BuildProperties}: one origin, one
 * refresh interval, however many sites.
 */
@Data
@ConfigurationProperties("jeap.doc.publication")
public class PublicationProperties {

    /**
     * The origin the documentation is published under, without a path - it is what the page metadata names, as
     * the canonical URL of a page and its Open Graph tags. The path below it is the context path of the service
     * plus the site.
     */
    private String url;

    /**
     * The title every site of this instance shows beside its logo, so that the header bar reads the same on all
     * of them and the site tabs say where the reader is. Without it, the title of the {@code default} site, or
     * else of the first configured site. A page's title in the browser keeps naming its own site.
     */
    private String title;

    /**
     * How often an instance re-reads which build of a site is the published one, so that it picks up what
     * another instance published without asking the database on every request.
     */
    private Duration refresh = Duration.ofSeconds(10);
}
