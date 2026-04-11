package sn.innolink.kyvshield

/**
 * KyvShield Kotlin SDK — Data Models
 *
 * All data classes represent the exact JSON contract of the KyvShield REST API.
 * Field names follow the API's snake_case JSON keys; idiomatic Kotlin names are
 * provided via explicit parameter names where helpful.
 */

// ─── SDK Constants ────────────────────────────────────────────────────────────

/** Default maximum image width in pixels before resize. */
const val DEFAULT_IMAGE_MAX_WIDTH: Int = 1280

/** Default JPEG compression quality (0–100). */
const val DEFAULT_IMAGE_QUALITY: Int = 90

/** Maximum number of images compressed in parallel. */
const val DEFAULT_MAX_CONCURRENT_COMPRESS: Int = 20

// ─── Enums ───────────────────────────────────────────────────────────────────

/** Challenge intensity mode */
enum class ChallengeMode(val value: String) {
    MINIMAL("minimal"),
    STANDARD("standard"),
    STRICT("strict");

    override fun toString(): String = value

    companion object {
        /** Returns the matching [ChallengeMode] or [STANDARD] for unrecognised values. */
        fun fromString(value: String): ChallengeMode =
            entries.firstOrNull { it.value == value } ?: STANDARD
    }
}

/** Verification step type */
enum class Step(val value: String) {
    SELFIE("selfie"),
    RECTO("recto"),
    VERSO("verso");

    override fun toString(): String = value

    companion object {
        /** Returns the matching [Step] or [SELFIE] for unrecognised values. */
        fun fromString(value: String): Step =
            entries.firstOrNull { it.value == value } ?: SELFIE
    }
}

/** Supported document target types */
enum class DocumentTarget(val value: String) {
    SN_CIN("SN-CIN"),
    SN_PASSPORT("SN-PASSPORT"),
    SN_DRIVER_LICENCE("SN-DRIVER-LICENCE");

    override fun toString(): String = value
}

/** Supported response languages */
enum class Language(val value: String) {
    FRENCH("fr"),
    ENGLISH("en"),
    WOLOF("wo");

    override fun toString(): String = value
}

/** Overall verification outcome */
enum class OverallStatus(val value: String) {
    PASS("pass"),
    REJECT("reject");

    override fun toString(): String = value

    companion object {
        /** Returns the matching [OverallStatus] or [REJECT] for unrecognised values. */
        fun fromString(value: String): OverallStatus =
            entries.firstOrNull { it.value == value } ?: REJECT
    }
}

/** Qualitative confidence descriptor returned inside LivenessResult */
enum class ConfidenceLevel(val value: String) {
    HIGH("HIGH"),
    MEDIUM("MEDIUM"),
    LOW("LOW");

    override fun toString(): String = value

    companion object {
        fun fromString(value: String): ConfidenceLevel =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown confidence level: '$value'")
    }
}

// ─── Challenges ──────────────────────────────────────────────────────────────

/**
 * Available challenges grouped by intensity for a single asset type
 * (document or selfie).
 */
data class ChallengeModeMap(
    /** Challenges required at minimal intensity */
    val minimal: List<String>,
    /** Challenges required at standard intensity */
    val standard: List<String>,
    /** Challenges required at strict intensity */
    val strict: List<String>,
)

/**
 * Challenges block inside [ChallengesResponse].
 */
data class ChallengesBlock(
    /** Challenges applicable to document steps (recto / verso) */
    val document: ChallengeModeMap,
    /** Challenges applicable to selfie steps */
    val selfie: ChallengeModeMap,
)

/**
 * Full response from `GET /api/v1/challenges`.
 */
data class ChallengesResponse(
    /** Whether the API call succeeded */
    val success: Boolean,
    /** The challenges configuration */
    val challenges: ChallengesBlock,
)

// ─── Extraction ──────────────────────────────────────────────────────────────

/**
 * A single extracted text field from a document (OCR output).
 */
data class ExtractionField(
    /** Machine-readable field key, e.g. `"first_name"` */
    val key: String,
    /** Key as used in the document specification */
    val documentKey: String,
    /** Human-readable label in the session language */
    val label: String,
    /** Extracted value */
    val value: String,
    /** Display ordering hint — lower value means higher priority */
    val displayPriority: Int,
    /** Optional icon identifier for UI rendering (may be null) */
    val icon: String?,
)

