package ch.admin.bit.jeap.doc.markdown;

/**
 * The {@code _category_.json} that gives a folder its name and its place in the navigation.
 * <p>
 * Docusaurus strips a number prefix such as {@code 5-} from the URL. This is how the number gets back into the
 * sidebar.
 */
public final class CategoryFile {

    /** The file name, which Docusaurus fixes. */
    public static final String NAME = "_category_.json";

    private CategoryFile() {
    }

    /** A category with a label and a position among its siblings, collapsed until a reader opens it. */
    public static String of(String label, int position) {
        return """
                {
                  "label": %s,
                  "position": %d
                }
                """.formatted(Scalars.quoted(label), position);
    }

    /** A category whose position comes from the number prefix of its folder, collapsed. */
    public static String of(String label) {
        return """
                {
                  "label": %s
                }
                """.formatted(Scalars.quoted(label));
    }

    /**
     * The same, open when the page is first shown. Meant for a category at the top of a sidebar: everything
     * below starts closed, and Docusaurus opens the path to the page the reader is on.
     */
    public static String expanded(String label, int position) {
        return """
                {
                  "label": %s,
                  "position": %d,
                  "collapsed": false
                }
                """.formatted(Scalars.quoted(label), position);
    }

    /**
     * A category the site template can find among the others, by a custom property rather than by its label.
     * <p>
     * Docusaurus passes {@code customProps} through onto the sidebar item, so the template can recognise one
     * category out of a generated tree. A label would be the other way to recognise it - and a label is
     * exactly what someone changes.
     */
    public static String marked(String label, int position, String property) {
        return """
                {
                  "label": %s,
                  "position": %d,
                  "customProps": {
                    %s: true
                  }
                }
                """.formatted(Scalars.quoted(label), position, Scalars.quoted(property));
    }
}
