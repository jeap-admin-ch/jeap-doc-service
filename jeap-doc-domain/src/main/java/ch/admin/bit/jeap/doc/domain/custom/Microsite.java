package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;

/**
 * One uploaded HTML microsite, as the page that frames it needs to know it.
 * <p>
 * A microsite has no pages of its own in the sense the rest of this model uses the word: it is published as it
 * was built and served file by file, so what a generated page needs is where to point a frame and what to call
 * the entry in the navigation.
 *
 * @param label    what the navigation and the heading of the page show
 * @param location the chapter folder the page is written into
 * @param topic    what identifies this microsite within that chapter, and the last segment of its route
 * @param url        where its entry point is served, as a path within this service
 * @param provenance the repository, the commit and the upload the files came from
 */
public record Microsite(String label, String location, String topic, String url,
                        CustomProvenance provenance) {

    /**
     * The segment every microsite is served under, spelled here and nowhere else: the URL a page frames, the
     * path the web adapter resolves, and the site ids that may not take it all have to agree.
     */
    public static final String SEGMENT = "microsites";

    /**
     * The microsite a set describes, with the URL its files are served under.
     * <p>
     * <b>The route is built here</b>, from the same key the request is resolved back into, so the page that
     * frames a microsite and the handler that serves it cannot disagree about where it is.
     */
    public static Microsite of(CustomSet set) {
        CustomSetKey key = set.key();
        String subject = key.kind() == SubjectKind.SYSTEM ? key.system()
                : key.system() + "/" + kindSegmentOf(key.kind()) + "/" + key.name();
        String url = "/" + SEGMENT + "/" + subject + "/" + key.template() + "/" + key.location() + "/"
                     + key.topic() + "/";
        return new Microsite(set.label(), key.location(), key.topic(), url, set.provenance());
    }

    /**
     * What names the kind in a route. A component and a library are named, a system is not: without it, a
     * component called after a template could not be told from a system's own microsite.
     */
    private static String kindSegmentOf(SubjectKind kind) {
        return kind == SubjectKind.LIBRARY ? "libraries" : "components";
    }

    /** What the page is called on disk, which a reader never sees - its route is the topic. */
    public String fileName() {
        return topic + "-microsite.md";
    }

    /** What orders it among the uploaded pages of its chapter: the label, as a title orders a page. */
    public String sortKey() {
        return label == null ? topic : label;
    }
}
