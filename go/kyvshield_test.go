package kyvshield_test

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	kyvshield "github.com/moussa-innolink/kyv_shield_restfull/go"
)

// ─── Test Fixtures ────────────────────────────────────────────────────────────

// envOrDefault returns the value of an environment variable or a default.
func envOrDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

// resolveBaseURL returns the API base URL based on KYVSHIELD_ENV / KYVSHIELD_BASE_URL.
func resolveBaseURL() string {
	if u := os.Getenv("KYVSHIELD_BASE_URL"); u != "" {
		return u
	}
	envMap := map[string]string{
		"local": "http://localhost:8080",
		"dev":   "https://kyvshield.innolink.sn",
		"prod":  "https://kyvshield-naruto.innolinkcloud.com",
	}
	env := envOrDefault("KYVSHIELD_ENV", "local")
	if u, ok := envMap[env]; ok {
		return u
	}
	return "http://localhost:8080"
}

// imageDir returns the directory containing test document images.
func imageDir() string {
	return envOrDefault("KYVSHIELD_IMAGE_DIR",
		"/Users/macbookpro/GolandProjects/cin_verification/api/document_id")
}

var (
	testRectoPath = imageDir() + "/SN-CIN/RECTO.jpg"
	testVersoPath = imageDir() + "/SN-CIN/VERSO.jpg"
)

// stubChallengesJSON is a canned response for the challenges endpoint.
const stubChallengesJSON = `{
  "challenges": {
    "document": {
      "minimal":  ["center_document"],
      "standard": ["center_document", "tilt_left", "tilt_right"],
      "strict":   ["center_document", "tilt_left", "tilt_right", "tilt_up", "tilt_down"]
    },
    "selfie": {
      "minimal":  ["center_face"],
      "standard": ["center_face", "turn_left", "turn_right"],
      "strict":   ["center_face", "turn_left", "turn_right", "blink", "smile"]
    }
  }
}`

// stubVerifyJSON is a canned KYC response used for unit tests.
const stubVerifyJSON = `{
  "success": true,
  "session_id": "sess_test_001",
  "overall_status": "pass",
  "overall_confidence": 0.97,
  "processing_time_ms": 1234,
  "face_verification": {
    "is_match": true,
    "similarity_score": 87.4
  },
  "steps": [
    {
      "step_index": 0,
      "step_type": "recto",
      "success": true,
      "processing_time_ms": 620,
      "liveness": {
        "is_live": true,
        "score": 0.95,
        "confidence": "HIGH"
      },
      "verification": {
        "is_authentic": true,
        "confidence": 0.96,
        "checks_passed": ["texture_analysis", "reflection_check"],
        "fraud_indicators": [],
        "warnings": [],
        "issues": []
      },
      "user_messages": ["Document recto vérifié avec succès."],
      "aligned_document": "base64encodedimage==",
      "extraction": [
        {
          "key": "last_name",
          "document_key": "NOM",
          "label": "Nom",
          "value": "DIALLO",
          "display_priority": 1,
          "icon": "person"
        }
      ],
      "extracted_photos": [
        {
          "image": "base64photo==",
          "confidence": 0.99,
          "bbox": [120.0, 80.0, 140.0, 180.0],
          "area": 25200.0,
          "width": 140,
          "height": 180
        }
      ]
    },
    {
      "step_index": 1,
      "step_type": "verso",
      "success": true,
      "processing_time_ms": 614,
      "liveness": {
        "is_live": true,
        "score": 0.93,
        "confidence": "HIGH"
      },
      "verification": {
        "is_authentic": true,
        "confidence": 0.94,
        "checks_passed": ["texture_analysis"],
        "fraud_indicators": [],
        "warnings": [],
        "issues": []
      },
      "user_messages": ["Document verso vérifié avec succès."],
      "extraction": []
    }
  ]
}`

// ─── Helper: compute HMAC-SHA256 ──────────────────────────────────────────────

// signHMAC returns the hex-encoded HMAC-SHA256 of payload using key.
// This mirrors the algorithm inside kyvshield.VerifyWebhookSignature so that
// tests can produce valid test signatures without depending on internal details.
func signHMAC(payload []byte, key string) string {
	mac := hmac.New(sha256.New, []byte(key))
	mac.Write(payload)
	return hex.EncodeToString(mac.Sum(nil))
}

