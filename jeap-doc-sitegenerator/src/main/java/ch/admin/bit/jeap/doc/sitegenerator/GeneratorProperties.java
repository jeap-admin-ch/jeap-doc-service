package ch.admin.bit.jeap.doc.sitegenerator;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
    }
}
