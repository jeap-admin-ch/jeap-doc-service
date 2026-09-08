/**
 * Fills in what is true right now, on the page describing the documentation.
 *
 * The page is generated content, so a tree whose documentation has not moved is not generated again and the
 * page stays exactly as it was. That is the truthful reading for its provenance - this content was imported
 * then, this build wrote it - and a lie for its status: when the architecture repository was last read, and
 * when the schedule fires next. So the doc service leaves those cells empty and answers them live, and this
 * fills them in.
 *
 * It finds them the way `publicationNumbers.js` finds the numbers of the run, and for the same three reasons:
 *
 *  - **The URL comes from the link on the page.** The page carries an absolute link to the resource, so the
 *    fetch and the link can never disagree, and nothing here has to know how a site's base URL is built.
 *  - **Only its path is used.** A configured origin that is not the one the reader is on would make the fetch
 *    cross-origin, and the site's Content-Security-Policy allows `connect-src 'self'` and nothing else.
 *  - **Nothing happens when it fails.** The page reads correctly without this: it names the resource and says
 *    what is in it. A reader with no scripts and a fetch that fails both get the page as written.
 */

/** The name of the resource, which is also how the link to it is recognised. */
const FILE_NAME = 'live-status.json';

/**
 * The column headings the cells are found by. Nothing else on the page carries them, and they are constants
 * in `AboutThisDocumentation` for the same reason - the two have to agree, and what catches a disagreement is
 * the browser test asserting the fetched values appear on the page.
 */
const LAST_READ_COLUMN = 'Last read';
const SCHEDULE_COLUMN = 'Schedule';
const NEXT_COLUMN = 'Next';

/** Marks what this module wrote, so a second run replaces it instead of adding to it. */
const MARKER = 'data-jeap-doc-live';

function headingsOf(table) {
    return Array.from(table.querySelectorAll('thead th')).map((cell) => cell.textContent.trim());
}

/** The table whose header carries all of the given columns, or null when the page has no such table. */
function tableWith(...columns) {
    for (const table of document.querySelectorAll('table')) {
        const headings = headingsOf(table);
        if (columns.every((column) => headings.includes(column))) {
            return table;
        }
    }
    return null;
}

function cellsOf(row) {
    return Array.from(row.querySelectorAll('td'));
}

/**
 * Puts a value into one cell, replacing whatever is in it - the generated cell holds the dash that stands for
 * a value the page does not know. An empty value is left alone: there is nothing to say, and the dash says
 * that better than a blank cell.
 */
function fill(cell, value) {
    if (!cell || !value) {
        return;
    }
    const written = document.createElement('span');
    written.setAttribute(MARKER, 'true');
    written.textContent = value;
    cell.replaceChildren(written);
}

/** The last read of every environment, keyed by the environment id its row names in the first column. */
function fillEnvironments(environments) {
    const table = tableWith(LAST_READ_COLUMN);
    if (!table) {
        return;
    }
    const column = headingsOf(table).indexOf(LAST_READ_COLUMN);
    const byId = new Map(environments.map((environment) => [environment.id, environment.lastRead]));
    for (const row of table.querySelectorAll('tbody tr')) {
        const cells = cellsOf(row);
        const id = cells[0] ? cells[0].textContent.trim() : '';
        fill(cells[column], byId.get(id));
    }
}

/**
 * When each schedule fires next, keyed by the cron expression its row prints. The expression is configuration
 * and is on the page; the moment it fires next is not, which is the whole reason this is fetched.
 */
function fillSchedules(schedules) {
    const table = tableWith(SCHEDULE_COLUMN, NEXT_COLUMN);
    if (!table) {
        return;
    }
    const headings = headingsOf(table);
    const scheduleColumn = headings.indexOf(SCHEDULE_COLUMN);
    const nextColumn = headings.indexOf(NEXT_COLUMN);
    const byCron = new Map(schedules.map((schedule) => [schedule.cron, schedule.next]));
    for (const row of table.querySelectorAll('tbody tr')) {
        const cells = cellsOf(row);
        const cron = cells[scheduleColumn] ? cells[scheduleColumn].textContent.trim() : '';
        fill(cells[nextColumn], byCron.get(cron));
    }
}

/**
 * Whether the resource held what this expects, rather than any valid JSON. Both lists are checked, and every
 * entry of them: the file is whatever lies at a path the page pointed at, and this module trusts none of it.
 */
function isLiveStatus(status) {
    return typeof status === 'object' && status !== null && !Array.isArray(status)
        && Array.isArray(status.environments) && status.environments.every(isEnvironment)
        && Array.isArray(status.schedules) && status.schedules.every(isSchedule);
}

function isEnvironment(environment) {
    return typeof environment === 'object' && environment !== null
        && typeof environment.id === 'string'
        && (environment.lastRead === null || typeof environment.lastRead === 'string');
}

function isSchedule(schedule) {
    return typeof schedule === 'object' && schedule !== null
        && typeof schedule.cron === 'string'
        && (schedule.next === null || typeof schedule.next === 'string');
}

async function fillIn() {
    const link = document.querySelector(`a[href$="${FILE_NAME}"]`);
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
        // A reader who cannot reach the service, or an older publication whose page has no such link. The
        // page stands as written.
        return;
    }
    if (!isLiveStatus(status)) {
        return;
    }
    // The reader has navigated away, or to another environment's copy of this page, while the fetch was in
    // flight: the link this run found is no longer in the document, and the cells it would fill belong to a
    // page that is being replaced.
    if (!link.isConnected) {
        return;
    }
    fillEnvironments(status.environments);
    fillSchedules(status.schedules);
}

export default {
    onRouteDidUpdate() {
        // Reported, not swallowed. Every failure this module expects - no link, a fetch that fails, a
        // resource that is not this object, a page the reader has navigated away from - returns quietly
        // above, so anything that reaches here is a defect in this file. The browser suite asserts the
        // console is empty, which is the only thing watching this code at all.
        fillIn().catch((error) => console.error('The live status of this documentation could not be shown.', error));
    },
};