// ─── Helper: assert APIError ──────────────────────────────────────────────────

func requireAPIError(t *testing.T, err error) *kyvshield.APIError {
	t.Helper()
	if err == nil {
		t.Fatal("expected an error, got nil")
	}
	apiErr, ok := err.(*kyvshield.APIError)
	if !ok {
		t.Fatalf("expected *kyvshield.APIError, got %T: %v", err, err)
	}
	return apiErr
}

// ─── GetChallenges ────────────────────────────────────────────────────────────

func TestGetChallenges_OK(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/challenges" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		if r.Method != http.MethodGet {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if got := r.Header.Get("X-API-Key"); got != "test-key" {
			t.Errorf("expected X-API-Key=test-key, got %q", got)
		}
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, stubChallengesJSON)
	}))
	defer srv.Close()

	client := kyvshield.NewClient("test-key", kyvshield.WithBaseURL(srv.URL))
	resp, err := client.GetChallenges(context.Background())
	if err != nil {
		t.Fatalf("GetChallenges returned error: %v", err)
	}

	if len(resp.Challenges.Document.Minimal) == 0 {
		t.Error("expected at least one minimal document challenge")
	}
	if resp.Challenges.Document.Minimal[0] != "center_document" {
		t.Errorf("expected 'center_document', got %q", resp.Challenges.Document.Minimal[0])
	}
	if len(resp.Challenges.Document.Standard) != 3 {
		t.Errorf("expected 3 standard document challenges, got %d", len(resp.Challenges.Document.Standard))
	}
	if len(resp.Challenges.Selfie.Standard) != 3 {
		t.Errorf("expected 3 standard selfie challenges, got %d", len(resp.Challenges.Selfie.Standard))
	}
	if len(resp.Challenges.Document.Strict) != 5 {
		t.Errorf("expected 5 strict document challenges, got %d", len(resp.Challenges.Document.Strict))
	}
}

func TestGetChallenges_HTTPError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
	}))
	defer srv.Close()

	client := kyvshield.NewClient("bad-key", kyvshield.WithBaseURL(srv.URL))
	_, err := client.GetChallenges(context.Background())
	apiErr := requireAPIError(t, err)
	if apiErr.StatusCode != http.StatusUnauthorized {
		t.Errorf("expected StatusCode 401, got %d", apiErr.StatusCode)
	}
}

func TestGetChallenges_InvalidJSON(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, `{not valid json`)
	}))
	defer srv.Close()

	client := kyvshield.NewClient("k", kyvshield.WithBaseURL(srv.URL))
	_, err := client.GetChallenges(context.Background())
	if err == nil {
		t.Fatal("expected JSON parse error, got nil")
	}
}

// ─── Verify ───────────────────────────────────────────────────────────────────

