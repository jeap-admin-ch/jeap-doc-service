package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.SystemTopology;
import ch.admin.bit.jeap.doc.domain.Slugs;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportMetrics;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * Imports the architecture model of one environment: the whole landscape, fetched whole and written whole.
 * <p>
 * There is no diff and no synchronisation. The upstream computes the entity tag of a model resource over the
 * serialized body, so a conditional request would cost it exactly what an unconditional one costs; and fetching
 * everything makes the write a delete and an insert, which removes torn state, a prune step and any need to
 * detect that a system was deleted.
 * <p>
 * <b>All or nothing.</b> A run that cannot read every system writes nothing at all and leaves the stored
 * landscape serving. A landscape missing one system is not a landscape, and the alternative to failing is
 * silently deleting documentation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArchitectureModelImportStep implements ArchitectureImportStep {

    /**
     * How often phase one starts over when a system disappears under it. Once: the landscape moved, the second
     * attempt sees the list it moved to, and a third would only be chasing an upstream that is changing faster
     * than it can be read.
     */
    private static final int ATTEMPTS = 2;

    /** What every failure of this step ends with: the run is abandoned, and the stored landscape serves on. */
    private static final String LANDSCAPE_IS_KEPT = "The landscape stored before it is still being generated "
                                                    + "from.";

    private final ArchitectureModelUpstream upstream;
    private final ArchitectureModelRepository models;
    private final ArchitectureImportRepository imports;
    private final ArchitectureImportMetrics metrics;
    private final Clock clock;

    @Override
    public ArchitectureImportKind kind() {
        return ArchitectureImportKind.MODEL;
    }

    @Override
    public ImportOutcome run(String environment, Deadline deadline) {
        if (!upstream.environments().contains(environment)) {
            return ImportOutcome.NOT_CONFIGURED;
        }
        Instant startedAt = clock.instant();
        ArchitectureImportState before = imports.state(environment, kind());
        log.info("Importing the architecture model of the environment {} from {}.",
                environment, upstream.urlOf(environment).orElse("the architecture repository"));
        try {
            Fetch fetched = fetch(environment, deadline);
            if (fetched == null) {
                return recordOutcome(environment, before, startedAt, ImportOutcome.PARTIAL,
                        "The import stopped before it had read the whole landscape: %s."
                                .formatted(deadline.reason()));
            }
            if (fetched.contentHash().equals(before.contentHash())) {
                log.debug("The architecture model of the environment {} is unchanged ({} systems).",
                        environment, fetched.model().systems().size());
                return recordOutcome(environment, before, startedAt, ImportOutcome.UNCHANGED, null);
            }
            models.replace(environment, fetched.model(), startedAt);
            log.info("Imported the architecture model of the environment {}: {} systems, {} components, "
                     + "{} messages ({}).", environment, fetched.model().systems().size(),
                    countOf(fetched.model(), system -> system.components().size()),
                    countOf(fetched.model(), system -> system.messages().size()),
                    Duration.between(startedAt, clock.instant()));
            return recordOutcome(environment, before.withContentHash(fetched.contentHash()), startedAt,
                    ImportOutcome.REPLACED, null, fetched.model().systems().size());
        } catch (ImpossibleNameException e) {
            // The one thing here that somebody has to act on: it will not fix itself, and renaming it in the
            // architecture repository is what resolves it.
            log.error("The architecture model of the environment {} was not imported: {} {}",
                    environment, e.getMessage(), LANDSCAPE_IS_KEPT);
            return recordOutcome(environment, before, startedAt, ImportOutcome.FAILED, e.getMessage());
        } catch (ArchitectureModelUnavailableException e) {
            log.warn("The architecture model of the environment {} was not imported: {} {}",
                    environment, e.getMessage(), LANDSCAPE_IS_KEPT);
            return recordOutcome(environment, before, startedAt, ImportOutcome.FAILED, e.getMessage());
        } catch (RuntimeException e) {
            // Anything the write throws - a constraint the upstream data violates, a database that went away.
            // It has to reach the state row and the meter all the same, or the staleness gauge reads healthy
            // for an environment whose import has been failing since the last deployment.
            log.error("The architecture model of the environment {} could not be stored. {}",
                    environment, LANDSCAPE_IS_KEPT, e);
            return recordOutcome(environment, before, startedAt, ImportOutcome.FAILED, e.getMessage());
        }
    }

    /**
     * Everything the landscape is made of, or null when the deadline ran out first.
     * <p>
     * Nothing is written here, and nothing is held open: this is HTTP and no transaction.
     */
    private Fetch fetch(String environment, Deadline deadline) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            List<String> names = upstream.systemNames(environment);
            List<DocumentedSystem> systems = new ArrayList<>();
            Map<String, String> slugs = new LinkedHashMap<>();
            boolean landscapeMoved = false;
            for (String name : names) {
                if (deadline.hasExpired()) {
                    logStoppedByDeadline(environment, deadline, systems.size(), names.size());
                    return null;
                }
                String slug = slugOf("system", "the environment " + environment, name, slugs);
                Optional<SystemTopology> topology = upstream.topology(environment, name);
                Optional<List<DocumentedMessage>> messages = upstream.messages(environment, name);
                if (topology.isEmpty() || messages.isEmpty()) {
                    landscapeMoved = true;
                    break;
                }
                systems.add(systemOf(environment, slug, topology.get(), messages.get()));
            }
            if (!landscapeMoved) {
                ArchitectureModel model = ArchitectureModel.of(systems);
                return new Fetch(model, hashOf(fingerprintOf(model)));
            }
            log.info("A system of the environment {} went away while it was being read; the landscape is read "
                     + "again (attempt {} of {}).", environment, attempt, ATTEMPTS);
        }
        throw new ArchitectureModelUnavailableException(
                ("The landscape of the environment %s kept changing while it was being read. It is left as it "
                 + "was and read again on the next run.").formatted(environment));
    }

    /**
     * A shutdown at INFO: it is what every deployment does to the import that is running, and there is nothing
     * to act on. A deadline that ran out at WARN: that one is a landscape that has grown past its budget.
     */
    private static void logStoppedByDeadline(String environment, Deadline deadline, int read, int total) {
        String message = "The import of the architecture model of the environment {} stopped after "
                         + "{} of {} systems: {}. Nothing is written.";
        if (deadline.isBecauseOfShutdown()) {
            log.info(message, environment, read, total, deadline.reason());
        } else {
            log.warn(message, environment, read, total, deadline.reason());
        }
    }

    /**
     * The path segment a name is documented under. Deriving one rather than refusing the name is what makes
     * sure nothing is ever quietly left out - see {@link Slugs#toSlug}.
     * <p>
     * <b>The same name twice is refused here too</b>, and not left to the database. Systems and components are
     * stored under a unique slug, so the second of two would fail the write on a constraint - and what reaches
     * the operator is then a constraint violation, where every other impossible name here reaches them as the
     * one sentence that resolves it.
     *
     * @param what  what is being named, {@code system} or {@code component}: this is shared, and a message
     *              telling somebody to rename a system when two components collide names the wrong thing
     * @param where where the names have to be unique, for the same reason
     */
    private static String slugOf(String what, String where, String name, Map<String, String> taken) {
        String slug;
        try {
            slug = Slugs.toSlug(name);
        } catch (IllegalArgumentException e) {
            throw new ImpossibleNameException(("The %s '%s' of %s has no slug, so it cannot be a path segment. "
                                               + "Rename it in the architecture repository.")
                    .formatted(what, name, where));
        }
        String already = taken.putIfAbsent(slug, name);
        if (already == null) {
            return slug;
        }
        if (already.equals(name)) {
            throw new ImpossibleNameException(("The %s '%s' is listed twice in %s, and it is documented once. "
                                               + "Fix it in the architecture repository.")
                    .formatted(what, name, where));
        }
        throw new ImpossibleNameException(("The %ss '%s' and '%s' of %s are both documented at '%s', and the "
                                           + "second would overwrite the first. Rename one of them in the "
                                           + "architecture repository.")
                .formatted(what, already, name, where, slug));
    }

    private static DocumentedSystem systemOf(String environment, String slug, SystemTopology topology,
                                             List<DocumentedMessage> messages) {
        Map<String, String> componentSlugs = new LinkedHashMap<>();
        List<DocumentedComponent> components = new ArrayList<>();
        String where = "the system '%s' of the environment %s".formatted(topology.name(), environment);
        for (DocumentedComponent component : topology.components()) {
            components.add(component.withSlug(slugOf("component", where, component.name(), componentSlugs)));
        }
        return new DocumentedSystem(topology.name(), slug, topology.description(), topology.aliases(),
                topology.team(), components, topology.relations(),
                messagesOf(environment, topology.name(), messages));
    }

    /**
     * The messages of one system, each under the segment its page is served at, refusing two that share a
     * name or a segment.
     * <p>
     * A message is stored under its name within its system, so two of them are one row and the second would
     * fail the write with a constraint violation rather than with something a reader can act on. And a message
     * is documented under its slug, so two names that kebab-case the same - {@code OrdersFooEvent} and
     * {@code Orders-Foo-Event} - would be one page, the second written over the first while the listing still
     * named both. Nothing downstream notices one file replacing another, so it is refused here, where every
     * other impossible name is refused: the run is abandoned and the stored landscape serves on.
     * <p>
     * The segment the listing of a group occupies is refused the same way, for the same reason.
     */
    private static List<DocumentedMessage> messagesOf(String environment, String system,
                                                      List<DocumentedMessage> messages) {
        Map<String, DocumentedMessage> byName = new LinkedHashMap<>();
        Map<String, String> slugs = new LinkedHashMap<>();
        List<DocumentedMessage> documented = new ArrayList<>();
        for (DocumentedMessage message : messages) {
            if (byName.putIfAbsent(message.name(), message) != null) {
                throw new ImpossibleNameException(("The system '%s' of the environment %s defines the message "
                                                   + "'%s' twice, and a message is documented once per system. "
                                                   + "Fix it in the architecture repository.")
                        .formatted(system, environment, message.name()));
            }
            documented.add(message.withSlug(messageSlugOf(environment, system, message.name(), slugs)));
        }
        return List.copyOf(documented);
    }

    private static String messageSlugOf(String environment, String system, String name,
                                        Map<String, String> taken) {
        String slug;
        try {
            slug = Slugs.toMessageSlug(name);
        } catch (IllegalArgumentException e) {
            throw new ImpossibleNameException(("The message '%s' of the system '%s' of the environment %s has "
                                               + "no slug, so it cannot be a path segment. Rename it in the "
                                               + "architecture repository.")
                    .formatted(name, system, environment));
        }
        if (DocumentationPaths.INDEX_SEGMENT.equals(slug)) {
            throw new ImpossibleNameException(("The message '%s' of the system '%s' of the environment %s "
                                               + "would be documented at '%s', which is the listing of its "
                                               + "group, and its page would be written over it. Rename it in "
                                               + "the architecture repository.")
                    .formatted(name, system, environment, slug));
        }
        String already = taken.putIfAbsent(slug, name);
        if (already != null) {
            throw new ImpossibleNameException(("The messages '%s' and '%s' of the system '%s' of the "
                                               + "environment %s are both documented at '%s', and the second "
                                               + "would overwrite the first. Rename one of them in the "
                                               + "architecture repository.")
                    .formatted(already, name, system, environment, slug));
        }
        return slug;
    }

    private ImportOutcome recordOutcome(String environment, ArchitectureImportState before, Instant startedAt,
                                 ImportOutcome outcome, String failureReason) {
        return recordOutcome(environment, before, startedAt, outcome, failureReason, before.itemCount());
    }

    private ImportOutcome recordOutcome(String environment, ArchitectureImportState before, Instant startedAt,
                                 ImportOutcome outcome, String failureReason, int itemCount) {
        Instant now = clock.instant();
        boolean succeeded = outcome == ImportOutcome.REPLACED || outcome == ImportOutcome.UNCHANGED;
        imports.save(new ArchitectureImportState(environment, kind(), before.contentHash(), null, succeeded,
                itemCount, now, succeeded ? now : before.lastSuccessAt(), outcome, failureReason));
        metrics.imported(environment, kind(), outcome, Duration.between(startedAt, now), itemCount);
        return outcome;
    }

    private static int countOf(ArchitectureModel model, ToIntFunction<DocumentedSystem> of) {
        return model.systems().stream().mapToInt(of).sum();
    }

    /**
     * What the landscape looked like, in the form two runs compare it in.
     * <p>
     * <b>In the documented order</b>, which {@link ArchitectureModel#of} fixes, and not in the order the
     * upstream happened to list its systems in: how a repository orders its answer is not part of the
     * landscape, and reading it differently twice must not look like a landscape that changed.
     * <p>
     * <b>A component's {@code lastSeen} counts by the day.</b> An importer upstream advances it continuously,
     * so hashing it to the second is hashing a clock - the comparison would never match, and every hourly run
     * of every environment would delete and re-insert every row to store what was already there, which is
     * exactly the cost the hash exists to avoid. By the day it fires for twenty-three runs out of twenty-four,
     * the "Last seen" a page shows is at most a day behind, and the fortnight
     * {@link DocumentedComponent#STALE_AFTER} measures is far coarser than that anyway.
     * <p>
     * Everything else is the record's own {@code toString}, deliberately. A field added anywhere in the tree is
     * hashed without anybody having to remember this method; the price is that adding one invalidates every
     * stored hash and costs one rewrite per environment, which is the harmless direction. Listing the fields by
     * hand would fail in the other one, and a change nobody hashes is a page that never updates.
     */
    private static String fingerprintOf(ArchitectureModel model) {
        StringBuilder fingerprint = new StringBuilder();
        for (DocumentedSystem system : model.systems()) {
            fingerprint.append(system.withComponents(system.components().stream()
                    .map(DocumentedComponent::seenByTheDay).toList())).append('\n');
        }
        return fingerprint.toString();
    }

    /**
     * The fingerprint as one value, so that a run which read the same thing again writes nothing. It is one
     * comparison and not a diff: without it every run would rewrite every row to store what was already there.
     */
    private static String hashOf(String fingerprint) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(fingerprint.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }

    private record Fetch(ArchitectureModel model, String contentHash) {
    }

    /** A name the architecture repository serves that cannot become a path segment. */
    private static final class ImpossibleNameException extends RuntimeException {

        private ImpossibleNameException(String message) {
            super(message);
        }
    }
}
