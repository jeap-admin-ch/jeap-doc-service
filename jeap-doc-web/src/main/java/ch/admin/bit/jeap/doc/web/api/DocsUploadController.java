package ch.admin.bit.jeap.doc.web.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Receives the documentation sets the doc pipelines upload.
 * <p>
 * A caller may only change the documentation of its own system: the write role is granted per system in the
 * tenant part of the semantic role, and the {@code system} parameter of the request names the system the upload
 * is for, so a token for one system cannot publish into another system's documentation.
 */
@Slf4j
@RestController
@RequestMapping("/api/docs")
@Tag(name = "docs", description = "Upload of documentation")
public class DocsUploadController {

    @Operation(summary = "Upload a documentation set",
            description = "Uploads the ZIP bundle of one documentation set of the given system.")
    @PutMapping(path = "/uploads/{uploadId}", consumes = "application/zip")
    @PreAuthorize(Roles.HAS_DOCS_WRITE_ROLE_FOR_SYSTEM)
    public void upload(@PathVariable UUID uploadId,
                       @RequestParam String system,
                       @RequestBody byte[] documentationSet) {
        log.info("Accepted the upload {} of a documentation set of the system {} ({} bytes).",
                uploadId, system, documentationSet.length);
    }
}