func TestVerify_MockServer(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/kyc/verify" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		if r.Method != http.MethodPost {
			t.Errorf("expected POST, got %s", r.Method)
		}
		ct := r.Header.Get("Content-Type")
		if !strings.HasPrefix(ct, "multipart/form-data") {
			t.Errorf("expected multipart/form-data Content-Type, got %q", ct)
		}
		if got := r.Header.Get("X-API-Key"); got != "test-key" {
			t.Errorf("expected X-API-Key=test-key, got %q", got)
		}

		if err := r.ParseMultipartForm(32 << 20); err != nil {
			t.Errorf("ParseMultipartForm: %v", err)
		}

		// Validate steps field.
		stepsRaw := r.FormValue("steps")
		if stepsRaw == "" {
			t.Error("'steps' field missing")
		}
		var steps []string
		if err := json.Unmarshal([]byte(stepsRaw), &steps); err != nil {
			t.Errorf("steps JSON invalid: %v", err)
		}
		if len(steps) != 2 || steps[0] != "recto" || steps[1] != "verso" {
			t.Errorf("unexpected steps: %v", steps)
		}

		// Validate other text fields.
		if got := r.FormValue("target"); got != "SN-CIN" {
			t.Errorf("expected target=SN-CIN, got %q", got)
		}
		if got := r.FormValue("language"); got != "fr" {
			t.Errorf("expected language=fr, got %q", got)
		}
		if got := r.FormValue("challenge_mode"); got != "standard" {
			t.Errorf("expected challenge_mode=standard, got %q", got)
		}
		if got := r.FormValue("require_face_match"); got != "false" {
			t.Errorf("expected require_face_match=false, got %q", got)
		}

		// Validate per-step override.
		if got := r.FormValue("recto_challenge_mode"); got != "strict" {
			t.Errorf("expected recto_challenge_mode=strict, got %q", got)
		}

		// Validate kyc_identifier.
		if got := r.FormValue("kyc_identifier"); got != "user-123" {
			t.Errorf("expected kyc_identifier=user-123, got %q", got)
		}

		// Validate image files were attached.
		if _, _, err := r.FormFile("recto_center_document"); err != nil {
			t.Errorf("recto_center_document file missing: %v", err)
		}
		if _, _, err := r.FormFile("verso_center_document"); err != nil {
			t.Errorf("verso_center_document file missing: %v", err)
		}

		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, stubVerifyJSON)
	}))
	defer srv.Close()

	client := kyvshield.NewClient("test-key", kyvshield.WithBaseURL(srv.URL))
	resp, err := client.Verify(context.Background(), &kyvshield.VerifyOptions{
		Steps:         []string{"recto", "verso"},
		Target:        "SN-CIN",
		Language:      "fr",
		ChallengeMode: "standard",
		StepChallengeModes: map[string]string{
			"recto_challenge_mode": "strict",
		},
		RequireFaceMatch: false,
		KycIdentifier:    "user-123",
		Images: map[string]string{
			"recto_center_document": testRectoPath,
			"verso_center_document": testVersoPath,
		},
	})
	if err != nil {
		t.Fatalf("Verify returned error: %v", err)
	}

	// Top-level fields.
	if !resp.Success {
		t.Error("expected Success=true")
	}
	if resp.SessionID != "sess_test_001" {
		t.Errorf("expected session_id=sess_test_001, got %q", resp.SessionID)
	}
	if resp.OverallStatus != "pass" {
		t.Errorf("expected overall_status=pass, got %q", resp.OverallStatus)
	}
	if resp.OverallConfidence != 0.97 {
		t.Errorf("expected overall_confidence=0.97, got %f", resp.OverallConfidence)
	}
	if resp.ProcessingTimeMs != 1234 {
		t.Errorf("expected processing_time_ms=1234, got %d", resp.ProcessingTimeMs)
	}

	// Face verification.
	if resp.FaceVerification == nil {
		t.Fatal("expected non-nil face_verification")
	}
	if !resp.FaceVerification.IsMatch {
		t.Error("expected face_verification.is_match=true")
	}
	if resp.FaceVerification.SimilarityScore != 87.4 {
		t.Errorf("expected similarity_score=87.4, got %f", resp.FaceVerification.SimilarityScore)
	}

	// Steps.
	if len(resp.Steps) != 2 {
		t.Fatalf("expected 2 steps, got %d", len(resp.Steps))
	}

	recto := resp.Steps[0]
	if recto.StepIndex != 0 {
		t.Errorf("expected step_index=0, got %d", recto.StepIndex)
	}
	if recto.StepType != "recto" {
		t.Errorf("expected step_type=recto, got %q", recto.StepType)
	}
	if !recto.Success {
		t.Error("expected recto success=true")
	}
	if recto.ProcessingTimeMs != 620 {
		t.Errorf("expected processing_time_ms=620, got %d", recto.ProcessingTimeMs)
	}

	// Liveness.
	if !recto.Liveness.IsLive {
		t.Error("expected liveness.is_live=true")
	}
	if recto.Liveness.Score != 0.95 {
		t.Errorf("expected liveness.score=0.95, got %f", recto.Liveness.Score)
	}
	if recto.Liveness.Confidence != "HIGH" {
		t.Errorf("expected liveness.confidence=HIGH, got %q", recto.Liveness.Confidence)
	}

	// Verification.
	if !recto.Verification.IsAuthentic {
		t.Error("expected verification.is_authentic=true")
	}
	if len(recto.Verification.ChecksPassed) != 2 {
		t.Errorf("expected 2 checks_passed, got %d", len(recto.Verification.ChecksPassed))
	}
	if len(recto.Verification.FraudIndicators) != 0 {
		t.Errorf("expected 0 fraud_indicators, got %d", len(recto.Verification.FraudIndicators))
	}

	// Extraction.
	if len(recto.Extraction) == 0 {
		t.Fatal("expected at least one extraction field")
	}
	field := recto.Extraction[0]
	if field.Key != "last_name" {
		t.Errorf("expected extraction[0].key=last_name, got %q", field.Key)
	}
	if field.DocumentKey != "NOM" {
		t.Errorf("expected extraction[0].document_key=NOM, got %q", field.DocumentKey)
	}
	if field.Value != "DIALLO" {
		t.Errorf("expected extraction[0].value=DIALLO, got %q", field.Value)
	}
	if field.DisplayPriority != 1 {
		t.Errorf("expected display_priority=1, got %d", field.DisplayPriority)
	}

	// Extracted photos.
	if len(recto.ExtractedPhotos) == 0 {
		t.Fatal("expected at least one extracted photo")
	}
	photo := recto.ExtractedPhotos[0]
	if photo.Width != 140 {
		t.Errorf("expected photo width=140, got %d", photo.Width)
	}
	if photo.Height != 180 {
		t.Errorf("expected photo height=180, got %d", photo.Height)
	}
	if len(photo.Bbox) != 4 {
		t.Errorf("expected bbox length=4, got %d", len(photo.Bbox))
	}

	// Verso step.
	verso := resp.Steps[1]
	if verso.StepType != "verso" {
		t.Errorf("expected step_type=verso, got %q", verso.StepType)
	}
	if !verso.Success {
		t.Error("expected verso success=true")
	}
}

