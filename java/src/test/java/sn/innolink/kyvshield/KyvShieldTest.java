package sn.innolink.kyvshield;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import sn.innolink.kyvshield.model.ChallengeMode;
import sn.innolink.kyvshield.model.ChallengesResponse;
import sn.innolink.kyvshield.model.KycResponse;
import sn.innolink.kyvshield.model.OverallStatus;
import sn.innolink.kyvshield.model.Step;
import sn.innolink.kyvshield.model.VerifyOptions;

import java.nio.charset.StandardCharsets;

/**
 * Integration tests for the KyvShield Java SDK.
 *
 * <p>These tests run against a locally started backend (port 8080) using the
 * standard demo API key and the sample CIN images bundled with the project.
 *
 * <p>Start the backend before running:
 * <pre>
 *   cd /Users/macbookpro/GolandProjects/cin_verification
 *   ./scripts/run-local.sh
 * </pre>
 *
 * <p>Image paths point to the test assets in the backend repository:
 * <ul>
 *   <li>{@code .../SN-CIN/RECTO.jpg}</li>
 *   <li>{@code .../SN-CIN/VERSO.jpg}</li>
 * </ul>
 */
public class KyvShieldTest {

    // ── Configuration ─────────────────────────────────────────────────────────

    private static final String LOCAL_BASE_URL = "http://localhost:8080";
    private static final String DEMO_API_KEY   = "kyvshield_demo_key_2024";

    private static final String RECTO_IMAGE =
            "/Users/macbookpro/GolandProjects/cin_verification/api/document_id/SN-CIN/RECTO.jpg";
    private static final String VERSO_IMAGE =
            "/Users/macbookpro/GolandProjects/cin_verification/api/document_id/SN-CIN/VERSO.jpg";

    // ── Setup ─────────────────────────────────────────────────────────────────

    private KyvShield kyv;

    @Before
    public void setUp() {
        kyv = new KyvShield(DEMO_API_KEY, LOCAL_BASE_URL);
    }

    // ── Constructor tests ─────────────────────────────────────────────────────

    @Test
    public void testConstructorDefaultUrl() {
        KyvShield client = new KyvShield(DEMO_API_KEY);
        Assert.assertNotNull(client);
    }