/**
 * A photo extracted from a document image (face crop from CIN / passport).
 */
data class ExtractedPhoto(
    /** Base-64-encoded JPEG image data */
    val image: String,
    /** Model confidence for this extraction (0–1) */
    val confidence: Double,
    /** Bounding box `[x, y, width, height]` in pixels */
    val bbox: List<Double>,
    /** Area of the bounding box in pixels² */
    val area: Double,
    /** Width of the extracted region in pixels */
    val width: Int,
    /** Height of the extracted region in pixels */
    val height: Int,
)

// ─── Step Result ─────────────────────────────────────────────────────────────

/**
 * Liveness sub-result for a single verification step.
 */
data class LivenessResult(
    /** Whether the subject / document is considered live / physical */
    val isLive: Boolean,
    /** Liveness score (0–1) */
    val score: Double,
    /** Qualitative confidence descriptor */
    val confidence: ConfidenceLevel,
)

/**
 * Authenticity and fraud sub-result for a single verification step.
 */
data class VerificationResult(
    /** Whether the document / selfie is considered authentic */
    val isAuthentic: Boolean,
    /** Confidence of the authenticity decision (0–1) */
    val confidence: Double,
    /** List of checks that passed */
    val checksPassed: List<String>,
    /** Detected fraud indicators (non-empty only when fraud suspected) */
    val fraudIndicators: List<String>,
    /** Non-blocking warnings */
    val warnings: List<String>,
    /** Blocking issues that caused a REJECT */
    val issues: List<String>,
)

/**
 * Result for a single verification step.
 *
 * Optional fields are present only for relevant step types:
 * - [alignedDocument], [extraction], [extractedPhotos] — document steps (recto / verso)
 * - [capturedImage] — selfie step only
 */
data class StepResult(
    /** Zero-based index of this step within the submitted steps array */
    val stepIndex: Int,
    /** Type of this step */
    val stepType: Step,
    /** Whether this step succeeded overall */
    val success: Boolean,
    /** Server-side processing time for this step in milliseconds */
    val processingTimeMs: Int,
    /** Liveness detection sub-result */
    val liveness: LivenessResult,
    /** Authenticity / fraud sub-result */
    val verification: VerificationResult,
    /** Localised messages intended for display to the end-user */
    val userMessages: List<String>,
    // ── Document steps only ──────────────────────────────────────────────────
    /** Base-64-encoded aligned/deskewed document image (document steps only) */
    val alignedDocument: String?,
    /** Extracted text fields (document steps only) */
    val extraction: List<ExtractionField>?,
    /** Extracted photos from the document, e.g. the face photo on a CIN */
    val extractedPhotos: List<ExtractedPhoto>?,
    // ── Selfie step only ─────────────────────────────────────────────────────
    /** Base-64-encoded captured selfie image (selfie step only) */
    val capturedImage: String?,
)

// ─── Face Verification ───────────────────────────────────────────────────────

/**
 * Cross-step face-match result.
 * Present in [KycResponse] only when `requireFaceMatch` was `true`.
 */
data class FaceVerification(
    /** Whether the selfie face matches the document face */
    val isMatch: Boolean,
    /** Cosine similarity score (0–100) */
    val similarityScore: Double,
)

// ─── AML Screening ───────────────────────────────────────────────────────────

/**
 * A single entity match from AML/sanctions screening.
 */
data class AMLMatch(
    /** Entity identifier from the sanctions list */
    val entityId: String,
    /** Matched entity name */
    val name: String,
    /** Match confidence score (0–1) */
    val score: Double,
    /** Datasets where the match was found */
    val datasets: List<String>,
    /** Match topics (e.g., "sanction", "pep") */
    val topics: List<String>,
)

/**
 * AML/sanctions screening result.
 * Present in [KycResponse] only when AML screening was configured.
 */
data class AMLScreening(
    /** Whether AML screening was performed */
    val performed: Boolean,
    /** Screening status: "clear", "match", "error", or "disabled" */
    val status: String,
    /** Risk level: "low", "medium", "high", or "critical" */
    val riskLevel: String,
    /** Total number of matches found */
    val totalMatches: Int,
    /** List of matched entities */
    val matches: List<AMLMatch>,
    /** ISO 8601 timestamp when screening was performed */
    val screenedAt: String?,
    /** Processing duration in milliseconds */
    val durationMs: Int,
) {
    /** Whether the screening result is clear (no matches) */
    val isClear: Boolean get() = status == "clear"

    /** Whether matches were found */
    val hasMatches: Boolean get() = status == "match" && totalMatches > 0
}

