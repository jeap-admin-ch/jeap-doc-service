package ch.admin.bit.jeap.doc.template.arc42;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Puts the arc42 template into the application.
 * <p>
 * Nothing outside this module names {@link Arc42Template}. The site generator injects every
 * {@code StructureTemplate} and the web layer asks the registry, so a second template is a dependency and a
 * bean. An instance using another methodology drops this module from its POM.
 */
@AutoConfiguration
@ComponentScan
public class Arc42TemplateConfiguration {
}
