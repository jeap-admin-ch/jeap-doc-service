package ch.admin.bit.jeap.doc.sitegenerator;

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
     * How many <b>sibling components</b> a component's context view may draw.
     * <p>
     * Only the siblings the component exchanges something with count, so this is reached less often than the
     * size of a system suggests. Above it the diagram is cut and says so, and the page's table of relations
     * lists every one.
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

    /** Which paths a page describes, compiled once - see {@link DocumentedApiPaths}. */
    public DocumentedApiPaths apiPaths() {
        return DocumentedApiPaths.excluding(restApiExcludedPaths);
    }

    /** The five bounds in one value, which is what a template is handed. */
    public DiagramLimits limits() {
        return new DiagramLimits(maxDiagramNodes, maxEdgeLabels, maxContextComponents, maxSchemaTableDiagram,
                maxSchemaTableList);
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
                    + "view needs room for at least one neighbour.");
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
        try {
            apiPaths();
        } catch (IllegalArgumentException e) {
            // A pattern that does not compile would otherwise throw on the first page that has a REST API,
            // which is a build failing an hour after the deployment that caused it.
            throw new IllegalStateException(
                    "jeap.doc.generator.rest-api-excluded-paths is not usable: " + e.getMessage(), e);
        }
    }
}
