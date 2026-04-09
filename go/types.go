// Package kyvshield provides a fully typed Go client for the KyvShield REST KYC API.
package kyvshield

// ─── SDK Constants ────────────────────────────────────────────────────────────

// DefaultImageMaxWidth is the default maximum image width in pixels before resize.
const DefaultImageMaxWidth = 1280

// DefaultImageQuality is the default JPEG compression quality (0–100).
const DefaultImageQuality = 90

// DefaultMaxConcurrentCompress is the maximum number of images compressed in parallel.
const DefaultMaxConcurrentCompress = 20

// ─── Enums / String Constants ─────────────────────────────────────────────────

// ChallengeMode controls the intensity of the liveness challenge.
type ChallengeMode string

const (
	ChallengeModeMinimal  ChallengeMode = "minimal"
	ChallengeModeStandard ChallengeMode = "standard"
	ChallengeModeStrict   ChallengeMode = "strict"
)

// Step represents a single verification step type.
type Step string

const (
	StepSelfie Step = "selfie"
	StepRecto  Step = "recto"
	StepVerso  Step = "verso"
)

// DocumentTarget identifies the document type being verified.
type DocumentTarget string

const (
	DocumentTargetCIN            DocumentTarget = "SN-CIN"
	DocumentTargetPassport       DocumentTarget = "SN-PASSPORT"
	DocumentTargetDriverLicence  DocumentTarget = "SN-DRIVER-LICENCE"
)

// Language selects the locale for user-facing messages in the API response.
type Language string

const (
	LanguageFrench  Language = "fr"
	LanguageEnglish Language = "en"
	LanguageWolof   Language = "wo"
)

// OverallStatus is the aggregate pass/reject decision.
type OverallStatus string

const (
	OverallStatusPass   OverallStatus = "pass"
	OverallStatusReject OverallStatus = "reject"
)

// ─── Verify Options ───────────────────────────────────────────────────────────

// VerifyOptions holds all parameters for the POST /api/v1/kyc/verify call.
//
// Example:
//
//	opts := &kyvshield.VerifyOptions{
//	    Steps:            []string{"recto", "verso"},
//	    Target:           string(kyvshield.DocumentTargetCIN),
//	    Language:         string(kyvshield.LanguageFrench),
//	    ChallengeMode:    string(kyvshield.ChallengeModeStandard),
//	    RequireFaceMatch: false,
//	    Images: map[string]string{
//	        "recto_center_document": "/path/to/recto.jpg",
//	        "verso_center_document": "/path/to/verso.jpg",
//	    },
//	}
type VerifyOptions struct {
	// Steps is an ordered list of step types to execute, e.g. ["selfie","recto","verso"].
	Steps []string

	// Target is the document type, e.g. "SN-CIN".
	Target string

	// Language is the locale for user-facing messages. Defaults to "fr".
	Language string

	// ChallengeMode is the global fallback challenge intensity. Defaults to "standard".
	ChallengeMode string

	// StepChallengeModes overrides the challenge mode per step.
	// Valid keys: "selfie_challenge_mode", "recto_challenge_mode", "verso_challenge_mode".
	//
	// Example:
	//   StepChallengeModes: map[string]string{
	//       "recto_challenge_mode": "strict",
	//   }
	StepChallengeModes map[string]string

	// RequireFaceMatch enables cross-step face matching between selfie and document photo.
	RequireFaceMatch bool

	// KycIdentifier is an optional caller-provided identifier for correlating sessions.
	KycIdentifier string

	// Images maps multipart field names to image values.
	// Keys follow the pattern "{step}_{challenge}", e.g.:
	//   "selfie_center_face"    → /path/to/selfie.jpg
	//   "recto_center_document" → /path/to/recto.jpg
	//   "recto_tilt_left"       → /path/to/recto_tilt.jpg
	//
	// Each string value is resolved in order:
	//   - "http://" or "https://" prefix  → URL, downloaded automatically
	//   - "data:image/" prefix            → data URI, base64 decoded
	//   - long string without path sep.   → treated as raw base64, decoded
	//   - otherwise                       → filesystem path, read from disk
	Images map[string]string

	// ImageBytes maps multipart field names to raw image bytes.
	// Useful when the caller already has the image data in memory.
	// Entries in ImageBytes are processed after Images (same field names
	// in ImageBytes take priority).
	ImageBytes map[string][]byte
}

