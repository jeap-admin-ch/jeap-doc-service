import {test} from 'node:test';
import assert from 'node:assert/strict';
import {
    readEntry, contextPathOf, governanceUrl, scoreMarker, trendLabel, shortenComment, formatDate, formatDateTime,
    systemRows, componentEntry, complianceLine, sparkline, COMMENT_LIMIT,
} from '../../main/resources/site/src/components/Excerpts/model.mjs';

test('the placeholder is read, and anything else is not an excerpt', () => {
    assert.deepEqual(readEntry('{"page": "system", "system": "Orders", "environment": "prod", "components": ["a"]}\n'),
        {page: 'system', system: 'Orders', environment: 'prod', components: ['a']});
    assert.equal(readEntry('{not json'), null);
    assert.equal(readEntry('{"page": "message", "system": "Orders", "components": []}'), null);
    assert.equal(readEntry('{"page": "system", "system": "", "components": []}'), null);
    assert.equal(readEntry('{"page": "system", "system": "Orders", "components": [1]}'), null);
});

test('the context path is the base URL without the site, and the default site owns it', () => {
    assert.equal(contextPathOf('/docs/', 'default'), '/docs');
    assert.equal(contextPathOf('/', 'default'), '');
    assert.equal(contextPathOf('/docs/site/architecture/', 'architecture'), '/docs');
    assert.equal(contextPathOf('/site/architecture/', 'architecture'), '');
    assert.equal(governanceUrl('/docs', 'Orders & Co'), '/docs/ui-api/excerpts/governance/systems?system=Orders%20%26%20Co');
    assert.equal(governanceUrl('', 'a/b;c\\d%e'), '/ui-api/excerpts/governance/systems?system=a%2Fb%3Bc%5Cd%25e');
});

test('scores carry the governance site markers', () => {
    assert.equal(scoreMarker(80), '🟢');
    assert.equal(scoreMarker(79), '🟡');
    assert.equal(scoreMarker(50), '🟡');
    assert.equal(scoreMarker(49), '🔴');
    assert.equal(scoreMarker(null), '');
});

test('a trend is an arrow and a word, and nothing without one', () => {
    assert.deepEqual(trendLabel('UP'), {arrow: '↑', text: 'up'});
    assert.deepEqual(trendLabel('DOWN'), {arrow: '↓', text: 'down'});
    assert.deepEqual(trendLabel('STABLE'), {arrow: '→', text: 'stable'});
    assert.equal(trendLabel('NO_DATA'), null);
    assert.equal(trendLabel(null), null);
    assert.deepEqual(trendLabel('ODD'), {arrow: '', text: 'odd'});
});

test('a comment is joined with its first detail, cut, and counts the rest', () => {
    assert.deepEqual(shortenComment('Signature is not enforced', []), {text: 'Signature is not enforced', more: ''});
    assert.deepEqual(shortenComment('Outdated contracts', ['a uses 2.0.0', 'b uses 1.0.0', 'c uses 1.1.0']),
        {text: 'Outdated contracts: a uses 2.0.0', more: '+ 2 more'});
    assert.deepEqual(shortenComment(null, ['only detail']), {text: 'only detail', more: ''});
    const cut = shortenComment('x'.repeat(250), []);
    assert.equal(cut.text.length, COMMENT_LIMIT + 1);
    assert.ok(cut.text.endsWith('…'));
});

test('times are read in the offset the service delivered, not converted', () => {
    assert.equal(formatDateTime('2026-10-22T08:45:21+02:00'), '22.10.2026 08:45');
    assert.equal(formatDateTime('2026-10-22T23:45:21-05:00'), '22.10.2026 23:45');
    assert.equal(formatDate('2026-10-20T08:00:00+02:00'), '20.10.2026');
    assert.equal(formatDateTime(null), '');
    assert.equal(formatDate(undefined), '');
});

const excerpt = {
    components: [
        {name: 'core', score: 86, failingRules: []},
        {name: 'borderpanel', score: 78, failingRules: [{ruleId: 'r'}]},
        {name: 'archive', score: 86, failingRules: []},
        {name: 'unscored', score: null, failingRules: []},
        {name: 'not-in-the-model', score: 10, failingRules: []},
    ],
};

test('the system page lists the components of the model the answer knows, lowest score first, then by name', () => {
    assert.deepEqual(systemRows(excerpt, ['core', 'borderpanel', 'archive', 'unscored', 'unknown']).map(c => c.name),
        ['borderpanel', 'archive', 'core', 'unscored']);
    assert.deepEqual(systemRows(excerpt, ['unknown']), []);
    assert.deepEqual(systemRows(excerpt, ['Core', 'CORE']).map(c => c.name), ['core']);
    assert.deepEqual(systemRows(null, ['core']), []);
});

test('a component page shows its own entry, and nothing for one the answer does not know', () => {
    assert.equal(componentEntry(excerpt, 'core').score, 86);
    assert.equal(componentEntry(excerpt, 'Core').score, 86);
    assert.equal(componentEntry(excerpt, 'unknown'), null);
});

test('the line under the component table counts the further compliant rules and the paused or disabled ones', () => {
    assert.equal(complianceLine({compliant: 12, pausedOrDisabled: 0, failingRules: [{}]}), '12 further rules compliant.');
    assert.equal(complianceLine({compliant: 12, pausedOrDisabled: 2, failingRules: [{}]}),
        '12 further rules compliant, 2 paused or disabled.');
    assert.equal(complianceLine({compliant: 1, pausedOrDisabled: 0, failingRules: []}), '1 rule compliant.');
});

test('the history line spans the box from the oldest to the newest day', () => {
    assert.equal(sparkline([{score: 90}, {score: 80}], 100, 20), '0,0 100,20');
    assert.equal(sparkline([{score: 90}, {score: 90}, {score: 90}], 100, 20), '0,10 50,10 100,10');
    assert.equal(sparkline([{score: 90}], 100, 20), '');
});
