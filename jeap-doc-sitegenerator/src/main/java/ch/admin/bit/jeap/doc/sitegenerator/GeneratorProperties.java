package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.architecture.view.ExcludedRelation;
import ch.admin.bit.jeap.doc.domain.architecture.view.ViewExclusions;
import ch.admin.bit.jeap.doc.domain.template.DiagramLimits;
import ch.admin.bit.jeap.doc.domain.template.DocumentedApiPaths;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * What the generated pages look like, as opposed to how a build runs.
 */
@Data
@ConfigurationProperties("jeap.doc.generator")
public class GeneratorProperties {

    /**
     * How many <b>other systems</b> a diagram may draw.
     * <p>
     * A system exchanging something with a hundred others renders a picture nobody can read. Above this the
     * diagram is cut and says so; the table on the page still lists every one. A system's own components are
     * never cut - the whitebox view draws all of them.
     */
    private int maxDiagramNodes = 100;

    /**
     * How many names one arrow of a diagram may carry.
     * <p>
     * Above this the arrow shows the count for its kind - {@code 5 Events} - and the page's table names every
     * one of them. This is not only about size: the diagram engine lays a label out by recursion and
     * overflows its stack at about sixty lines, so an uncapped label is a diagram that does not render at all.
     * <p>
     * Zero is legal and means an arrow always shows a count.
     */
    private int maxEdgeLabels = 4;

    /**
     * How many <b>component boxes</b> a component's context view may draw: its siblings and the counterpart
     * components of other systems together.
     * <p>
     * Only the counterparts the component exchanges something with count, so this is reached less often than
     * the size of a landscape suggests. The siblings are drawn first and then one component of each other
     * system in turn, so one large neighbour cannot push a component's own siblings off its own page. Above
     * the bound a system whose components got no box is drawn as a single box instead, the diagram says how
     * many counterparts it left out, and the page's table of relations lists every one.
     */
    private int maxContextComponents = 40;

    /**
     * How many tables an entity relationship diagram may draw.
     * <p>
     * The limit is the reader's browser: the diagram engine lays a diagram out by recursion, so a schema of
     * three hundred tables renders as nothing at all. Above this the page says how many tables were left out,
     * and the list of tables still carries them - up to its own, larger bound below.
     * <p>
     * <b>It used to be called {@code max-schema-tables}</b>, a name that said nothing about which of the two
     * renderings it bounds. Renamed without an alias because nothing set it: a renamed key is not an error
     * here - {@code ignoreUnknownFields} has to stay on - so an instance that had set the old one would have
     * fallen back to the default in silence.
     */
    private int maxSchemaTableDiagram = 100;

    /**
     * How many table entries a database schema page writes with their columns.
     * <p>
     * <b>This is what makes the page's cost finite.</b> The diagram was always bounded and the list never
     * was: one component's page carried 6583 tables and 33 527 rows of columns, which cost 46 minutes of
     * build time on an idle container and an hour and a half under load. Two hundred is generous - 810 of the
     * 830 published schemas in the measured estate hold fewer than that even before their partitions are
     * grouped.
     * <p>
     * Larger than {@code max-schema-table-diagram} on purpose: a diagram of a hundred tables is already at
     * the edge of what renders, and a list can afford far more than a picture.
     */
    private int maxSchemaTableList = 200;

    /**
     * How many relations a whitebox picture may draw at all. Above this it is <b>not drawn</b> and the page
     * says so in a sentence.
     * <p>
     * <b>Dropped whole rather than cut</b>, which no other bound here does. An entity relationship diagram of
     * three hundred tables has a long tail and its first hundred tables are still true; a whitebox view is
     * dense everywhere, so there is no part of it that is both readable and honest. The tables below the
     * picture carry every component and every relation either way.
     */
    private int maxDiagramEdges = 40;

    /**
     * How many relations a whitebox picture may draw <b>in full</b> - an arrow per kind and direction, in its
     * colour, carrying the names. Above this the picture is folded to its shape: one grey line per pair of
     * boxes, with an arrowhead only where the pair is joined one way.
     * <p>
     * Between the two bounds sits the picture whose <i>shape</i> is worth seeing - which components are hubs,
     * which corner of the system is dense - while the names on the arrows are what make it unreadable.
     */
    private int maxDetailedEdges = 20;

    /**
     * The paths of a REST specification this documentation does <b>not</b> describe, as regular expressions
     * that have to match a whole path.
     * <p>
     * <b>The default is the actuator.</b> Every jEAP service publishes the operational endpoints the platform
     * needs - health, metrics, the AppConfig refresh - and they are in its specification. They are not what a
     * reader of the architecture documentation is looking for, and on a small service they outnumber the
     * operations that are.
     * <p>
     * A list rather than a rule about the actuator: a component with a management context path of its own, or
     * an internal API on the same specification, is the same question with a different answer. An empty list
     * documents every path.
     */
    private List<String> restApiExcludedPaths = new ArrayList<>(List.of("/actuator(/.*)?"));

    /**
     * Which paths a page describes - see {@link DocumentedApiPaths}. The patterns are compiled on every call:
     * this bean is mutable, so a value kept in a field would outlive the setter that changed the list.
     */
    public DocumentedApiPaths apiPaths() {
        return DocumentedApiPaths.excluding(restApiExcludedPaths);
    }

    /**
     * The components left out of the diagrams and relations tables of other pages, as regular expressions that
     * have to match a whole component name - a test double that publishes other systems' events, say. Their
     * own pages stay. Empty by default.
     * <p>
     * <b>It bounds the views and what they write, and nothing else.</b> A counterpart column - the callers of
     * an operation, the consumers and publishers of a message - is read from the relations and the contracts
     * rather than from a view, and names an excluded component like any other.
     */
    private List<String> viewExcludedComponents = new ArrayList<>();

