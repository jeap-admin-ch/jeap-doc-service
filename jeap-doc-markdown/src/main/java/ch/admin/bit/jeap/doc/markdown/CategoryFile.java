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

    /** A category with a label and a position among its siblings. */
    public static String of(String label, int position) {
        return """
                {
                  "label": %s,
                  "position": %d
                }
                """.formatted(Scalars.quoted(label), position);
    }

    /** A category whose position comes from the number prefix of its folder. */
    public static String of(String label) {
        return """
                {
                  "label": %s
                }
                """.formatted(Scalars.quoted(label));
    }
}
