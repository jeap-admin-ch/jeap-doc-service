package ch.admin.bit.jeap.doc.domain.governance;

import ch.admin.bit.jeap.doc.domain.Slugs;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which name of one list gets a page on the governance site, and at which slug.
 * <p>
 * The governance site writes its pages by it, and the governance excerpt on the architecture pages links by it,
 * so that an excerpt links to exactly the pages the site has. A second copy of the rule is the one that would
 * link to a page the site left out.
 * <p>
 * Names are asked for in the order of their list, and the order decides: a second name with the slug of an earlier
 * one gets no page, and neither does a name that yields no slug at all - {@code ..} among them - so nothing from
 * upstream is ever used as a path segment unslugged. Both are logged and left out rather than failing the site: a
 * name collision in the Governance Service's own data must not take every other page down with it.
 */
@Slf4j
public final class GovernanceSlugs {

    private final String what;
    private final boolean logged;
    private final Map<String, String> taken = new LinkedHashMap<>();

    private GovernanceSlugs(String what, boolean logged) {
        this.what = what;
        this.logged = logged;
    }

    /**
     * The slugs of one list as the site writes it: a name that gets no page is logged.
     *
     * @param what what the list holds, for the log - {@code system}, {@code component} or {@code rule}
     */
    public static GovernanceSlugs forPages(String what) {
        return new GovernanceSlugs(what, true);
    }

    /**
     * The slugs of one list as a link to the site is resolved by: the same rule, silently - the site already
     * logged what it left out when it was written, and a link is resolved on every request.
     */
    public static GovernanceSlugs forLinks(String what) {
        return new GovernanceSlugs(what, false);
    }

    /**
     * @return the slug of the page this name gets, or null where it gets none - and null for the same name asked
     * for a second time, so that a name listed twice is written once
     */
    public String slugOf(String name) {
        String slug;
        try {
            slug = Slugs.toSlug(name);
        } catch (IllegalArgumentException e) {
            if (logged) {
                log.warn("The {} '{}' has no slug, so no page is generated for it.", what, name);
            }
            return null;
        }
        String already = taken.putIfAbsent(slug, name);
        if (already == null) {
            return slug;
        }
        if (already.equals(name)) {
            return null;
        }
        if (logged) {
            log.warn("The {}s '{}' and '{}' would both be documented at '{}'. No page is generated for '{}'.",
                    what, already, name, slug, name);
        }
        return null;
    }

    /**
     * The slugs of a whole list at once, by name: every name that gets a page, and none that does not.
     */
    public static Map<String, String> ofLinks(String what, Iterable<String> names) {
        GovernanceSlugs slugs = forLinks(what);
        Map<String, String> byName = new LinkedHashMap<>();
        for (String name : names) {
            if (byName.containsKey(name)) {
                continue;
            }
            String slug = slugs.slugOf(name);
            if (slug != null) {
                byName.put(name, slug);
            }
        }
        return byName;
    }
}
