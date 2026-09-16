package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessageVersion;
import ch.admin.bit.jeap.doc.domain.architecture.MessageSchema;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageContract;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.ReactionIds;
import ch.admin.bit.jeap.doc.domain.template.ReactionViews;
import ch.admin.bit.jeap.doc.markdown.Markdown;
import ch.admin.bit.jeap.doc.markdown.MarkdownWriter;
import ch.admin.bit.jeap.doc.markdown.Md;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.BUILDING_BLOCK_VIEW;

/**
 * The event and command pages: the successor of the Confluence pages that documented one message each.
 * <p>
 * A message belongs to the system and not to the component that publishes it, so the pages sit below the
 * system's building block view, grouped by kind.
 * <p>
 * The Avro schemas of each version are on the page too, where they have been replicated: the table names them
 * and links them into the message type registry, and the sections below it carry the rendering the
 * architecture repository produces - which is meant to be read and is deliberately not valid Avro IDL.
 */
final class Arc42MessagePages {

    /** Where the events of a system are grouped, inside the building block view. */
    static final String EVENTS = "events";

    /** And the commands, after them. */
    static final String COMMANDS = "commands";

    private static final String TOPIC = "Topic";
    private static final String VERSIONS = "Versions";

    /** The columns of every contract table on a message page. */
    private static final List<String> CONTRACT_COLUMNS = List.of("Component", "System", TOPIC, VERSIONS);

    private Arc42MessagePages() {
    }

    /**
     * Writes the group of one kind of message, and answers whether there is one.
     * <p>
     * False means nothing was written because the system defines no message of the kind. The caller links to
     * the group only then, because a link to a directory nothing wrote fails the build of every site of the
     * environment.
     * <p>
     * Every message the system defines gets a page: its slug is derived and checked by the importer, which
     * refuses a name that yields none, one that would be the listing of its group, and two that yield the
     * same - so nothing here has to be left out, and nothing here decides what a path segment is.
     */
    static boolean write(DocumentedSystem system, MessageKind kind, String group, int position,
                         GenerationContext context, Path buildingBlockDirectory) throws IOException {
        List<DocumentedMessage> messages = system.messagesOfKind(kind);
        if (messages.isEmpty()) {
            return false;
        }
        Path directory = buildingBlockDirectory.resolve(group);
        Arc42Pages.writeCategory(directory, kind.plural(), position);
        writeIndex(system, kind, group, messages, context, directory);
        for (DocumentedMessage message : messages) {
            writeMessage(message, context, directory);
        }
        return true;
    }

