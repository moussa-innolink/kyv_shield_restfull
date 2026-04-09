// Package kyvshield provides a fully typed Go client for the KyvShield REST KYC API.
//
// Usage:
//
//	client := kyvshield.NewClient("your-api-key")
//
//	// Fetch available challenges
//	challenges, err := client.GetChallenges(ctx)
//
//	// Run a KYC verification
//	resp, err := client.Verify(ctx, &kyvshield.VerifyOptions{
//	    Steps:         []string{"recto", "verso"},
//	    Target:        "SN-CIN",
//	    Language:      "fr",
//	    ChallengeMode: "standard",
//	    Images: map[string]string{
//	        "recto_center_document": "/path/to/recto.jpg",
//	        "verso_center_document": "/path/to/verso.jpg",
//	    },
//	})
package kyvshield

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// defaultBaseURL is the production KyvShield API endpoint.
const defaultBaseURL = "https://kyvshield-naruto.innolinkcloud.com"

// ─── Option Pattern ───────────────────────────────────────────────────────────

// Option is a functional option for configuring a Client.
type Option func(*Client)

// WithBaseURL overrides the API base URL. Useful for local development or staging.
//
//	client := kyvshield.NewClient(apiKey, kyvshield.WithBaseURL("http://localhost:8080"))
func WithBaseURL(url string) Option {
	return func(c *Client) {
		// Trim trailing slash for consistent URL construction.
		c.baseURL = strings.TrimRight(url, "/")
	}
}

// WithHTTPClient replaces the default http.Client. Use this to set custom
// timeouts, transport settings, or proxy configurations.
func WithHTTPClient(hc *http.Client) Option {
	return func(c *Client) {
		c.httpClient = hc
	}
}

// WithTimeout sets the HTTP request timeout. Defaults to 120 seconds.
func WithTimeout(d time.Duration) Option {
	return func(c *Client) {
		c.httpClient.Timeout = d
	}
}

// ─── Client ───────────────────────────────────────────────────────────────────

// Client is the KyvShield API client. Create one with NewClient and reuse it
// across requests; it is safe for concurrent use.
type Client struct {
	apiKey     string
	baseURL    string
	httpClient *http.Client
}

// NewClient creates a new KyvShield API client authenticated with the given API key.
// Additional configuration is applied through the Option pattern.
//
//	// Default client (production endpoint, 120-second timeout)
//	client := kyvshield.NewClient("your-api-key")
//
//	// Custom base URL for local testing
//	client := kyvshield.NewClient("your-api-key", kyvshield.WithBaseURL("http://localhost:8080"))
func NewClient(apiKey string, opts ...Option) *Client {
	c := &Client{
		apiKey:  apiKey,
		baseURL: defaultBaseURL,
		httpClient: &http.Client{
			Timeout: 120 * time.Second,
		},
	}
	for _, opt := range opts {
		opt(c)
	}
	return c
}

// ─── GetChallenges ────────────────────────────────────────────────────────────

// GetChallenges retrieves the available challenge names grouped by step type and
// intensity mode from GET /api/v1/challenges.
//
// The returned ChallengesResponse describes which challenge images the client
// must capture for each combination of step (document / selfie) and mode
// (minimal / standard / strict).
func (c *Client) GetChallenges(ctx context.Context) (*ChallengesResponse, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		c.baseURL+"/api/v1/challenges", nil)
	if err != nil {
		return nil, fmt.Errorf("kyvshield: build request: %w", err)
	}
	c.setAuthHeader(req)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("kyvshield: GET /api/v1/challenges: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("kyvshield: read response body: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return nil, &APIError{
			StatusCode: resp.StatusCode,
			Message:    fmt.Sprintf("kyvshield: GET /api/v1/challenges returned HTTP %d", resp.StatusCode),
			Body:       body,
		}
	}

	var result ChallengesResponse
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("kyvshield: decode challenges response: %w", err)
	}
	return &result, nil
}

// ─── Verify ───────────────────────────────────────────────────────────────────