    /**
     * The relations left out of the diagrams and relations tables of other pages - the platform's own
     * plumbing rather than a component's architecture, such as every component uploading its database schema
     * to the architecture repository.
     * <p>
     * <b>It removes arrows, never boxes</b>, which is the whole difference to the property above it: a
     * component named here keeps its box on every picture, including the whitebox view of its own system, and
     * its own pages show what it exchanges. So the worst a wrong entry can do is hide a relation.
     * <p>
     * All the fields an entry gives have to match; one it leaves out matches anything. A {@code consumer}, a
     * {@code provider} and a {@code message-type} are regular expressions over the whole name; a {@code path}
     * is <b>not</b> - it is compared literally after the normalisation of {@code RelationPaths}, because
     * {@code /api/openapi/&#123;systemComponentName&#125;} is no regular expression at all and is exactly what
     * an operator writes.
     */
    private List<ExcludedRelationProperties> viewExcludedRelations = new ArrayList<>();

    /** What the views leave out - see {@link ViewExclusions}. Compiled on every call. */
    public ViewExclusions viewExclusions() {
        return ViewExclusions.excluding(viewExcludedComponents,
                viewExcludedRelations.stream().map(ExcludedRelationProperties::toExcludedRelation).toList());
    }

    /**
     * One entry of {@code view-excluded-relations}, as the configuration spells it.
     * <p>
     * A class of its own rather than a string to parse: a grammar would have to be documented, and every
     * mistake in it would come back as a complaint about a format rather than about a field.
     */
    @Data
    public static class ExcludedRelationProperties {

        /** The consuming component, as a regular expression over the whole name. */
        private String consumer;

        /** The providing component, as a regular expression over the whole name. */
        private String provider;

        /** The HTTP method, compared literally and ignoring case. */
        private String method;

        /** The resource path, compared literally after normalisation - not a regular expression. */
        private String path;

        /** The event or command that travels, as a regular expression over the whole name. */
        private String messageType;

        ExcludedRelation toExcludedRelation() {
            return ExcludedRelation.of(consumer, provider, method, path, messageType);
        }
    }

    /** The five bounds in one value, which is what a template is handed. */
    public DiagramLimits limits() {
        return new DiagramLimits(maxDiagramNodes, maxEdgeLabels, maxContextComponents, maxSchemaTableDiagram,
                maxSchemaTableList, maxDiagramEdges, maxDetailedEdges);
    }

    // A configuration error should stop the deployment, not the first build.
    @PostConstruct
    void check() {
        if (maxDiagramNodes < 1) {
            throw new IllegalStateException(
                    "jeap.doc.generator.max-diagram-nodes is " + maxDiagramNodes + ". A diagram needs room "
                    + "for at least one box.");
        }
        if (maxEdgeLabels < 0) {
            throw new IllegalStateException(
                    "jeap.doc.generator.max-edge-labels is " + maxEdgeLabels + ". An arrow cannot carry fewer "
                    + "than no names; zero means it always shows their count.");
        }
        if (maxContextComponents < 1) {
            throw new IllegalStateException(
                    "jeap.doc.generator.max-context-components is " + maxContextComponents + ". A context "
                    + "view needs room for at least one counterpart.");
        }
        if (maxSchemaTableDiagram < 1) {
            throw new IllegalStateException(
                    "jeap.doc.generator.max-schema-table-diagram is " + maxSchemaTableDiagram
                    + ". An entity relationship diagram needs room for at least one table.");
        }
        if (maxSchemaTableList < 1) {
            throw new IllegalStateException(
                    "jeap.doc.generator.max-schema-table-list is " + maxSchemaTableList
                    + ". A schema page needs room for at least one table.");
        }
        if (maxDiagramEdges < 1) {
            throw new IllegalStateException(
                    "jeap.doc.generator.max-diagram-edges is " + maxDiagramEdges + ". A diagram needs room "
                    + "for at least one relation.");
        }
        if (maxDetailedEdges < 1) {
            throw new IllegalStateException(
                    "jeap.doc.generator.max-detailed-edges is " + maxDetailedEdges + ". A diagram needs room "
                    + "for at least one relation drawn in full.");
        }
        // A bound that folds everything it draws is a configuration nobody means: the middle band would be
        // empty and every picture that fits would be grey.
        if (maxDetailedEdges > maxDiagramEdges) {
            throw new IllegalStateException(
                    "jeap.doc.generator.max-detailed-edges is " + maxDetailedEdges + " and "
                    + "jeap.doc.generator.max-diagram-edges is " + maxDiagramEdges
                    + ". A picture cannot be drawn in full beyond the point where it is not drawn at all.");
        }
        try {
            apiPaths();
        } catch (IllegalArgumentException e) {
            // A pattern that does not compile would otherwise throw on the first page that has a REST API,
            // which is a build failing an hour after the deployment that caused it.
            throw new IllegalStateException(
                    "jeap.doc.generator.rest-api-excluded-paths is not usable: " + e.getMessage(), e);
        }
        try {
            ViewExclusions.excluding(viewExcludedComponents);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "jeap.doc.generator.view-excluded-components is not usable: " + e.getMessage(), e);
        }
        // An entry that matches nothing, or everything, is a typo a page would report by silently missing
        // something - so it stops the deployment instead.
        try {
            viewExcludedRelations.forEach(ExcludedRelationProperties::toExcludedRelation);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "jeap.doc.generator.view-excluded-relations is not usable: " + e.getMessage(), e);
        }
    }
}
