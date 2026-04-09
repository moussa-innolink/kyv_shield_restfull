// KyvShield Go SDK — Integration Tests
//
// Tests against the production endpoint using the demo API key.
//
// Run with:
//
//	cd tests/go && go run main.go
package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"os"
	"time"

	kyvshield "github.com/moussa-innolink/kyv_shield_restfull/go"
)

// ─── Configuration ────────────────────────────────────────────────────────────

const (
	apiKey   = "kyvshield_demo_key_2024"
	baseURL  = "https://kyvshield-naruto.innolinkcloud.com"
	rectoImg = "/Users/macbookpro/GolandProjects/cin_verification/api/document_id/SN-CIN/RECTO.jpg"
	versoImg = "/Users/macbookpro/GolandProjects/cin_verification/api/document_id/SN-CIN/VERSO.jpg"
)

// ─── Helpers ──────────────────────────────────────────────────────────────────

var (
	passed int
	failed int
)

func pass(name string) {
	passed++
	fmt.Printf("\033[32m[PASS]\033[0m %s\n", name)
}

func fail(name, reason string) {
	failed++
	fmt.Printf("\033[31m[FAIL]\033[0m %s: %s\n", name, reason)
}

func section(title string) {
	fmt.Printf("\n\033[1m── %s ──\033[0m\n", title)
}

// computeHMACHex computes HMAC-SHA256 and returns the hex-encoded digest.
func computeHMACHex(payload []byte, key string) string {
	mac := hmac.New(sha256.New, []byte(key))
	mac.Write(payload)
	return hex.EncodeToString(mac.Sum(nil))
}

// ─── Main ─────────────────────────────────────────────────────────────────────

