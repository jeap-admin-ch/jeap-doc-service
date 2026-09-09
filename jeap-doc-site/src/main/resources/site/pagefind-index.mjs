/**
 * Builds the search index of a documentation site.
 *
 * Usage: node pagefind-index.mjs <records.jsonl> <output-directory>
 *
 * This is **not part of the Docusaurus application** beside it. It is run by the doc service on its own, over
 * the content of the whole site rather than of one part, because a site is published as one build per part and
 * an index over one part would let a reader inside one system search only that system.
 *
 * It decides nothing about what is indexed. Every record arrives ready-made, one JSON object per line, and the
 * only thing this adds is the shape Pagefind wants:
 *
 *   {"url": "/systems/orders/", "title": "Orders", "content": "…", "environment": "prod",
 *    "system": "orders", "component": "orders-intake"}
 *
 * `environment` becomes a filter rather than a separate index. That is what scopes a search to the tree the
 * reader is in, and it is a better version of what the removed plugin did with one index per environment: one
 * corpus means the ranking is comparable across systems, and the filter is applied before it.
 */
import {createIndex} from 'pagefind';
import fs from 'node:fs';
import readline from 'node:readline';

const [recordsFile, outputPath] = process.argv.slice(2);
if (!recordsFile || !outputPath) {
    console.error('Usage: node pagefind-index.mjs <records.jsonl> <output-directory>');
    process.exit(2);
}

/** Pagefind reports failures in an `errors` array rather than by throwing, so every call is checked. */
function checked(result, what) {
    if (result?.errors?.length) {
        throw new Error(`${what}: ${result.errors.join('; ')}`);
    }
    return result;
}

const started = Date.now();

// One language for the whole index. The documentation is English by decision, and letting Pagefind detect a
// language per record would split the index into one per language it thought it saw.
const {index} = checked(await createIndex({forceLanguage: 'en'}), 'creating the index');

let records = 0;
const lines = readline.createInterface({input: fs.createReadStream(recordsFile), crlfDelay: Infinity});
for await (const line of lines) {
    if (!line.trim()) {
        continue;
    }
    const record = JSON.parse(line);
    checked(await index.addCustomRecord({
        url: record.url,
        content: record.content,
        language: 'en',
        // Where the page is, for a result to show beside its title: a title like "Component Architecture"
        // says nothing on a site of fifty components. Absent keys are left out rather than sent empty, so a
        // page of the site itself carries neither.
        meta: {
            title: record.title,
            ...(record.system ? {system: record.system} : {}),
            ...(record.component ? {component: record.component} : {}),
        },
        filters: {environment: [record.environment]},
    }), `adding ${record.url}`);
    records++;
}

if (records === 0) {
    // Pagefind writes an index of nothing without complaining, and a site that answers every query with
    // nothing looks exactly like a site whose search is broken.
    throw new Error(`${recordsFile} held no records; there is nothing to index.`);
}

checked(await index.writeFiles({outputPath}), 'writing the index');
console.log(`Indexed ${records} page(s) in ${((Date.now() - started) / 1000).toFixed(1)} s -> ${outputPath}`);
