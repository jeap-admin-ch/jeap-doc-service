package ch.admin.bit.jeap.doc.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * The driving adapter: the REST API, the security of the service and the documentation it serves.
 * <p>
 * The service's default properties are <b>not</b> declared here with {@code @PropertySource} - that is applied
 * when this class is parsed, too late for a value read while the environment is prepared. They are contributed
 * by {@link DocDefaultPropertiesEnvironmentPostProcessor} instead.
 */
@AutoConfiguration
@ComponentScan
public class DocWebConfiguration {
}
