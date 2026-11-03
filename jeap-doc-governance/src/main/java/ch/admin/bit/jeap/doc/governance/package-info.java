/**
 * The client of the Governance Service's {@code /docs-api}: the adapter behind {@link
 * ch.admin.bit.jeap.doc.domain.port.GovernanceUpstream}, the one upstream port the governance import reads.
 * <p>
 * An adapter like any other: it depends on the domain, on {@code jeap-doc-upstream} for the transport every
 * outbound client of this service shares, and on no other adapter.
 */
package ch.admin.bit.jeap.doc.governance;
