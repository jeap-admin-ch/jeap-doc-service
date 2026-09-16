package ch.admin.bit.jeap.doc.domain.template;

/**
 * How much a generated page may render.
 * <p>
 * <b>All but the last bound the picture rather than the facts</b>: whatever a diagram leaves out is still in
 * the table below it on the page. {@code maxSchemaTableList} is the exception - it bounds the facts
 * themselves, because a list of 33 527 rows of columns is not a page a reader can use either, and the page
 * says how many it did not write.
 * <p>
 * They are one record rather than a row of parameters because they are all ints. Swapping two of them would
 * compile and change every page of the site.
 *
 * @param maxDiagramNodes       how many <b>other systems</b> a diagram may draw
 * @param maxEdgeLabels         how many names one arrow may carry before it shows their count instead. Zero is
 *                              legal and means an arrow always shows a count
 * @param maxContextComponents  how many <b>component boxes</b> a component's context view may draw - its
 *                              siblings and the counterpart components of other systems together
 * @param maxSchemaTableDiagram how many tables an entity relationship diagram may draw
 * @param maxSchemaTableList    how many table entries a database schema page writes with their columns
 * @param maxDiagramEdges       how many relations a whitebox picture may draw at all. Above this it is not
 *                              drawn and the page says so: a whitebox view is dense rather than long-tailed,
 *                              so no part of it is both readable and honest
 * @param maxDetailedEdges      how many relations a whitebox picture may draw <b>in full</b> - an arrow per
 *                              kind and direction, in its colour, with the names on it. Above this the
 *                              picture is folded to its shape
 */
public record DiagramLimits(int maxDiagramNodes, int maxEdgeLabels, int maxContextComponents,
                            int maxSchemaTableDiagram, int maxSchemaTableList,
                            int maxDiagramEdges, int maxDetailedEdges) {
}
