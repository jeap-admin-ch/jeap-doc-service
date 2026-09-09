package ch.admin.bit.jeap.doc.sitegenerator;

import java.util.List;

/**
 * One page of a site, as the search index holds it.
 *
 * @param url         the path the page is served at, with a leading and a trailing slash - the only thing a
 *                    search result can offer a reader, so a wrong one is a dead link rather than a bad rank
 * @param title       the page's title, from its front matter
 * @param headings    its headings, in the order they appear
 * @param body        everything else, as plain text
 * @param environment the environment tree the page is in - what scopes a search to the tree the reader is in
 * @param system      the system it documents, or null for a page of the site itself
 * @param component   the component it documents, or null for a page that is not inside a component's tree -
 *                    what tells a reader which of a system's components a hit is in
 */
public record SearchRecord(String url, String title, List<String> headings, String body,
                           String environment, String system, String component) {

    public SearchRecord {
        headings = headings == null ? List.of() : List.copyOf(headings);
    }

    /**
     * What is handed to the indexer: the title, then the headings, then the body.
     * <p>
     * The title comes first because it is also what the indexer ranks on - there is no per-field weight for a
     * record added this way - so a page whose title matches should have that match early in its text.
     */
    public String content() {
        StringBuilder text = new StringBuilder(body.length() + 128);
        text.append(title);
        for (String heading : headings) {
            text.append(". ").append(heading);
        }
        if (!body.isEmpty()) {
            text.append(". ").append(body);
        }
        return text.toString();
    }
}