// ─── Challenges Response ──────────────────────────────────────────────────────

// ChallengeModeMap lists the challenge names available at each intensity.
type ChallengeModeMap struct {
	Minimal  []string `json:"minimal"`
	Standard []string `json:"standard"`
	Strict   []string `json:"strict"`
}

// ChallengesPayload groups document and selfie challenge maps.
type ChallengesPayload struct {
	Document ChallengeModeMap `json:"document"`
	Selfie   ChallengeModeMap `json:"selfie"`
}

// ChallengesResponse is returned by GET /api/v1/challenges.
type ChallengesResponse struct {
	// Success indicates whether the API call itself succeeded.
	Success    bool              `json:"success"`
	Challenges ChallengesPayload `json:"challenges"`
}

// ─── Extraction ───────────────────────────────────────────────────────────────

// ExtractionField represents a single text field extracted from a document.
type ExtractionField struct {
	// Key is the machine-readable field identifier.
	Key string `json:"key"`

	// DocumentKey is the field name as it appears in the document specification.
	DocumentKey string `json:"document_key"`

	// Label is the human-readable field name.
	Label string `json:"label"`

	// Value is the extracted text value.
	Value string `json:"value"`

	// DisplayPriority controls sort order for UI rendering (lower = higher priority).
	DisplayPriority int `json:"display_priority"`

	// Icon is an optional icon identifier for UI frameworks.
	Icon string `json:"icon,omitempty"`
}

// ExtractedPhoto is a photo region detected and cropped from a document image.
type ExtractedPhoto struct {
	// Image is the base-64-encoded JPEG of the extracted region.
	Image string `json:"image"`

	// Confidence is the model's confidence for this extraction (0–1).
	Confidence float64 `json:"confidence"`

	// Bbox is the bounding box [x, y, width, height] in pixels.
	Bbox []float64 `json:"bbox"`

	// Area is the bounding-box area in pixels².
	Area float64 `json:"area"`

	// Width is the width of the extracted region in pixels.
	Width int `json:"width"`

	// Height is the height of the extracted region in pixels.
	Height int `json:"height"`
}

// ─── Step Sub-results ─────────────────────────────────────────────────────────

// LivenessResult contains the liveness detection outcome for a single step.
type LivenessResult struct {
	// IsLive indicates whether the subject or document is considered live/physical.
	IsLive bool `json:"is_live"`

	// Score is the raw liveness score in the range [0, 1].
	Score float64 `json:"score"`

	// Confidence is a qualitative descriptor: "HIGH", "MEDIUM", or "LOW".
	Confidence string `json:"confidence"`
}

// VerificationResult contains the document/selfie authenticity outcome.
type VerificationResult struct {
	// IsAuthentic indicates whether the document or selfie is genuine.
	IsAuthentic bool `json:"is_authentic"`

	// Confidence is the authenticity decision confidence in the range [0, 1].
	Confidence float64 `json:"confidence"`

	// ChecksPassed lists the individual checks that passed.
	ChecksPassed []string `json:"checks_passed"`

	// FraudIndicators lists detected fraud signals.
	FraudIndicators []string `json:"fraud_indicators"`

	// Warnings lists non-blocking concerns.
	Warnings []string `json:"warnings"`

	// Issues lists blocking problems that caused rejection.
	Issues []string `json:"issues"`
}

// ─── Step Result ──────────────────────────────────────────────────────────────

