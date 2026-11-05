package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.architecture.imports.StoredReactionGraph;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class ReactionGraphRepositoryAdapterIT extends PostgresTestContainerBase {
    private static final Instant IMPORTED_AT = Instant.parse("2026-09-09T08:00:11Z");
    @Autowired private ReactionGraphRepository graphs;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void snapshotsRoundTripAndReplaceOnlyTheirOwnEnvironment() {
        graphs.store(graph("full-dev", "observer-v2", "first"));
        graphs.store(graph("full-prod", "", "legacy-unwrapped"));
        var replacement = graph("full-dev", "observer-v2", "replacement");
        graphs.store(replacement);
        assertThat(graphs.find("full-dev")).contains(replacement);
        assertThat(graphs.find("full-prod")).contains(graph("full-prod", "", "legacy-unwrapped"));
        assertThat(jdbc.queryForObject("select count(*) from reaction_graph where environment = 'full-dev'", Integer.class)).isEqualTo(1);
    }

    @Test
    void refsNeverLoadTheBlobAndConfirmationOnlyChangesTheCheckTime() {
        var stored = graph("full-confirm", "observer-v1", "content");
        graphs.store(stored);
        var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        assertThat(graphs.findRef("full-confirm")).get().satisfies(ref -> {
            assertThat(ref.etag()).isEqualTo("tag-content");
            assertThat(ref.sourceFormat()).isEqualTo("observer-v1");
            assertThat(ref.drawable()).isTrue();
            assertThat(ref.importedAt()).isEqualTo(IMPORTED_AT);
        });
        assertThat(stats.getEntityLoadCount()).isZero();
        graphs.confirm("full-confirm", IMPORTED_AT.plusSeconds(60));
        assertThat(graphs.find("full-confirm")).contains(stored);
        assertThat(graphs.findRef("full-confirm")).get().satisfies(ref ->
                assertThat(ref.checkedAt()).isEqualTo(IMPORTED_AT.plusSeconds(60)));
    }

    @Test
    void retirementMigrationRemovesOnlySubjectSnapshotsAndTheirStates() {
        var stored = graph("full-migration", "observer-v2", "keep-full-bytes");
        graphs.store(stored);
        for (String kind : java.util.List.of("SYSTEM_REACTIONS", "COMPONENT_REACTIONS", "MESSAGE_REACTIONS")) {
            jdbc.update("""
                    insert into reaction_graph (id, environment, kind, name, variant, system_name, upstream_name,
                        etag, fingerprint, graph_data, drawable_nodes, size_in_bytes, imported_at, checked_at)
                    select nextval('reaction_graph_id_seq'), environment, ?, 'old-subject', '', '', 'old-upstream',
                        'old-tag', fingerprint, graph_data, drawable_nodes, size_in_bytes, imported_at, checked_at
                    from reaction_graph where environment = 'full-migration' and kind = 'FULL_REACTIONS'
                    """, kind);
            jdbc.update("insert into architecture_import (environment, kind, complete) values ('full-migration', ?, true)", kind);
        }
        jdbc.update("insert into architecture_import (environment, kind, complete, item_count, last_success_at) values ('full-migration', 'FULL_REACTIONS', true, 1, ?)", java.sql.Timestamp.from(IMPORTED_AT));
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_11_0__retire-partial-reaction-imports.sql"))
                .execute(jdbc.getDataSource());
        assertThat(graphs.find("full-migration")).contains(stored);
        assertThat(jdbc.queryForList("select kind from reaction_graph where environment = 'full-migration'", String.class))
                .containsExactly("FULL_REACTIONS");
        assertThat(jdbc.queryForList("select kind from architecture_import where environment = 'full-migration'", String.class))
                .containsExactly("FULL_REACTIONS");
        assertThat(jdbc.queryForObject("select last_success_at from architecture_import where environment = 'full-migration'", java.sql.Timestamp.class).toInstant())
                .isEqualTo(IMPORTED_AT);
    }

    private static StoredReactionGraph graph(String environment, String format, String content) {
        return new StoredReactionGraph(environment, format, "tag-" + content, "fingerprint",
                content.getBytes(StandardCharsets.UTF_8), 3, IMPORTED_AT);
    }
}
