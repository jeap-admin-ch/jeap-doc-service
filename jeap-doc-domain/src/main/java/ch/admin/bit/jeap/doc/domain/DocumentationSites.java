package ch.admin.bit.jeap.doc.domain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The documentation sites of this instance, resolved from the configuration once and checked while the service
 * starts.
 * <p>
 * The checks are here rather than in a separate validator because they cannot then be bypassed: there is no way
 * to obtain a {@link Site} that has not passed them. A configuration error therefore fails the startup instead
 * of the first build, which is the rule the bucket and the spool directory already follow.
 */
@Slf4j
@Component
public class DocumentationSites {

    /**
     * Path segments the service itself owns, which an environment of the <b>default</b> site may therefore not
     * be named after.
     * <p>
     * That site owns the context root and its environments take a top-level segment each, so an environment
     * called {@code api} would be matched by the security chain of the REST API and answer 401 for every page
     * of it, and one called {@code actuator} would disappear behind the management endpoints. It is a
     * configuration error, so it fails the startup rather than being discovered by a reader.
     * <p>
     * <b>It says nothing about a site id, and nothing about the environments of any other site.</b> Every site
     * but the default one is served below {@link Site#SITE_SEGMENT}, which is a namespace of its own - see the
     * constant.
     */
    static final Set<String> RESERVED_TOP_LEVEL_SEGMENTS = Set.of("api", "actuator", "swagger-ui", "api-docs",
            "webjars", "error", "assets", "img");

    /**
     * The longest a site id may be. A site's build lock is named after it, and the {@code shedlock} table's
     * name column holds 64 characters - so a longer id starts cleanly and then fails every build of that site
     * on an insert, which is the one configuration error this module would otherwise not catch at startup.
     */
    static final int MAX_SITE_ID_LENGTH = 64 - DocumentationBuildRunner.LOCK_PREFIX.length();

    /**
     * Names the site generator uses for itself, which an environment therefore may not be called: the id of its
     * own documentation instance, and the static directory it copies verbatim to the site root.
     */
    static final Set<String> GENERATOR_RESERVED_ENVIRONMENT_IDS = Set.of(Site.DEFAULT_SITE, "static");

    private static final List<SiteEnvironment> DEFAULT_ENVIRONMENTS = List.of(
            new SiteEnvironment("dev", "DEV", "Development", 1, false, true),
            new SiteEnvironment("ref", "REF", "Reference", 2, false, false),
            new SiteEnvironment("abn", "ABN", "Acceptance", 3, false, false),
            new SiteEnvironment("prod", "PROD", "Production", 4, true, false));

    private final Map<String, Site> sites;

    public DocumentationSites(SiteProperties properties) {
        this.sites = resolve(properties);
        log.info("Documentation sites: {}.", describe());
    }

    /**
     * The site with the given id, if it is configured. An upload naming anything else is rejected.
     */
    public Optional<Site> find(String id) {
        return Optional.ofNullable(sites.get(id));
    }

    /**
     * All configured sites, in the order they were configured.
     */
    public List<Site> all() {
        return List.copyOf(sites.values());
    }

    /**
     * The ids of all configured sites, for a message that has to say what does exist.
     */
    public Set<String> ids() {
        return sites.keySet();
    }

    private static Map<String, Site> resolve(SiteProperties properties) {
        Map<String, SiteProperties.Site> configured = properties.getSites().isEmpty()
                // An instance that configures no site gets the default one with every default value.
                ? Map.of(Site.DEFAULT_SITE, new SiteProperties.Site())
                : properties.getSites();

        Map<String, Site> resolved = new LinkedHashMap<>();
        configured.forEach((id, site) -> resolved.put(id, toSite(id, site)));
        // Not Map.copyOf: that is an immutable map whose iteration order is unspecified and in practice
        // randomised per JVM, and both the startup line and the message listing the configured sites read
        // better in the order someone wrote them.
        return java.util.Collections.unmodifiableMap(resolved);
    }

    private static Site toSite(String id, SiteProperties.Site configured) {
        require(Slugs.isSlug(id), "The site id '%s' is not a slug (%s).", id, Slugs.DESCRIPTION);
        require(id.length() <= MAX_SITE_ID_LENGTH,
                "The site id '%s' is %d characters long; at most %d fit the name of the lock its builds take.",
                id, id.length(), MAX_SITE_ID_LENGTH);
        // And that is every rule a site id has to keep. A site is served below Site.SITE_SEGMENT, so it cannot
        // collide with a path this service answers on, with an environment of the default site, or with the
        // URLs of another site.

        // That the colour scheme is one the template ships is checked while the service starts, by the site
        // generator - it reads the stylesheets rather than a list of their names, which is the only way the
        // check cannot drift away from what is actually shipped.
        require(configured.getColorScheme() != null && !configured.getColorScheme().isBlank(),
                "The site '%s' configures no colour scheme.", id);

        List<SiteEnvironment> environments = environmentsOf(id, configured);
        return new Site(
                id,
                titleOf(id, configured.getTitle()),
                configured.getTagline(),
                configured.getLogo(),
                configured.getFavicon() == null ? configured.getLogo() : configured.getFavicon(),
                configured.getColorScheme(),
                environments,
                configured.getPublicationSchedule(),
                configured.isPublishOnUpload(),
                configured.isArchitectureModelRequired());
    }