func TestVerify_RequireFaceMatch(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseMultipartForm(32 << 20); err != nil {
			t.Errorf("ParseMultipartForm: %v", err)
		}
		if got := r.FormValue("require_face_match"); got != "true" {
			t.Errorf("expected require_face_match=true, got %q", got)
		}
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, stubVerifyJSON)
	}))
	defer srv.Close()

	client := kyvshield.NewClient("k", kyvshield.WithBaseURL(srv.URL))
	_, err := client.Verify(context.Background(), &kyvshield.VerifyOptions{
		Steps:            []string{"recto"},
		Target:           "SN-CIN",
		RequireFaceMatch: true,
		Images: map[string]string{
			"recto_center_document": testRectoPath,
		},
	})
	if err != nil {
		t.Fatalf("Verify: %v", err)
	}
}

func TestVerify_HTTPError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		fmt.Fprint(w, `{"error":"invalid target"}`)
	}))
	defer srv.Close()

	client := kyvshield.NewClient("test-key", kyvshield.WithBaseURL(srv.URL))
	_, err := client.Verify(context.Background(), &kyvshield.VerifyOptions{
		Steps:  []string{"recto"},
		Target: "INVALID",
		Images: map[string]string{
			"recto_center_document": testRectoPath,
		},
	})
	apiErr := requireAPIError(t, err)
	if apiErr.StatusCode != http.StatusBadRequest {
		t.Errorf("expected StatusCode 400, got %d", apiErr.StatusCode)
	}
	if len(apiErr.Body) == 0 {
		t.Error("expected non-empty Body in APIError")
	}
}

func TestVerify_NilOptions(t *testing.T) {
	client := kyvshield.NewClient("test-key")
	_, err := client.Verify(context.Background(), nil)
	if err == nil {
		t.Fatal("expected error for nil options")
	}
}

func TestVerify_ContextCancellation(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(500 * time.Millisecond)
		fmt.Fprint(w, stubVerifyJSON)
	}))
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()

	client := kyvshield.NewClient("test-key", kyvshield.WithBaseURL(srv.URL))
	_, err := client.Verify(ctx, &kyvshield.VerifyOptions{
		Steps:  []string{"recto"},
		Target: "SN-CIN",
		Images: map[string]string{
			"recto_center_document": testRectoPath,
		},
	})
	if err == nil {
		t.Fatal("expected context deadline error, got nil")
	}
}

