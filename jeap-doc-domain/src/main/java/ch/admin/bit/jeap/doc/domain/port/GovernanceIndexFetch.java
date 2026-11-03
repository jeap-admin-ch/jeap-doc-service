package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceRef;

import java.util.List;

/**
 * One index of the Governance Service's {@code /docs-api}: the systems index or the rules index.
 * <p>
 * Both the raw body - stored so a page can later read the scoring time and trend period off it - and the
 * entries the import diffs against what is stored are carried together, because they are one answer from one
 * request.
 *
 * @param body    the index as it arrived, to store under {@link
 *                ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceKind#SYSTEMS_INDEX} or {@code
 *                RULES_INDEX}
 * @param etag    the entity tag of the index itself, verbatim
 * @param entries every system or rule the index lists, with its own entity tag
 */
public record GovernanceIndexFetch(byte[] body, String etag, List<GovernanceResourceRef> entries) {
}
