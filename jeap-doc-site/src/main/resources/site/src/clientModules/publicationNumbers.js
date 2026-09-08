/**
 * Fills in what the run that produced this site cost, on the page describing the documentation.
 *
 * A page cannot describe the build that writes it: the pages, the bytes and the duration are known when the
 * generator has finished, and the page was written at the start of the same run. So the doc service writes
 * them beside the site as JSON, between the generator and the upload, and this fetches them.
 *
 * Three things about how it finds them are deliberate:
 *
 *  - **The URL comes from the link on the page.** The page carries an absolute link to the file, so the fetch
 *    and the link can never disagree, and nothing here has to know how a site's base URL is built.
 *  - **Only its path is used.** The link is absolute so that Docusaurus' link checker leaves it alone, but a
 *    configured origin that is not the one the reader is on would make the fetch cross-origin - and the site's
 *    Content-Security-Policy allows `connect-src 'self'` and nothing else. Taking the path keeps it same-origin
 *    whatever the origin is configured to be.
 *  - **Nothing happens when it fails.** The page reads correctly without this: it says what the file is called
 *    and where it is. A reader with no scripts, an older publication with no such file, and a fetch that 404s
 *    all get the page as written rather than an error.
 */

/** The heading the table is inserted after. It is the anchor Docusaurus derives from the heading text. */
const HEADING_ID = 'the-publication-you-are-reading';

/** Marks what this module inserted, so that a second visit replaces it instead of adding another. */
const MARKER = 'data-jeap-doc-publication';

function spellOutDuration(millis) {
    const seconds = Math.round(millis / 1000);
    if (seconds < 60) {
        return `${seconds} s`;
    }
    const minutes = Math.floor(seconds / 60);
    const rest = seconds % 60;
    return rest === 0 ? `${minutes} min` : `${minutes} min ${rest} s`;
}

/**
 * The rows to show: what the run that wrote this page cost.
 *
 * No memory row. It used to print the container's high-water mark around this build, which was only ever this
 * build's own while one build ran at a time - with several parts building at once each of them reset what the
 * others had accumulated. What the container does is a series to query
 * (`jeap.doc.container.memory.used`), not a number for a page to claim.
 *
 * No page count and no size either, and for the same kind of reason. A site is built one part at a time, and
 * only the part carrying this page publishes its numbers where this can fetch them - so those two would be one
 * part's while reading as the whole site's. A duration is honest as one part's: this page belongs to that build.
 */
function rowsOf(status) {
    return [
        ['Generated in', spellOutDuration(status.generatedInMillis)],
        ['Of which the site generator', spellOutDuration(status.generatorMillis)],
    ];
}

function tableOf(status) {
    const table = document.createElement('table');
    table.setAttribute(MARKER, 'true');
    const body = document.createElement('tbody');
    for (const [label, value] of rowsOf(status)) {
        const row = document.createElement('tr');
        const name = document.createElement('td');
        name.textContent = label;
        const cell = document.createElement('td');
        cell.textContent = value;
        row.append(name, cell);
        body.append(row);
    }
    table.append(body);
    return table;
}

/**
 * Whether the file held what this expects: an object with the numbers on it, rather than any valid JSON.
 *
 * Every number the table formats is checked, not just the first. `spellOutDuration` does arithmetic and would
 * put `NaN s` on a published page, which is worse than the sentence the page already carries without them.
 */
function isStatus(status) {
    return typeof status === 'object' && status !== null && !Array.isArray(status)
        && isNumber(status.generatedInMillis) && isNumber(status.generatorMillis);
}

function isNumber(value) {
    return typeof value === 'number' && Number.isFinite(value);
}

async function fill() {
    const heading = document.getElementById(HEADING_ID);
    if (!heading) {
        return;
    }

    // The link on the page is the one source of the file's location - see the note above.
    const link = document.querySelector('a[href$="about-this-documentation.json"]');
    if (!link) {
        return;
    }
    let status;
    try {
        const response = await fetch(new URL(link.getAttribute('href'), globalThis.location.href).pathname, {
            headers: {Accept: 'application/json'},
        });
        if (!response.ok) {
            return;
        }
        status = await response.json();
    } catch {
        // An older publication without the file, or a reader who cannot reach it. The page stands as written.
        return;
    }
    // Anything but the object this expects is a page that stands as written, rather than a TypeError in the
    // console: the file is whatever lies at a path the page pointed at, and this module trusts none of it.
    if (!isStatus(status)) {
        return;
    }
    // The reader has navigated away and back, or to another environment's copy of this page, while the fetch
    // was in flight: this run's heading is no longer in the document. Sweeping now would remove the table the
    // newer run has already inserted, and the insert would go into a detached node - so the page would end up
    // showing no numbers at all.
    if (!heading.isConnected) {
        return;
    }
    // Swept after the fetch rather than before it: two route updates onto this page while a fetch was in
    // flight would both have found nothing to remove, and both have inserted a table.
    document.querySelectorAll(`[${MARKER}]`).forEach((node) => node.remove());
    // After the sentence that follows the heading, so the reading order stays heading, sentence, numbers.
    const sentence = heading.nextElementSibling;
    const anchor = sentence ?? heading;
    anchor.after(tableOf(status));
}

export default {
    onRouteDidUpdate() {
        // Reported, not swallowed. Every failure this module expects - no heading, no link, a fetch that
        // 404s, a file that is not this object, a page the reader has navigated away from - returns quietly
        // above, so anything that reaches here is a defect in this file. Left as a dangling rejection it
        // would still reach the console; caught and dropped it would reach nothing, and the browser suite
        // asserts the console is empty, which is the only thing watching this code at all.
        fill().catch((error) => console.error('The numbers of this publication could not be shown.', error));
    },
};