// ─── KYC Response ────────────────────────────────────────────────────────────

/**
 * Top-level response from `POST /api/v1/kyc/verify`.
 */
data class KycResponse(
    /** Whether the API call itself succeeded */
    val success: Boolean,
    /** Unique session identifier for this verification run */
    val sessionId: String,
    /** Aggregated pass / reject decision across all steps */
    val overallStatus: OverallStatus,
    /** Aggregated confidence score (0–1) */
    val overallConfidence: Double,
    /** Total server-side processing time in milliseconds */
    val processingTimeMs: Int,
    /** Face-match result (present when requireFaceMatch was true) */
    val faceVerification: FaceVerification?,
    /** AML/sanctions screening result (optional) */
    val amlScreening: AMLScreening?,
    /** Per-step results in submission order */
    val steps: List<StepResult>,
)

// ─── Batch Result ─────────────────────────────────────────────────────────────

/**
 * Holds the outcome of a single entry in a [KyvShield.verifyBatch] call.
 */
data class BatchResult(
    /** Whether this individual verification succeeded */
    val success: Boolean,
    /** The KYC response when [success] is `true` */
    val result: KycResponse?,
    /** The error message when [success] is `false` */
    val error: String?,
)

// ─── Verify Options ──────────────────────────────────────────────────────────

/**
 * Options for [KyvShield.verify].
 *
 * ```kotlin
 * val options = VerifyOptions(
 *     steps = listOf(Step.SELFIE, Step.RECTO, Step.VERSO),
 *     target = "SN-CIN",
 *     language = Language.FRENCH,
 *     challengeMode = ChallengeMode.STANDARD,
 *     requireFaceMatch = true,
 *     images = mapOf(
 *         "selfie_center_face"    to "/path/to/selfie.jpg",
 *         "recto_center_document" to "/path/to/recto.jpg",
 *         "verso_center_document" to "/path/to/verso.jpg",
 *     ),
 * )
 * ```
 */
data class VerifyOptions(
    /**
     * Ordered list of steps to execute.
     * Example: `listOf(Step.SELFIE, Step.RECTO, Step.VERSO)`
     */
    val steps: List<Step>,

    /**
     * Document type to verify against.
     * Can be a [DocumentTarget] enum value or any custom string.
     * Example: `"SN-CIN"`
     */
    val target: String,

    /**
     * Language for user-facing messages in the response.
     * Defaults to [Language.FRENCH].
     */
    val language: Language = Language.FRENCH,

    /**
     * Global fallback challenge mode applied to all steps unless overridden.
     * Defaults to [ChallengeMode.STANDARD].
     */
    val challengeMode: ChallengeMode = ChallengeMode.STANDARD,

    /**
     * Per-step challenge mode overrides keyed by step name.
     * Keys are `"selfie"`, `"recto"`, or `"verso"`.
     * Example: `mapOf("selfie" to ChallengeMode.MINIMAL, "recto" to ChallengeMode.STRICT)`
     */
    val stepChallengeModes: Map<String, ChallengeMode>? = null,

    /**
     * Whether to perform a cross-step face match between selfie and document photo.
     * Defaults to `false`.
     */
    val requireFaceMatch: Boolean = false,

    /**
     * Whether to perform AML (Anti-Money Laundering) sanctions screening.
     * Defaults to `false`.
     */
    val requireAml: Boolean = false,

    /**
     * Optional caller-provided identifier for correlating sessions in your system.
     */
    val kycIdentifier: String? = null,

    /**
     * Map of images to submit.
     * Keys follow the pattern `{step}_{challenge}`, e.g.:
     * - `"selfie_center_face"`
     * - `"recto_center_document"`
     * - `"recto_tilt_left"`
     *
     * Values can be one of four types:
     * - **[ByteArray]** — raw image bytes, used directly
     * - **`http://…` / `https://…`** — URL, the SDK downloads automatically
     * - **`data:image/…;base64,…`** — data URI, the SDK strips the prefix and decodes
     * - **base64 string** — long string without path separators, decoded as base64
     * - **file path** — any other string is treated as a local filesystem path
     */
    val images: Map<String, Any>,
)
