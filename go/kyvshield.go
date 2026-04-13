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
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	_ "image/png"  // register PNG decoder
	_ "image/gif"  // register GIF decoder
	"io"
	"log"
	"mime/multipart"
	"net/http"
	"net/url"
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

// WithImageMaxWidth sets the maximum image width in pixels.
// Images wider than this will be resized down while maintaining aspect ratio.
// Defaults to 1280.
func WithImageMaxWidth(maxWidth int) Option {
	return func(c *Client) {
		c.imageMaxWidth = maxWidth
	}
}

// WithImageQuality sets the JPEG compression quality (0–100).
// Defaults to 90.
func WithImageQuality(quality int) Option {
	return func(c *Client) {
		c.imageQuality = quality
	}
}

// WithLogger enables [KyvShield] tagged logging to stderr.
//
//	client := kyvshield.NewClient(apiKey, kyvshield.WithLogger(true))
func WithLogger(enabled bool) Option {
	return func(c *Client) {
		c.enableLog = enabled
	}
}

// ─── Client ───────────────────────────────────────────────────────────────────

// Client is the KyvShield API client. Create one with NewClient and reuse it
// across requests; it is safe for concurrent use.
type Client struct {
	apiKey        string
	baseURL       string
	httpClient    *http.Client
	imageMaxWidth int
	imageQuality  int
	enableLog     bool
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
		apiKey:        apiKey,
		baseURL:       defaultBaseURL,
		imageMaxWidth: 1280,
		imageQuality:  90,
		enableLog:     true,
		httpClient: &http.Client{
			Timeout: 120 * time.Second,
		},
	}
	for _, opt := range opts {
		opt(c)
	}
	c.logf("Initialized (baseUrl=%s, imageMaxWidth=%d, imageQuality=%d)", c.baseURL, c.imageMaxWidth, c.imageQuality)
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
	c.logf("GET /api/v1/challenges...")
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
	c.logf("Challenges loaded")
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

	// ── Input validation ──────────────────────────────────────────────────────

	if len(opts.Steps) == 0 {
		return nil, fmt.Errorf("kyvshield: Steps must contain at least one step")
	}
	if opts.Target == "" {
		return nil, fmt.Errorf("kyvshield: Target must not be empty")
	}
	if len(opts.Images) == 0 && len(opts.ImageBytes) == 0 {
		return nil, fmt.Errorf("kyvshield: Images must contain at least one entry")
	}

	imageCount := len(opts.Images) + len(opts.ImageBytes)
	c.logf("POST /api/v1/kyc/verify (steps=%v, target=%s, images=%d)", opts.Steps, opts.Target, imageCount)
	startMs := time.Now()

	// ── Parallel image resolution + compression (semaphore: max 20) ──────────

	type resolvedImage struct {
		fieldName string
		filename  string
		data      []byte
		err       error
	}

	type imageEntry struct {
		fieldName string
		value     string // empty for ImageBytes entries
		rawBytes  []byte // nil for Images entries
	}

	var entries []imageEntry
	for fieldName, value := range opts.Images {
		entries = append(entries, imageEntry{fieldName: fieldName, value: value})
	}
	for fieldName, raw := range opts.ImageBytes {
		entries = append(entries, imageEntry{fieldName: fieldName, rawBytes: raw})
	}

	resolved := make([]resolvedImage, len(entries))
	sem := make(chan struct{}, DefaultMaxConcurrentCompress)
	var wgImgs sync.WaitGroup

	for i, entry := range entries {
		wgImgs.Add(1)
		go func(idx int, e imageEntry) {
			defer wgImgs.Done()
			sem <- struct{}{}
			defer func() { <-sem }()

			var raw []byte
			var filename string
			var err error

			if e.rawBytes != nil {
				raw = e.rawBytes
				filename = e.fieldName + ".jpg"
			} else {
				raw, err = c.resolveImageWithLog(e.value, e.fieldName)
				if err != nil {
					resolved[idx] = resolvedImage{fieldName: e.fieldName, err: err}
					return
				}
				filename = deriveFilename(e.value, e.fieldName)
			}

			processed, procErr := c.processImage(raw, e.fieldName)
			if procErr != nil {
				c.logf("Image %s: processing failed (%v) — using original", e.fieldName, procErr)
				processed = raw
			}
			resolved[idx] = resolvedImage{fieldName: e.fieldName, filename: filename, data: processed}
		}(i, entry)
	}
	wgImgs.Wait()

	// ── Build multipart body ──────────────────────────────────────────────────

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

	// require_aml — always send to be explicit.
	amlVal := "false"
	if opts.RequireAml {
		amlVal = "true"
	}
	if err := mw.WriteField("require_aml", amlVal); err != nil {
		return nil, fmt.Errorf("kyvshield: write field 'require_aml': %w", err)
	}

	if opts.KycIdentifier != "" {
		if err := mw.WriteField("kyc_identifier", opts.KycIdentifier); err != nil {
			return nil, fmt.Errorf("kyvshield: write field 'kyc_identifier': %w", err)
		}
	}

	// ── Attach pre-processed images ───────────────────────────────────────────

	for _, r := range resolved {
		if r.err != nil {
			return nil, fmt.Errorf("kyvshield: resolve image for field %q: %w", r.fieldName, r.err)
		}
		if err := attachBytes(mw, r.fieldName, r.filename, r.data); err != nil {
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
	elapsed := time.Since(startMs).Milliseconds()
	c.logf("Verify complete: %s (%.2f) in %dms", result.OverallStatus, result.OverallConfidence, elapsed)
	return &result, nil
}

// ─── VerifyBatch ──────────────────────────────────────────────────────────────

// VerifyBatch runs multiple KYC verifications concurrently using goroutines.
// Results are returned in the same order as the input slice.
// Maximum batch size is 10.
func (c *Client) VerifyBatch(ctx context.Context, optsList []*VerifyOptions) ([]BatchResult, error) {
	if len(optsList) > 10 {
		return nil, fmt.Errorf("kyvshield: batch size cannot exceed 10")
	}
	c.logf("Batch verify: %d requests...", len(optsList))

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

	passed := 0
	for _, r := range results {
		if r.Success {
			passed++
		}
	}
	c.logf("Batch complete: %d pass, %d reject", passed, len(results)-passed)
	return results, nil
}

// ─── Identify ─────────────────────────────────────────────────────────────────

// Identify submits a face image to POST /api/v1/identify and returns the
// top matching subjects from the enrolled gallery.
//
// The image parameter accepts the same formats as VerifyOptions.Images values:
//   - file path on disk
//   - "http://" or "https://" URL (downloaded automatically)
//   - raw []byte (passed directly)
//   - base64 string or data URI
//
// opts may be nil to use server-side defaults.
func (c *Client) Identify(ctx context.Context, image interface{}, opts *IdentifyOptions) (*IdentifyResponse, error) {
	raw, filename, err := c.resolveImageInput(image, "image")
	if err != nil {
		return nil, fmt.Errorf("kyvshield: resolve image: %w", err)
	}

	processed, procErr := c.processImage(raw, "image")
	if procErr != nil {
		c.logf("Image image: processing failed (%v) — using original", procErr)
		processed = raw
	}

	c.logf("POST /api/v1/identify...")
	startMs := time.Now()

	// ── Build multipart body ──────────────────────────────────────────────────

	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)

	if err := attachBytes(mw, "image", filename, processed); err != nil {
		return nil, err
	}

	if opts != nil {
		if opts.TopK > 0 {
			if err := mw.WriteField("top_k", fmt.Sprintf("%d", opts.TopK)); err != nil {
				return nil, fmt.Errorf("kyvshield: write field 'top_k': %w", err)
			}
		}
		if opts.MinScore > 0 {
			if err := mw.WriteField("min_score", fmt.Sprintf("%g", opts.MinScore)); err != nil {
				return nil, fmt.Errorf("kyvshield: write field 'min_score': %w", err)
			}
		}
	}

	if err := mw.Close(); err != nil {
		return nil, fmt.Errorf("kyvshield: close multipart writer: %w", err)
	}

	// ── HTTP request ──────────────────────────────────────────────────────────

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		c.baseURL+"/api/v1/identify", &buf)
	if err != nil {
		return nil, fmt.Errorf("kyvshield: build request: %w", err)
	}
	c.setAuthHeader(req)
	req.Header.Set("Content-Type", mw.FormDataContentType())

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("kyvshield: POST /api/v1/identify: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("kyvshield: read response body: %w", err)
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, &APIError{
			StatusCode: resp.StatusCode,
			Message:    fmt.Sprintf("kyvshield: POST /api/v1/identify returned HTTP %d", resp.StatusCode),
			Body:       body,
		}
	}

	var result IdentifyResponse
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("kyvshield: decode identify response: %w", err)
	}
	elapsed := time.Since(startMs).Milliseconds()
	c.logf("Identify complete: %d matches in %dms", len(result.Matches), elapsed)
	return &result, nil
}

