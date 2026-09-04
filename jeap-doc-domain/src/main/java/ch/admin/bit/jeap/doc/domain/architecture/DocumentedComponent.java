package ch.admin.bit.jeap.doc.domain.architecture;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureModelImportStep;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * One component of a system, as the architecture repository knows it.
 *
 * @param name           the component name as the architecture repository has it
 * @param slug           the same name, lower-cased: the path segment it is documented under, and the
 *                       one an upload for this component names
 * @param description    what it does, or null
 * @param type           what kind of building block it is
 * @param team           who owns it, already falling back to the system's default owner, or null
 * @param importer       which source it was imported from - the provenance a page shows
 * @param lastSeen       when an importer last saw it, or null
 * @param restApis       the operations it provides
 * @param openApi        its OpenAPI specification, or null
 * @param databaseSchema its database schema, or null
 */
public record DocumentedComponent(
        String name,
        String slug,
        String description,
        ComponentType type,
        Team team,
        String importer,
        ZonedDateTime lastSeen,
        List<RestApiOperation> restApis,
        OpenApiReference openApi,
        DatabaseSchemaReference databaseSchema) {

    /**
     * How long a component may go unseen before its documentation is worth distrusting. The same fortnight the
     * architecture repository uses, so the two agree about what stale means.
     */
    public static final Duration STALE_AFTER = Duration.ofDays(14);

    public DocumentedComponent {
        restApis = restApis == null ? List.of() : List.copyOf(restApis);
    }

    /** Whether nothing has seen this component for a fortnight. The page says so when it has not. */
    public boolean isStaleAt(Instant now) {
        return lastSeen != null && lastSeen.toInstant().isBefore(now.minus(STALE_AFTER));
    }

    /**
     * The same component, addressed under the given slug. The upstream serves the name; how it becomes a path
     * segment is this service's decision, so the importer fills it in.
     */
    public DocumentedComponent withSlug(String slug) {
        return new DocumentedComponent(name, slug, description, type, team, importer, lastSeen, restApis,
                openApi, databaseSchema);
    }

    /**
     * The same component with {@code lastSeen} counted by the day rather than to the second.
     * <p>
     * It is the resolution the import compares a landscape at. An importer upstream advances {@code lastSeen}
     * continuously, so a comparison that reads it to the second is comparing a clock and never matches - see
     * {@code ArchitectureModelImportStep}. Nothing stored or rendered goes through here.
     */
    public DocumentedComponent seenByTheDay() {
        return lastSeen == null ? this
                : new DocumentedComponent(name, slug, description, type, team, importer,
                        lastSeen.truncatedTo(ChronoUnit.DAYS), restApis, openApi, databaseSchema);
    }

    public boolean hasRestApi() {
        return openApi != null || !restApis.isEmpty();
    }
}
