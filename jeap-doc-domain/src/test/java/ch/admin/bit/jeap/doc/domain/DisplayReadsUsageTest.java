package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DisplayReads;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A display read may lag the latest write, so only what shows or serves something may use one. A build, an
 * import, an upload or a clean-up that decided on a lagging read would be wrong in a way no test with one
 * database shows.
 */
class DisplayReadsUsageTest {

    private static final Set<String> MAY_READ_WHAT_LAGS = Set.of(
            PublishedDocumentation.class.getName(),
            DocumentationSiteStatus.class.getName(),
            DocumentationParts.class.getName(),
            DocumentationProvenance.class.getName());

    @Test
    void onlyTheDisplayServicesOfTheDomainDependOnDisplayReads() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));

        Set<String> users = new TreeSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("ch.admin.bit.jeap.doc.domain")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            if (type.getName().endsWith("Test") || type.getName().contains("Test$")) {
                continue;
            }
            if (Arrays.stream(type.getDeclaredFields()).map(Field::getType).anyMatch(DisplayReads.class::equals)) {
                users.add(type.getName());
            }
        }

        assertThat(users).isEqualTo(new TreeSet<>(MAY_READ_WHAT_LAGS));
    }
}