func main() {
	ctx := context.Background()

	client := kyvshield.NewClient(apiKey, kyvshield.WithBaseURL(baseURL))

	// ── Test 1: GetChallenges ─────────────────────────────────────────────────

	section("TEST 1: GetChallenges")
	challenges, err := client.GetChallenges(ctx)
	if err != nil {
		fail("GetChallenges", err.Error())
	} else {
		if !challenges.Success {
			fail("GetChallenges success flag", "expected true")
		} else {
			pass("GetChallenges returns success=true")
		}

		if len(challenges.Challenges.Document.Minimal) == 0 {
			fail("document.minimal challenges", "empty slice")
		} else {
			pass(fmt.Sprintf("document.minimal challenges: %v", challenges.Challenges.Document.Minimal))
		}

		if len(challenges.Challenges.Selfie.Standard) == 0 {
			fail("selfie.standard challenges", "empty slice")
		} else {
			pass(fmt.Sprintf("selfie.standard challenges: %v", challenges.Challenges.Selfie.Standard))
		}
	}

	// ── Test 2: Verify recto + verso (file paths) ─────────────────────────────

	section("TEST 2: Verify recto + verso (file paths)")
	opts := &kyvshield.VerifyOptions{
		Steps:         []string{"recto", "verso"},
		Target:        "SN-CIN",
		Language:      "fr",
		ChallengeMode: "minimal",
		StepChallengeModes: map[string]string{
			"recto_challenge_mode": "minimal",
			"verso_challenge_mode": "minimal",
		},
		RequireFaceMatch: false,
		KycIdentifier:    "go-integration-test-001",
		Images: map[string]string{
			"recto_center_document": rectoImg,
			"verso_center_document": versoImg,
		},
	}

	verifyCtx, cancel := context.WithTimeout(ctx, 120*time.Second)
	resp, err := client.Verify(verifyCtx, opts)
	cancel()

	var rectoBytes, versoBytes []byte // populated here, reused in Tests 3-5

	if err != nil {
		fail("Verify recto+verso", err.Error())
	} else {
		if resp.SessionID == "" {
			fail("session_id", "empty")
		} else {
			pass("session_id: " + resp.SessionID)
		}

		if resp.OverallStatus != "pass" && resp.OverallStatus != "reject" {
			fail("overall_status", "unexpected: "+resp.OverallStatus)
		} else {
			pass("overall_status: " + resp.OverallStatus)
		}

		if resp.OverallConfidence < 0 || resp.OverallConfidence > 1 {
			fail("overall_confidence", fmt.Sprintf("out of range: %f", resp.OverallConfidence))
		} else {
			pass(fmt.Sprintf("overall_confidence: %.4f", resp.OverallConfidence))
		}

		if len(resp.Steps) != 2 {
			fail("steps count", fmt.Sprintf("expected 2, got %d", len(resp.Steps)))
		} else {
			pass(fmt.Sprintf("steps array has %d elements", len(resp.Steps)))
		}

		for i, step := range resp.Steps {
			label := fmt.Sprintf("step[%d] (%s)", i, step.StepType)
			if step.Liveness == nil {
				fail(label+" liveness", "nil")
			} else {
				pass(fmt.Sprintf("%s liveness: is_live=%v score=%.4f", label, step.Liveness.IsLive, step.Liveness.Score))
			}
			if step.Verification == nil {
				fail(label+" verification", "nil")
			} else {
				pass(fmt.Sprintf("%s verification: is_authentic=%v", label, step.Verification.IsAuthentic))
			}
		}

		// Print extraction fields with display_priority
		fmt.Println("\n  Extraction fields:")
		for _, step := range resp.Steps {
			for _, field := range step.Extraction {
				fmt.Printf("    [%s][priority=%d] %s (%s) = %s\n",
					step.StepType, field.DisplayPriority, field.Label, field.Key, field.Value)
			}
		}
	}

	// ── Test 3: Verify with raw bytes (ImageBytes map) ────────────────────────

	section("TEST 3: Verify with raw bytes (ImageBytes)")
	rectoBytes, err1 := os.ReadFile(rectoImg)
	versoBytes, err2 := os.ReadFile(versoImg)
	if err1 != nil || err2 != nil {
		fail("read test images", fmt.Sprintf("err1=%v err2=%v", err1, err2))
	} else {
		opts3 := &kyvshield.VerifyOptions{
			Steps:         []string{"recto", "verso"},
			Target:        "SN-CIN",
			Language:      "fr",
			ChallengeMode: "minimal",
			KycIdentifier: "go-integration-test-bytes-001",
			ImageBytes: map[string][]byte{
				"recto_center_document": rectoBytes,
				"verso_center_document": versoBytes,
			},
		}
		bytesCtx, cancel3 := context.WithTimeout(ctx, 120*time.Second)
		resp3, err3 := client.Verify(bytesCtx, opts3)
		cancel3()
		if err3 != nil {
			fail("Verify with ImageBytes", err3.Error())
		} else {
			pass("Verify with ImageBytes: overall_status = " + resp3.OverallStatus)
		}
	}

	// ── Test 4: Verify with base64 strings ────────────────────────────────────

	section("TEST 4: Verify with base64 strings")
	if len(rectoBytes) > 0 && len(versoBytes) > 0 {
		rectoB64 := base64.StdEncoding.EncodeToString(rectoBytes)
		versoB64 := base64.StdEncoding.EncodeToString(versoBytes)
		opts4 := &kyvshield.VerifyOptions{
			Steps:         []string{"recto", "verso"},
			Target:        "SN-CIN",
			Language:      "fr",
			ChallengeMode: "minimal",
			KycIdentifier: "go-integration-test-b64-001",
			Images: map[string]string{
				"recto_center_document": rectoB64,
				"verso_center_document": versoB64,
			},
		}
		b64Ctx, cancel4 := context.WithTimeout(ctx, 120*time.Second)
		resp4, err4 := client.Verify(b64Ctx, opts4)
		cancel4()
		if err4 != nil {
			fail("Verify with base64 strings", err4.Error())
		} else {
			pass("Verify with base64 strings: overall_status = " + resp4.OverallStatus)
		}

		// ── Test 5: Verify with data URI ──────────────────────────────────────

		section("TEST 5: Verify with data URI")
		opts5 := &kyvshield.VerifyOptions{
			Steps:         []string{"recto", "verso"},
			Target:        "SN-CIN",
			Language:      "fr",
			ChallengeMode: "minimal",
			KycIdentifier: "go-integration-test-datauri-001",
			Images: map[string]string{
				"recto_center_document": "data:image/jpeg;base64," + rectoB64,
				"verso_center_document": "data:image/jpeg;base64," + versoB64,
			},
		}
		durCtx, cancel5 := context.WithTimeout(ctx, 120*time.Second)
		resp5, err5 := client.Verify(durCtx, opts5)
		cancel5()
		if err5 != nil {
			fail("Verify with data URI", err5.Error())
		} else {
			pass("Verify with data URI: overall_status = " + resp5.OverallStatus)
		}
	} else {
		fail("Verify with base64 / data URI", "skipped — test images could not be read")
	}

	// ── Test 6: VerifyWebhookSignature ────────────────────────────────────────

	section("TEST 6: VerifyWebhookSignature")
	webhookPayload := []byte(`{"session_id":"test-session","overall_status":"pass"}`)
	expectedSig := computeHMACHex(webhookPayload, apiKey)

	if !kyvshield.VerifyWebhookSignature(webhookPayload, apiKey, expectedSig) {
		fail("VerifyWebhookSignature (bare hex)", "returned false for valid signature")
	} else {
		pass("VerifyWebhookSignature validates bare hex signature")
	}

	if !kyvshield.VerifyWebhookSignature(webhookPayload, apiKey, "sha256="+expectedSig) {
		fail("VerifyWebhookSignature (sha256= prefix)", "returned false for valid signature")
	} else {
		pass("VerifyWebhookSignature validates sha256= prefixed signature")
	}

	if kyvshield.VerifyWebhookSignature(webhookPayload, apiKey, "deadbeef0011223344556677") {
		fail("VerifyWebhookSignature (wrong sig)", "returned true for invalid signature")
	} else {
		pass("VerifyWebhookSignature rejects invalid signature")
	}

	// ── Summary ───────────────────────────────────────────────────────────────

	fmt.Printf("\n\033[1m━━━ Results: %d passed, %d failed ━━━\033[0m\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}
