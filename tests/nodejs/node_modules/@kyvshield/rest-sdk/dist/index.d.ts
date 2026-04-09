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
import type { ChallengesResponse, KycResponse, KyvShieldErrorDetails, KyvShieldOptions, VerifyOptions } from './types.js';
export * from './types.js';
/** Default maximum image width in pixels before resize. */
export declare const DEFAULT_IMAGE_MAX_WIDTH = 1280;
/** Default JPEG compression quality (0–100). */
export declare const DEFAULT_IMAGE_QUALITY = 90;
/** Maximum number of images compressed in parallel. */
export declare const DEFAULT_MAX_CONCURRENT_COMPRESS = 20;
/**
 * Error thrown when the KyvShield API returns a non-2xx response
 * or when a request cannot be completed.
 */
export declare class KyvShieldError extends Error {
    readonly statusCode?: number;
    readonly body?: unknown;
    constructor(message: string, details?: KyvShieldErrorDetails);
}
/**
 * KyvShield SDK client.
 *
 * Instantiate once and reuse across your application.
 */
export declare class KyvShield {
    private readonly apiKey;
    private readonly baseUrl;
    private readonly imageOptions;
    private readonly enableLog;
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
    constructor(apiKey: string, baseUrl?: string, options?: KyvShieldOptions);
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
    getChallenges(): Promise<ChallengesResponse>;
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
    verify(options: VerifyOptions): Promise<KycResponse>;
    /**
     * Submit multiple KYC verification requests concurrently.
     * All requests run in parallel; results are settled (fulfilled or rejected).
     *
     * @param optionsList - Array of VerifyOptions (max 10 entries).
     * @returns Array of PromiseSettledResult in the same order as the input.
     *
     * @throws {@link KyvShieldError} if the batch size exceeds 10.
     */
    verifyBatch(optionsList: VerifyOptions[]): Promise<PromiseSettledResult<KycResponse>[]>;
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
    static verifyWebhookSignature(payload: Buffer, apiKey: string, signatureHeader: string): boolean;
    /**
     * Tagged logger. All messages are prefixed with `[KyvShield]`.
     * Does nothing when `enableLog` is false.
     */
    private log;
    /** Build the common headers used by all non-multipart requests. */
    private buildHeaders;
    /**
     * Validate required fields in {@link VerifyOptions} before sending.
     * Throws a descriptive {@link KyvShieldError} on the first problem found.
     * Note: file-path existence is only checked for plain path strings (not URLs,
     * base64, or Buffers — those are validated/fetched at send time).
     */
    private validateVerifyOptions;
    /**
     * Resolve an image value (Buffer, URL, data URI, base64, or file path)
     * into raw bytes.
     *
     * @param value - The image value from {@link VerifyOptions.images}.
     * @returns A Buffer containing the image bytes.
     */
    private resolveImage;
    /**
     * Warn if image exceeds the configured maxWidth threshold (approximated by size).
     * Node.js has no native image resize — if > 1MB, log a warning.
     */
    private warnIfLarge;
    /**
     * Build a native `multipart/form-data` body from {@link VerifyOptions}.
     *
     * Constructs the multipart body manually using `Buffer.concat` without any
     * external dependency. Returns the binary body and the Content-Type header
     * value (which includes the boundary).
     *
     * This method is async to support URL image downloads.
     */
    private buildMultipartBody;
    /**
     * Throw a {@link KyvShieldError} when the HTTP response is not successful (2xx).
     * Tries to parse JSON error details from the body when available.
     */
    private assertOk;
    /** Return a MIME type based on file extension. Defaults to `image/jpeg`. */
    private guessMimeType;
}
export default KyvShield;
//# sourceMappingURL=index.d.ts.map