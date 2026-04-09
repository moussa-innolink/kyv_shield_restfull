/**
 * KyvShield REST SDK - Type Definitions
 * All TypeScript interfaces and types for the KyvShield KYC API.
 */
/** Challenge intensity mode */
export type ChallengeMode = 'minimal' | 'standard' | 'strict';
/** Verification step type */
export type Step = 'selfie' | 'recto' | 'verso';
/** Supported document target types */
export type DocumentTarget = 'SN-CIN' | 'SN-PASSPORT' | 'SN-DRIVER-LICENCE' | string;
/** Supported languages */
export type Language = 'fr' | 'en' | 'wo';
/** Overall verification outcome */
export type OverallStatus = 'pass' | 'reject';
/** Confidence level descriptor */
export type ConfidenceLevel = 'HIGH' | 'MEDIUM' | 'LOW';
/** Available challenges grouped by intensity for a single asset type */
export interface ChallengeModeMap {
    minimal: string[];
    standard: string[];
    strict: string[];
}
/** Full challenges response from GET /api/v1/challenges */
export interface ChallengesResponse {
    challenges: {
        /** Challenges applicable to document steps (recto / verso) */
        document: ChallengeModeMap;
        /** Challenges applicable to selfie steps */
        selfie: ChallengeModeMap;
    };
}
/** A single extracted text field from a document */
export interface ExtractionField {
    /** Machine-readable field key */
    key: string;
    /** Key as used in the document specification */
    document_key: string;
    /** Human-readable label */
    label: string;
    /** Extracted value */
    value: string;
    /** Display ordering hint (lower = higher priority) */
    display_priority: number;
    /** Optional icon identifier */
    icon?: string;
}
/** A photo extracted from a document image */
export interface ExtractedPhoto {
    /** Base-64-encoded JPEG image data */
    image: string;
    /** Model confidence for this extraction (0–1) */
    confidence: number;
    /** Bounding box [x, y, width, height] in pixels */
    bbox: number[];
    /** Area of the bounding box in pixels² */
    area: number;
    /** Width of the extracted region in pixels */
    width: number;
    /** Height of the extracted region in pixels */
    height: number;
}
/** Liveness sub-result for a single step */
export interface LivenessResult {
    /** Whether the subject / document is considered live / physical */
    is_live: boolean;
    /** Liveness score (0–1) */
    score: number;
    /** Qualitative confidence descriptor */
    confidence: ConfidenceLevel;
}
/** Authenticity & fraud sub-result for a single step */
export interface VerificationResult {
    /** Whether the document / selfie is considered authentic */
    is_authentic: boolean;
    /** Confidence of the authenticity decision (0–1) */
    confidence: number;
    /** List of checks that passed */
    checks_passed: string[];
    /** Detected fraud indicators */
    fraud_indicators: string[];
    /** Non-blocking warnings */
    warnings: string[];
    /** Blocking issues */
    issues: string[];
}
/** Result for a single verification step */
export interface StepResult {
    /** Zero-based index of this step within the submitted steps array */
    step_index: number;
    /** Type of this step */
    step_type: Step;
    /** Whether this step succeeded overall */
    success: boolean;
    /** Server-side processing time for this step in milliseconds */
    processing_time_ms: number;
    /** Liveness detection result */
    liveness: LivenessResult;
    /** Document / selfie authenticity result */
    verification: VerificationResult;
    /** Localised messages intended for display to the end-user */
    user_messages: string[];
    /** Base-64-encoded aligned/deskewed document image (document steps only) */
    aligned_document?: string;
    /** Extracted text fields (document steps only) */
    extraction?: ExtractionField[];
    /** Extracted photos from the document (document steps only) */
    extracted_photos?: ExtractedPhoto[];
    /** Base-64-encoded captured selfie image (selfie step only) */
    captured_image?: string;
}
/** Cross-step face-match result */
export interface FaceVerification {
    /** Whether the selfie face matches the document face */
    is_match: boolean;
    /** Cosine similarity score (0–100) */
    similarity_score: number;
}
/** Top-level response from POST /api/v1/kyc/verify */
export interface KycResponse {
    /** Whether the API call itself succeeded (HTTP-level success) */
    success: boolean;
    /** Unique session identifier for this verification run */
    session_id: string;
    /** Aggregated pass / reject decision across all steps */
    overall_status: OverallStatus;
    /** Aggregated confidence score (0–1) */
    overall_confidence: number;
    /** Total server-side processing time in milliseconds */
    processing_time_ms: number;
    /** Face-match result (present when require_face_match was true) */
    face_verification?: FaceVerification;
    /** Per-step results in submission order */
    steps: StepResult[];
}
/**
 * Options for KyvShield.verify().
 *
 * @example
 * ```ts
 * const options: VerifyOptions = {
 *   steps: ['selfie', 'recto', 'verso'],
 *   target: 'SN-CIN',
 *   language: 'fr',
 *   challengeMode: 'standard',
 *   requireFaceMatch: true,
 *   images: {
 *     selfie_center_face:     '/path/to/selfie.jpg',
 *     recto_center_document:  '/path/to/recto.jpg',
 *     verso_center_document:  '/path/to/verso.jpg',
 *   },
 * };
 * ```
 */
export interface VerifyOptions {
    /**
     * Ordered list of steps to execute.
     * Example: `['selfie', 'recto', 'verso']`
     */
    steps: Step[];
    /**
     * Document type to verify against.
     * Example: `'SN-CIN'`
     */
    target: DocumentTarget;
    /**
     * Language for user-facing messages in the response.
     * @default 'fr'
     */
    language?: Language;
    /**
     * Global fallback challenge mode applied to all steps unless overridden.
     * @default 'standard'
     */
    challengeMode?: ChallengeMode;
    /** Per-step challenge mode override for the selfie step */
    selfieChallengeMode?: ChallengeMode;
    /** Per-step challenge mode override for the recto step */
    rectoChallengeMode?: ChallengeMode;
    /** Per-step challenge mode override for the verso step */
    versoChallengeMode?: ChallengeMode;
    /**
     * Whether to perform a cross-step face match between selfie and document photo.
     * @default false
     */
    requireFaceMatch?: boolean;
    /**
     * Optional caller-provided identifier for correlating sessions in your system.
     */
    kycIdentifier?: string;
    /**
     * Map of image files to submit.
     * Keys follow the pattern `{step}_{challenge}`, e.g.:
     * - `'selfie_center_face'`
     * - `'recto_center_document'`
     * - `'recto_tilt_left'`
     *
     * Values are absolute or relative file-system paths to JPEG/PNG images.
     */
    images: Record<string, string>;
}
/** Structured error thrown by the SDK */
export interface KyvShieldErrorDetails {
    /** HTTP status code, if the error originated from an HTTP response */
    statusCode?: number;
    /** Raw response body, if available */
    body?: unknown;
}
//# sourceMappingURL=types.d.ts.map