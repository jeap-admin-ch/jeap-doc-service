package ch.admin.bit.jeap.doc.html;

import ch.admin.bit.jeap.doc.domain.port.HtmlText;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** The HTML adapter: it reads an uploaded document as text, and holds the parser that does it. */
@AutoConfiguration
@EnableConfigurationProperties(DocHtmlProperties.class)
public class DocHtmlConfiguration {

    @Bean
    HtmlText htmlText(DocHtmlProperties properties) {
        return new JsoupHtmlText(properties);
    }
}
