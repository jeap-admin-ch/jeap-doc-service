package ch.admin.bit.jeap.doc.web.api.docs;

import ch.admin.bit.jeap.doc.domain.custom.CustomDocumentationRemoval;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a removal took away.
 *
 * @param setsRemoved how many documentation sets were removed
 * @param buildAsked  whether a build of the part that published them was asked for. False says the pages stay
 *                    published until something else asks - not that the removal failed
 */
@Schema(description = "What a removal of custom documentation took away")
record CustomDocsRemovedDto(int setsRemoved, boolean buildAsked) {

    static CustomDocsRemovedDto of(CustomDocumentationRemoval.Removal removal) {
        return new CustomDocsRemovedDto(removal.setsRemoved(), removal.buildAsked());
    }
}
