package ch.admin.bit.jeap.doc.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

interface SearchIndexJpaRepository extends JpaRepository<SearchIndexEntity, Long> {

    /**
     * The published indexes of a site, newest first. The first is the one the site is served from and the rest
     * are what it superseded, which is the one scan both questions are answered from.
     */
    List<SearchIndexEntity> findBySiteAndStateOrderByIdDesc(String site, SearchIndexState state, Limit limit);

    /**
     * The runs that produced nothing anyone is served and are old enough to forget - across every site, so
     * that one nightly pass clears the lot.
     * <p>
     * <b>Published runs cannot match</b>, whatever their dates: what a site is served from is one of those.
     */
    @Query("""
            select i from SearchIndexEntity i
            where (i.state = ch.admin.bit.jeap.doc.persistence.SearchIndexState.RUNNING
                   and i.startedAt < :runningStartedBefore)
               or (i.state = ch.admin.bit.jeap.doc.persistence.SearchIndexState.FAILED
                   and i.finishedAt < :failedFinishedBefore)
            order by i.id
            """)
    List<SearchIndexEntity> findAbandoned(@Param("runningStartedBefore") Instant runningStartedBefore,
                                          @Param("failedFinishedBefore") Instant failedFinishedBefore,
                                          Limit limit);
}
