package ch.admin.bit.jeap.doc.html;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * What is read out of an uploaded HTML document, and what is left out of it.
 */
@Data
@ConfigurationProperties("jeap.doc.html")
public class DocHtmlProperties {

    /**
     * The elements that are not text, removed before the text is taken.
     * <p>
     * The first four are never content. The rest is Javadoc's navigation, and it is here because a generated
     * documentation site repeats its navigation on every page: without it, the excerpt of a class page is the
     * same list of links as the excerpt of every other class page, and a reader cannot tell them apart.
     * A generator whose furniture is not on this list is indexed with it, which costs excerpt quality and
     * nothing else.
     */
    private List<String> ignoredSelectors = List.of(
            "script", "style", "noscript", "template",
            "nav", "header", "footer",
            ".topNav", ".subNav", ".bottomNav", ".navPadding", ".skipNav");

    /** The selectors as one query, which is how a parser is asked for them. */
    public String ignoredSelectorQuery() {
        return String.join(", ", ignoredSelectors);
    }
}
