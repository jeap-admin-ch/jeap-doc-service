package ch.admin.bit.jeap.doc.domain.upload.validation;

import ch.admin.bit.jeap.doc.domain.custom.UploadedPlacement;
import ch.admin.bit.jeap.doc.domain.custom.UploadedTitles;
import ch.admin.bit.jeap.doc.domain.port.UploadedBundles;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationPlacement;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationType;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Would a page's own placement be accepted?
 * <p>
 * <b>The shape of what one page says, not yet whether it means anything.</b> This checks
 * {@code sidebar_position}, {@code sidebar_after} and {@code sidebar_before} the way {@link StructureValidation}
 * checks a path: is the value one this service can read at all. Whether a reference names a real sibling, and
 * whether a set of references introduces a cycle, is not checked here - see {@code ChapterOrder} for where
 * that is read once a set is taken over, and {@link FindingCode#INVALID_POSITION} /
 * {@link FindingCode#INVALID_REFERENCE} for what this does check.
 * <p>
 * <b>Only a markdown page has a placement of its own.</b> An HTML microsite carries none in its files - its
 * three upload parameters are not yet checked here - and a chapter's generated siblings carry none either:
 * they are declared by the template, not uploaded.
 */
@Service
public class OrderValidation {

    /**
     * Checks every markdown page of the tree for a placement this service cannot make sense of.
     *
     * @param accepted  the report {@link StructureValidation} already gave this tree - its own findings are
     *                  empty, because this runs only once that is true, and its template and counts travel
     *                  into the report this returns
     * @param placement what the upload documents, and in which format
     * @param paths     the tree, as received
     * @param bundle    where a page's front matter is read from - the same budget {@code HEAD_BYTES} bounds,
     *                  and read only because the tree already passed {@link StructureValidation}
     * @return a report naming what is wrong with one or more pages' own placement, or nothing where every
     * page's is fine
     */
    public Optional<StructureReport> check(StructureReport accepted, DocumentationPlacement placement,
                                           List<String> paths, UploadedBundles.ReceivedBundle bundle) {
        if (placement.sourceFormat() == SourceFormat.HTML) {
            return Optional.empty();
        }
        boolean staticSite = placement.type() == DocumentationType.SITE_DOCS;
        List<StructureFinding> findings = new ArrayList<>();
        for (String path : paths) {
            if (IgnoredPaths.isIgnored(path) || !isMarkdownPage(path)) {
                continue;
            }
            UploadedPlacement raw = UploadedTitles.rawPlacementOf(bundle.head(path, UploadedTitles.HEAD_BYTES));
            positionFindingOf(raw, path).ifPresent(findings::add);
            referenceFindingOf(raw, path, staticSite).ifPresent(findings::add);
        }
        if (findings.isEmpty()) {
            return Optional.empty();
        }
        List<StructureFinding> ordered = findings.stream().sorted(StructureReport.ORDER).toList();
        return Optional.of(new StructureReport(accepted.template(), accepted.pathsChecked(),
                accepted.pathsIgnored(), accepted.allowedFolders(), accepted.allowedExtensions(),
                accepted.refusedExtensions(), ordered, 0));
    }

    private static Optional<StructureFinding> positionFindingOf(UploadedPlacement raw, String path) {
        if (!raw.positionIsInvalid()) {
            return Optional.empty();
        }
        return Optional.of(StructureFinding.of(FindingCode.INVALID_POSITION, path,
                ("'%s' is not a finite number, and sidebar_position has to be one - an integer or a decimal, "
                 + "not quoted.").formatted(raw.position())));
    }

    /** The first reference rule one page breaks, if it breaks any. */
    private static Optional<StructureFinding> referenceFindingOf(UploadedPlacement raw, String path,
                                                                  boolean staticSite) {
        if (staticSite && raw.namesAReference()) {
            return Optional.of(StructureFinding.of(FindingCode.INVALID_REFERENCE, path,
                    "sidebar_after and sidebar_before place a page next to a named sibling of its chapter, "
                    + "and a static site has none: its pages are ordered by Docusaurus itself. Use "
                    + "sidebar_position instead."));
        }
        if (raw.namesBothDirections()) {
            return Optional.of(StructureFinding.of(FindingCode.INVALID_REFERENCE, path,
                    "sidebar_after and sidebar_before cannot both be given: a page places itself next to at "
                    + "most one other item."));
        }
        if (raw.afterIsInvalid()) {
            return Optional.of(StructureFinding.of(FindingCode.INVALID_REFERENCE, path,
                    ("'%s' is not a single, non-blank name, and sidebar_after has to be one.")
                            .formatted(raw.after())));
        }
        if (raw.beforeIsInvalid()) {
            return Optional.of(StructureFinding.of(FindingCode.INVALID_REFERENCE, path,
                    ("'%s' is not a single, non-blank name, and sidebar_before has to be one.")
                            .formatted(raw.before())));
        }
        return Optional.empty();
    }

    private static boolean isMarkdownPage(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith("." + DocumentationPaths.MARKDOWN_EXTENSION);
    }
}