// ─── VerifyFace ───────────────────────────────────────────────────────────────

// VerifyFace compares two face images via POST /api/v1/verify/face and returns
// whether they match along with a similarity score.
//
// Both targetImage and sourceImage accept the same formats as VerifyOptions.Images
// values: file path, URL, raw []byte, base64 string, or data URI.
//
// opts may be nil to use server-side defaults.
func (c *Client) VerifyFace(ctx context.Context, targetImage, sourceImage interface{}, opts *FaceVerifyOptions) (*FaceVerifyResponse, error) {
	// Resolve both images in parallel.
	type resolved struct {
		data     []byte
		filename string
		err      error
	}

	var wg sync.WaitGroup
	var target, source resolved

	wg.Add(2)
	go func() {
		defer wg.Done()
		target.data, target.filename, target.err = c.resolveImageInput(targetImage, "target")
	}()
	go func() {
		defer wg.Done()
		source.data, source.filename, source.err = c.resolveImageInput(sourceImage, "source")
	}()
	wg.Wait()

	if target.err != nil {
		return nil, fmt.Errorf("kyvshield: resolve target image: %w", target.err)
	}
	if source.err != nil {
		return nil, fmt.Errorf("kyvshield: resolve source image: %w", source.err)
	}

	// Process both images in parallel.
	wg.Add(2)
	go func() {
		defer wg.Done()
		processed, err := c.processImage(target.data, "target")
		if err != nil {
			c.logf("Image target: processing failed (%v) — using original", err)
			return
		}
		target.data = processed
	}()
	go func() {
		defer wg.Done()
		processed, err := c.processImage(source.data, "source")
		if err != nil {
			c.logf("Image source: processing failed (%v) — using original", err)
			return
		}
		source.data = processed
	}()
	wg.Wait()

	c.logf("POST /api/v1/verify/face...")
	startMs := time.Now()

	// ── Build multipart body ──────────────────────────────────────────────────

	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)

	if err := attachBytes(mw, "target", target.filename, target.data); err != nil {
		return nil, err
	}
	if err := attachBytes(mw, "source", source.filename, source.data); err != nil {
		return nil, err
	}

	if opts != nil {
		if opts.DetectionModel != "" {
			if err := mw.WriteField("detection_model", opts.DetectionModel); err != nil {
				return nil, fmt.Errorf("kyvshield: write field 'detection_model': %w", err)
			}
		}
		if opts.RecognitionModel != "" {
			if err := mw.WriteField("recognition_model", opts.RecognitionModel); err != nil {
				return nil, fmt.Errorf("kyvshield: write field 'recognition_model': %w", err)
			}
		}
	}

	if err := mw.Close(); err != nil {
		return nil, fmt.Errorf("kyvshield: close multipart writer: %w", err)
	}

	// ── HTTP request ──────────────────────────────────────────────────────────

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		c.baseURL+"/api/v1/verify/face", &buf)
	if err != nil {
		return nil, fmt.Errorf("kyvshield: build request: %w", err)
	}
	c.setAuthHeader(req)
	req.Header.Set("Content-Type", mw.FormDataContentType())

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("kyvshield: POST /api/v1/verify/face: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("kyvshield: read response body: %w", err)
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, &APIError{
			StatusCode: resp.StatusCode,
			Message:    fmt.Sprintf("kyvshield: POST /api/v1/verify/face returned HTTP %d", resp.StatusCode),
			Body:       body,
		}
	}

	var result FaceVerifyResponse
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("kyvshield: decode verify/face response: %w", err)
	}
	elapsed := time.Since(startMs).Milliseconds()
	c.logf("VerifyFace complete: match=%v score=%.2f in %dms", result.IsMatch, result.SimilarityScore, elapsed)
	return &result, nil
}

