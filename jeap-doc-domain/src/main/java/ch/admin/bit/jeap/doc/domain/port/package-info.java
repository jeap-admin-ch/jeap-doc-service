/**
 * The ports of the domain: the interfaces the domain needs from the outside, e.g. for storing documents or
 * their metadata.
 * <p>
 * A port is declared here in the language of the domain and implemented by an adapter module
 * ({@code jeap-doc-objectstorage}, {@code jeap-doc-persistence}), which keeps the technology out of the domain.
 */
package ch.admin.bit.jeap.doc.domain.port;
