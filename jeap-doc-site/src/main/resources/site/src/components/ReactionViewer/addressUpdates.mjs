/** One page has one address: the last viewer interaction wins, including across asynchronous encoders. */
export function createAddressUpdates({delay = 250, setTimer = setTimeout, clearTimer = clearTimeout} = {}) {
    let active = null, timer = null;
    const clear = () => { if (timer !== null) clearTimer(timer); timer = null; };
    const begin = owner => { clear(); active = {owner}; return active; };
    const current = ticket => ticket !== null && active === ticket;
    return {
        begin,
        current,
        schedule(owner, operation) {
            const ticket = begin(owner);
            timer = setTimer(async () => {
                if (!current(ticket)) return;
                timer = null;
                try {
                    const link = await operation.generate();
                    if (current(ticket)) operation.publish(link);
                } catch (error) { if (current(ticket)) operation.fail(error); }
            }, delay);
        },
        cancel(owner) { if (active?.owner === owner) { clear(); active = null; } },
        dispose() { clear(); active = null; },
    };
}