// ─── resolveImageInput ────────────────────────────────────────────────────────

// resolveImageInput converts the polymorphic image parameter (string, []byte, or
// any supported format) into raw bytes and a filename for multipart attachment.
func (c *Client) resolveImageInput(image interface{}, label string) ([]byte, string, error) {
	switch v := image.(type) {
	case []byte:
		if len(v) == 0 {
			return nil, "", fmt.Errorf("empty byte slice for %s", label)
		}
		return v, label + ".jpg", nil
	case string:
		if v == "" {
			return nil, "", fmt.Errorf("empty string for %s", label)
		}
		raw, err := c.resolveImageWithLog(v, label)
		if err != nil {
			return nil, "", err
		}
		return raw, deriveFilename(v, label), nil
	default:
		return nil, "", fmt.Errorf("unsupported image type %T for %s: must be string or []byte", image, label)
	}
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

// logf logs a tagged message when enableLog is true.
// All messages are prefixed with [KyvShield].
func (c *Client) logf(format string, args ...interface{}) {
	if c.enableLog {
		log.Printf("[KyvShield] "+format, args...)
	}
}

// processImage resizes and JPEG-compresses image bytes using only the stdlib.
//
// Resolution order:
//  1. Decode the image (JPEG, PNG, or GIF via registered decoders).
//  2. If width > imageMaxWidth, resize using a simple nearest-neighbor algorithm.
//  3. Encode as JPEG at imageQuality.
//
// If anything fails the original bytes are returned unchanged.
func (c *Client) processImage(data []byte, label string) ([]byte, error) {
	originalKb := len(data) / 1024

	img, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		// Not a recognised image format (e.g. WebP) — return as-is
		c.logf("Image %s: could not decode for processing (%v) — using original (%dKB)", label, err, originalKb)
		return data, nil
	}

	bounds := img.Bounds()
	origWidth := bounds.Dx()
	origHeight := bounds.Dy()

	var toEncode image.Image
	if origWidth > c.imageMaxWidth {
		newWidth := c.imageMaxWidth
		newHeight := origHeight * newWidth / origWidth
		dst := image.NewRGBA(image.Rect(0, 0, newWidth, newHeight))
		// Simple nearest-neighbor resize (no external deps)
		for y := 0; y < newHeight; y++ {
			for x := 0; x < newWidth; x++ {
				srcX := x * origWidth / newWidth
				srcY := y * origHeight / newHeight
				r, g, b, a := img.At(bounds.Min.X+srcX, bounds.Min.Y+srcY).RGBA()
				dst.SetRGBA(x, y, color.RGBA{
					R: uint8(r >> 8),
					G: uint8(g >> 8),
					B: uint8(b >> 8),
					A: uint8(a >> 8),
				})
			}
		}
		toEncode = dst
	} else {
		toEncode = img
	}

	var buf bytes.Buffer
	opts := &jpeg.Options{Quality: c.imageQuality}
	if err := jpeg.Encode(&buf, toEncode, opts); err != nil {
		c.logf("Image %s: JPEG encode failed (%v) — using original", label, err)
		return data, nil
	}

	result := buf.Bytes()
	finalKb := len(result) / 1024

	if origWidth > c.imageMaxWidth {
		c.logf("Image %s: %dKB → resized %dpx → compressed %d%% → %dKB",
			label, originalKb, c.imageMaxWidth, c.imageQuality, finalKb)
	} else {
		c.logf("Image %s: %dKB → compressed %d%% → %dKB",
			label, originalKb, c.imageQuality, finalKb)
	}
	return result, nil
}

