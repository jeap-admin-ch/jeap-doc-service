package ch.admin.bit.jeap.doc.domain.upload.validation;

/**
 * One problem with one path, or with the tree as a whole.
 *
 * @param code    what is wrong, for a pipeline to branch on
 * @param path    the path it is about, or null where the finding is about the whole tree
 * @param message one sentence saying what is wrong and, where there is one, a second saying what to do
 *                instead. It never repeats the list of chapters or extensions - the report carries those once
 */
public record StructureFinding(FindingCode code, String path, String message) {

    static StructureFinding of(FindingCode code, String path, String message) {
        return new StructureFinding(code, path, message);
    }

    /** A finding about the tree rather than about one path. */
    static StructureFinding ofTree(FindingCode code, String message) {
        return new StructureFinding(code, null, message);
    }
}
