package ch.admin.bit.jeap.doc.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.PropertySource;

@AutoConfiguration
@ComponentScan
@PropertySource("classpath:jeapDocDefaultProperties.properties")
public class DocWebConfiguration {
}
