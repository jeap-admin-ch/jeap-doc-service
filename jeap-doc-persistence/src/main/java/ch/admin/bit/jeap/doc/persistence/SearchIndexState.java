package ch.admin.bit.jeap.doc.persistence;

/** What has become of one run of the search indexer. */
enum SearchIndexState {

    /** Being built right now, by the instance on the row. */
    RUNNING,

    /** Built and published. The newest of these per site is the index that site is served from. */
    PUBLISHED,

    /** It could not be built, and the row says why. What was published before goes on being served. */
    FAILED
}
