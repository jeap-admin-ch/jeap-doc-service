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
 *    "source": "generated", "subject": "component", "system": "orders", "name": "orders-intake"}
 *
 * `environment`, `source` and `subject` become filters rather than separate indexes. The first scopes a search
 * to the tree the reader is in - a better version of what the removed plugin did with one index per
 * environment, because one corpus means the ranking is comparable across systems. The other two are what the
 * reader narrows with: what produced a page, and what it documents.
 *
 * A key a record has no value for is left out rather than sent empty. A record with no value for a key a query
 * names is excluded by the index, which is exactly what should happen to the site's own pages - they document
 * no subject - when a reader narrows the search to one.
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
        // says nothing on a site of fifty components. `name` is the component or the library, and `microsite`
        // is the uploaded documentation a hit was found inside.
        meta: {
            title: record.title,
            ...(record.system ? {system: record.system} : {}),
            ...(record.name ? {name: record.name} : {}),
            ...(record.microsite ? {microsite: record.microsite} : {}),
        },
        filters: {
            environment: [record.environment],
            source: [record.source],
            ...(record.subject ? {subject: [record.subject]} : {}),
        },
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
