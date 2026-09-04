/**
 * The views computed across the landscape: what a diagram of one system shows.
 * <p>
 * A system's context view and its level-1 whitebox view cannot be computed from that system alone - in the
 * architecture repository a relation belongs to the system that <i>defines</i> it, so a system that only
 * consumes another's events exports no relations while its context view has to show them. These read across
 * every system, which is why they are their own thing rather than methods on
 * {@link ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem}.
 */
package ch.admin.bit.jeap.doc.domain.architecture.view;
