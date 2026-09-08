package ch.admin.bit.jeap.doc.domain.upload.validation;

import java.util.Comparator;
import java.util.List;

/**
 * What the validation found, and what the template allows.
 * <p>
 * <b>There is no {@code valid} field.</b> The web layer answers {@code 200} for a report with no finding and
 * {@code 422} for one with findings, so the verdict is the status line - two sources of one truth is one too
 * many. {@link #isValid()} is for the callers inside the service.
 *
 * @param template          the template the tree was checked against
 * @param pathsChecked      how many paths were checked - the ignored ones are not among them
 * @param pathsIgnored      how many paths were dropped before any rule ran, see {@link IgnoredPaths}. Reported
 *                          because a count that silently omits what it skipped is the same lie as a truncated
 *                          list of findings
 * @param allowedFolders    the chapter folders of the template, so a workflow can print them once instead of
 *                          the service repeating them in every message. Empty where the template is unknown
 * @param allowedExtensions what an upload to this template may carry
 * @param findings          every problem, sorted by path and then by code so that two runs of one tree print
 *                          the same list
 * @param findingsOmitted   how many findings were left out by the cap
 */
public record StructureReport(
        String template,
        int pathsChecked,
        int pathsIgnored,
        List<String> allowedFolders,
        List<String> allowedExtensions,
        List<StructureFinding> findings,
        int findingsOmitted) {

    /**
     * Findings about the whole tree first - they are the ones a workflow prints at the top - then by path,
     * then by code.
     */
    static final Comparator<StructureFinding> ORDER = Comparator
            .comparing(StructureFinding::path, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(StructureFinding::code);

    public StructureReport {
        allowedFolders = allowedFolders == null ? List.of() : List.copyOf(allowedFolders);
        allowedExtensions = allowedExtensions == null ? List.of() : List.copyOf(allowedExtensions);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public boolean isValid() {
        return findings.isEmpty();
    }
}
