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

class DeflateBits {
    constructor(bytes) {
        this.bytes = bytes;
        this.position = 0;
    }

    read(count) {
        if (this.position + count > this.bytes.byteLength * 8) throw new Error('Invalid link encoding');
        let value = 0;
        for (let bit = 0; bit < count; bit++, this.position++) {
            value |= ((this.bytes[this.position >> 3] >> (this.position & 7)) & 1) << bit;
        }
        return value;
    }

    align() {
        this.position = (this.position + 7) & ~7;
    }

    skipBytes(count) {
        if (this.position + count * 8 > this.bytes.byteLength * 8) throw new Error('Invalid link encoding');
        this.position += count * 8;
    }

    consumedBytes() {
        return Math.ceil(this.position / 8);
    }
}

function reversed(value, length) {
    let result = 0;
    for (let bit = 0; bit < length; bit++) {
        result = (result << 1) | ((value >> bit) & 1);
    }
    return result;
}

function huffman(lengths) {
    const counts = Array(16).fill(0), next = Array(16).fill(0), symbols = new Map();
    for (const length of lengths) {
        if (length < 0 || length > 15) throw new Error('Invalid link encoding');
        if (length) counts[length]++;
    }
    let code = 0;
    for (let length = 1; length <= 15; length++) {
        code = (code + counts[length - 1]) << 1;
        next[length] = code;
        if (code + counts[length] > 1 << length) throw new Error('Invalid link encoding');
    }
    lengths.forEach((length, symbol) => {
        if (length) symbols.set(`${length}:${reversed(next[length]++, length)}`, symbol);
    });
    return symbols;
}

function symbol(bits, table) {
    let code = 0;
    for (let length = 1; length <= 15; length++) {
        code |= bits.read(1) << (length - 1);
        const value = table.get(`${length}:${code}`);
        if (value !== undefined) return value;
    }
    throw new Error('Invalid link encoding');
}

const LENGTH_BASES = [
    3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
];
const LENGTH_EXTRA_BITS = [
    0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
];
const DISTANCE_BASES = [1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769,
    1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577];
const DISTANCE_EXTRA_BITS = [0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10,
    11, 11, 12, 12, 13, 13];

function dynamicTables(bits) {
    const literalCount = bits.read(5) + 257, distanceCount = bits.read(5) + 1, codeLengthCount = bits.read(4) + 4;
    const order = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15];
    const codeLengths = Array(19).fill(0);
    for (let index = 0; index < codeLengthCount; index++) codeLengths[order[index]] = bits.read(3);
    const table = huffman(codeLengths), lengths = [];
    while (lengths.length < literalCount + distanceCount) {
        const value = symbol(bits, table);
        if (value <= 15) lengths.push(value);
        else {
            if (value === 16 && !lengths.length) throw new Error('Invalid link encoding');
            const repeat = value === 16 ? bits.read(2) + 3 : value === 17 ? bits.read(3) + 3 : bits.read(7) + 11;
            const repeated = value === 16 ? lengths.at(-1) : 0;
            if (lengths.length + repeat > literalCount + distanceCount) throw new Error('Invalid link encoding');
            lengths.push(...Array(repeat).fill(repeated));
        }
    }
    return [huffman(lengths.slice(0, literalCount)), huffman(lengths.slice(literalCount))];
}

function compressedBlock(bits, literalTable, distanceTable, outputLength) {
    while (true) {
        const value = symbol(bits, literalTable);
        if (value < 256) outputLength++;
        else if (value === 256) return outputLength;
        else {
            const lengthIndex = value - 257;
            if (lengthIndex < 0 || lengthIndex >= LENGTH_BASES.length) throw new Error('Invalid link encoding');
            const length = LENGTH_BASES[lengthIndex] + bits.read(LENGTH_EXTRA_BITS[lengthIndex]);
            const distanceCode = symbol(bits, distanceTable);
            if (distanceCode >= DISTANCE_BASES.length) throw new Error('Invalid link encoding');
            const distance = DISTANCE_BASES[distanceCode] + bits.read(DISTANCE_EXTRA_BITS[distanceCode]);
            if (distance > outputLength) throw new Error('Invalid link encoding');
            outputLength += length;
        }
    }
}

function requireCompleteDeflateStream(bytes) {
    const bits = new DeflateBits(bytes);
    let final = false, outputLength = 0;
    while (!final) {
        final = Boolean(bits.read(1));
        const type = bits.read(2);
        if (type === 0) {
            bits.align();
            const length = bits.read(16), complement = bits.read(16);
            if ((length ^ 0xffff) !== complement) throw new Error('Invalid link encoding');
            bits.skipBytes(length);
            outputLength += length;
        } else if (type === 1) {
            const literalLengths = [
                ...Array(144).fill(8), ...Array(112).fill(9), ...Array(24).fill(7), ...Array(8).fill(8),
            ];
            outputLength = compressedBlock(bits, huffman(literalLengths), huffman(Array(32).fill(5)), outputLength);
        } else if (type === 2) {
            const [literalTable, distanceTable] = dynamicTables(bits);
            outputLength = compressedBlock(bits, literalTable, distanceTable, outputLength);
        } else throw new Error('Invalid link encoding');
    }
    if (bits.consumedBytes() !== bytes.byteLength) throw new Error('Invalid link encoding');
}

/** Compression preserves the original UTF-8 JSON text, including whitespace and number spelling. */
export async function encodeLinkText(value) {
    const bytes = new TextEncoder().encode(value);
    if (bytes.byteLength > MAX_STATE_BYTES) throw new Error('This view exceeds the maximum state size (256 KiB).');
    if (typeof CompressionStream === 'undefined') throw new Error('Link compression is unavailable in this browser.');
    const stream = new Blob([bytes]).stream().pipeThrough(new CompressionStream('deflate-raw'));
    return COMPRESSION_PREFIX + base64url(await readBounded(stream, MAX_STATE_BYTES + 1024));
}

/** Bound decompression before parsing JSON; bare Base64URL remains the legacy transport. */
export async function decodeLinkText(token) {
    if (typeof token !== 'string' || token.length > MAX_LINK_LENGTH) throw new Error('Invalid link encoding');
    let bytes;
    if (token.startsWith(COMPRESSION_PREFIX)) {
        bytes = fromBase64url(token.slice(COMPRESSION_PREFIX.length));
        requireCompleteDeflateStream(bytes);
        const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
        bytes = await readBounded(stream, MAX_STATE_BYTES);
    } else bytes = fromBase64url(token);
    return new TextDecoder('utf-8', {fatal: true}).decode(bytes);
}
