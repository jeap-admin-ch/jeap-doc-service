package ch.admin.bit.jeap.doc.web.api.upload;

import ch.admin.bit.jeap.doc.domain.upload.UploadProperties;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.unit.DataSize;

import java.util.Map;

/**
 * Lets the servlet container swallow as much of a rejected upload as the doc service accepts.
 * <p>
 * An upload is often rejected before its body is read - a size that is not announced, a parameter that is wrong,
 * another attempt of the same upload already running. The container then reads and discards the rest of the
 * request so it can answer on the same connection, but only up to {@code server.tomcat.max-swallow-size}; beyond
 * that the connection is closed and the caller sees a reset instead of the problem document telling it what to
 * fix. The limit therefore belongs to the size an upload may have, and is derived from it here instead of being
 * one more property an instance has to know about and keep in step.
 */
public class UploadSwallowSizeEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String MAX_SIZE_PROPERTY = "jeap.doc.upload.max-size";
    static final String MAX_SWALLOW_SIZE_PROPERTY = "server.tomcat.max-swallow-size";

    /**
     * What is granted on top of the accepted size, because the one upload that is rejected for being too large
     * is by definition larger than it: without a margin, a `413` would be the answer the caller never sees.
     * <p>
     * No finite margin covers an upload that overshoots by an arbitrary amount - one that does still ends in a
     * closed connection, which is the right outcome for a body nobody asked for.
     */
    static final DataSize MARGIN = DataSize.ofMegabytes(10);

    private static final String PROPERTY_SOURCE_NAME = "jeapDocUploadSwallowSize";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configured = environment.getProperty(MAX_SIZE_PROPERTY);
        DataSize maxSize = configured == null ? UploadProperties.DEFAULT_MAX_SIZE : DataSize.parse(configured);
        // Added last, so an instance that has a reason to say something else still wins.
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME,
                Map.of(MAX_SWALLOW_SIZE_PROPERTY, Long.toString(maxSize.toBytes() + MARGIN.toBytes()))));
    }
}