    /**
     * What the navbar and the browser tab call this site.
     * <p>
     * A site that configures no title is called after its id, which reads well for a named site - but the
     * default site would then be titled "default", and that is the site every instance gets without configuring
     * anything.
     */
    private static String titleOf(String id, String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return Site.DEFAULT_SITE.equals(id) ? "Documentation" : id;
    }

    private static List<SiteEnvironment> environmentsOf(String siteId, SiteProperties.Site configured) {
        if (configured.getEnvironments().isEmpty()) {
            return DEFAULT_ENVIRONMENTS;
        }
        List<SiteEnvironment> environments = configured.getEnvironments().stream()
                .map(environment -> toEnvironment(siteId, environment))
                .sorted(Comparator.comparingInt(SiteEnvironment::order).thenComparing(SiteEnvironment::id))
                .toList();

        require(environments.stream().map(SiteEnvironment::id).distinct().count() == environments.size(),
                "The site '%s' configures the same environment twice.", siteId);
        requireExactlyOne(siteId, environments, SiteEnvironment::main, "main",
                "the environment served at the site root");
        requireExactlyOne(siteId, environments, SiteEnvironment::latest, "latest",
                "where the documentation of a component that is not deployed anywhere goes");
        return environments;
    }

    private static SiteEnvironment toEnvironment(String siteId, SiteProperties.Environment configured) {
        String id = configured.getId();
        require(Slugs.isSlug(id), "The site '%s' configures the environment id '%s', which is not a slug (%s).",
                siteId, id, Slugs.DESCRIPTION);
        // The environments of the default site are what takes a top-level path segment each, so it is there -
        // and only there - that the segments the service answers on itself are unusable: an environment called
        // 'api' would be matched by the API's security chain and answer 401 for every page of that tree. The
        // environments of any other site sit below /site/<id>/ and can be called whatever they like.
        if (Site.DEFAULT_SITE.equals(siteId)) {
            require(!RESERVED_TOP_LEVEL_SEGMENTS.contains(id),
                    "The site '%s' configures an environment called '%s', which is a path the doc service "
                    + "answers on itself. Reserved: %s.", siteId, id,
                    RESERVED_TOP_LEVEL_SEGMENTS.stream().sorted().toList());
            // The one reservation the /site/<id> layout costs: this segment is where every other site is
            // served, so the default site's environment of that name would fight all of them for it.
            require(!Site.SITE_SEGMENT.equals(id),
                    "The site '%s' configures an environment called '%s', which is the path every other "
                    + "documentation site is served below (/%s/<site>/). Rename the environment.",
                    siteId, id, Site.SITE_SEGMENT);
        }
        // And two the site generator itself owns: 'default' is the id of its own docs instance, so an
        // environment named after it fails every build on a duplicate plugin id - and 'static' is one of its
        // static directories, so an environment named after it would have its Markdown copied verbatim to the
        // site root instead of being rendered.
        require(!GENERATOR_RESERVED_ENVIRONMENT_IDS.contains(id),
                "The site '%s' configures an environment called '%s', which is a name the site generator uses "
                + "for itself. Reserved: %s.", siteId, id,
                GENERATOR_RESERVED_ENVIRONMENT_IDS.stream().sorted().toList());
        return new SiteEnvironment(
                id,
                configured.getShortName() == null || configured.getShortName().isBlank()
                        ? id.toUpperCase(Locale.ROOT) : configured.getShortName(),
                configured.getLabel() == null || configured.getLabel().isBlank() ? id : configured.getLabel(),
                configured.getOrder(),
                configured.isMain(),
                configured.isLatest());
    }

    private static void requireExactlyOne(String siteId, List<SiteEnvironment> environments,
                                          Predicate<SiteEnvironment> flag,
                                          String name, String what) {
        List<String> marked = environments.stream().filter(flag).map(SiteEnvironment::id).toList();
        require(marked.size() == 1,
                "The site '%s' marks %d environments as '%s' (%s); exactly one has to be, and it is %s.",
                siteId, marked.size(), name, marked, what);
    }

    private static void require(boolean condition, String message, Object... arguments) {
        if (!condition) {
            throw new IllegalStateException(message.formatted(arguments));
        }
    }

    private String describe() {
        return sites.values().stream()
                .map(site -> "%s (%s, %s)".formatted(site.id(),
                        site.environments().stream().map(SiteEnvironment::id).toList(),
                        site.schedule().map("on '%s'"::formatted).orElse("on upload only")))
                .toList().toString();
    }
}
