package ch.admin.bit.jeap.doc.domain;

/**
 * Which part of which site: the key a build, a publication, a lock and a build request all hang on.
 * <p>
 * One value rather than two strings side by side. Every one of those four things used to be keyed by the site
 * alone, so every signature would otherwise have gained a second string of the same type next to the first -
 * and a call that swapped them would compile.
 *
 * @param site the identifier of the site
 * @param part the identifier of the part within that site
 */
public record PartKey(String site, String part) {

    public static PartKey of(String site, String part) {
        return new PartKey(site, part);
    }

    /** The part that owns the site's own pages and everything no other part claims. */
    public static PartKey shellOf(String site) {
        return new PartKey(site, SitePart.SHELL);
    }

    public boolean isShell() {
        return SitePart.SHELL.equals(part);
    }

    /** Site and part as one name, for a log line and for the name of a lock. */
    @Override
    public String toString() {
        return site + "/" + part;
    }
}