// Verify submits a KYC verification request to POST /api/v1/kyc/verify.
//
// The request is sent as a multipart/form-data body. Each image entry in
// opts.Images is opened from the local filesystem and attached using the map
// key as the multipart field name.
//
// All fields defined in VerifyOptions map directly to the API form fields:
//
//   - opts.Steps            → "steps"      (JSON array, e.g. `["recto","verso"]`)
//   - opts.Target           → "target"
//   - opts.Language         → "language"
//   - opts.ChallengeMode    → "challenge_mode"
//   - opts.StepChallengeModes → individual keys, e.g. "recto_challenge_mode"
//   - opts.RequireFaceMatch → "require_face_match" ("true"/"false")
//   - opts.KycIdentifier    → "kyc_identifier"    (omitted when empty)
//   - opts.Images           → one file field per entry
func (c *Client) Verify(ctx context.Context, opts *VerifyOptions) (*KycResponse, error) {
	if opts == nil {
		return nil, fmt.Errorf("kyvshield: VerifyOptions must not be nil")
	}

	// Build multipart body.
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)

	// ── Required text fields ──────────────────────────────────────────────────

	stepsJSON, err := json.Marshal(opts.Steps)
	if err != nil {
		return nil, fmt.Errorf("kyvshield: marshal steps: %w", err)
	}
	if err := mw.WriteField("steps", string(stepsJSON)); err != nil {
		return nil, fmt.Errorf("kyvshield: write field 'steps': %w", err)
	}

	if opts.Target != "" {
		if err := mw.WriteField("target", opts.Target); err != nil {
			return nil, fmt.Errorf("kyvshield: write field 'target': %w", err)
		}
	}

	// ── Optional text fields ──────────────────────────────────────────────────

	if opts.Language != "" {
		if err := mw.WriteField("language", opts.Language); err != nil {
			return nil, fmt.Errorf("kyvshield: write field 'language': %w", err)
		}
	}

	if opts.ChallengeMode != "" {
		if err := mw.WriteField("challenge_mode", opts.ChallengeMode); err != nil {
			return nil, fmt.Errorf("kyvshield: write field 'challenge_mode': %w", err)
		}
	}

	// Per-step challenge mode overrides.
	for step, val := range opts.StepChallengeModes {
		if val != "" {
			key := step
			if !strings.HasSuffix(key, "_challenge_mode") {
				key = step + "_challenge_mode"
			}
			if err := mw.WriteField(key, val); err != nil {
				return nil, fmt.Errorf("kyvshield: write field %q: %w", key, err)
			}
		}
	}

	// require_face_match — always send to be explicit.
	faceMatchVal := "false"
	if opts.RequireFaceMatch {
		faceMatchVal = "true"
	}
	if err := mw.WriteField("require_face_match", faceMatchVal); err != nil {
		return nil, fmt.Errorf("kyvshield: write field 'require_face_match': %w", err)
	}

	if opts.KycIdentifier != "" {
		if err := mw.WriteField("kyc_identifier", opts.KycIdentifier); err != nil {
			return nil, fmt.Errorf("kyvshield: write field 'kyc_identifier': %w", err)
		}
	}

	// ── Image files ───────────────────────────────────────────────────────────

	for fieldName, filePath := range opts.Images {
		if err := attachFile(mw, fieldName, filePath); err != nil {
			return nil, err
		}
	}

	if err := mw.Close(); err != nil {
		return nil, fmt.Errorf("kyvshield: close multipart writer: %w", err)
	}

	// ── HTTP request ──────────────────────────────────────────────────────────

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		c.baseURL+"/api/v1/kyc/verify", &buf)
	if err != nil {
		return nil, fmt.Errorf("kyvshield: build request: %w", err)
	}
	c.setAuthHeader(req)
	req.Header.Set("Content-Type", mw.FormDataContentType())

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("kyvshield: POST /api/v1/kyc/verify: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("kyvshield: read response body: %w", err)
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, &APIError{
			StatusCode: resp.StatusCode,
			Message:    fmt.Sprintf("kyvshield: POST /api/v1/kyc/verify returned HTTP %d", resp.StatusCode),
			Body:       body,
		}
	}

	var result KycResponse
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("kyvshield: decode verify response: %w", err)
	}
	return &result, nil
}

