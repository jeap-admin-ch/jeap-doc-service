import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createAddressUpdates} from '../../main/resources/site/src/components/ReactionViewer/addressUpdates.mjs';

function fixture() {
    const pending = new Map(), delays = [], published = [], failed = [];
    let next = 0;
    const updates = createAddressUpdates({
        setTimer: (callback, delay) => { delays.push(delay); pending.set(++next, callback); return next; },
        clearTimer: id => pending.delete(id),
    });
    const flush = () => { const work = [...pending.values()]; pending.clear(); return Promise.all(work.map(callback => callback())); };
    const operation = generate => ({generate, publish: link => published.push(link), fail: error => failed.push(error.message)});
    return {updates, pending, delays, published, failed, flush, operation};
}

test('rapid interactions collapse into one generation after the debounce interval', async () => {
    const f = fixture(), owner = {};
    let generated = 0;
    for (let i = 0; i < 30; i++) f.updates.schedule(owner, f.operation(async () => { generated++; return `link-${i}`; }));
    assert.equal(generated, 0);
    assert.equal(f.pending.size, 1);
    assert.ok(f.delays.every(delay => delay === 250));
    await f.flush();
    assert.equal(generated, 1);
    assert.deepEqual(f.published, ['link-29']);
});

test('the most recent viewer owns the address, even if an earlier encoder finishes later', async () => {
    const f = fixture(), first = {}, second = {};
    let finish;
    f.updates.schedule(first, f.operation(() => new Promise(resolve => { finish = resolve; })));
    const old = f.flush();
    f.updates.schedule(second, f.operation(async () => 'second-viewer'));
    await f.flush();
    finish('stale-first-viewer'); await old;
    assert.deepEqual(f.published, ['second-viewer']);
    assert.deepEqual(f.failed, []);
});

test('manual sharing supersedes pending automatic work and has a checkable publication ticket', async () => {
    const f = fixture(), owner = {};
    f.updates.schedule(owner, f.operation(async () => 'automatic'));
    const manual = f.updates.begin(owner);
    assert.equal(f.pending.size, 0);
    assert.ok(f.updates.current(manual));
    f.updates.schedule({}, f.operation(async () => 'newer-interaction'));
    assert.equal(f.updates.current(manual), false);
    await f.flush();
    assert.deepEqual(f.published, ['newer-interaction']);
});

test('unmount, navigation and cancellation suppress pending and in-flight results', async () => {
    const f = fixture(), owner = {};
    f.updates.schedule(owner, f.operation(async () => 'cancelled'));
    f.updates.cancel({});
    assert.equal(f.pending.size, 1);
    f.updates.cancel(owner);
    await f.flush();
    assert.deepEqual(f.published, []);
    let finish;
    f.updates.schedule(owner, f.operation(() => new Promise(resolve => { finish = resolve; })));
    const inFlight = f.flush();
    f.updates.dispose(); finish('disposed'); await inFlight;
    assert.deepEqual(f.published, []);
    f.updates.schedule(owner, f.operation(async () => 'resumed'));
    f.updates.dispose();
    assert.equal(f.pending.size, 0);
});

test('only the current failure is reported; stale failures cannot clear a newer address', async () => {
    const f = fixture(), owner = {};
    let reject;
    f.updates.schedule(owner, f.operation(() => new Promise((resolve, failure) => { reject = failure; })));
    const old = f.flush();
    f.updates.schedule({}, f.operation(async () => 'valid-new-address'));
    await f.flush(); reject(new Error('obsolete')); await old;
    assert.deepEqual(f.failed, []);
    f.updates.schedule(owner, f.operation(async () => { throw new Error('too many steps'); }));
    await f.flush();
    assert.deepEqual(f.published, ['valid-new-address']);
    assert.deepEqual(f.failed, ['too many steps']);
});
