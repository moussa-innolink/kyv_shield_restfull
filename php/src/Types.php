<?php

declare(strict_types=1);

namespace KyvShield;

// =============================================================================
// VERIFY OPTIONS
// =============================================================================

/**
 * Options for POST /api/v1/kyc/verify
 */
final class VerifyOptions
{
    /**
     * @param  string[]  $steps              e.g. ['recto', 'verso'] or ['selfie', 'recto', 'verso']
     * @param  string    $target             SN-CIN | SN-PASSPORT | SN-DRIVER-LICENCE
     * @param  string    $language           fr | en | wo
     * @param  string    $challengeMode      minimal | standard | strict  (global fallback)
     * @param  array<string,string>  $stepChallengeModes   per-step override keyed by step name
     * @param  bool      $requireFaceMatch   whether to compare selfie vs document photo
     * @param  string|null $kycIdentifier    optional caller-side identifier
     * @param  array<string,string>  $images  field-name → absolute file path
     *                                         e.g. ['recto_center_document' => '/path/recto.jpg']
     */
    public function __construct(
        public readonly array $steps,
        public readonly string $target,
        public readonly string $language,
        public readonly string $challengeMode,
        public readonly array $stepChallengeModes = [],
        public readonly bool $requireFaceMatch = false,
        public readonly ?string $kycIdentifier = null,
        public readonly array $images = [],
    ) {
    }
}

// =============================================================================
// RESPONSE TYPES
// =============================================================================

/**
 * Top-level response from POST /api/v1/kyc/verify
 */
final class KycResponse
{
    /**
     * @param  StepResult[]  $steps
     */
    public function __construct(
        public readonly bool $success,
        public readonly string $sessionId,
        public readonly string $overallStatus,
        public readonly float $overallConfidence,
        public readonly int $processingTimeMs,
        public readonly array $steps,
        public readonly ?FaceVerification $faceVerification = null,
    ) {
    }

    /**
     * @param  array<string,mixed>  $data
     */
    public static function fromArray(array $data): self
    {
        $steps = [];
        foreach (($data['steps'] ?? []) as $stepData) {
            $steps[] = StepResult::fromArray($stepData);
        }

        $faceVerification = null;
        if (isset($data['face_verification'])) {
            $faceVerification = FaceVerification::fromArray($data['face_verification']);
        }

        return new self(
            success: (bool) ($data['success'] ?? false),
            sessionId: (string) ($data['session_id'] ?? ''),
            overallStatus: (string) ($data['overall_status'] ?? 'reject'),
            overallConfidence: (float) ($data['overall_confidence'] ?? 0.0),
            processingTimeMs: (int) ($data['processing_time_ms'] ?? 0),
            steps: $steps,
            faceVerification: $faceVerification,
        );
    }
}

// =============================================================================
// STEP RESULT
// =============================================================================

/**
 * Result for a single verification step (selfie, recto, or verso)
 */
final class StepResult
{
    /**
     * @param  string[]        $userMessages
     * @param  ExtractionField[]  $extraction
     * @param  ExtractedPhoto[]   $extractedPhotos
     */
    public function __construct(
        public readonly int $stepIndex,
        public readonly string $stepType,
        public readonly bool $success,
        public readonly int $processingTimeMs,
        public readonly ?LivenessResult $liveness,
        public readonly ?VerificationResult $verification,
        public readonly array $userMessages,
        public readonly ?string $alignedDocument,
        public readonly array $extraction,
        public readonly array $extractedPhotos,
        public readonly ?string $capturedImage,
    ) {
    }

    /**
     * @param  array<string,mixed>  $data
     */
    public static function fromArray(array $data): self
    {
        $liveness = null;
        if (isset($data['liveness'])) {
            $liveness = LivenessResult::fromArray($data['liveness']);
        }

        $verification = null;
        if (isset($data['verification'])) {
            $verification = VerificationResult::fromArray($data['verification']);
        }

        $extraction = [];
        foreach (($data['extraction'] ?? []) as $field) {
            $extraction[] = ExtractionField::fromArray($field);
        }

        $extractedPhotos = [];
        foreach (($data['extracted_photos'] ?? []) as $photo) {
            $extractedPhotos[] = ExtractedPhoto::fromArray($photo);
        }

        return new self(
            stepIndex: (int) ($data['step_index'] ?? 0),
            stepType: (string) ($data['step_type'] ?? ''),
            success: (bool) ($data['success'] ?? false),
            processingTimeMs: (int) ($data['processing_time_ms'] ?? 0),
            liveness: $liveness,
            verification: $verification,
            userMessages: array_map('strval', $data['user_messages'] ?? []),
            alignedDocument: isset($data['aligned_document']) ? (string) $data['aligned_document'] : null,
            extraction: $extraction,
            extractedPhotos: $extractedPhotos,
            capturedImage: isset($data['captured_image']) ? (string) $data['captured_image'] : null,
        );
    }
}

// =============================================================================
// LIVENESS RESULT
// =============================================================================

/**
 * Liveness detection result for a step
 */
final class LivenessResult
{
    public function __construct(
        public readonly bool $isLive,
        public readonly float $score,
        public readonly string $confidence,
    ) {
    }

    /**
     * @param  array<string,mixed>  $data
     */
    public static function fromArray(array $data): self
    {
        return new self(
            isLive: (bool) ($data['is_live'] ?? false),
            score: (float) ($data['score'] ?? 0.0),
            confidence: (string) ($data['confidence'] ?? 'low'),
        );
    }
}

