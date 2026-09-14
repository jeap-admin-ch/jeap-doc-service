package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.custom.CustomDocumentation;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;

import java.util.List;
import java.util.Optional;

/**
 * A site nobody has uploaded documentation for, for the tests that are about something else.
 */
public class NoCustomDocumentation implements CustomDocumentationRepository {

    @Override
    public Replaced replace(CustomSet set) {
        throw new UnsupportedOperationException("nothing is uploaded in this test");
    }

    @Override
    public Optional<CustomSet> find(CustomSetKey key) {
        return Optional.empty();
    }

    @Override
    public CustomDocumentation of(String site, String system) {
        return CustomDocumentation.nothing();
    }

    @Override
    public List<CustomSubject> subjectsOf(String site) {
        return List.of();
    }

    @Override
    public boolean remove(CustomSetKey key) {
        return false;
    }

    @Override
    public int removeSubject(CustomSubject subject) {
        return 0;
    }

    @Override
    public List<String> removeSystem(String site, String system) {
        return List.of();
    }

    @Override
    public List<String> allObjectKeys() {
        return List.of();
    }

    @Override
    public java.util.List<ch.admin.bit.jeap.doc.domain.custom.CustomSet> micrositesOf(String site) {
        return java.util.List.of();
    }
}
