/**
 * KyvShield REST SDK — Node.js / TypeScript
 *
 * A fully typed SDK for the KyvShield KYC REST API.
 * Requires Node.js 18+ (uses native fetch and crypto).
 *
 * @example
 * ```ts
 * import { KyvShield } from '@kyvshield/rest-sdk';
 *
 * const kyv = new KyvShield('your-api-key');
 *
 * const result = await kyv.verify({
 *   steps: ['selfie', 'recto', 'verso'],
 *   target: 'SN-CIN',
 *   language: 'fr',
 *   challengeMode: 'standard',
 *   requireFaceMatch: true,
 *   images: {
 *     selfie_center_face:    './selfie.jpg',
 *     recto_center_document: './recto.jpg',
 *     verso_center_document: './verso.jpg',
 *   },
 * });
 *
 * console.log(result.overall_status); // 'pass' | 'reject'
 * ```
 */
import * as fs from 'node:fs';
import * as path from 'node:path';
import * as crypto from 'node:crypto';
export * from './types.js';
// ─── Constants ────────────────────────────────────────────────────────────────
const DEFAULT_BASE_URL = 'https://kyvshield-naruto.innolinkcloud.com';
const API_VERSION = '/api/v1';
// ─── SDK Constants ────────────────────────────────────────────────────────────
/** Default maximum image width in pixels before resize. */
export const DEFAULT_IMAGE_MAX_WIDTH = 1280;
/** Default JPEG compression quality (0–100). */
export const DEFAULT_IMAGE_QUALITY = 90;
/** Maximum number of images compressed in parallel. */
export const DEFAULT_MAX_CONCURRENT_COMPRESS = 20;
// ─── Error class ─────────────────────────────────────────────────────────────
/**
 * Error thrown when the KyvShield API returns a non-2xx response
 * or when a request cannot be completed.
 */
export class KyvShieldError extends Error {
    statusCode;
    body;
    constructor(message, details) {
        super(message);
        this.name = 'KyvShieldError';
        this.statusCode = details?.statusCode;
        this.body = details?.body;
    }
}
// ─── SDK Class ────────────────────────────────────────────────────────────────
/**
 * KyvShield SDK client.
 *
 * Instantiate once and reuse across your application.
 */
