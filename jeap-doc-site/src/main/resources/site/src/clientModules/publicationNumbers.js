/**
 * Fills in what the run that produced this site cost, on the page describing the documentation.
 *
 * A page cannot describe the build that writes it: the pages, the bytes, the duration and the memory peak are
 * known when the generator has finished, and the page was written at the start of the same run. So the doc
 * service writes them beside the site as JSON, between the generator and the upload, and this fetches them.
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

const MEGABYTE = 1024 * 1024;

function megabytes(bytes) {
    // Rounded, and the Java side rounds too - the log line of a build and this table are read side by side.
    return `${Math.round(bytes / MEGABYTE)} MB`;
}

function spellOutDuration(millis) {
    const seconds = Math.round(millis / 1000);
    if (seconds < 60) {
        return `${seconds} s`;
    }
    const minutes = Math.floor(seconds / 60);
    const rest = seconds % 60;
    return rest === 0 ? `${minutes} min` : `${minutes} min ${rest} s`;
}

function memoryOf(status) {
    if (!isNumber(status.memoryPeakBytes)) {
        return null;
    }
    const peak = status.memoryPeakExact === false ? 'at most ' : '';
    if (!isNumber(status.memoryLimitBytes) || status.memoryLimitBytes <= 0) {
        return `${peak}${megabytes(status.memoryPeakBytes)}`;
    }
    const percent = Math.round((status.memoryPeakBytes / status.memoryLimitBytes) * 100);
    return `${peak}${megabytes(status.memoryPeakBytes)} of ${megabytes(status.memoryLimitBytes)} (${percent}%)`;
}

/** The rows to show, in the order they read: what came out, then what it cost. */
function rowsOf(status) {
    const rows = [
        ['Pages', String(status.pageCount)],
        ['Size', megabytes(status.sizeInBytes)],
        ['Generated in', spellOutDuration(status.generatedInMillis)],
        ['Of which the site generator', spellOutDuration(status.generatorMillis)],
    ];
    const memory = memoryOf(status);
    if (memory) {
        rows.push(['Memory of the container', memory]);
    }
    return rows;
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
 * Every number the table formats is checked, not just the first. `megabytes` and `spellOutDuration` do
 * arithmetic and would put `NaN MB` and `NaN s` on a published page, which is worse than the sentence the page
 * already carries without them.
 */
function isStatus(status) {
    return typeof status === 'object' && status !== null && !Array.isArray(status)
        && isNumber(status.pageCount) && isNumber(status.sizeInBytes)
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
