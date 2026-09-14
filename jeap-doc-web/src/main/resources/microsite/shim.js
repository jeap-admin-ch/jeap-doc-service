/**
 * Gives a sandboxed microsite back the two things its opaque origin takes away: a storage object and a cookie
 * jar, both in memory and both gone when the page is closed.
 *
 * A document with an opaque origin may not touch localStorage, sessionStorage or document.cookie - reading the
 * property throws SecurityError - and an application that reads one while it loads never starts. Nothing here
 * persists anything, which is what an isolated microsite is meant to do.
 *
 * The doc service injects this into every HTML page of a microsite it serves, before the page's own scripts.
 */
(function () {
    function memoryStorage() {
        const values = new Map();
        const storage = {
            getItem: (key) => (values.has(String(key)) ? values.get(String(key)) : null),
            setItem: (key, value) => {
                values.set(String(key), String(value));
            },
            removeItem: (key) => {
                values.delete(String(key));
            },
            clear: () => values.clear(),
            key: (index) => [...values.keys()][index] ?? null,
        };
        Object.defineProperty(storage, 'length', {get: () => values.size});
        return storage;
    }

    for (const name of ['localStorage', 'sessionStorage']) {
        let usable = false;
        try {
            window[name].getItem('jeap-doc-probe');
            usable = true;
        } catch (e) {
            usable = false;
        }
        if (!usable) {
            try {
                Object.defineProperty(window, name, {value: memoryStorage(), configurable: true});
            } catch (e) {
                // Nothing else to try: the application sees the browser's own error.
            }
        }
    }

    let cookiesThrow = false;
    try {
        void document.cookie;
    } catch (e) {
        cookiesThrow = true;
    }
    if (cookiesThrow) {
        const jar = new Map();
        try {
            Object.defineProperty(document, 'cookie', {
                configurable: true,
                get: () => [...jar].map(([name, value]) => name + '=' + value).join('; '),
                set: (written) => {
                    const pair = String(written).split(';')[0];
                    const at = pair.indexOf('=');
                    if (at > 0) {
                        jar.set(pair.slice(0, at).trim(), pair.slice(at + 1).trim());
                    }
                },
            });
        } catch (e) {
            // Nothing else to try: the application sees the browser's own error.
        }
    }
})();
