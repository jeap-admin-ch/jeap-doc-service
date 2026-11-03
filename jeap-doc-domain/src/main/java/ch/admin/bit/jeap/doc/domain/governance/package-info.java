/**
 * The governance import: what it reads from the Governance Service's {@code /docs-api} and stores, and the one
 * class that replicates it.
 * <p>
 * Unlike the architecture import there is no environment and no multi-step chain - one kind of upstream, two
 * indexes, and a resource per system and per rule they list.
 * <p>
 * A build never reaches anything here: the content source and its pages read what was imported through
 * {@link ch.admin.bit.jeap.doc.domain.port.GovernanceResourceRepository}.
 */
package ch.admin.bit.jeap.doc.domain.governance;
