package ch.admin.bit.jeap.doc.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The doc service's defaults reach the environment before anything reads it, and never override an instance.
 */
class DocDefaultPropertiesEnvironmentPostProcessorTest {

    private final DocDefaultPropertiesEnvironmentPostProcessor processor =
            new DocDefaultPropertiesEnvironmentPostProcessor();

    /**
     * Each of these is read before or while the context is built, which is why they are contributed here rather
     * than by a {@code @PropertySource} on the auto-configuration.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "server.shutdown",
            "spring.lifecycle.timeout-per-shutdown-phase",
            "spring.web.resources.add-mappings",
            "spring.task.scheduling.pool.size",
            "spring.jpa.open-in-view",
            "jeap.web.headers.content-security-policy"})
    void postProcessEnvironment_thenTheDefaultIsInTheEnvironment(String property) {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(property))
                .describedAs("the default of %s", property)
                .isNotNull();
    }

    @Test
    void postProcessEnvironment_thenTheValuesAreTheOnesTheServiceNeeds() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(environment.getProperty("spring.web.resources.add-mappings")).isEqualTo("false");
        // Above jeap.doc.build.shutdown-timeout, so that giving up a build is never the phase that is cut short.
        assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("20s");
    }

    /**
     * Defaults, not decisions: an instance with a reason to say something else still wins.
     */
    @Test
    void postProcessEnvironment_whenTheInstanceSaysSomethingElse_thenTheInstanceWins() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("theInstance",
                Map.of("server.shutdown", "immediate")));

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("server.shutdown")).isEqualTo("immediate");
    }

    @Test
    void postProcessEnvironment_thenTheSourceIsNamedSoItCanBeFoundInAnActuatorDump() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources()
                .contains(DocDefaultPropertiesEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isTrue();
    }
}
