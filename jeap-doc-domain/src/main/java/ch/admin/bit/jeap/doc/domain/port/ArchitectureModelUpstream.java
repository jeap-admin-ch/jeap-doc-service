package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.SystemTopology;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The architecture model, as the architecture repository serves it.
 * <p>
 * The methods are shaped like the upstream's resources rather than like the model, because a system's topology
 * and its messages are two resources and are read one system at a time.
 * <p>
 * Nothing here is conditional. The architecture repository computes the entity tag of a model resource over
 * the serialized body, so answering "not modified" costs it exactly what answering with the body costs, and a
 * conditional request would save this service some bytes and the upstream nothing. The importer therefore
 * fetches the whole landscape and compares it after the fact.
 */
public interface ArchitectureModelUpstream {

    /**
     * The environment ids an architecture repository is configured for. An environment that is not among them
     * has no model-derived documentation, which is a legitimate configuration.
     */
    Set<String> environments();

    /** Where the model of an environment is read from, which every generated page names. */
    Optional<String> urlOf(String environment);

    /**
     * The names of every system of one environment, in the order the upstream lists them.
     *
     * @throws ArchitectureModelUnavailableException when the architecture repository could not be read
     */
    List<String> systemNames(String environment);

    /**
     * One system with its components and relations, but without its messages.
     *
     * @return empty when the system is gone - it was listed and then was not there, which is a race between
     * two requests and not an error
     */
    Optional<SystemTopology> topology(String environment, String system);

    /**
     * The events and commands one system defines.
     *
     * @return empty when the system is gone, as {@link #topology}
     */
    Optional<List<DocumentedMessage>> messages(String environment, String system);
}
