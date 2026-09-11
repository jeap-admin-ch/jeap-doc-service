package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationPlacement;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;

/**
 * What identifies a documentation set, and therefore what an upload replaces.
 * <p>
 * The subject, the format its documents are written in, the structure template they follow, and - for an
 * HTML microsite - the section and the slug it is embedded under. Everything else an upload carries is a
 * property of the set rather than part of its identity: a new version replaces the documentation, which is
 * what <i>current</i> means.
 * <p>
 * <b>The template is part of the key.</b> A subject may therefore carry two methodologies at once, and a team
 * that switches from one to the other leaves the old set behind. Removing it is an explicit call.
 */
public record CustomSetKey(
        String site,
        SubjectKind kind,
        String system,
        String name,
        SourceFormat sourceFormat,
        String template,
        String location,
        String topic) {

    public CustomSetKey {
        if (sourceFormat == null || template == null) {
            throw new IllegalArgumentException("A set names a source format and a template.");
        }
    }

    /** The key of the set an upload with this placement belongs to. */
    public static CustomSetKey of(String site, DocumentationPlacement placement) {
        return new CustomSetKey(site, placement.subject(), placement.system(), placement.subjectName(),
                placement.sourceFormat(), placement.template(), placement.location(), placement.topic());
    }

    public CustomSubject subject() {
        return new CustomSubject(site, kind, system, name);
    }
}
