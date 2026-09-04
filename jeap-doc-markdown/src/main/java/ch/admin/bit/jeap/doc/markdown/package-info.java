/**
 * How a documentation page is written: Markdown, Docusaurus front matter and {@code _category_.json}.
 * <p>
 * This package has no dependencies and must keep none. The structure templates and the site generator both
 * write through it, and through the templates it reaches the upload validation in the web layer.
 * <p>
 * The rule it exists for: every string this service did not author reaches a page through
 * {@link ch.admin.bit.jeap.doc.markdown.Md}, which escapes it. There is no way to put text on a page
 * unescaped.
 */
package ch.admin.bit.jeap.doc.markdown;
