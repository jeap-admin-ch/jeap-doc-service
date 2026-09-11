package ch.admin.bit.jeap.doc.web.api.docs;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.Slugs;
import ch.admin.bit.jeap.doc.domain.custom.CustomDocumentationRemoval;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationPlacement;
import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.web.api.Roles;
import ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationTypeDto;
import ch.admin.bit.jeap.doc.web.api.upload.docs.SourceFormatDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Removes the documentation a team uploaded.
 * <p>
 * A caller removes the documentation of its own system with the same write role an upload needs, granted per
 * system in the tenant part - removing a system's documentation is changing it - or holds the sites
 * administrator role, which is there for documentation whose pipeline no longer exists. Removing everything
 * of one system is the administrator's alone.
 * <p>
 * The parameters are named like the keys of the doc workflow configuration, exactly as the upload's are - the
 * caller of this is a workflow, and it knows a placement and nothing else.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "custom-docs", description = "The documentation a team uploaded")
class CustomDocsAdminController {

    private final CustomDocumentationRemoval removal;

    @Operation(summary = "Remove one documentation set",
            description = "Removes the set of documents a team uploaded for one subject, in one format and "
                          + "under one structure template, and asks for the part that published it to be "
                          + "built. Answers 200 with what was removed, or 404 when there is no such set.")
    @DeleteMapping(DocsPaths.SETS)
    @PreAuthorize(Roles.HAS_UPLOADS_WRITE_ROLE_FOR_SYSTEM_OR_IS_SITES_ADMIN)
    ResponseEntity<CustomDocsRemovedDto> removeSet(
            @Parameter(description = "The documentation site the set belongs to")
            @RequestParam(required = false) String site,
            @Parameter(description = "What the set documents") @RequestParam String type,
            @Parameter(description = "The system the set belongs to") @RequestParam String system,
            @Parameter(description = "The component, for component documentation")
            @RequestParam(required = false) String component,
            @Parameter(description = "The library, for library documentation")
            @RequestParam(required = false) String library,
            @Parameter(description = "The structure template the documents follow") @RequestParam String template,
            @Parameter(description = "The format the documents are written in")
            @RequestParam("source-format") String sourceFormat,
            @Parameter(description = "The section an HTML microsite is embedded in")
            @RequestParam(required = false) String location,
            @Parameter(description = "The slug identifying an HTML microsite within its section")
            @RequestParam(required = false) String topic) {
        CustomSetKey key = CustomSetKey.of(siteOr(site), new DocumentationPlacement(
                DocumentationTypeDto.fromParameterValue(type).toDomain(), system, component, library, template,
                SourceFormatDto.fromParameterValue(sourceFormat).toDomain(), location, topic));

        return removal.removeSet(key)
                .map(removed -> ResponseEntity.ok(CustomDocsRemovedDto.of(removed)))
                .orElseGet(() -> {
                    log.info("No documentation set of the system {} matches the removal of a {} set.",
                            system, type);
                    return ResponseEntity.notFound().build();
                });
    }

    @Operation(summary = "Remove everything documented for one subject",
            description = "Removes every documentation set of one system, component or library, whatever its "
                          + "format or structure template, and asks for the part that published them to be "
                          + "built. The subject stays in the catalogue of what has been documented. Answers "
                          + "200 with how many sets there were.")
    @DeleteMapping(DocsPaths.SUBJECTS)
    @PreAuthorize(Roles.HAS_UPLOADS_WRITE_ROLE_FOR_SYSTEM_OR_IS_SITES_ADMIN)
    ResponseEntity<CustomDocsRemovedDto> removeSubject(
            @Parameter(description = "The documentation site the subject belongs to")
            @RequestParam(required = false) String site,
            @Parameter(description = "What kind of thing it is") @RequestParam String type,
            @Parameter(description = "The system") @RequestParam String system,
            @Parameter(description = "The component, for component documentation")
            @RequestParam(required = false) String component,
            @Parameter(description = "The library, for library documentation")
            @RequestParam(required = false) String library) {
        // Asked of the placement rules, so that what a subject is - a system with no name of its own, a
        // component or library with one - is decided in the one place that already decides it for an upload.
        // It names no template and no source format: this removes every set of the subject, whichever they
        // are, and inventing values to throw away would put a template's name in a layer that must not know
        // one.
        DocumentationPlacement.Subject named = DocumentationPlacement.subjectOf(
                DocumentationTypeDto.fromParameterValue(type).toDomain(), system, component, library);
        CustomSubject subject =
                new CustomSubject(siteOr(site), named.kind(), named.system(), named.name());

        CustomDocumentationRemoval.Removal removed = removal.removeSubject(subject);
        log.info("Removed the {} documentation set(s) of the {} {} of the system {}.", removed.setsRemoved(),
                named.kind(), subject.slug(), system);
        return ResponseEntity.ok(CustomDocsRemovedDto.of(removed));
    }

    @Operation(summary = "Remove everything documented for one system",
            description = "Removes every documentation set of one system - its own, and those of all its "
                          + "components and libraries - whatever their format and structure template, and "
                          + "asks for the part that published them to be built. Answers 200 with how many "
                          + "sets there were.")
    @DeleteMapping(DocsPaths.SYSTEMS)
    @PreAuthorize(Roles.HAS_SITES_ADMIN_ROLE)
    ResponseEntity<CustomDocsRemovedDto> removeSystem(
            @Parameter(description = "The documentation site the system belongs to")
            @RequestParam(required = false) String site,
            @Parameter(description = "The system") @RequestParam String system) {
        // Checked here, because this endpoint carries no placement to check it: nothing else would refuse a
        // system that is not a slug, and a request naming one would answer 200 having removed nothing.
        if (!Slugs.isSlug(system)) {
            throw InvalidUploadException.invalidValue("system", system, Slugs.DESCRIPTION);
        }

        CustomDocumentationRemoval.Removal removed = removal.removeSystem(siteOr(site), system);
        log.info("Removed every documentation set of the system {}: {} set(s).", system,
                removed.setsRemoved());
        return ResponseEntity.ok(CustomDocsRemovedDto.of(removed));
    }

    /** An upload that names no site means the default one, and so does a removal. */
    private static String siteOr(String site) {
        return site == null || site.isBlank() ? Site.DEFAULT_SITE : site;
    }
}
