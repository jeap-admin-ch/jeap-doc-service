/**
 * How the architecture model is replicated into this service's own database, and what one run of that is.
 * <p>
 * The job, its schedule, its executor and its shutdown; the four kinds and the step that replicates each of
 * them; the deadline one run is given, the outcome it records, and the references and content it works on.
 * <p>
 * <b>It depends on {@link ch.admin.bit.jeap.doc.domain.architecture} and that package does not depend on it.</b>
 * A build never reaches anything here: it reads what was imported, through
 * {@link ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource}.
 */
package ch.admin.bit.jeap.doc.domain.architecture.imports;
