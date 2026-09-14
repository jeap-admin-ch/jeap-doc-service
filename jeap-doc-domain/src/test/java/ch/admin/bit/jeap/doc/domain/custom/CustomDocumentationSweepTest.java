package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import ch.admin.bit.jeap.doc.domain.DirectExclusiveWork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the sweep may delete, and what it may not. */
@ExtendWith(MockitoExtension.class)
class CustomDocumentationSweepTest {

    private static final Instant NOW = Instant.parse("2026-09-11T02:50:00Z");

    @Mock
    private CustomDocumentationRepository documentation;
    @Mock
    private CustomDocumentationStorage storage;

    private CustomDocumentationSweep sweep;

    @BeforeEach
    void setUp() {
        // That only one instance sweeps is the lock adapter's job and is tested there.
        sweep = new CustomDocumentationSweep(documentation, storage, new DirectExclusiveWork(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void anObjectNoSetNames_isRemoved() {
        when(storage.listWrittenBefore(any())).thenReturn(List.of("current/docs/a/1/bundle.zip",
                "current/docs/a/2/bundle.zip"));
        when(documentation.allObjectKeys()).thenReturn(List.of("current/docs/a/2/bundle.zip"));

        sweep.removeUnreferencedObjects();

        verify(storage).delete("current/docs/a/1/bundle.zip");
        verify(storage, never()).delete("current/docs/a/2/bundle.zip");
    }

    @Test
    void everyObjectBeingNamed_meansNothingIsRemoved() {
        when(storage.listWrittenBefore(any())).thenReturn(List.of("current/docs/a/2/bundle.zip"));
        when(documentation.allObjectKeys()).thenReturn(List.of("current/docs/a/2/bundle.zip"));

        sweep.removeUnreferencedObjects();

        verify(storage, never()).delete(anyString());
    }

    /**
     * <b>An HTML set's row names the prefix its files lie under</b>, not one object. Without counting a
     * prefix as a reference, every file of every microsite would be unreferenced - and the first sweep
     * after a team uploaded one would delete it six hours later.
     */
    @Test
    void aFileUnderAPrefixASetNames_isKept() {
        when(storage.listWrittenBefore(any())).thenReturn(List.of(
                "current/docs/a/3/1/files/index.html",
                "current/docs/a/3/1/files/assets/app.js",
                "current/docs/a/2/1/files/index.html"));
        when(documentation.allObjectKeys()).thenReturn(List.of("current/docs/a/3/1/files/"));

        sweep.removeUnreferencedObjects();

        verify(storage, never()).delete("current/docs/a/3/1/files/index.html");
        verify(storage, never()).delete("current/docs/a/3/1/files/assets/app.js");
        verify(storage).delete("current/docs/a/2/1/files/index.html");
    }

    /**
     * An upload copies its object before it commits the rows that name it, so a young object with no row is
     * not an orphan - it is an upload in flight. Only what is older is even listed.
     */
    @Test
    void anObjectThatMayStillBeArriving_isNotEvenListed() {
        when(storage.listWrittenBefore(any())).thenReturn(List.of());
        when(documentation.allObjectKeys()).thenReturn(List.of());

        sweep.removeUnreferencedObjects();

        verify(storage).listWrittenBefore(NOW.minus(CustomDocumentationSweep.YOUNG_ENOUGH_TO_STILL_BE_ARRIVING));
        verify(storage, never()).delete(anyString());
    }

    @Test
    void anObjectThatCannotBeRemoved_doesNotStopTheOthers() {
        when(storage.listWrittenBefore(any())).thenReturn(List.of("current/docs/a/1/bundle.zip",
                "current/docs/a/2/bundle.zip"));
        when(documentation.allObjectKeys()).thenReturn(List.of());
        doThrow(new IllegalStateException("no")).when(storage).delete("current/docs/a/1/bundle.zip");

        sweep.removeUnreferencedObjects();

        verify(storage).delete("current/docs/a/2/bundle.zip");
    }
}