// =============================================================================
// VERIFICATION RESULT
// =============================================================================

/**
 * Authenticity / fraud-check result for a step
 */
final class VerificationResult
{
    /**
     * @param  string[]  $checksPassed
     * @param  string[]  $fraudIndicators
     * @param  string[]  $warnings
     * @param  string[]  $issues
     */
    public function __construct(
        public readonly bool $isAuthentic,
        public readonly float $confidence,
        public readonly array $checksPassed,
        public readonly array $fraudIndicators,
        public readonly array $warnings,
        public readonly array $issues,
    ) {
    }

    /**
     * @param  array<string,mixed>  $data
     */
    public static function fromArray(array $data): self
    {
        return new self(
            isAuthentic: (bool) ($data['is_authentic'] ?? false),
            confidence: (float) ($data['confidence'] ?? 0.0),
            checksPassed: array_map('strval', $data['checks_passed'] ?? []),
            fraudIndicators: array_map('strval', $data['fraud_indicators'] ?? []),
            warnings: array_map('strval', $data['warnings'] ?? []),
            issues: array_map('strval', $data['issues'] ?? []),
        );
    }
}

// =============================================================================
// EXTRACTION FIELD
// =============================================================================

/**
 * A single OCR-extracted field from the document
 */
final class ExtractionField
{
    public function __construct(
        public readonly string $key,
        public readonly string $documentKey,
        public readonly string $label,
        public readonly string $value,
        public readonly int $displayPriority,
        public readonly ?string $icon = null,
    ) {
    }

    /**
     * @param  array<string,mixed>  $data
     */
    public static function fromArray(array $data): self
    {
        return new self(
            key: (string) ($data['key'] ?? ''),
            documentKey: (string) ($data['document_key'] ?? ''),
            label: (string) ($data['label'] ?? ''),
            value: (string) ($data['value'] ?? ''),
            displayPriority: (int) ($data['display_priority'] ?? 0),
            icon: isset($data['icon']) && $data['icon'] !== '' ? (string) $data['icon'] : null,
        );
    }
}

// =============================================================================
// EXTRACTED PHOTO
// =============================================================================

/**
 * A face photo extracted from a document image
 */
final class ExtractedPhoto
{
    /**
     * @param  float[]  $bbox  Bounding box [x1, y1, x2, y2]
     */
    public function __construct(
        public readonly string $image,
        public readonly float $confidence,
        public readonly array $bbox,
        public readonly float $area,
        public readonly int $width,
        public readonly int $height,
    ) {
    }

    /**
     * @param  array<string,mixed>  $data
     */
    public static function fromArray(array $data): self
    {
        return new self(
            image: (string) ($data['image'] ?? ''),
            confidence: (float) ($data['confidence'] ?? 0.0),
            bbox: array_map('floatval', $data['bbox'] ?? []),
            area: (float) ($data['area'] ?? 0.0),
            width: (int) ($data['width'] ?? 0),
            height: (int) ($data['height'] ?? 0),
        );
    }
}

// =============================================================================
// FACE VERIFICATION
// =============================================================================

/**
 * Result of comparing selfie vs document photo
 */
final class FaceVerification
{
    public function __construct(
        public readonly bool $isMatch,
        public readonly float $similarityScore,
    ) {
    }

    /**
     * @param  array<string,mixed>  $data
     */
    public static function fromArray(array $data): self
    {
        return new self(
            isMatch: (bool) ($data['is_match'] ?? false),
            similarityScore: (float) ($data['similarity_score'] ?? 0.0),
        );
    }
}

// =============================================================================
// CHALLENGES RESPONSE
// =============================================================================

/**
 * Response from GET /api/v1/challenges
 *
 * Structure:
 * {
 *   "success": true,
 *   "challenges": {
 *     "document": {
 *       "minimal":  ["center_document"],
 *       "standard": ["center_document", "tilt_left"],
 *       "strict":   ["center_document", "tilt_left", "tilt_right", "show_reflection"]
 *     },
 *     "selfie": {
 *       "minimal":  ["center_face"],
 *       "standard": ["center_face", "close_eyes"],
 *       "strict":   ["center_face", "close_eyes", "turn_left", "turn_right"]
 *     }
 *   }
 * }
 */
final class ChallengesResponse
{
    /**
     * @param  array<string, array<string, string[]>>  $challenges  category → mode → challenge list
     */
    public function __construct(
        public readonly bool $success,
        public readonly array $challenges,
    ) {
    }

    /**
     * @param  array<string,mixed>  $data
     */
    public static function fromArray(array $data): self
    {
        return new self(
            success: (bool) ($data['success'] ?? false),
            challenges: $data['challenges'] ?? [],
        );
    }

    /**
     * Get challenges for a given category and mode.
     *
     * @param  string  $category  'document' | 'selfie'
     * @param  string  $mode      'minimal' | 'standard' | 'strict'
     * @return string[]
     */
    public function getChallenges(string $category, string $mode): array
    {
        return $this->challenges[$category][$mode] ?? [];
    }
}

// =============================================================================
// EXCEPTION
// =============================================================================

/**
 * Base exception for all KyvShield SDK errors
 */
final class KyvShieldException extends \RuntimeException
{
    public function __construct(
        string $message,
        public readonly int $httpStatus = 0,
        public readonly ?string $errorCode = null,
        ?\Throwable $previous = null,
    ) {
        parent::__construct($message, $httpStatus, $previous);
    }
}
