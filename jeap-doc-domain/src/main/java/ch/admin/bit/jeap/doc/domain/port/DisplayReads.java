package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.BuildRequest;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState;

import java.util.List;
import java.util.Optional;

/**
 * The reads behind what the service shows and serves: the published site, the site status, the parts listing,
 * the live status and the import states.
 * <p>
 * <b>They may lag the latest write by a moment</b>, because an instance with a read replica answers them from
 * it. So nothing that decides a write may use this port - a build, an import, an upload or a clean-up reads
 * through the repository ports instead. Each method answers what the method of the same name on those ports
 * answers.
 */
public interface DisplayReads {

    /** See {@link DocumentationBuildRepository#publishedPartsOf(String)}. */
    List<PublishedPart> publishedPartsOf(String site);

    /** See {@link SearchIndexRepository#currentOf(String)}. */
    Optional<PublishedSearchIndex> currentSearchIndexOf(String site);

    /** See {@link DocumentationBuildRepository#recent(String, int)}. */
    List<DocumentationBuild> recentBuilds(String site, int limit);

    /** See {@link DocumentationBuildRepository#recentOf(PartKey, int)}. */
    List<DocumentationBuild> recentBuildsOf(PartKey part, int limit);

    /** See {@link DocumentationBuildRepository#find(String, long)}. */
    Optional<DocumentationBuild> build(String site, long id);

    /** See {@link DocumentationBuildRepository#published(PartKey)}. */
    Optional<DocumentationBuild> publishedBuild(PartKey part);

    /** See {@link DocumentationBuildRepository#running()}. */
    List<DocumentationBuild> runningBuilds();

    /** See {@link DocumentationBuildRequestRepository#pending()}. */
    List<BuildRequest> pendingRequests();

    /** See {@link ArchitectureImportRepository#state(String, ArchitectureImportKind)}. */
    ArchitectureImportState importState(String environment, ArchitectureImportKind kind);
}
