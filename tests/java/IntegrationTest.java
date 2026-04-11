/**
 * KyvShield Java SDK — Integration Tests
 *
 * Tests against the production endpoint using the demo API key.
 *
 * Compile and run (from the project root):
 *
 *   # 1. Build the SDK first (produces java/build/libs/java-1.0.0.jar)
 *   ./gradlew :java:jar
 *
 *   # 2. Find the org.json jar bundled in Gradle's cache
 *   JSON_JAR=$(find ~/.gradle -name "json-*.jar" | grep -v sources | head -1)
 *
 *   # 3. Compile the test
 *   javac -cp java/build/libs/java-1.0.0.jar:$JSON_JAR \
 *         -d tests/java tests/java/IntegrationTest.java
 *
 *   # 4. Run
 *   java -cp tests/java:java/build/libs/java-1.0.0.jar:$JSON_JAR IntegrationTest
 */

import sn.innolink.kyvshield.KyvShield;
import sn.innolink.kyvshield.KyvShieldException;
import sn.innolink.kyvshield.model.BatchResult;
import sn.innolink.kyvshield.model.ChallengeMode;
import sn.innolink.kyvshield.model.ChallengesResponse;
import sn.innolink.kyvshield.model.KycResponse;
import sn.innolink.kyvshield.model.Step;
import sn.innolink.kyvshield.model.StepResult;
import sn.innolink.kyvshield.model.ExtractionField;
import sn.innolink.kyvshield.model.AMLScreening;
import sn.innolink.kyvshield.model.AMLMatch;
import sn.innolink.kyvshield.model.VerifyOptions;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;

public class IntegrationTest {

    // ─── Configuration ────────────────────────────────────────────────────────

    private static final String API_KEY   = "kyvshield_demo_key_2024";
    private static final String BASE_URL  = "https://kyvshield-naruto.innolinkcloud.com";
    private static final String RECTO_IMG = "/Users/macbookpro/GolandProjects/cin_verification/api/document_id/SN-CIN/RECTO.jpg";
    private static final String VERSO_IMG = "/Users/macbookpro/GolandProjects/cin_verification/api/document_id/SN-CIN/VERSO.jpg";

    // ─── Result counters ──────────────────────────────────────────────────────

    private static int passed = 0;
    private static int failed = 0;

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static void pass(String name) {
        passed++;
        System.out.println("\033[32m[PASS]\033[0m " + name);
    }

    private static void fail(String name, String reason) {
        failed++;
        System.out.println("\033[31m[FAIL]\033[0m " + name + ": " + reason);
    }

    private static void section(String title) {
        System.out.println("\n\033[1m── " + title + " ──\033[0m");
    }