// StepResult holds the full outcome for a single verification step.
type StepResult struct {
	// StepIndex is the zero-based position of this step within the submitted steps array.
	StepIndex int `json:"step_index"`

	// StepType is the type of this step: "selfie", "recto", or "verso".
	StepType string `json:"step_type"`

	// Success indicates whether this step passed overall.
	Success bool `json:"success"`

	// ProcessingTimeMs is the server-side processing duration for this step.
	ProcessingTimeMs int `json:"processing_time_ms"`

	// Liveness is the liveness detection sub-result.
	Liveness *LivenessResult `json:"liveness,omitempty"`

	// Verification is the authenticity/fraud sub-result.
	Verification *VerificationResult `json:"verification,omitempty"`

	// UserMessages contains localised messages intended for display to the end-user.
	UserMessages []string `json:"user_messages"`

	// AlignedDocument is the base-64-encoded aligned/deskewed document image.
	// Present only for document steps (recto / verso).
	AlignedDocument string `json:"aligned_document,omitempty"`

	// Extraction contains all text fields extracted from the document.
	// Present only for document steps.
	Extraction []ExtractionField `json:"extraction,omitempty"`

	// ExtractedPhotos contains photos cropped from the document.
	// Present only for document steps.
	ExtractedPhotos []ExtractedPhoto `json:"extracted_photos,omitempty"`

	// CapturedImage is the base-64-encoded selfie captured during verification.
	// Present only for the selfie step.
	CapturedImage string `json:"captured_image,omitempty"`
}

// ─── Face Verification ────────────────────────────────────────────────────────

// FaceVerification holds the cross-step face-match result.
type FaceVerification struct {
	// IsMatch indicates whether the selfie face matches the document face.
	IsMatch bool `json:"is_match"`

	// SimilarityScore is the cosine similarity expressed as a value in [0, 100].
	SimilarityScore float64 `json:"similarity_score"`
}

// ─── KYC Response ─────────────────────────────────────────────────────────────

// KycResponse is the top-level response from POST /api/v1/kyc/verify.
type KycResponse struct {
	// Success indicates whether the API call itself succeeded at the HTTP level.
	Success bool `json:"success"`

	// SessionID is the unique identifier for this verification session.
	SessionID string `json:"session_id"`

	// OverallStatus is the aggregated pass/reject decision across all steps.
	OverallStatus string `json:"overall_status"`

	// OverallConfidence is the aggregated confidence score in the range [0, 1].
	OverallConfidence float64 `json:"overall_confidence"`

	// ProcessingTimeMs is the total server-side processing time in milliseconds.
	ProcessingTimeMs int `json:"processing_time_ms"`

	// FaceVerification contains the face-match result when RequireFaceMatch was true.
	FaceVerification *FaceVerification `json:"face_verification,omitempty"`

	// Steps contains the per-step results in submission order.
	Steps []StepResult `json:"steps"`
}

// ─── Batch Result ─────────────────────────────────────────────────────────────

// BatchResult holds the outcome of a single entry in a VerifyBatch call.
type BatchResult struct {
	// Success indicates whether this individual verification succeeded.
	Success bool

	// Result is the KYC response when Success is true.
	Result *KycResponse

	// Error holds the error when Success is false.
	Error error
}

// ─── Error Types ──────────────────────────────────────────────────────────────

// APIError represents a structured error returned by the KyvShield API.
type APIError struct {
	// StatusCode is the HTTP status code.
	StatusCode int

	// Message is a human-readable error description.
	Message string

	// Body is the raw response body, if available.
	Body []byte
}

func (e *APIError) Error() string {
	if e.Message != "" {
		return e.Message
	}
	return "kyvshield: API error (HTTP " + itoa(e.StatusCode) + ")"
}

// itoa is a minimal int-to-string helper to avoid importing strconv in types.go.
func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := false
	if n < 0 {
		neg = true
		n = -n
	}
	buf := [20]byte{}
	pos := len(buf)
	for n > 0 {
		pos--
		buf[pos] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		pos--
		buf[pos] = '-'
	}
	return string(buf[pos:])
}
