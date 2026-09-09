package ch.admin.bit.jeap.doc.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * The search indexing a build pass ends with, recorded rather than done.
 * <p>
 * What indexing does is {@link SearchIndexingTest}'s business; what the runner has to do is call it once per
 * site it published a part of, and carry on when it throws.
 */
class RecordingSearchIndexing extends SearchIndexing {

    private final List<String> indexed = new ArrayList<>();

    private RuntimeException failure;

    RecordingSearchIndexing() {
        // Nothing here is reached: every method that would use a collaborator is overridden below.
        super(null, null, null, null, new SearchProperties(), null, null);
    }

    void failWith(RuntimeException e) {
        failure = e;
    }

    List<String> indexed() {
        return List.copyOf(indexed);
    }

    @Override
    public boolean index(Site site) {
        if (failure != null) {
            throw failure;
        }
        indexed.add(site.id());
        return true;
    }
}