func TestVerify_MissingImageFile(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Should never reach here.
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	client := kyvshield.NewClient("k", kyvshield.WithBaseURL(srv.URL))
	_, err := client.Verify(context.Background(), &kyvshield.VerifyOptions{
		Steps:  []string{"recto"},
		Target: "SN-CIN",
		Images: map[string]string{
			"recto_center_document": "/nonexistent/path/recto.jpg",
		},
	})
	if err == nil {
		t.Fatal("expected error for missing file, got nil")
	}
}

// ─── Webhook Signature ────────────────────────────────────────────────────────

func TestVerifyWebhookSignature_Valid(t *testing.T) {
	payload := []byte(`{"event":"kyc.completed","session_id":"sess_abc"}`)
	apiKey := "my-secret-key"
	sig := signHMAC(payload, apiKey)

	if !kyvshield.VerifyWebhookSignature(payload, apiKey, sig) {
		t.Error("expected valid HMAC signature to pass")
	}
}

func TestVerifyWebhookSignature_Invalid(t *testing.T) {
	payload := []byte(`{"event":"kyc.completed"}`)
	if kyvshield.VerifyWebhookSignature(payload, "secret", "0000000000000000") {
		t.Error("expected wrong signature to fail")
	}
}

func TestVerifyWebhookSignature_EmptySignature(t *testing.T) {
	if kyvshield.VerifyWebhookSignature([]byte("payload"), "key", "") {
		t.Error("expected empty signature to fail")
	}
}

func TestVerifyWebhookSignature_TamperedPayload(t *testing.T) {
	original := []byte(`{"event":"kyc.completed"}`)
	tampered := []byte(`{"event":"kyc.fraud"}`)
	apiKey := "secret"
	sig := signHMAC(original, apiKey)

	if kyvshield.VerifyWebhookSignature(tampered, apiKey, sig) {
		t.Error("expected tampered payload to fail signature check")
	}
}

func TestVerifyWebhookSignature_WrongKey(t *testing.T) {
	payload := []byte(`{"event":"kyc.completed"}`)
	sig := signHMAC(payload, "correct-key")
	if kyvshield.VerifyWebhookSignature(payload, "wrong-key", sig) {
		t.Error("expected wrong key to fail signature check")
	}
}

func TestVerifyWebhookSignature_Sha256Prefix(t *testing.T) {
	payload := []byte(`{"event":"kyc.completed","session_id":"sess_abc"}`)
	apiKey := "my-secret-key"
	sig := signHMAC(payload, apiKey)

	// Accept with sha256= prefix
	if !kyvshield.VerifyWebhookSignature(payload, apiKey, "sha256="+sig) {
		t.Error("expected signature with sha256= prefix to pass")
	}

	// Accept bare hex (no prefix)
	if !kyvshield.VerifyWebhookSignature(payload, apiKey, sig) {
		t.Error("expected bare hex signature to pass")
	}
}

// ─── Option Tests ─────────────────────────────────────────────────────────────

func TestWithBaseURL_TrailingSlash(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/challenges" {
			t.Errorf("unexpected path: %q (trailing slash not stripped?)", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, stubChallengesJSON)
	}))
	defer srv.Close()

	// Trailing slash on the base URL must be handled transparently.
	client := kyvshield.NewClient("k", kyvshield.WithBaseURL(srv.URL+"/"))
	_, err := client.GetChallenges(context.Background())
	if err != nil {
		t.Fatalf("WithBaseURL trailing slash: %v", err)
	}
}

func TestWithTimeout_RequestTimeout(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(200 * time.Millisecond)
		fmt.Fprint(w, stubChallengesJSON)
	}))
	defer srv.Close()

	client := kyvshield.NewClient("k",
		kyvshield.WithBaseURL(srv.URL),
		kyvshield.WithTimeout(50*time.Millisecond),
	)
	_, err := client.GetChallenges(context.Background())
	if err == nil {
		t.Fatal("expected timeout error, got nil")
	}
}

func TestWithHTTPClient(t *testing.T) {
	called := false
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, stubChallengesJSON)
	}))
	defer srv.Close()

	custom := &http.Client{Timeout: 10 * time.Second}
	client := kyvshield.NewClient("k",
		kyvshield.WithBaseURL(srv.URL),
		kyvshield.WithHTTPClient(custom),
	)
	_, err := client.GetChallenges(context.Background())
	if err != nil {
		t.Fatalf("WithHTTPClient: %v", err)
	}
	if !called {
		t.Error("expected server to be called via custom HTTP client")
	}
}

