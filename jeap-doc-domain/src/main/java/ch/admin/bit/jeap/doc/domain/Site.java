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
 * @param id                  the identifier of the site. Every site but the default one is served below
 *                            {@link #SITE_SEGMENT}, so nothing a site is called can collide with anything
 * @param title               what the navbar and the browser tab say
 * @param tagline             one line under the title, or null
 * @param logo                where the site's logo comes from, or null for the one the template ships
 * @param favicon             where the site's favicon comes from, or null for the one the template ships
 * @param colorScheme         the colour scheme of the template this site uses
 * @param environments        the environments of this site, in the order the switcher shows them
 * @param publicationSchedule the cron expression the site is regenerated on, or null for never
 * @param publishOnUpload     whether an upload for this site asks for a build
 * @param architectureModelRequired whether this site may only be published once the architecture model of
 *                            its environments has been imported
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
        boolean publishOnUpload,
        boolean architectureModelRequired) {

    /**
     * The site an upload belongs to when it names none.
     * <p>
     * An instance that configures no sites at all gets this one with every default. One that configures named
     * sites has it <b>only if it names it</b> - and since an upload without a {@code site} parameter targets it
     * either way, such an instance has to configure it too, or those uploads are refused.
     */
    public static final String DEFAULT_SITE = "default";

    /**
     * The path segment every site but the default one is served below: {@code /site/<id>/}.
     * <p>
     * One segment the service owns, rather than a namespace shared between three things. Before it, a site took
     * a top-level segment - beside the paths the service answers on itself and beside the default site's
     * environments, which take one each - so a site called {@code api} was matched by the API's security chain
     * and answered 401 for every page of it, and a site called {@code dev} fought the default site's
     * {@code dev} environment for the same URLs. Both had to be refused while the service started, by a list of
     * reserved names that had to be kept in step by hand with every path this service or its starters ever
     * answer on. Below one segment of our own, <b>a site may be called anything a slug may be</b>.
     * <p>
     * What it costs is one reservation in the other direction: an environment of the <b>default</b> site may
     * not be called this, because that site's environments are what still take a top-level segment each.
     */
    public static final String SITE_SEGMENT = "site";

    public Site {
        environments = List.copyOf(environments);
    }

    /**
     * Whether this site is served at the context root rather than below {@link #SITE_SEGMENT}.
     */
    public boolean servedAtRoot() {
        return DEFAULT_SITE.equals(id);
    }

    /**
     * The path this site is served under, empty for the default site and {@code /site/<id>} for every other -
     * see {@link #SITE_SEGMENT}.
     */
    public String routePrefix() {
        return servedAtRoot() ? "" : "/" + SITE_SEGMENT + "/" + id;
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
