package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationUpload;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationUploadDescriptor;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationUploadService;
import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.domain.upload.UploadProperties;
import ch.admin.bit.jeap.doc.domain.upload.UploadReceipt;
import ch.admin.bit.jeap.doc.web.api.Roles;
import ch.admin.bit.jeap.doc.web.api.upload.UploadBodies;
import ch.admin.bit.jeap.doc.web.api.upload.UploadPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
@RequestMapping(UploadPaths.DOCS)
@RequiredArgsConstructor
@Tag(name = "doc-uploads", description = "Upload of documentation")
class DocumentationUploadController {

    private final DocumentationUploadService uploadService;
    private final UploadProperties uploadProperties;

    @Operation(summary = "Upload a documentation set",
            description = "Uploads the ZIP bundle of one documentation set of the given system. Which parameters " +
                          "are required depends on the type of the documentation set and on the format of its " +
                          "documents. Answers 201 when the bundle was stored, and 200 when the upload had " +
                          "already been stored under the same upload id.")
    @PutMapping(path = "/{uploadId}", consumes = "application/zip", produces = "application/json")
    @PreAuthorize(Roles.HAS_UPLOADS_WRITE_ROLE_FOR_SYSTEM)
    public ResponseEntity<DocumentationUploadResultDto> upload(
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
        DocumentationUploadDescriptor upload = DocumentationUploadDto.builder()
                .site(site)
                .type(DocumentationTypeDto.fromParameterValue(type))
                .system(system)
                .component(component)
                .library(library)
                .template(template)
                .sourceFormat(SourceFormatDto.fromParameterValue(sourceFormat))
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
                .build()
                .toDescriptor();

        long announcedSize = announcedSize(request);
        try (InputStream bundle = limited(request)) {
            UploadReceipt receipt = uploadService.receive(uploadId, upload, bundle, announcedSize);
            // A request that stored a bundle created the upload; one that repeated an upload already stored did
            // not - the target URI is the upload itself, so a 201 needs no Location.
            return ResponseEntity.status(receipt.stored() ? HttpStatus.CREATED : HttpStatus.OK)
                    .body(DocumentationUploadResultDto.of(receipt.upload()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Operation(summary = "Read the state of an upload",
               description = "Answers what became of an upload of the given system - whether its bundle was " +
                             "stored and is waiting for the documentation generator.")
    @GetMapping(path = "/{uploadId}", produces = "application/json")
    @PreAuthorize(Roles.HAS_UPLOADS_WRITE_ROLE_FOR_SYSTEM)
    public DocumentationUploadStatusDto status(
            @Parameter(description = "Identifier of the upload")
            @PathVariable UUID uploadId,
            @Parameter(description = "System the upload belongs to, and the system the write role is checked for")
            @RequestParam("system") String system) {
        return uploadService.statusOf(uploadId, system)
                .map(DocumentationUploadStatusDto::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No upload %s of the system %s.".formatted(uploadId, system)));
    }

    /**
     * The size the upload announces. Content-Length is mandatory: it lets an oversized bundle be rejected before
     * it is transferred, it is what the object storage is told to expect, and it is what a body cut short is
     * recognised by - a chunked request would offer none of that.
     */
    private long announcedSize(HttpServletRequest request) {
        long announced = request.getContentLengthLong();
        if (announced < 0) {
            throw new InvalidUploadException(InvalidUploadException.Code.LENGTH_REQUIRED,
                    "The upload has to announce the size of its bundle in the Content-Length header.");
        }
        long limit = uploadProperties.getMaxSize().toBytes();
        if (announced > limit) {
            throw InvalidUploadException.tooLarge(limit);
        }
        return announced;
    }

    /**
     * The body, with the accepted size enforced while it is read.
     */
    private InputStream limited(HttpServletRequest request) {
        try {
            return UploadBodies.limitedTo(request.getInputStream(), uploadProperties.getMaxSize().toBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