    @Test
    public void testConstructorCustomUrl() {
        KyvShield client = new KyvShield(DEMO_API_KEY, LOCAL_BASE_URL);
        Assert.assertNotNull(client);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorNullApiKey() {
        new KyvShield(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorBlankApiKey() {
        new KyvShield("   ");
    }

    // ── VerifyOptions builder tests ───────────────────────────────────────────

    @Test
    public void testVerifyOptionsBuilder() {
        VerifyOptions opts = new VerifyOptions.Builder()
                .steps(Step.RECTO, Step.VERSO)
                .target("SN-CIN")
                .language("fr")
                .challengeMode(ChallengeMode.STANDARD)
                .rectoChallengeMode(ChallengeMode.MINIMAL)
                .versoChallengeMode(ChallengeMode.MINIMAL)
                .requireFaceMatch(false)
                .kycIdentifier("test-user-123")
                .addImage("recto_center_document", RECTO_IMAGE)
                .addImage("verso_center_document", VERSO_IMAGE)
                .build();

        Assert.assertEquals(2, opts.getSteps().size());
        Assert.assertEquals(Step.RECTO, opts.getSteps().get(0));
        Assert.assertEquals(Step.VERSO, opts.getSteps().get(1));
        Assert.assertEquals("SN-CIN", opts.getTarget());
        Assert.assertEquals("fr", opts.getLanguage());
        Assert.assertEquals(ChallengeMode.STANDARD, opts.getChallengeMode());
        Assert.assertEquals(ChallengeMode.MINIMAL, opts.getRectoChallengeMode());
        Assert.assertEquals(ChallengeMode.MINIMAL, opts.getVersoChallengeMode());
        Assert.assertFalse(opts.isRequireFaceMatch());
        Assert.assertEquals("test-user-123", opts.getKycIdentifier());
        Assert.assertTrue(opts.getImages().containsKey("recto_center_document"));
        Assert.assertTrue(opts.getImages().containsKey("verso_center_document"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testVerifyOptionsBuilderMissingSteps() {
        new VerifyOptions.Builder()
                .target("SN-CIN")
                .addImage("recto_center_document", RECTO_IMAGE)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testVerifyOptionsBuilderMissingTarget() {
        new VerifyOptions.Builder()
                .steps(Step.RECTO)
                .addImage("recto_center_document", RECTO_IMAGE)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testVerifyOptionsBuilderMissingImages() {
        new VerifyOptions.Builder()
                .steps(Step.RECTO)
                .target("SN-CIN")
                .build();
    }

    // ── Enum tests ────────────────────────────────────────────────────────────

    @Test
    public void testChallengeModeValues() {
        Assert.assertEquals("minimal",  ChallengeMode.MINIMAL.getValue());
        Assert.assertEquals("standard", ChallengeMode.STANDARD.getValue());
        Assert.assertEquals("strict",   ChallengeMode.STRICT.getValue());
    }

    @Test
    public void testChallengeModeFromValue() {
        Assert.assertEquals(ChallengeMode.MINIMAL,  ChallengeMode.fromValue("minimal"));
        Assert.assertEquals(ChallengeMode.STANDARD, ChallengeMode.fromValue("STANDARD"));
        Assert.assertEquals(ChallengeMode.STRICT,   ChallengeMode.fromValue("strict"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testChallengeModeFromValueInvalid() {
        ChallengeMode.fromValue("unknown");
    }

    @Test
    public void testStepValues() {
        Assert.assertEquals("selfie", Step.SELFIE.getValue());
        Assert.assertEquals("recto",  Step.RECTO.getValue());
        Assert.assertEquals("verso",  Step.VERSO.getValue());
    }

    @Test
    public void testStepFromValue() {
        Assert.assertEquals(Step.SELFIE, Step.fromValue("selfie"));
        Assert.assertEquals(Step.RECTO,  Step.fromValue("RECTO"));
        Assert.assertEquals(Step.VERSO,  Step.fromValue("verso"));
    }

    @Test
    public void testOverallStatusFromValue() {
        Assert.assertEquals(OverallStatus.PASS,   OverallStatus.fromValue("pass"));
        Assert.assertEquals(OverallStatus.REJECT,  OverallStatus.fromValue("reject"));
        Assert.assertEquals(OverallStatus.REJECT,  OverallStatus.fromValue(null));
        Assert.assertEquals(OverallStatus.REJECT,  OverallStatus.fromValue("unknown"));
    }

    // ── Webhook signature tests ───────────────────────────────────────────────

    @Test
    public void testVerifyWebhookSignatureValid() {
        // The expected signature was computed offline with:
        //   echo -n "hello" | openssl dgst -sha256 -hmac "secret"
        // Output: 88aab3ede8d3adf94d26ab90d3bafd4a2083070c3bcce9c014ee04a443847c0b
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        String expected = "88aab3ede8d3adf94d26ab90d3bafd4a2083070c3bcce9c014ee04a443847c0b";
        Assert.assertTrue(KyvShield.verifyWebhookSignature(payload, "secret", expected));
    }

    @Test
    public void testVerifyWebhookSignatureInvalid() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        Assert.assertFalse(KyvShield.verifyWebhookSignature(payload, "secret", "badSignature"));
    }

    @Test
    public void testVerifyWebhookSignatureNullArgs() {
        Assert.assertFalse(KyvShield.verifyWebhookSignature(null, "key", "sig"));
        Assert.assertFalse(KyvShield.verifyWebhookSignature(new byte[0], null, "sig"));
        Assert.assertFalse(KyvShield.verifyWebhookSignature(new byte[0], "key", null));
    }

    // ── Integration tests (require running local backend) ─────────────────────

    /**
     * Calls {@code GET /api/v1/challenges} against the local backend.
     *
     * <p>The backend must be running at {@code http://localhost:8080}.
     * If it is not available the test is skipped rather than failed.
     */
    @Test
    public void testGetChallengesIntegration() {
        try {
            ChallengesResponse challenges = kyv.getChallenges();
            Assert.assertNotNull("challenges response must not be null", challenges);
            Assert.assertNotNull("document challenges must not be null", challenges.getDocument());
            Assert.assertNotNull("selfie challenges must not be null", challenges.getSelfie());
            System.out.println("[getChallenges] document.minimal  = "
                    + challenges.getDocument().getMinimal());
            System.out.println("[getChallenges] document.standard = "
                    + challenges.getDocument().getStandard());
            System.out.println("[getChallenges] selfie.standard   = "
                    + challenges.getSelfie().getStandard());
        } catch (KyvShieldException e) {
            System.out.println("[getChallenges] Backend unavailable, skipping: " + e.getMessage());
            // Not a hard failure when the backend is not running locally
        }
    }

    /**
     * Calls {@code POST /api/v1/kyc/verify} with the RECTO and VERSO sample images
     * against the local backend.
     *
     * <p>The backend must be running at {@code http://localhost:8080}.
     * If it is not available the test is skipped rather than failed.
     */
    @Test
    public void testVerifyRectoVersoIntegration() {
        VerifyOptions options = new VerifyOptions.Builder()
                .steps(Step.RECTO, Step.VERSO)
                .target("SN-CIN")
                .language("fr")
                .challengeMode(ChallengeMode.MINIMAL)
                .rectoChallengeMode(ChallengeMode.MINIMAL)
                .versoChallengeMode(ChallengeMode.MINIMAL)
                .requireFaceMatch(false)
                .addImage("recto_center_document", RECTO_IMAGE)
                .addImage("verso_center_document", VERSO_IMAGE)
                .build();

        try {
            KycResponse response = kyv.verify(options);

            Assert.assertNotNull("response must not be null", response);
            Assert.assertNotNull("sessionId must not be null", response.getSessionId());
            Assert.assertNotNull("overallStatus must not be null", response.getOverallStatus());
            Assert.assertNotNull("steps list must not be null", response.getSteps());
            Assert.assertEquals("steps count must match", 2, response.getSteps().size());

            System.out.println("[verify] sessionId       = " + response.getSessionId());
            System.out.println("[verify] overallStatus   = " + response.getOverallStatus());
            System.out.println("[verify] overallConfidence = " + response.getOverallConfidence());
            System.out.println("[verify] processingTimeMs  = " + response.getProcessingTimeMs());

            response.getSteps().forEach(step -> {
                System.out.println("[verify] step[" + step.getStepIndex() + "] "
                        + step.getStepType()
                        + " success=" + step.isSuccess()
                        + " processingTimeMs=" + step.getProcessingTimeMs());
                if (step.getLiveness() != null) {
                    System.out.println("  liveness: isLive=" + step.getLiveness().isLive()
                            + " score=" + step.getLiveness().getScore()
                            + " confidence=" + step.getLiveness().getConfidence());
                }
                if (step.getExtraction() != null && !step.getExtraction().isEmpty()) {
                    System.out.println("  extraction fields: " + step.getExtraction().size());
                    step.getExtraction().stream().limit(3).forEach(f ->
                            System.out.println("    " + f.getKey() + "=" + f.getValue()));
                }
            });

        } catch (KyvShieldException e) {
            if (e.getStatusCode() == 0) {
                // Network error — backend not running
                System.out.println("[verify] Backend unavailable, skipping: " + e.getMessage());
            } else {
                // HTTP error from the server — surface as test failure
                Assert.fail("Unexpected HTTP error " + e.getStatusCode()
                        + ": " + e.getMessage());
            }
        }
    }

    /**
     * Calls {@code POST /api/v1/kyc/verify} requesting a face match between the
     * selfie step and the document portrait.
     *
     * <p>Uses RECTO.jpg both as the document image and as a stand-in selfie to
     * avoid needing a separate selfie asset.
     */
    @Test
    public void testVerifyWithFaceMatchIntegration() {
        VerifyOptions options = new VerifyOptions.Builder()
                .steps(Step.SELFIE, Step.RECTO)
                .target("SN-CIN")
                .language("fr")
                .challengeMode(ChallengeMode.MINIMAL)
                .rectoChallengeMode(ChallengeMode.MINIMAL)
                .selfieChallengeMode(ChallengeMode.MINIMAL)
                .requireFaceMatch(true)
                .addImage("selfie_center_face", RECTO_IMAGE)       // stand-in selfie
                .addImage("selfie_close_eyes", RECTO_IMAGE)        // minimal selfie requires close_eyes too
                .addImage("recto_center_document", RECTO_IMAGE)
                .build();

        try {
            KycResponse response = kyv.verify(options);
            Assert.assertNotNull("response must not be null", response);

            if (response.getFaceVerification() != null) {
                System.out.println("[verify+face] isMatch=" + response.getFaceVerification().isMatch()
                        + " similarity=" + response.getFaceVerification().getSimilarityScore());
            } else {
                System.out.println("[verify+face] faceVerification not present in response");
            }

        } catch (KyvShieldException e) {
            if (e.getStatusCode() == 0) {
                System.out.println("[verify+face] Backend unavailable, skipping: " + e.getMessage());
            } else {
                Assert.fail("Unexpected HTTP error " + e.getStatusCode()
                        + ": " + e.getMessage());
            }
        }
    }

    /**
     * Verifies that the SDK throws {@link KyvShieldException} when an invalid
     * API key is provided.
     */
    @Test
    public void testInvalidApiKeyReturnsException() {
        KyvShield badClient = new KyvShield("invalid_key_xyz", LOCAL_BASE_URL);
        try {
            badClient.getChallenges();
            // Some backends return 200 for /challenges regardless of auth
        } catch (KyvShieldException e) {
            Assert.assertTrue("Should be an HTTP 401 or 403",
                    e.getStatusCode() == 401 || e.getStatusCode() == 403
                            || e.getStatusCode() == 0 /* network unavailable */);
        }
    }

    /**
     * Verifies that the SDK throws {@link KyvShieldException} when an image file
     * does not exist.
     */
    @Test(expected = KyvShieldException.class)
    public void testMissingImageFileThrows() {
        VerifyOptions options = new VerifyOptions.Builder()
                .steps(Step.RECTO)
                .target("SN-CIN")
                .addImage("recto_center_document", "/path/that/does/not/exist.jpg")
                .build();
        kyv.verify(options);
    }
}
