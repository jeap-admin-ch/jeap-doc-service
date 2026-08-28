package ch.admin.bit.jeap.doc.domain;

import java.util.List;
import java.util.Optional;

/**
 * One documentation site: what is generated, published and served as a whole.
 * <p>
 * A site is configured rather than discovered. An upload names the site it belongs to, and an upload naming a
 * site nobody configured is rejected - a typo in a workflow configuration would otherwise produce a second
 * documentation site, generated and served next to the real one, and nobody would notice.
 *
 * @param id                  the identifier of the site, and the path segment it is served under unless it is the default site
 * @param title               what the navbar and the browser tab say
 * @param tagline             one line under the title, or null
 * @param logo                where the site's logo comes from, or null for the one the template ships
 * @param favicon             where the site's favicon comes from, or null for the one the template ships
 * @param colorScheme         the colour scheme of the template this site uses
 * @param environments        the environments of this site, in the order the switcher shows them
 * @param publicationSchedule the cron expression the site is regenerated on, or null for never
 * @param publishOnUpload     whether an upload for this site asks for a build
 */
public record Site(
        String id,
        String title,
        String tagline,
        String logo,
        String favicon,
        String colorScheme,
        List<SiteEnvironment> environments,
        String publicationSchedule,
        boolean publishOnUpload) {

    /**
     * The site an upload belongs to when it names none.
     * <p>
     * An instance that configures no sites at all gets this one with every default. One that configures named
     * sites has it <b>only if it names it</b> - and since an upload without a {@code site} parameter targets it
     * either way, such an instance has to configure it too, or those uploads are refused.
     */
    public static final String DEFAULT_SITE = "default";

    public Site {
        environments = List.copyOf(environments);
    }

    /**
     * Whether this site is served at the context root rather than under a path segment of its own.
     */
    public boolean servedAtRoot() {
        return DEFAULT_SITE.equals(id);
    }

    /**
     * The path segment this site is served under, empty for the default site.
     */
    public String routePrefix() {
        return servedAtRoot() ? "" : "/" + id;
    }

    /**
     * The environment served at the site root. Exactly one environment is marked as such, which is checked
     * while the site is built from its configuration.
     */
    public SiteEnvironment mainEnvironment() {
        return environments.stream().filter(SiteEnvironment::main).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "The site %s has no main environment.".formatted(id)));
    }

    /**
     * The environment the documentation of a component's current state goes to, before it is deployed.
     */
    public SiteEnvironment latestEnvironment() {
        return environments.stream().filter(SiteEnvironment::latest).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "The site %s has no latest environment.".formatted(id)));
    }

    /**
     * Whether this site is regenerated on a schedule at all.
     */
    public Optional<String> schedule() {
        return Optional.ofNullable(publicationSchedule).filter(cron -> !cron.isBlank());
    }
}