// ─── APIError ─────────────────────────────────────────────────────────────────

func TestAPIError_ErrorString(t *testing.T) {
	e := &kyvshield.APIError{
		StatusCode: 429,
		Message:    "rate limit exceeded",
	}
	if !strings.Contains(e.Error(), "rate limit exceeded") {
		t.Errorf("Error() should contain message, got: %q", e.Error())
	}
}

func TestAPIError_ErrorString_NoMessage(t *testing.T) {
	e := &kyvshield.APIError{StatusCode: 500}
	s := e.Error()
	if !strings.Contains(s, "500") {
		t.Errorf("Error() should contain status code, got: %q", s)
	}
}

// ─── Integration Test against localhost:8080 ─────────────────────────────────

// TestIntegration_Localhost runs against a locally running KyvShield instance.
// It is skipped automatically when the server is unreachable.
//
// Start the backend first:
//
//	cd /Users/macbookpro/GolandProjects/cin_verification && bash scripts/run-local.sh
//
// Then run:
//
//	go test -v -run TestIntegration_Localhost ./...
func TestIntegration_Localhost(t *testing.T) {
	baseURL := resolveBaseURL()
	apiKey := envOrDefault("KYVSHIELD_API_KEY", "kyvshield_demo_key_2024")

	// Probe the server with a short timeout so CI is not blocked.
	probe := &http.Client{Timeout: 2 * time.Second}
	probeResp, probeErr := probe.Get(baseURL + "/api/v1/challenges")
	if probeErr != nil {
		t.Skipf("%s unreachable (%v) — skipping integration test", baseURL, probeErr)
	}
	probeResp.Body.Close()
	// A 401/403 without a real key means the server is up but we have no valid key;
	// skip gracefully so the integration test does not fail in CI.
	if probeResp.StatusCode == http.StatusUnauthorized || probeResp.StatusCode == http.StatusForbidden {
		t.Skipf("%s returned %d — set KYVSHIELD_API_KEY to run integration tests",
			baseURL, probeResp.StatusCode)
	}

	client := kyvshield.NewClient(apiKey,
		kyvshield.WithBaseURL(baseURL),
		kyvshield.WithTimeout(120*time.Second),
	)

	// ── GetChallenges ─────────────────────────────────────────────────────────
	t.Run("GetChallenges", func(t *testing.T) {
		challenges, err := client.GetChallenges(context.Background())
		if err != nil {
			t.Fatalf("GetChallenges: %v", err)
		}
		t.Logf("document.minimal:  %v", challenges.Challenges.Document.Minimal)
		t.Logf("document.standard: %v", challenges.Challenges.Document.Standard)
		t.Logf("selfie.standard:   %v", challenges.Challenges.Selfie.Standard)
	})

	// ── Verify recto + verso (minimal challenge mode) ─────────────────────────
	t.Run("Verify_RectoVerso", func(t *testing.T) {
		resp, err := client.Verify(context.Background(), &kyvshield.VerifyOptions{
			Steps:         []string{"recto", "verso"},
			Target:        string(kyvshield.DocumentTargetCIN),
			Language:      string(kyvshield.LanguageFrench),
			ChallengeMode: string(kyvshield.ChallengeModeMinimal),
			Images: map[string]string{
				"recto_center_document": testRectoPath,
				"verso_center_document": testVersoPath,
			},
		})
		if err != nil {
			t.Fatalf("Verify: %v", err)
		}

		t.Logf("session_id:         %s", resp.SessionID)
		t.Logf("overall_status:     %s", resp.OverallStatus)
		t.Logf("overall_confidence: %.3f", resp.OverallConfidence)
		t.Logf("processing_time_ms: %d", resp.ProcessingTimeMs)

		for _, step := range resp.Steps {
			t.Logf("step[%d] %-8s success=%-5v live=%-5v authentic=%-5v fields=%d",
				step.StepIndex, step.StepType, step.Success,
				step.Liveness.IsLive, step.Verification.IsAuthentic,
				len(step.Extraction),
			)
			for _, f := range step.Extraction {
				t.Logf("  %-20s = %s", f.Label, f.Value)
			}
		}
	})
}
