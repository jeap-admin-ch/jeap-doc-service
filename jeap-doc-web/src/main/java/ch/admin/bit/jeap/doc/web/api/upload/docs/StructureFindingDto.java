package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.validation.StructureFinding;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One problem with one path, or with the tree as a whole.
 *
 * @param code    what is wrong. A pipeline branches on this rather than on the message
 * @param path    the path it is about, absent where the finding is about the whole tree
 * @param message one sentence saying what is wrong and, where there is one, a second saying what to do
 */
@Schema(description = "One problem with a documentation set")
record StructureFindingDto(String code, String path, String message) {

    static StructureFindingDto of(StructureFinding finding) {
        return new StructureFindingDto(finding.code().name(), finding.path(), finding.message());
    }
}
