package ch.admin.bit.jeap.doc.domain;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The documentation sites this instance generates and serves, keyed by site id.
 * <p>
 * Everything that can differ between one site and the next lives here; the machinery that is the same for all
 * of them is {@link BuildProperties} and {@link PublicationProperties}. An instance that configures no site
 * gets the default site with every default value, which is what keeps a single-site instance a short file.
 */
@Data
@ConfigurationProperties("jeap.doc")
public class SiteProperties {

    /**
     * The sites, keyed by their id. The key is a slug: it becomes a path segment of every URL of that site.
     */
    private Map<String, Site> sites = new LinkedHashMap<>();

    /**
     * One documentation site. Every value has a default, so a site may be configured as an empty entry.
     */
    @Data
    public static class Site {

        /**
         * What the navbar and the browser tab say. Defaults to the site id.
         */
        private String title;

        /**
         * One line under the title on the root page.
         */
        private String tagline;

        /**
         * The site's logo, as a {@code classpath:} or filesystem resource. Without one the template's own is
         * used.
         */
        private String logo;

        /**
         * The site's favicon, as a {@code classpath:} or filesystem resource. Without one the logo is used,
         * and without that the template's own.
         */
        private String favicon;

        /**
         * One of the colour schemes the template ships. A site does not bring its own CSS: a free-form
         * stylesheet would make every later change to the template able to break someone's override.
         */
        private String colorScheme = "jeap";

        /**
         * The environments of this site. Empty means the four the doc service defaults to.
         */
        private List<Environment> environments = List.of();

        /**
         * When the site is regenerated, as a cron expression in the time zone of the service. **An empty value
         * means never on a schedule** - the site is then published only when something is uploaded to it. There is no
         * separate enabled flag: a schedule that is not there is a schedule that does not run.
         * <p>
         * The default is hourly through the working day, from 06:05 to 20:05: documentation that is a day old is
         * documentation nobody trusts, and a build outside those hours would only regenerate what nobody is
         * reading. Five minutes past the hour rather than on it, so that every site of every instance does not
         * ask for a build in the same second.
         */
        private String publicationSchedule = "0 5 6-20 * * *";

        /**
         * Whether an upload for this site asks for a build of it.
         */
        private boolean publishOnUpload = true;
    }

    /**
     * One environment of a site, as an instance configures it. {@code routePrefix} is derived rather than
     * configured: it is the id, and nothing at all for the main environment.
     */
    @Data
    public static class Environment {

        /**
         * The identifier of the environment, and the path segment it is served under.
         */
        private String id;

        /**
         * The abbreviation the switcher and the banner show. Defaults to the id in upper case.
         */
        private String shortName;

        /**
         * The name a reader sees. Defaults to the id.
         */
        private String label;

        /**
         * Where the environment appears in the switcher.
         */
        private int order;

        /**
         * Whether this is the environment served at the site root and offered to search engines. **Exactly one
         * environment of a site carries it**, and the service does not start otherwise.
         */
        private boolean main;

        /**
         * Whether this is where the documentation of a component's current state goes before it is deployed
         * anywhere. **Exactly one environment of a site carries it.**
         */
        private boolean latest;
    }
}
