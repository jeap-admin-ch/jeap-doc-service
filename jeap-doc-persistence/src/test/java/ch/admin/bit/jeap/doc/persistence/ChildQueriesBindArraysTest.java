package ch.admin.bit.jeap.doc.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.jpa.repository.Query;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That every query reading a child table of the architecture model binds <b>one array</b> rather than one
 * parameter per identifier.
 * <p>
 * A derived {@code …In} query binds each element of its collection separately, so the parameter count of the
 * statement is the row count of the table above it: components for the REST operations, messages for the
 * contracts, contracts for their versions. PostgreSQL's protocol allows 65535 parameters, and a landscape that
 * grew past that would not have got slower - it would have failed <b>every build from then on</b>, at a size
 * nobody was watching for.
 * <p>
 * Written against the interfaces rather than against a landscape, because reproducing the failure needs tens
 * of thousands of rows. It enumerates the package instead of naming the repositories, so a child table added
 * later is covered without anybody remembering this test.
 */
class ChildQueriesBindArraysTest {

    /** What a method reading a child table by its parents is called - the Spring Data derivation keyword. */
    private static final String READS_BY_PARENT_IDS = "IdIn";

    @Test
    void everyQueryReadingAChildTableByItsParents_bindsOneArray() throws Exception {
        List<Method> reads = childTableReads();

        assertThat(reads).describedAs("the queries this test is about").hasSizeGreaterThanOrEqualTo(8);
        assertThat(reads).allSatisfy(method -> {
            assertThat(method.getParameterTypes()[0])
                    .describedAs("%s binds its identifiers as", method.getName())
                    .isEqualTo(Long[].class);
            Query query = method.getAnnotation(Query.class);
            assertThat(query).describedAs("%s has a query of its own rather than a derived one",
                    method.getName()).isNotNull();
            assertThat(query.value()).describedAs("%s compares against the whole array", method.getName())
                    .contains("= any(");
        });
    }

    /** Every method of this package's repositories that reads a table by the identifiers of its parents. */
    private static List<Method> childTableReads() throws IOException, ClassNotFoundException {
        List<Method> reads = new ArrayList<>();
        String packagePath = ChildQueriesBindArraysTest.class.getPackageName().replace('.', '/');
        Resource[] classes = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:" + packagePath + "/*JpaRepository.class");
        for (Resource resource : classes) {
            String name = resource.getFilename().replace(".class", "");
            for (Method method : Class.forName(ChildQueriesBindArraysTest.class.getPackageName() + "." + name)
                    .getDeclaredMethods()) {
                if (method.getName().contains(READS_BY_PARENT_IDS)) {
                    reads.add(method);
                }
            }
        }
        return reads;
    }
}