// resolveImageWithLog wraps resolveImage with download/decode log messages.
func (c *Client) resolveImageWithLog(value, fieldName string) ([]byte, error) {
	if strings.HasPrefix(value, "http://") || strings.HasPrefix(value, "https://") {
		c.logf("Downloading image from %s", value)
	} else if strings.HasPrefix(value, "data:image/") {
		commaIdx := strings.Index(value, ",")
		if commaIdx != -1 {
			sizeKb := int(float64(len(value)-commaIdx-1)*0.75) / 1024
			c.logf("Decoding base64 image %s (%dKB)", fieldName, sizeKb)
		}
	} else if strings.HasPrefix(value, "/9j/") || strings.HasPrefix(value, "iVBOR") ||
		strings.HasPrefix(value, "UklGR") || strings.HasPrefix(value, "R0lGO") ||
		(!strings.ContainsAny(value, "/\\") && len(value) > 64) {
		sizeKb := int(float64(len(value))*0.75) / 1024
		c.logf("Decoding base64 image %s (%dKB)", fieldName, sizeKb)
	}
	return resolveImage(value)
}

// setAuthHeader attaches the API key authentication header to the request.
func (c *Client) setAuthHeader(req *http.Request) {
	req.Header.Set("X-API-Key", c.apiKey)
}

