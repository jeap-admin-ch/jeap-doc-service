package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.content.SystemDocumentationContent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Writes the documentation of the systems: each environment's tree the part carries, with the systems in it and
 * - where the part carries whole trees - each tree's root page.
 */
@Component
@RequiredArgsConstructor
public class SystemDocumentationWriter implements ContentWriter {

    private final SystemPages systemPages;

    @Override
    public String contentSource() {
        return SystemDocumentationContent.SOURCE_ID;
    }

    @Override
    public Map<String, EnvironmentModel> write(Site site, SitePart part, Path contentDirectory,
                                               Instant generatedAt) throws IOException {
        Map<String, EnvironmentModel> models = new LinkedHashMap<>();
        for (SiteEnvironment environment : site.environments()) {
            if (!part.carries(environment.id())) {
                continue;
            }
            Path directory = contentDirectory.resolve(environment.id());
            // The systems first, so the root page can say whether there are any.
            Optional<EnvironmentModel> model = systemPages.write(site, environment, part, directory, generatedAt);
            if (part.carriesWholeEnvironments()) {
                RootPage.write(site, environment, directory, generatedAt, contentsOf(model), systemsRowOf(model));
            }
            model.ifPresent(counts -> models.put(environment.id(), counts));
        }
        return models;
    }

    /** What the root page says about this tree: where to start, or that nothing has been published yet. */
    private static String contentsOf(Optional<EnvironmentModel> model) {
        if (model.map(EnvironmentModel::systemCount).orElse(0) == 0) {
            return "Nothing has been published into it yet: the documentation of the systems, their components "
                   + "and their libraries arrives as the pipelines of those repositories upload it, and as the "
                   + "architecture model is read.";
        }
        return "Start at [Systems](/systems/), which lists every system of the landscape with its components, "
               + "its events and its commands.";
    }

    /**
     * The row counting the systems, or no row at all.
     * <p>
     * An environment that reads no architecture model knows nothing about how many systems there are. A zero
     * would say the landscape is empty rather than that it was never looked at.
     */
    private static String systemsRowOf(Optional<EnvironmentModel> model) {
        return model.map(counts -> "| Systems | " + counts.systemCount() + " |\n").orElse("");
    }
}