    /** Every message of one kind, with who produces and who consumes each. */
    private static void writeIndex(DocumentedSystem system, MessageKind kind, String group,
                                   List<DocumentedMessage> messages, GenerationContext context, Path directory)
            throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(kind.plural(), 0, context))
                .heading(1, kind.plural())
                .paragraph(Md.sentence("The {} the system {} defines.",
                        Md.text(kind.plural().toLowerCase(Locale.ROOT)),
                        Md.code(system.name())));

        List<List<Markdown>> rows = new ArrayList<>();
        for (DocumentedMessage message : messages) {
            rows.add(List.of(
                    Md.link(DocumentationPaths.page(system.slug(), Arc42Template.SYSTEM_SEGMENT,
                            BUILDING_BLOCK_VIEW, group, message.slug()), message.name()),
                    componentsOf(message.producers(), context.model()),
                    componentsOf(message.consumers(), context.model()),
                    Md.text(message.description())));
        }
        page.table(List.of(kind.label(), kind.producerRole(), kind.consumerRole(), "Description"), rows);
        Arc42Pages.write(directory, Arc42Pages.INDEX, page);
    }

    /**
     * The versions of a message type: a row per version, with a sub-row for its key schema and one for its
     * value schema.
     * <p>
     * Each schema is folded in its cell, above its name and what the version is compatible with. A resolved
     * schema is hundreds of lines; open, five versions of them push the contracts off the screen.
     */
    private static void writeVersions(MarkdownWriter page, DocumentedMessage message) {
        page.heading(2, VERSIONS);
        if (message.versions().isEmpty()) {
            page.paragraph("No version of this message is published.");
            return;
        }
        List<MarkdownWriter.Group> groups = new ArrayList<>();
        for (DocumentedMessageVersion version : message.versions()) {
            List<MarkdownWriter.Row> rows = new ArrayList<>();
            if (version.key() != null) {
                // Compatibility is a statement about the value, so the key row only carries it without one.
                rows.add(new MarkdownWriter.Row(Md.text("Key"), cell -> writeSchema(cell, version.key(),
                        version.value() == null ? version : null)));
            }
            if (version.value() != null) {
                rows.add(new MarkdownWriter.Row(Md.text("Value"),
                        cell -> writeSchema(cell, version.value(), version)));
            }
            if (rows.isEmpty()) {
                rows.add(new MarkdownWriter.Row(Markdown.EMPTY,
                        cell -> cell.paragraph("No schema of this version is replicated.")));
            }
            groups.add(new MarkdownWriter.Group(Md.code(version.version()), rows));
        }
        page.groupedTable("Version", "", "Schema", groups);
    }

    /**
     * One schema in its cell: the fold first, so the folds of a version line up at the top of their rows, then
     * the file name and the compatibility.
     * <p>
     * Fenced as {@code java} rather than left plain: the rendering is <b>deliberately not valid Avro IDL</b> -
     * every import is inlined, the namespaces and the enclosing braces are gone - and there is no language for
     * what it actually is. Java highlights it closely enough to be read and wrongly enough that nobody mistakes
     * it for the file, which the schema's link points at.
     *
     * @param compatibility the version whose compatibility this row states, or null
     */
    private static void writeSchema(MarkdownWriter cell, MessageSchema schema, DocumentedMessageVersion compatibility) {
        if (schema.hasSource()) {
            cell.details("Schema", fold -> fold.fence("java", schema.resolvedSchema()));
        }
        cell.paragraph(schemaName(schema));
        if (compatibility != null && compatibility.hasCompatibility()) {
            cell.paragraph(compatibility.compatibleVersion() == null
                    ? Md.sentence("Avro Schema Compatibility: {}", Md.bold(compatibility.compatibilityMode()))
                    : Md.sentence("Avro Schema Compatibility with Version {}: {}",
                            Md.text(compatibility.compatibleVersion()),
                            Md.bold(compatibility.compatibilityMode())));
        }
    }

    /**
     * The schema's file name, linked where the registry URL can carry a link and shown as code where it cannot.
     * <p>
     * {@code linkOrCode} rather than {@code link}, because the URL is whatever the architecture repository
     * stores: {@code link} throws on a target it will not put on a page, and one such value out of one registry
     * would end the generation of every system of the environment, not just this page.
     */
    private static Markdown schemaName(MessageSchema schema) {
        // A blank URL counts as none: linkOrCode answers nothing at all for one, and a cell with nothing in it
        // fails the table and with it the build of the whole part.
        return schema.schemaUrl() == null || schema.schemaUrl().isBlank() ? Md.code(nameOf(schema))
                : Md.linkOrCode(schema.schemaUrl(), nameOf(schema));
    }

    /** Never blank, so that the cell this names always has content. */
    private static String nameOf(MessageSchema schema) {
        return schema.schemaName() == null || schema.schemaName().isBlank() ? "schema" : schema.schemaName();
    }

    /** One message: what it is, its versions, and the contracts on it. */
    private static void writeMessage(DocumentedMessage message, GenerationContext context, Path directory)
            throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(message.name(), 0, context)
                        .put("description", message.description()))
                .heading(1, message.name())
                .paragraphOrNothing(Md.text(message.description()),
                        "The architecture repository holds no description of this message.");

        page.table(List.of("", ""), List.of(
                List.of(Md.text("Kind"), Md.text(message.kind().label())),
                List.of(Md.text("Scope"), Md.textOr(message.scope(), MarkdownWriter.NOT_KNOWN)),
                List.of(Md.text(TOPIC), Md.code(message.topic())),
                List.of(Md.text("Descriptor"), linkOrNothing(message.descriptorUrl())),
                List.of(Md.text("Documentation"), linkOrNothing(message.documentationUrl()))));

        writeVersions(page, message);

        writeContracts(page, message, context.model(), true);
        writeContracts(page, message, context.model(), false);
        writeUnknownContracts(page, message, context.model());

        writeReactions(page, message, context);

        Arc42Pages.write(directory, message.slug() + ".md", page);
    }

    /**
     * What was observed reacting to this message, one diagram per variant.
     * <p>
     * <b>The one runtime view that is not in chapter 6.</b> A message is a building block of the system that
     * defines it, so its page lives in chapter 5 - and a reader who has the message in front of them should
     * not have to go to another chapter to see what answers it. The section names its own source and its own
     * age, because the page's front matter can only name one and it names the model.
     * <p>
     * A message nothing has been observed reacting to has no section at all, as a system with no reactions has
     * no chapter.
     */
    private static void writeReactions(MarkdownWriter page, DocumentedMessage message,
                                       GenerationContext context) {
        List<ReactionViews.VariantView> variants = context.reactions().ofMessage(message.name());
        if (variants.isEmpty()) {
            return;
        }
        page.heading(2, "Reactions");
        page.paragraph(Md.sentence("What was observed reacting to {} at runtime, and what those reactions "
                                   + "published in answer.", Md.code(message.name())));
        // A prefix per diagram, derived from the variant rather than counted, so that a graph on another
        // page can address a node on this one - see ReactionIds.
        List<String> prefixes = ReactionIds.prefixesOf(
                variants.stream().map(ReactionViews.VariantView::variant).toList());
        for (int index = 0; index < variants.size(); index++) {
            ReactionViews.VariantView variant = variants.get(index);
            if (variant.hasVariant()) {
                // A variant is a graph of its own upstream, so it is a heading of its own here: two diagrams
                // with no way to tell which is which would be worse than one.
                page.heading(3, "Variant " + variant.variant());
            }
            Arc42ReactionPages.write(page, variant.view(), context, null, prefixes.get(index));
        }
    }

    private static void writeContracts(MarkdownWriter page, DocumentedMessage message, ArchitectureModel model,
                                       boolean producers) {
        List<MessageContract> contracts = producers ? message.producers() : message.consumers();
        String role = producers ? message.kind().producerRole() : message.kind().consumerRole();
        page.heading(2, role + " Contracts");
        if (contracts.isEmpty()) {
            page.paragraph("None.");
            return;
        }
        List<List<Markdown>> rows = contracts.stream()
                .map(contract -> List.of(
                        componentLink(contract.component(), contract.system(), model),
                        Md.textOr(contract.system(), MarkdownWriter.NOT_KNOWN),
                        Md.code(contract.topic()),
                        Md.joinWith(", ", contract.versions().stream().map(Md::code).toList())))
                .toList();
        page.table(CONTRACT_COLUMNS, rows);
    }

    /**
     * The contracts whose role the architecture repository named in a way this service does not know.
     * <p>
     * Guessing a side would be a wrong answer that looks right, and dropping the contract would hide that the
     * component is involved at all. So the component is listed, and the page says the side is not known.
     */
    private static void writeUnknownContracts(MarkdownWriter page, DocumentedMessage message,
                                              ArchitectureModel model) {
        List<MessageContract> contracts = message.unknownContracts();
        if (contracts.isEmpty()) {
            return;
        }
        page.heading(2, "Contracts With An Unrecognised Role");
        page.paragraph("These components have a contract for this message, and the architecture model names a "
                       + "role this service does not know. Which side they are on is therefore not shown.");
        page.table(CONTRACT_COLUMNS, contracts.stream()
                .map(contract -> List.of(
                        componentLink(contract.component(), contract.system(), model),
                        Md.textOr(contract.system(), MarkdownWriter.NOT_KNOWN),
                        Md.code(contract.topic()),
                        Md.joinWith(", ", contract.versions().stream().map(Md::code).toList())))
                .toList());
    }

    private static Markdown componentsOf(List<MessageContract> contracts, ArchitectureModel model) {
        return Md.joinWith(", ", contracts.stream()
                .map(contract -> componentLink(contract.component(), contract.system(), model))
                .toList());
    }

    /**
     * A component name, linked when this run documents it. A link to a missing page fails the site build.
     * <p>
     * A contract that names its system is resolved through that system: two systems may each have a component
     * of the same name, and the first one found is not the one meant. A contract that names none is linked
     * only when exactly one system has the component - a guess between two would be a wrong link that looks
     * right, so the name is shown as code instead.
     */
    static Markdown componentLink(String component, String system, ArchitectureModel model) {
        if (component == null || component.isBlank()) {
            return Markdown.EMPTY;
        }
        Optional<DocumentedSystem> owner;
        if (system == null || system.isBlank()) {
            List<DocumentedSystem> owners = model.systemsOf(component);
            owner = owners.size() == 1 ? Optional.of(owners.getFirst()) : Optional.empty();
        } else {
            owner = model.systemNamed(system).filter(named -> named.hasComponent(component));
        }
        return owner.flatMap(found -> found.components().stream()
                        .filter(candidate -> candidate.name().equalsIgnoreCase(component))
                        .findFirst()
                        .map(candidate -> Md.link(DocumentationPaths.component(found.slug(),
                                Arc42Template.SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW, candidate.slug()),
                                candidate.name())))
                .orElseGet(() -> Md.code(component));
    }

    /** A descriptor or documentation URL. It is free text, so a bad one is shown as code, not thrown on. */
    private static Markdown linkOrNothing(String url) {
        return Md.linkOrCode(url, "Link");
    }
}
