package ch.admin.bit.jeap.doc.domain.content;

import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The content sources this instance provides, and which one each site has.
 * <p>
 * Every site is checked while the service starts: a {@code content} no source provides, or a site its source
 * refuses, fails the startup and names the site. So {@link #of} never meets an unknown source at run time.
 */
@Slf4j
@Component
public class ContentSources {

    private final Map<String, ContentSource> byId = new TreeMap<>();

    public ContentSources(List<ContentSource> sources, DocumentationSites sites) {
        for (ContentSource source : sources) {
            ContentSource other = byId.put(source.id(), source);
            if (other != null) {
                throw new IllegalStateException("Two content sources are called '%s': %s and %s."
                        .formatted(source.id(), other.getClass().getName(), source.getClass().getName()));
            }
        }
        for (Site site : sites.all()) {
            of(site).check(site);
        }
        log.info("Content sources: {}.", byId.keySet());
    }

    /** The source of the given site. */
    public ContentSource of(Site site) {
        ContentSource source = byId.get(site.content());
        if (source == null) {
            throw new IllegalStateException(("The site '%s' sets content: '%s', which no content source of this "
                    + "instance provides. Provided: %s.").formatted(site.id(), site.content(), byId.keySet()));
        }
        return source;
    }

    /** The ids of the sources this instance provides. */
    public Set<String> ids() {
        return byId.keySet();
    }
}
