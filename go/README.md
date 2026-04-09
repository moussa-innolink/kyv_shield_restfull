# KyvShield Go SDK

A fully-typed, zero-dependency Go client for the [KyvShield](https://kyvshield-naruto.innolinkcloud.com) REST KYC API.

- Pure Go standard library (`net/http`, `mime/multipart`, `encoding/json`, `crypto/hmac`)
- No external dependencies
- Full `context.Context` support on every network call
- Structured error types
- Option pattern for client configuration
- 19 unit tests that run entirely offline via `httptest`

---

## Requirements

- Go 1.21 or later

---

## Installation

```bash
go get github.com/moussa-innolink/kyv_shield_restfull/go
```

---

## Quick Start

```go
package main

import (
    "context"
    "fmt"
    "log"

    kyvshield "github.com/moussa-innolink/kyv_shield_restfull/go"
)

func main() {
    client := kyvshield.NewClient("your-api-key")

    // 1. Fetch available challenges
    challenges, err := client.GetChallenges(context.Background())
    if err != nil {
        log.Fatal(err)
    }
    fmt.Println("minimal document challenges:", challenges.Challenges.Document.Minimal)

    // 2. Run a KYC verification
    resp, err := client.Verify(context.Background(), &kyvshield.VerifyOptions{
        Steps:         []string{"recto", "verso"},
        Target:        string(kyvshield.DocumentTargetCIN),
        Language:      string(kyvshield.LanguageFrench),
        ChallengeMode: string(kyvshield.ChallengeModeStandard),
        Images: map[string]string{
            "recto_center_document": "/path/to/recto.jpg",
            "verso_center_document": "/path/to/verso.jpg",
        },
    })
    if err != nil {
        log.Fatal(err)
    }

    fmt.Println("status:", resp.OverallStatus)
    fmt.Printf("confidence: %.2f\n", resp.OverallConfidence)
    for _, step := range resp.Steps {
        fmt.Printf("[%s] live=%v authentic=%v fields=%d\n",
            step.StepType,
            step.Liveness.IsLive,
            step.Verification.IsAuthentic,
            len(step.Extraction),
        )
    }
}
```

---

## API Reference

### `NewClient(apiKey string, opts ...Option) *Client`

Creates a new client. All options are applied in order.

```go
client := kyvshield.NewClient("your-api-key")

// Custom base URL (local dev / staging)
client := kyvshield.NewClient("key", kyvshield.WithBaseURL("http://localhost:8080"))

// Custom timeout (default: 120 s)
client := kyvshield.NewClient("key", kyvshield.WithTimeout(60*time.Second))

// Bring your own http.Client
client := kyvshield.NewClient("key", kyvshield.WithHTTPClient(myClient))
```

### `GetChallenges(ctx context.Context) (*ChallengesResponse, error)`

`GET /api/v1/challenges` — returns the challenge names grouped by step type and intensity.

### `Verify(ctx context.Context, opts *VerifyOptions) (*KycResponse, error)`

`POST /api/v1/kyc/verify` — submits images and options for a full KYC run.

### `VerifyBatch(ctx context.Context, optsList []*VerifyOptions) ([]BatchResult, error)`

Runs up to 10 KYC verifications concurrently. Results are returned in the same order as the input slice. Images within each verification are also compressed in parallel (up to `DefaultMaxConcurrentCompress = 20` goroutines).

```go
batch := []*kyvshield.VerifyOptions{
    {
        Steps:         []string{"recto", "verso"},
        Target:        "SN-CIN",
        KycIdentifier: "user-001",
        Images: map[string]string{
            "recto_center_document": "/path/to/user1_recto.jpg",
            "verso_center_document": "/path/to/user1_verso.jpg",
        },
    },
    {
        Steps:         []string{"selfie", "recto"},
        Target:        "SN-CIN",
        KycIdentifier: "user-002",
        Images: map[string]string{
            "selfie_center_face":    "/path/to/user2_selfie.jpg",
            "recto_center_document": "/path/to/user2_recto.jpg",
        },
    },
}

results, err := client.VerifyBatch(context.Background(), batch)
if err != nil {
    log.Fatal(err)
}
for i, r := range results {
    if r.Success {
        fmt.Printf("[%d] %s — confidence %.2f\n", i, r.Result.OverallStatus, r.Result.OverallConfidence)
    } else {
        fmt.Printf("[%d] ERROR: %v\n", i, r.Error)
    }
}
```

### `VerifyWebhookSignature(payload []byte, apiKey, signatureHeader string) bool`

Validates an incoming webhook callback signed with HMAC-SHA256.

```go
http.HandleFunc("/webhook", func(w http.ResponseWriter, r *http.Request) {
    body, _ := io.ReadAll(r.Body)
    if !kyvshield.VerifyWebhookSignature(body, apiKey, r.Header.Get("X-KyvShield-Signature")) {
        http.Error(w, "forbidden", http.StatusForbidden)
        return
    }
    // Process body ...
})
```

---

## VerifyOptions

| Field | Type | Description |
|---|---|---|
| `Steps` | `[]string` | Ordered steps: `"selfie"`, `"recto"`, `"verso"` |
| `Target` | `string` | Document type: `"SN-CIN"`, `"SN-PASSPORT"`, `"SN-DRIVER-LICENCE"` |
| `Language` | `string` | Response language: `"fr"`, `"en"`, `"wo"` (default `"fr"`) |
| `ChallengeMode` | `string` | Global challenge intensity: `"minimal"`, `"standard"`, `"strict"` |
| `StepChallengeModes` | `map[string]string` | Per-step overrides, e.g. `{"recto_challenge_mode": "strict"}` |
| `RequireFaceMatch` | `bool` | Cross-step face match between selfie and document photo |
| `KycIdentifier` | `string` | Optional caller-provided correlation ID |
| `Images` | `map[string]string` | Image map — values can be file paths, URLs, data URIs, or base64 strings |
| `ImageBytes` | `map[string][]byte` | Image map for raw bytes already in memory |

**Image input formats** — each value in `Images` is resolved in this order:

| Priority | Format | Example |
|----------|--------|---------|
| 1 | **URL** (`http://`/`https://`) | `"https://cdn.example.com/recto.jpg"` |
| 2 | **Data URI** (`data:image/…;base64,…`) | `"data:image/jpeg;base64,/9j/4AA…"` |
| 3 | **Base64 string** (long, no path separator) | `base64.StdEncoding.EncodeToString(data)` |
| 4 | **File path** (default) | `"/path/to/recto.jpg"` |

For raw bytes already in memory, use `ImageBytes` instead:

```go
// File paths (original behaviour — unchanged)
Images: map[string]string{
    "recto_center_document": "/path/to/recto.jpg",
},

// Raw bytes in memory
ImageBytes: map[string][]byte{
    "recto_center_document": rectoBytes,
},

// URL — downloaded automatically
Images: map[string]string{
    "recto_center_document": "https://cdn.example.com/recto.jpg",
},

// Data URI
Images: map[string]string{
    "recto_center_document": "data:image/jpeg;base64," + base64Str,
},

// Bare base64 string
Images: map[string]string{
    "recto_center_document": base64.StdEncoding.EncodeToString(rectoBytes),
},
```

**Image field names** follow the pattern `{step}_{challenge}`:

```
recto_center_document
recto_tilt_left
recto_tilt_right
verso_center_document
selfie_center_face
selfie_turn_left
```

---

## Error Handling

Network and HTTP errors are returned as standard Go `error` values. HTTP errors from
the API are wrapped in `*kyvshield.APIError` which exposes the HTTP status code and the
raw response body:

```go
resp, err := client.Verify(ctx, opts)
if err != nil {
    var apiErr *kyvshield.APIError
    if errors.As(err, &apiErr) {
        fmt.Println("HTTP status:", apiErr.StatusCode)
        fmt.Println("Body:", string(apiErr.Body))
    }
    log.Fatal(err)
}
```

---

## Running Tests

```bash
# Unit tests only (no server required)
go test ./...

# Verbose output
go test -v ./...

# Integration test against a running local backend
# Start the backend first: bash scripts/run-local.sh (from the cin_verification repo)
go test -v -run TestIntegration_Localhost ./...
```

---

## Supported Document Types

| Constant | Value |
|---|---|
| `DocumentTargetCIN` | `SN-CIN` |
| `DocumentTargetPassport` | `SN-PASSPORT` |
| `DocumentTargetDriverLicence` | `SN-DRIVER-LICENCE` |

## Challenge Modes

| Constant | Value | Description |
|---|---|---|
| `ChallengeModeMinimal` | `minimal` | Single center image |
| `ChallengeModeStandard` | `standard` | Center + two tilts |
| `ChallengeModeStrict` | `strict` | Five angles |

## Languages

| Constant | Value |
|---|---|
| `LanguageFrench` | `fr` |
| `LanguageEnglish` | `en` |
| `LanguageWolof` | `wo` |
