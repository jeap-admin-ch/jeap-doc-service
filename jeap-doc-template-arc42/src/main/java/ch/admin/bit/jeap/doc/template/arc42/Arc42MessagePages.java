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
            writeMessage(system, message, context, directory);
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
        Arc42Pages.provenance(page, context);
        Arc42Pages.write(directory, Arc42Pages.INDEX, page);
    }

    /**
     * The versions of a message type: the table of what exists, and the schemas below it.
     * <p>
     * The table carries the schema <b>names</b> linked into the registry and the compatibility; the schemas
     * themselves go in sections under it, one per version. A resolved schema is hundreds of lines, and inside a
     * table cell that is unreadable.
     */
    private static void writeVersions(MarkdownWriter page, DocumentedMessage message) {
        page.heading(2, VERSIONS);
        if (message.versions().isEmpty()) {
            page.paragraph("No version of this message is published.");
            return;
        }
        List<List<Markdown>> rows = new ArrayList<>();
        for (DocumentedMessageVersion version : message.versions()) {
            rows.add(List.of(Md.code(version.version()),
                    schemaCell(version.key()),
                    schemaCell(version.value()),
                    compatibility(version)));
        }
        page.table(List.of("Version", "Key schema", "Value schema", "Compatibility"), rows);

        for (DocumentedMessageVersion version : message.versions()) {
            if (!version.hasSchemas()) {
                continue;
            }
            page.heading(3, message.name() + " " + version.version());
            writeSchema(page, "Key schema", version.key());
            writeSchema(page, "Value schema", version.value());
        }
    }

    /**
     * One schema, fenced.
     * <p>
     * Fenced as {@code java} rather than left plain: the rendering is <b>deliberately not valid Avro IDL</b> -
     * every import is inlined, the namespaces and the enclosing braces are gone - and there is no language for
     * what it actually is. Java highlights it closely enough to be read and wrongly enough that nobody mistakes
     * it for the file, which the schema's link points at.
     */
    private static void writeSchema(MarkdownWriter page, String side, MessageSchema schema) {
        if (schema == null || !schema.hasSource()) {
            return;
        }
        page.paragraph(Md.join(Md.bold(side), Md.text(": "), schemaName(schema)));
        page.fence("java", schema.resolvedSchema());
    }

    /** The schema's file name, linked into the registry where there is a link to it. */
    private static Markdown schemaCell(MessageSchema schema) {
        return schema == null ? Md.text("") : schemaName(schema);
    }

    /**
     * The schema's file name, linked where the registry URL can carry a link and shown as code where it cannot.
     * <p>
     * {@code linkOrCode} rather than {@code link}, because the URL is whatever the architecture repository
     * stores: {@code link} throws on a target it will not put on a page, and one such value out of one registry
     * would end the generation of every system of the environment, not just this page.
     */
    private static Markdown schemaName(MessageSchema schema) {
        return schema.schemaUrl() == null ? Md.code(nameOf(schema))
                : Md.linkOrCode(schema.schemaUrl(), nameOf(schema));
    }

    private static String nameOf(MessageSchema schema) {
        return schema.schemaName() == null ? "schema" : schema.schemaName();
    }

    /** What a version declares it is compatible with, which is the answer to <i>may I upgrade</i>. */
    private static Markdown compatibility(DocumentedMessageVersion version) {
        if (!version.hasCompatibility()) {
            return Md.text("");
        }
        return version.compatibleVersion() == null
                ? Md.text(version.compatibilityMode())
                : Md.text(version.compatibilityMode() + " with " + version.compatibleVersion());
    }

    /** One message: what it is, its versions, and the contracts on it. */
    private static void writeMessage(DocumentedSystem system, DocumentedMessage message,
                                     GenerationContext context, Path directory) throws IOException {
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

        page.heading(2, "Reactions");
        page.paragraph(Md.sentence("The reaction graph of this message is imported from the reaction observer "
                                   + "and is not published yet. Until then, {} shows which components handle "
                                   + "it.", Md.link(DocumentationPaths.chapter(system.slug(),
                Arc42Template.SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW), "the building block view")));

        Arc42Pages.provenance(page, context);
        Arc42Pages.write(directory, message.slug() + ".md", page);
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
