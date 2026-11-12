export const MAX_LINK_LENGTH = 16384;
export const MAX_STATE_BYTES = 256 * 1024;
export const COMPRESSION_PREFIX = 'd1.';

async function readBounded(stream, maximum) {
    const reader = stream.getReader(), chunks = [];
    let length = 0;
    try {
        while (true) {
            const {done, value} = await reader.read();
            if (done) break;
            length += value.byteLength;
            if (length > maximum) {
                await reader.cancel();
                throw new Error('This view exceeds the maximum state size (256 KiB).');
            }
            chunks.push(value);
        }
    } finally { reader.releaseLock(); }
    const bytes = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    return bytes;
}

function base64url(bytes) {
    return btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join(''))
        .replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

function fromBase64url(value) {
    if (!value || !/^[\w-]+$/.test(value)) throw new Error('Invalid link encoding');
    const binary = atob(value.replaceAll('-', '+').replaceAll('_', '/'));
    return Uint8Array.from(binary, character => character.charCodeAt(0));
}

/** Compression preserves the original UTF-8 JSON text, including whitespace and number spelling. */
export async function encodeLinkText(value) {
    const bytes = new TextEncoder().encode(value);
    if (bytes.byteLength > MAX_STATE_BYTES) throw new Error('This view exceeds the maximum state size (256 KiB).');
    if (typeof CompressionStream === 'undefined') throw new Error('Link compression is unavailable in this browser.');
    const stream = new Blob([bytes]).stream().pipeThrough(new CompressionStream('deflate-raw'));
    return COMPRESSION_PREFIX + base64url(await readBounded(stream, MAX_STATE_BYTES + 1024));
}

/** The native decoder validates the stream; bound its output before parsing JSON. */
export async function decodeLinkText(token) {
    if (typeof token !== 'string' || token.length > MAX_LINK_LENGTH || !token.startsWith(COMPRESSION_PREFIX)) {
        throw new Error('Invalid link encoding');
    }
    const bytes = fromBase64url(token.slice(COMPRESSION_PREFIX.length));
    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
    return new TextDecoder('utf-8', {fatal: true}).decode(await readBounded(stream, MAX_STATE_BYTES));
}
