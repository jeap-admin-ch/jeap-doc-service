package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.validation.StructureReport;
import org.springframework.http.ProblemDetail;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What the validation found, and what the template allows.
 * <p>
 * <b>There is no {@code valid} field.</b> A tree with no finding is answered {@code 200} and one with findings
 * {@code 422}, so the verdict is the status line - two sources of one truth is one too many. On the
 * {@code 422} these fields are the extension members of the problem document.
 *
 * @param template          the template the tree was checked against
 * @param pathsChecked      how many paths were checked; the ignored ones are not among them
 * @param pathsIgnored      how many were dropped before any rule ran - the files a ZIP carries that nobody
 *                          wrote. Reported, because a count that omits what it skipped is the same lie as a
 *                          truncated list of findings
 * @param allowedFolders    the chapter folders of the template, so a workflow prints them once instead of the
 *                          service repeating them in every message
 * @param allowedExtensions what an upload of this source format may carry
 * @param findings          every problem, ordered so that two runs of one tree print the same list
 * @param findingsOmitted   how many findings the cap left out
 */
@Schema(description = "What the structure validation found")
record StructureReportDto(
        String template,
        int pathsChecked,
        int pathsIgnored,
        List<String> allowedFolders,
        List<String> allowedExtensions,
        List<StructureFindingDto> findings,
        int findingsOmitted) {

    static StructureReportDto of(StructureReport report) {
        return new StructureReportDto(report.template(), report.pathsChecked(), report.pathsIgnored(),
                report.allowedFolders(), report.allowedExtensions(),
                report.findings().stream().map(StructureFindingDto::of).toList(),
                report.findingsOmitted());
    }

    /**
     * Writes the report into a problem document as extension members, which is what RFC 9457 extension
     * members are for.
     * <p>
     * <b>One shape for both endpoints.</b> A set can be refused by the validation endpoint or by the upload
     * itself, and a pipeline that prints the findings should not have to know which of the two answered.
     */
    void into(ProblemDetail problem) {
        problem.setProperty("template", template);
        problem.setProperty("pathsChecked", pathsChecked);
        problem.setProperty("pathsIgnored", pathsIgnored);
        problem.setProperty("allowedFolders", allowedFolders);
        problem.setProperty("allowedExtensions", allowedExtensions);
        problem.setProperty("findings", findings);
        problem.setProperty("findingsOmitted", findingsOmitted);
    }
}
