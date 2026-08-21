package ch.admin.bit.jeap.doc.web.api.upload;

import ch.admin.bit.jeap.doc.web.api.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Receives the documentation sets the doc pipelines upload.
 * <p>
 * A caller may only change the documentation of its own system: the write role is granted per system in the
 * tenant part of the semantic role, and the {@code system} parameter of the request names the system the upload
 * is for, so a token for one system cannot publish into another system's documentation.
 * <p>
 * The parameters are named like the keys of the doc workflow configuration a repository writes, so the workflow
 * passes its configuration through instead of translating it.
 */
@Slf4j
@RestController
@RequestMapping(UploadController.UPLOADS_PATH)
@RequiredArgsConstructor
@Tag(name = "uploads", description = "Upload of documentation")
class UploadController {

    static final String UPLOADS_PATH = "/api/uploads";

    private static final int BUFFER_SIZE = 8192;

    private final UploadProperties uploadProperties;

    @Operation(summary = "Upload a documentation set",
            description = "Uploads the ZIP bundle of one documentation set of the given system. Which parameters " +
                          "are required depends on the type of the documentation set and on the format of its " +
                          "documents.")
    @PutMapping(path = "/{uploadId}", consumes = "application/zip")
    @PreAuthorize(Roles.HAS_DOCS_WRITE_ROLE_FOR_SYSTEM)
    public void upload(
            @Parameter(description = "Identifier of this upload, chosen by the client so it can be retried")
            @PathVariable UUID uploadId,
            @Parameter(description = "Site the documents belong to; without it the default site")
            @RequestParam(name = "site", required = false) String site,
            @Parameter(description = "What the documents document: system-docs, component-docs or library-docs")
            @RequestParam("type") String type,
            @Parameter(description = "System the documents belong to, and the system the write role is checked for")
            @RequestParam("system") String system,
            @Parameter(description = "Component the documents belong to, required for component-docs")
            @RequestParam(name = "component", required = false) String component,
            @Parameter(description = "Library the documents belong to, required for library-docs")
            @RequestParam(name = "library", required = false) String library,
            @Parameter(description = "Section catalog the documents follow, e.g. arc42 or bmad")
            @RequestParam("template") String template,
            @Parameter(description = "Format of the documents: markdown or html")
            @RequestParam("source-format") String sourceFormat,
            @Parameter(description = "Section HTML documents are embedded in, e.g. 6-runtime-view")
            @RequestParam(name = "location", required = false) String location,
            @Parameter(description = "Slug identifying HTML documents within their section, e.g. spring-rest-docs")
            @RequestParam(name = "topic", required = false) String topic,
            @Parameter(description = "Menu label of HTML documents, e.g. Spring REST Docs")
            @RequestParam(name = "label", required = false) String label,
            @Parameter(description = "Repository the documents came from")
            @RequestParam("source-repository") String sourceRepository,
            @Parameter(description = "Commit the documents were built from")
            @RequestParam("source-revision") String sourceRevision,
            @Parameter(description = "Branch or tag that was built")
            @RequestParam("source-ref") String sourceRef,
            @Parameter(description = "Timestamp of the commit the documents were built from, ISO-8601")
            @RequestParam("source-timestamp") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime sourceTimestamp,
            @Parameter(description = "Version of the component or library, required for component-docs and library-docs")
            @RequestParam(name = "version", required = false) String version,
            @Parameter(description = "Build that uploaded the documents")
            @RequestParam(name = "build-url", required = false) String buildUrl,
            @Parameter(description = "When the documents were generated, ISO-8601")
            @RequestParam(name = "generated-at", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime generatedAt,
            HttpServletRequest request) {
        DocumentationSetUpload upload = DocumentationSetUpload.builder()
                .site(site)
                .type(DocumentationSetType.fromParameterValue(type))
                .system(system)
                .component(component)
                .library(library)
                .template(template)
                .sourceFormat(SourceFormat.fromParameterValue(sourceFormat))
                .location(location)
                .topic(topic)
                .label(label)
                .sourceRepository(sourceRepository)
                .sourceRevision(sourceRevision)
                .sourceRef(sourceRef)
                .sourceTimestamp(sourceTimestamp)
                .version(version)
                .buildUrl(buildUrl)
                .generatedAt(generatedAt)
                .build();

        long size = readBundle(request);

        log.info("Accepted the upload {} of {} of the system {} for the site {} ({} bytes).",
                uploadId, upload.type().parameterValue(), upload.system(),
                upload.site() == null ? "default" : upload.site(), size);
    }

    /**
     * Reads the bundle and stops as soon as it exceeds the accepted size, so a large body cannot fill the heap of
     * the service. The bundle is not kept: storing it comes with the story that persists a documentation set.
     */
    private long readBundle(HttpServletRequest request) {
        long limit = uploadProperties.getMaxSize().toBytes();
        if (request.getContentLengthLong() > limit) {
            throw InvalidUploadException.tooLarge(limit);
        }
        try (InputStream bundle = request.getInputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long size = 0;
            int read;
            while ((read = bundle.read(buffer)) != -1) {
                size += read;
                if (size > limit) {
                    throw InvalidUploadException.tooLarge(limit);
                }
            }
            return size;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