    private static String hmacSha256Hex(byte[] payload, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(payload);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ─── Main ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        KyvShield client = new KyvShield(API_KEY, BASE_URL);

        // ── Test 1: getChallenges ──────────────────────────────────────────────

        section("TEST 1: getChallenges");
        try {
            ChallengesResponse challenges = client.getChallenges();

            // The Java model exposes getDocument() and getSelfie() ChallengeModeMap objects
            if (challenges.getDocument() == null) {
                fail("getChallenges document challenges", "null");
            } else {
                pass("getChallenges returned a challenges object");
            }

            List<String> docMinimal = challenges.getDocument().getMinimal();
            if (docMinimal.isEmpty()) {
                fail("document.minimal challenges", "empty list");
            } else {
                pass("document.minimal challenges: " + docMinimal);
            }

            List<String> selfieStandard = challenges.getSelfie().getStandard();
            if (selfieStandard.isEmpty()) {
                fail("selfie.standard challenges", "empty list");
            } else {
                pass("selfie.standard challenges: " + selfieStandard);
            }
        } catch (KyvShieldException e) {
            fail("getChallenges", e.getMessage());
        }

        // ── Test 2: verify recto + verso (file paths) ──────────────────────────

        section("TEST 2: verify recto + verso (file paths)");
        try {
            VerifyOptions options = new VerifyOptions.Builder()
                    .steps(Step.RECTO, Step.VERSO)
                    .target("SN-CIN")
                    .language("fr")
                    .challengeMode(ChallengeMode.MINIMAL)
                    .rectoChallengeMode(ChallengeMode.MINIMAL)
                    .versoChallengeMode(ChallengeMode.MINIMAL)
                    .requireFaceMatch(false)
                    .kycIdentifier("java-integration-test-001")
                    .addImage("recto_center_document", RECTO_IMG)
                    .addImage("verso_center_document", VERSO_IMG)
                    .build();

            KycResponse resp = client.verify(options);

            if (resp.getSessionId() == null || resp.getSessionId().isEmpty()) {
                fail("session_id", "empty");
            } else {
                pass("session_id: " + resp.getSessionId());
            }

            String status = resp.getOverallStatus().getValue();
            if (!status.equals("pass") && !status.equals("reject")) {
                fail("overall_status", "unexpected: " + status);
            } else {
                pass("overall_status: " + status);
            }

            if (resp.getOverallConfidence() < 0.0 || resp.getOverallConfidence() > 1.0) {
                fail("overall_confidence", "out of range: " + resp.getOverallConfidence());
            } else {
                pass(String.format("overall_confidence: %.4f", resp.getOverallConfidence()));
            }

            if (resp.getSteps().size() != 2) {
                fail("steps count", "expected 2, got " + resp.getSteps().size());
            } else {
                pass("steps array has " + resp.getSteps().size() + " elements");
            }

            for (int i = 0; i < resp.getSteps().size(); i++) {
                StepResult step = resp.getSteps().get(i);
                String label = "step[" + i + "] (" + step.getStepType().getValue() + ")";
                if (step.getLiveness() == null) {
                    fail(label + " liveness", "null");
                } else {
                    pass(label + " liveness: is_live=" + step.getLiveness().isLive()
                            + " score=" + String.format("%.4f", step.getLiveness().getScore()));
                }
                if (step.getVerification() == null) {
                    fail(label + " verification", "null");
                } else {
                    pass(label + " verification: is_authentic=" + step.getVerification().isAuthentic());
                }
            }

            // Print extraction fields
            System.out.println("\n  Extraction fields (sorted by display_priority):");
            for (StepResult step : resp.getSteps()) {
                if (step.getExtraction() != null) {
                    step.getExtraction().stream()
                            .sorted((a, b) -> Integer.compare(a.getDisplayPriority(), b.getDisplayPriority()))
                            .forEach(field -> System.out.printf(
                                    "    [%s][priority=%d] %s (%s) = %s%n",
                                    step.getStepType().getValue(),
                                    field.getDisplayPriority(),
                                    field.getLabel(),
                                    field.getKey(),
                                    field.getValue()));
                }
            }

            // AML Screening
            if (resp.getAmlScreening() != null) {
                AMLScreening aml = resp.getAmlScreening();
                System.out.println("\n  AML Screening:");
                System.out.println("    performed: " + aml.isPerformed());
                System.out.println("    status: " + aml.getStatus());
                System.out.println("    risk_level: " + aml.getRiskLevel());
                System.out.println("    total_matches: " + aml.getTotalMatches());
                System.out.println("    duration_ms: " + aml.getDurationMs());
                for (AMLMatch m : aml.getMatches()) {
                    System.out.printf("      match: %s (score=%.2f, datasets=%s, topics=%s)%n",
                            m.getName(), m.getScore(), m.getDatasets(), m.getTopics());
                }
            }
        } catch (KyvShieldException e) {
            fail("verify recto+verso", e.getMessage());
        }

        // ── Test 3: verify with raw bytes (imageBytes map) ────────────────────

        section("TEST 3: verify with raw bytes (imageBytes)");
        try {
            byte[] rectoBytes = Files.readAllBytes(Paths.get(RECTO_IMG));
            byte[] versoBytes = Files.readAllBytes(Paths.get(VERSO_IMG));

            VerifyOptions options3 = new VerifyOptions.Builder()
                    .steps(Step.RECTO, Step.VERSO)
                    .target("SN-CIN")
                    .language("fr")
                    .challengeMode(ChallengeMode.MINIMAL)
                    .kycIdentifier("java-integration-test-bytes-001")
                    .addImageBytes("recto_center_document", rectoBytes)
                    .addImageBytes("verso_center_document", versoBytes)
                    .build();

            KycResponse resp3 = client.verify(options3);
            pass("verify with imageBytes: overall_status = " + resp3.getOverallStatus().getValue());
        } catch (Exception e) {
            fail("verify with imageBytes", e.getMessage());
        }

        // ── Test 4: verify with base64 strings ────────────────────────────────

        section("TEST 4: verify with base64 strings");
        try {
            byte[] rectoBytes = Files.readAllBytes(Paths.get(RECTO_IMG));
            byte[] versoBytes = Files.readAllBytes(Paths.get(VERSO_IMG));
            String rectoB64 = Base64.getEncoder().encodeToString(rectoBytes);
            String versoB64 = Base64.getEncoder().encodeToString(versoBytes);

            VerifyOptions options4 = new VerifyOptions.Builder()
                    .steps(Step.RECTO, Step.VERSO)
                    .target("SN-CIN")
                    .language("fr")
                    .challengeMode(ChallengeMode.MINIMAL)
                    .kycIdentifier("java-integration-test-b64-001")
                    .addImage("recto_center_document", rectoB64)
                    .addImage("verso_center_document", versoB64)
                    .build();

            KycResponse resp4 = client.verify(options4);
            pass("verify with base64 strings: overall_status = " + resp4.getOverallStatus().getValue());
        } catch (Exception e) {
            fail("verify with base64 strings", e.getMessage());
        }

        // ── Test 5: verify with data URI ──────────────────────────────────────

        section("TEST 5: verify with data URI");
        try {
            byte[] rectoBytes = Files.readAllBytes(Paths.get(RECTO_IMG));
            byte[] versoBytes = Files.readAllBytes(Paths.get(VERSO_IMG));
            String rectoB64 = Base64.getEncoder().encodeToString(rectoBytes);
            String versoB64 = Base64.getEncoder().encodeToString(versoBytes);

            VerifyOptions options5 = new VerifyOptions.Builder()
                    .steps(Step.RECTO, Step.VERSO)
                    .target("SN-CIN")
                    .language("fr")
                    .challengeMode(ChallengeMode.MINIMAL)
                    .kycIdentifier("java-integration-test-datauri-001")
                    .addImage("recto_center_document", "data:image/jpeg;base64," + rectoB64)
                    .addImage("verso_center_document", "data:image/jpeg;base64," + versoB64)
                    .build();

            KycResponse resp5 = client.verify(options5);
            pass("verify with data URI: overall_status = " + resp5.getOverallStatus().getValue());
        } catch (Exception e) {
            fail("verify with data URI", e.getMessage());
        }

        // ── Test 6: verifyWebhookSignature ────────────────────────────────────

        section("TEST 6: verifyWebhookSignature");
        byte[] webhookPayload = "{\"session_id\":\"test-session\",\"overall_status\":\"pass\"}"
                .getBytes(StandardCharsets.UTF_8);
        String expectedSig = hmacSha256Hex(webhookPayload, API_KEY);

        if (!KyvShield.verifyWebhookSignature(webhookPayload, API_KEY, expectedSig)) {
            fail("verifyWebhookSignature (bare hex)", "returned false for valid signature");
        } else {
            pass("verifyWebhookSignature validates bare hex signature");
        }

        if (!KyvShield.verifyWebhookSignature(webhookPayload, API_KEY, "sha256=" + expectedSig)) {
            fail("verifyWebhookSignature (sha256= prefix)", "returned false for valid signature");
        } else {
            pass("verifyWebhookSignature validates sha256= prefixed signature");
        }

        if (KyvShield.verifyWebhookSignature(webhookPayload, API_KEY, "deadbeef00112233")) {
            fail("verifyWebhookSignature (wrong sig)", "returned true for invalid signature");
        } else {
            pass("verifyWebhookSignature rejects invalid signature");
        }

        // ── Summary ───────────────────────────────────────────────────────────

        System.out.printf("%n\033[1m━━━ Results: %d passed, %d failed ━━━\033[0m%n", passed, failed);
        if (failed > 0) System.exit(1);
    }
}
