package ch.admin.bit.jeap.doc.web;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Puts the doc service's own defaults into the environment, before anything reads it.
 * <p>
 * <b>Why not {@code @PropertySource} on the auto-configuration.</b> That is applied when the configuration class
 * is parsed, which is well after the environment is prepared - so a value only read early would never be seen.
 * Several of these are exactly that: {@code server.shutdown} is read while the web server is built,
 * {@code spring.lifecycle.timeout-per-shutdown-phase} while the lifecycle processor is created, and
 * {@code spring.web.resources.add-mappings} decides whether a handler is registered at all. Here they are in
 * place before the context exists, which is the only point at which "a default" means the same thing to
 * everything that reads it.
 * <p>
 * <b>Added last, so an instance still wins.</b> These are defaults, not decisions: anything an instance sets in
 * its own configuration has higher precedence and is left alone.
 */
public class DocDefaultPropertiesEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String RESOURCE = "jeapDocDefaultProperties.properties";
    static final String PROPERTY_SOURCE_NAME = "jeapDocDefaultProperties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(defaults());
    }

    private static PropertySource<?> defaults() {
        try {
            return new ResourcePropertySource(PROPERTY_SOURCE_NAME, new ClassPathResource(RESOURCE));
        } catch (IOException e) {
            // The file is part of this artifact. If it cannot be read, the artifact is broken, and starting
            // without the defaults would be worse than not starting: the security headers are among them.
            throw new UncheckedIOException(
                    "The default properties of the doc service (%s) could not be read.".formatted(RESOURCE), e);
        }
    }
}