export class KyvShield {
    apiKey;
    baseUrl;
    imageOptions;
    enableLog;
    /**
     * Create a new KyvShield client.
     *
     * @param apiKey  - Your KyvShield API key (sent as the `X-API-Key` header).
     * @param baseUrl - Optional base URL override. Defaults to the production endpoint.
     * @param options - Optional SDK configuration (logging, image options).
     *
     * @example
     * ```ts
     * // Production with logging enabled
     * const kyv = new KyvShield('your-api-key', undefined, { enableLog: true });
     *
     * // Local / staging
     * const kyv = new KyvShield('your-api-key', 'http://localhost:8080');
     *
     * // Custom image options
     * const kyv = new KyvShield('your-api-key', undefined, {
     *   imageOptions: { maxWidth: 1280, jpegQuality: 90 },
     *   enableLog: true,
     * });
     * ```
     */
    constructor(apiKey, baseUrl = DEFAULT_BASE_URL, options = {}) {
        if (!apiKey || apiKey.trim() === '') {
            throw new KyvShieldError('apiKey must be a non-empty string');
        }
        this.apiKey = apiKey;
        // Normalise: remove trailing slash so we can always append '/api/v1/...'
        this.baseUrl = baseUrl.replace(/\/+$/, '');
        this.enableLog = options.enableLog ?? true;
        this.imageOptions = {
            maxWidth: options.imageOptions?.maxWidth ?? 1280,
            jpegQuality: options.imageOptions?.jpegQuality ?? 90,
        };
        this.log('info', `Initialized (baseUrl=${this.baseUrl}, imageMaxWidth=${this.imageOptions.maxWidth}, imageQuality=${this.imageOptions.jpegQuality})`);
    }
    // ── Public methods ──────────────────────────────────────────────────────────
    /**
     * Retrieve the list of available challenges for each mode and step type.
     *
     * Useful for dynamically building the image map to pass to {@link verify}.
     *
     * @returns A {@link ChallengesResponse} object describing all available challenges.
     *
     * @example
     * ```ts
     * const { challenges } = await kyv.getChallenges();
     * console.log(challenges.selfie.standard);
     * // ['center_face', 'close_eyes', 'turn_left', 'turn_right']
     * ```
     */
    async getChallenges() {
        const url = `${this.baseUrl}${API_VERSION}/challenges`;
        this.log('info', `GET ${API_VERSION}/challenges...`);
        const response = await fetch(url, {
            method: 'GET',
            headers: this.buildHeaders(),
            signal: AbortSignal.timeout(120_000),
        });
        await this.assertOk(response);
        const result = await response.json();
        const selfieCount = result.challenges?.selfie
            ? Object.values(result.challenges.selfie).flat().length
            : 0;
        const docCount = result.challenges?.document
            ? Object.values(result.challenges.document).flat().length
            : 0;
        this.log('info', `Challenges loaded (${selfieCount} selfie modes, ${docCount} document modes)`);
        return result;
    }
    /**
     * Submit a KYC verification request.
     *
     * The SDK reads each image from disk, packages everything as a
     * `multipart/form-data` request, and returns the fully typed response.
     *
     * @param options - Verification options. See {@link VerifyOptions} for full documentation.
     * @returns A {@link KycResponse} with per-step results and an overall decision.
     *
     * @throws {@link KyvShieldError} on HTTP errors or missing image files.
     *
     * @example
     * ```ts
     * const result = await kyv.verify({
     *   steps: ['selfie', 'recto'],
     *   target: 'SN-CIN',
     *   language: 'en',
     *   challengeMode: 'minimal',
     *   requireFaceMatch: true,
     *   images: {
     *     selfie_center_face:    './selfie.jpg',
     *     recto_center_document: './recto.jpg',
     *   },
     * });
     *
     * if (result.overall_status === 'pass') {
     *   console.log('Verification passed!', result.session_id);
     * } else {
     *   console.warn('Verification rejected', result.steps.map(s => s.user_messages));
     * }
     * ```
     */
    async verify(options) {
        this.validateVerifyOptions(options);
        const imageCount = Object.keys(options.images).length;
        this.log('info', `POST ${API_VERSION}/kyc/verify (steps=[${options.steps.join(',')}], target=${options.target}, images=${imageCount})`);
        const startMs = Date.now();
        const { body, contentType } = await this.buildMultipartBody(options);
        const url = `${this.baseUrl}${API_VERSION}/kyc/verify`;
        const response = await fetch(url, {
            method: 'POST',
            headers: {
                'X-API-Key': this.apiKey,
                'Content-Type': contentType,
            },
            body,
            signal: AbortSignal.timeout(120_000),
        });
        await this.assertOk(response);
        const result = await response.json();
        const elapsed = Date.now() - startMs;
        this.log('info', `Verify complete: ${result.overall_status} (${result.overall_confidence?.toFixed(2)}) in ${elapsed}ms`);
        return result;
    }
    /**
     * Submit multiple KYC verification requests concurrently.
     * All requests run in parallel; results are settled (fulfilled or rejected).
     *
     * @param optionsList - Array of VerifyOptions (max 10 entries).
     * @returns Array of PromiseSettledResult in the same order as the input.
     *
     * @throws {@link KyvShieldError} if the batch size exceeds 10.
     */
    async verifyBatch(optionsList) {
        if (optionsList.length > 10) {
            throw new KyvShieldError('Batch size cannot exceed 10');
        }
        this.log('info', `Batch verify: ${optionsList.length} requests...`);
        const results = await Promise.allSettled(optionsList.map(opts => this.verify(opts)));
        const passed = results.filter(r => r.status === 'fulfilled' && r.value.overall_status === 'pass').length;
        const rejected = results.filter(r => r.status === 'fulfilled' && r.value.overall_status !== 'pass').length;
        const failed = results.filter(r => r.status === 'rejected').length;
        this.log('info', `Batch complete: ${passed} pass, ${rejected + failed} reject`);
        return results;
    }
    /**
     * Verify an incoming webhook signature.
     *
     * KyvShield signs webhook payloads with HMAC-SHA256 using your API key.
     * Call this before processing any webhook to confirm it came from KyvShield.
     *
     * @param payload         - Raw request body as a `Buffer`.
     * @param apiKey          - Your KyvShield API key (same one used to create the client).
     * @param signatureHeader - Value of the `X-KyvShield-Signature` header sent with the webhook.
     * @returns `true` if the signature is valid, `false` otherwise.
     *
     * @example
     * ```ts
     * // Express example
     * app.post('/webhook', express.raw({ type: '*\/*' }), (req, res) => {
     *   const valid = KyvShield.verifyWebhookSignature(
     *     req.body,
     *     process.env.KYVSHIELD_API_KEY!,
     *     req.headers['x-kyvshield-signature'] as string,
     *   );
     *
     *   if (!valid) return res.status(401).send('Invalid signature');
     *
     *   // process webhook ...
     *   res.sendStatus(200);
     * });
     * ```
     */
    static verifyWebhookSignature(payload, apiKey, signatureHeader) {
        if (!payload || !apiKey || !signatureHeader)
            return false;
        const expected = crypto
            .createHmac('sha256', apiKey)
            .update(payload)
            .digest('hex');
        // Use timingSafeEqual to prevent timing attacks
        try {
            const expectedBuf = Buffer.from(expected, 'hex');
            // Strip an optional 'sha256=' prefix the server might prepend
            const incomingSig = signatureHeader.startsWith('sha256=')
                ? signatureHeader.slice(7)
                : signatureHeader;
            const incomingBuf = Buffer.from(incomingSig, 'hex');
            if (expectedBuf.length !== incomingBuf.length)
                return false;
            return crypto.timingSafeEqual(expectedBuf, incomingBuf);
        }
        catch {
            return false;
        }
    }
    // ── Private helpers ─────────────────────────────────────────────────────────
    /**
     * Tagged logger. All messages are prefixed with `[KyvShield]`.
     * Does nothing when `enableLog` is false.
     */
    log(level, ...args) {
        if (!this.enableLog)
            return;
        const tag = '[KyvShield]';
        switch (level) {
            case 'debug':
                console.debug(tag, ...args);
                break;
            case 'info':
                console.info(tag, ...args);
                break;
            case 'warn':
                console.warn(tag, ...args);
                break;
            case 'error':
                console.error(tag, ...args);
                break;
        }
    }
    /** Build the common headers used by all non-multipart requests. */
    buildHeaders() {
        return {
            'X-API-Key': this.apiKey,
            Accept: 'application/json',
        };
    }
    /**
     * Validate required fields in {@link VerifyOptions} before sending.
     * Throws a descriptive {@link KyvShieldError} on the first problem found.
     * Note: file-path existence is only checked for plain path strings (not URLs,
     * base64, or Buffers — those are validated/fetched at send time).
     */
    validateVerifyOptions(options) {
        if (!options.steps || options.steps.length === 0) {
            throw new KyvShieldError('VerifyOptions.steps must contain at least one step');
        }
        if (!options.target || options.target.trim() === '') {
            throw new KyvShieldError('VerifyOptions.target must be a non-empty string');
        }
        if (!options.images || Object.keys(options.images).length === 0) {
            throw new KyvShieldError('VerifyOptions.images must contain at least one entry');
        }
        // Only validate file existence for plain filesystem paths
        for (const [key, value] of Object.entries(options.images)) {
            if (Buffer.isBuffer(value))
                continue;
            if (value.startsWith('http://') || value.startsWith('https://'))
                continue;
            if (value.startsWith('data:image/'))
                continue;
            // Check if it looks like a base64 string (no path separator, long, no extension)
            if (!value.includes('/') && !value.includes('\\') && value.length > 64 && !/\.\w{2,5}$/.test(value))
                continue;
            const resolved = path.resolve(value);
            if (!fs.existsSync(resolved)) {
                throw new KyvShieldError(`Image file for "${key}" not found: ${resolved}`);
            }
        }
    }
    /**
     * Resolve an image value (Buffer, URL, data URI, base64, or file path)
     * into raw bytes.
     *
     * @param value - The image value from {@link VerifyOptions.images}.
     * @returns A Buffer containing the image bytes.
     */
    async resolveImage(value, fieldName) {
        const label = fieldName ?? 'image';
        // 1. Already raw bytes
        if (Buffer.isBuffer(value)) {
            const buf = this.warnIfLarge(value, label);
            return buf;
        }
        // 2. URL — download with a 30-second timeout
        if (value.startsWith('http://') || value.startsWith('https://')) {
            this.log('debug', `Downloading image from ${value}`);
            const response = await fetch(value, {
                signal: AbortSignal.timeout(30_000),
            });
            if (!response.ok) {
                throw new KyvShieldError(`Failed to download image from URL "${value}": HTTP ${response.status}`);
            }
            const arrayBuffer = await response.arrayBuffer();
            const buf = Buffer.from(arrayBuffer);
            return this.warnIfLarge(buf, label);
        }
        // 3. Data URI — strip prefix and decode base64
        if (value.startsWith('data:image/')) {
            const commaIndex = value.indexOf(',');
            if (commaIndex === -1) {
                throw new KyvShieldError(`Invalid data URI for image: "${value.slice(0, 40)}..."`);
            }
            const b64 = value.slice(commaIndex + 1);
            const sizeKb = Math.round(b64.length * 0.75 / 1024);
            this.log('debug', `Decoding base64 image (${sizeKb}KB)`);
            const buf = Buffer.from(b64, 'base64');
            return this.warnIfLarge(buf, label);
        }
        // 4. Base64 string (no path separators, long, no file extension)
        if (!value.includes('/') && !value.includes('\\') && value.length > 64 && !/\.\w{2,5}$/.test(value)) {
            const sizeKb = Math.round(value.length * 0.75 / 1024);
            this.log('debug', `Decoding base64 image (${sizeKb}KB)`);
            const buf = Buffer.from(value, 'base64');
            return this.warnIfLarge(buf, label);
        }
        // 5. File path
        const resolved = path.resolve(value);
        const buf = fs.readFileSync(resolved);
        return this.warnIfLarge(buf, label);
    }
    /**
     * Warn if image exceeds the configured maxWidth threshold (approximated by size).
     * Node.js has no native image resize — if > 1MB, log a warning.
     */
    warnIfLarge(data, label) {
        const sizeKb = Math.round(data.length / 1024);
        const oneMB = 1024 * 1024;
        if (data.length > oneMB) {
            this.log('warn', `Image ${label}: ${sizeKb}KB exceeds 1MB. Node.js SDK cannot resize natively — consider pre-resizing to ${this.imageOptions.maxWidth}px before passing to SDK.`);
        }
        else {
            this.log('debug', `Image ${label}: ${sizeKb}KB`);
        }
        return data;
    }
    /**
     * Build a native `multipart/form-data` body from {@link VerifyOptions}.
     *
     * Constructs the multipart body manually using `Buffer.concat` without any
     * external dependency. Returns the binary body and the Content-Type header
     * value (which includes the boundary).
     *
     * This method is async to support URL image downloads.
     */
    async buildMultipartBody(options) {
        const boundary = crypto.randomUUID().replace(/-/g, '');
        const CRLF = '\r\n';
        const parts = [];
        const textPart = (name, value) => {
            const header = `--${boundary}${CRLF}Content-Disposition: form-data; name="${name}"${CRLF}${CRLF}`;
            return Buffer.concat([Buffer.from(header), Buffer.from(value, 'utf8'), Buffer.from(CRLF)]);
        };
        const filePart = (name, filename, mimeType, data) => {
            const header = `--${boundary}${CRLF}` +
                `Content-Disposition: form-data; name="${name}"; filename="${filename}"${CRLF}` +
                `Content-Type: ${mimeType}${CRLF}${CRLF}`;
            return Buffer.concat([Buffer.from(header), data, Buffer.from(CRLF)]);
        };
        // ── Text fields ─────────────────────────────────────────────────────────
        parts.push(textPart('steps', JSON.stringify(options.steps)));
        parts.push(textPart('target', options.target));
        parts.push(textPart('language', options.language ?? 'fr'));
        parts.push(textPart('challenge_mode', options.challengeMode ?? 'standard'));
        if (options.selfieChallengeMode !== undefined) {
            parts.push(textPart('selfie_challenge_mode', options.selfieChallengeMode));
        }
        if (options.rectoChallengeMode !== undefined) {
            parts.push(textPart('recto_challenge_mode', options.rectoChallengeMode));
        }
        if (options.versoChallengeMode !== undefined) {
            parts.push(textPart('verso_challenge_mode', options.versoChallengeMode));
        }
        parts.push(textPart('require_face_match', options.requireFaceMatch === true ? 'true' : 'false'));
        if (options.kycIdentifier !== undefined) {
            parts.push(textPart('kyc_identifier', options.kycIdentifier));
        }
        // ── Image files — resolve + compress in parallel (semaphore: max 20) ───
        const imageEntries = Object.entries(options.images);
        if (imageEntries.length > 0) {
            // Simple semaphore: allow up to DEFAULT_MAX_CONCURRENT_COMPRESS concurrent resolves
            let running = 0;
            const queue = [];
            const acquire = () => {
                if (running < DEFAULT_MAX_CONCURRENT_COMPRESS) {
                    running++;
                    return Promise.resolve();
                }
                return new Promise(resolve => queue.push(resolve));
            };
            const release = () => {
                running--;
                const next = queue.shift();
                if (next) {
                    running++;
                    next();
                }
            };
            const resolveWithSemaphore = async (key, value) => {
                await acquire();
                try {
                    const data = await this.resolveImage(value, key);
                    let filename = 'image.jpg';
                    if (!Buffer.isBuffer(value) && !value.startsWith('data:image/') && !value.startsWith('http')) {
                        filename = path.basename(value.includes('/') || value.includes('\\') ? value : key + '.jpg');
                    }
                    return { key, data, filename };
                }
                finally {
                    release();
                }
            };
            const resolved = await Promise.all(imageEntries.map(([key, value]) => resolveWithSemaphore(key, value)));
            for (const { key, data, filename } of resolved) {
                const mimeType = this.guessMimeType(filename);
                parts.push(filePart(key, filename, mimeType, data));
            }
        }
        // ── Closing boundary ────────────────────────────────────────────────────
        parts.push(Buffer.from(`--${boundary}--${CRLF}`));
        return {
            body: Buffer.concat(parts),
            contentType: `multipart/form-data; boundary=${boundary}`,
        };
    }
    /**
     * Throw a {@link KyvShieldError} when the HTTP response is not successful (2xx).
     * Tries to parse JSON error details from the body when available.
     */
    async assertOk(response) {
        if (response.ok)
            return;
        let body;
        try {
            body = await response.json();
        }
        catch {
            body = await response.text().catch(() => undefined);
        }
        throw new KyvShieldError(`KyvShield API error ${response.status}: ${response.statusText}`, { statusCode: response.status, body });
    }
    /** Return a MIME type based on file extension. Defaults to `image/jpeg`. */
    guessMimeType(filename) {
        const ext = path.extname(filename).toLowerCase();
        const map = {
            '.jpg': 'image/jpeg',
            '.jpeg': 'image/jpeg',
            '.png': 'image/png',
            '.webp': 'image/webp',
            '.gif': 'image/gif',
        };
        return map[ext] ?? 'image/jpeg';
    }
}
export default KyvShield;
//# sourceMappingURL=index.js.map