// resolveImage converts a string value to raw image bytes.
//
// Resolution order:
//  1. "http://" / "https://"  → downloaded via http.Get with a 30-second timeout
//  2. "data:image/" prefix    → data URI; base64 portion after ',' is decoded
//  3. long string, no path sep, no extension → treated as raw base64
//  4. otherwise               → treated as a filesystem path
func resolveImage(value string) ([]byte, error) {
	// 1. URL
	if strings.HasPrefix(value, "http://") || strings.HasPrefix(value, "https://") {
		client := &http.Client{Timeout: 30 * time.Second}
		resp, err := client.Get(value)
		if err != nil {
			return nil, fmt.Errorf("download image from URL %q: %w", value, err)
		}
		defer resp.Body.Close()
		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			return nil, fmt.Errorf("download image from URL %q: HTTP %d", value, resp.StatusCode)
		}
		return io.ReadAll(io.LimitReader(resp.Body, 50*1024*1024))
	}

	// 2. Data URI
	if strings.HasPrefix(value, "data:image/") {
		commaIdx := strings.Index(value, ",")
		if commaIdx == -1 {
			return nil, fmt.Errorf("invalid data URI for image")
		}
		return base64.StdEncoding.DecodeString(value[commaIdx+1:])
	}

	// 3. Bare base64 string
	// Detect known base64 image prefixes first — JPEG base64 starts with /9j/ which
	// contains '/', so checking for path separators alone is not reliable.
	isKnownB64Prefix := strings.HasPrefix(value, "/9j/") || // JPEG base64
		strings.HasPrefix(value, "iVBOR") || // PNG base64
		strings.HasPrefix(value, "UklGR") || // WebP base64
		strings.HasPrefix(value, "R0lGO")    // GIF base64
	isFallbackB64 := !strings.ContainsAny(value, "/\\") && len(value) > 64 &&
		(filepath.Ext(value) == "" || len(filepath.Ext(value)) > 5)
	if isKnownB64Prefix || isFallbackB64 {
		decoded, err := base64.StdEncoding.DecodeString(value)
		if err == nil {
			return decoded, nil
		}
		// Fall back to RawStdEncoding (no padding)
		decoded, err = base64.RawStdEncoding.DecodeString(value)
		if err == nil {
			return decoded, nil
		}
	}

	// 4. Filesystem path
	return os.ReadFile(value)
}

// deriveFilename returns a sensible filename for the multipart content-disposition.
func deriveFilename(value, fieldName string) string {
	if strings.HasPrefix(value, "http://") || strings.HasPrefix(value, "https://") {
		if u, err := url.Parse(value); err == nil {
			seg := filepath.Base(u.Path)
			if seg != "" && seg != "." && seg != "/" {
				return seg
			}
		}
		return fieldName + ".jpg"
	}
	// Known base64 image prefixes or fallback heuristic → use field name as filename.
	// JPEG base64 starts with /9j/ which contains '/' and would otherwise be treated as a path.
	isKnownB64 := strings.HasPrefix(value, "/9j/") || strings.HasPrefix(value, "iVBOR") ||
		strings.HasPrefix(value, "UklGR") || strings.HasPrefix(value, "R0lGO")
	isFallbackB64 := !strings.ContainsAny(value, "/\\") && len(value) > 64 && filepath.Ext(value) == ""
	if strings.HasPrefix(value, "data:image/") || isKnownB64 || isFallbackB64 {
		return fieldName + ".jpg"
	}
	return filepath.Base(value)
}

// attachBytes writes raw image bytes as a multipart file field.
func attachBytes(mw *multipart.Writer, fieldName, filename string, data []byte) error {
	contentType := mimeTypeFromBytes(data)

	h := make(map[string][]string)
	h["Content-Disposition"] = []string{
		fmt.Sprintf(`form-data; name="%s"; filename="%s"`, fieldName, filename),
	}
	h["Content-Type"] = []string{contentType}

	part, err := mw.CreatePart(h)
	if err != nil {
		return fmt.Errorf("kyvshield: create multipart part for field %q: %w", fieldName, err)
	}
	_, err = part.Write(data)
	return err
}

// mimeTypeFromBytes infers the MIME type from the first bytes of image data.
func mimeTypeFromBytes(data []byte) string {
	if len(data) >= 4 {
		if data[0] == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G' {
			return "image/png"
		}
		if data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F' {
			return "image/webp"
		}
	}
	return "image/jpeg"
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
