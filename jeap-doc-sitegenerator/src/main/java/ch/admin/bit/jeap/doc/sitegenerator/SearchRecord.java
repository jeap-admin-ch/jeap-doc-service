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
 * @param source      what produced it: {@link #GENERATED}, {@link #MARKDOWN} or {@link #HTML}
 * @param subject     what it documents: {@link #SUBJECT_SYSTEM}, {@link #SUBJECT_COMPONENT},
 *                    {@link #SUBJECT_LIBRARY}, or null for a page of the site itself, which documents nothing and
 *                    belongs to nobody
 * @param system      the system it documents, or null for a page of the site itself
 * @param name        the component or the library it documents, or null for a page that is the system's own -
 *                    what tells a reader which of a system's fifty components a hit is in
 * @param micrositeUrl where the microsite this page frames is served, or null for every other page. A page
 *                    that carries one stands for content that is in no content tree - see
 *                    {@code MicrositeSearchRecords}, which is the only thing that knows what that means
 * @param microsite   the microsite a record is a page <i>inside</i>, by its label, or null for every record
 *                    that is a page of the site itself. It is what lets a result say which uploaded
 *                    documentation it was found in
 */
public record SearchRecord(String url, String title, List<String> headings, String body,
                           String environment, String source, String subject, String system, String name,
                           String micrositeUrl, String microsite) {

    /** A page the service wrote from the architecture model. */
    public static final String GENERATED = "generated";

    /** A page a team uploaded as Markdown. */
    public static final String MARKDOWN = "markdown";

    /** A page of an uploaded HTML microsite, and the generated page that frames it. */
    public static final String HTML = "html";

    public static final String SUBJECT_SYSTEM = "system";
    public static final String SUBJECT_COMPONENT = "component";
    public static final String SUBJECT_LIBRARY = "library";

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