// ─── VerifyBatch ──────────────────────────────────────────────────────────────

// VerifyBatch runs multiple KYC verifications concurrently using goroutines.
// Results are returned in the same order as the input slice.
// Maximum batch size is 10.
func (c *Client) VerifyBatch(ctx context.Context, optsList []*VerifyOptions) []BatchResult {
	if len(optsList) > 10 {
		panic("kyvshield: batch size cannot exceed 10")
	}

	results := make([]BatchResult, len(optsList))
	var wg sync.WaitGroup

	for i, opts := range optsList {
		wg.Add(1)
		go func(idx int, o *VerifyOptions) {
			defer wg.Done()
			resp, err := c.Verify(ctx, o)
			if err != nil {
				results[idx] = BatchResult{Success: false, Error: err}
			} else {
				results[idx] = BatchResult{Success: true, Result: resp}
			}
		}(i, opts)
	}

	wg.Wait()
	return results
}

// ─── Webhook Signature Verification ──────────────────────────────────────────

// VerifyWebhookSignature verifies the HMAC-SHA256 signature of a webhook payload.
//
// KyvShield signs webhook POST bodies using HMAC-SHA256 keyed on the API key and
// sends the hex-encoded digest in the X-KyvShield-Signature header.
//
//	ok := kyvshield.VerifyWebhookSignature(body, apiKey, r.Header.Get("X-KyvShield-Signature"))
//	if !ok {
//	    http.Error(w, "invalid signature", http.StatusUnauthorized)
//	    return
//	}
func VerifyWebhookSignature(payload []byte, apiKey, signatureHeader string) bool {
	if signatureHeader == "" {
		return false
	}
	sig := signatureHeader
	if strings.HasPrefix(sig, "sha256=") {
		sig = sig[7:]
	}
	mac := hmac.New(sha256.New, []byte(apiKey))
	mac.Write(payload)
	expected := hex.EncodeToString(mac.Sum(nil))
	return hmac.Equal([]byte(expected), []byte(sig))
}

// ─── Internal Helpers ─────────────────────────────────────────────────────────

// setAuthHeader attaches the API key authentication header to the request.
func (c *Client) setAuthHeader(req *http.Request) {
	req.Header.Set("X-API-Key", c.apiKey)
}

// attachFile opens a local file and writes it as a multipart file field.
// The content-type is inferred from the file extension (.jpg/.jpeg → image/jpeg,
// .png → image/png, everything else → application/octet-stream).
func attachFile(mw *multipart.Writer, fieldName, filePath string) error {
	f, err := os.Open(filePath)
	if err != nil {
		return fmt.Errorf("kyvshield: open image file %q for field %q: %w", filePath, fieldName, err)
	}
	defer f.Close()

	contentType := mimeTypeFromPath(filePath)
	filename := filepath.Base(filePath)

	// Create a custom MIME part so we can set the content-type.
	h := make(map[string][]string)
	h["Content-Disposition"] = []string{
		fmt.Sprintf(`form-data; name="%s"; filename="%s"`, fieldName, filename),
	}
	h["Content-Type"] = []string{contentType}

	part, err := mw.CreatePart(h)
	if err != nil {
		return fmt.Errorf("kyvshield: create multipart part for field %q: %w", fieldName, err)
	}

	if _, err := io.Copy(part, f); err != nil {
		return fmt.Errorf("kyvshield: copy file data for field %q: %w", fieldName, err)
	}
	return nil
}

// mimeTypeFromPath returns a MIME type based on the file extension.
func mimeTypeFromPath(path string) string {
	ext := strings.ToLower(filepath.Ext(path))
	switch ext {
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	case ".gif":
		return "image/gif"
	case ".webp":
		return "image/webp"
	default:
		return "application/octet-stream"
	}
}
