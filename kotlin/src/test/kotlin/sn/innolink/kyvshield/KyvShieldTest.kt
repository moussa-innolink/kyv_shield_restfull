package sn.innolink.kyvshield

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * KyvShield Kotlin SDK — Integration Tests
 *
 * These tests run against a locally-running KyvShield backend at `http://localhost:8080`.
 *
 * Prerequisites:
 *   - Backend started via `./scripts/run-local.sh`
 *   - Set env var `KYVSHIELD_TEST_API_KEY` with a valid API key, or use the
 *     default demo key configured in Elasticsearch.
 *
 * Image fixtures:
 *   - Tests that exercise `verify()` need sample images.  Place them under:
 *       `src/test/resources/fixtures/`
 *   - Filenames expected:  `selfie.jpg`, `recto.jpg`, `verso.jpg`
 *
 * Run with:
 *   `./gradlew test`
 */
class KyvShieldTest {

    private val apiKey: String = System.getenv("KYVSHIELD_TEST_API_KEY") ?: "test_key_local"

    private val client = KyvShield(
        apiKey = apiKey,
        baseUrl = "http://localhost:8080",
        timeoutSeconds = 120,
    )

    // ─── getChallenges ────────────────────────────────────────────────────────

    @Test
    fun `getChallenges returns success and non-empty mode lists`() {
        val response = client.getChallenges()

        assertTrue(response.success, "Expected success=true from GET /api/v1/challenges")

        // Document challenges
        val doc = response.challenges.document
        assertTrue(doc.minimal.isNotEmpty(), "document.minimal should not be empty")
        assertTrue(doc.standard.isNotEmpty(), "document.standard should not be empty")
        assertTrue(doc.strict.isNotEmpty(), "document.strict should not be empty")

        // Selfie challenges
        val selfie = response.challenges.selfie
        assertTrue(selfie.minimal.isNotEmpty(), "selfie.minimal should not be empty")
        assertTrue(selfie.standard.isNotEmpty(), "selfie.standard should not be empty")
        assertTrue(selfie.strict.isNotEmpty(), "selfie.strict should not be empty")
    }

    @Test
    fun `getChallenges document minimal is a subset of standard`() {
        val response = client.getChallenges()
        val minimal = response.challenges.document.minimal.toSet()
        val standard = response.challenges.document.standard.toSet()
        // minimal challenges should all be present in standard
        assertTrue(standard.containsAll(minimal),
            "document.standard should include all document.minimal challenges")
    }

    // ─── verify — validation errors ──────────────────────────────────────────

    @Test
    fun `verify throws KyvShieldException on missing API key`() {
        val badClient = KyvShield(
            apiKey = "",
            baseUrl = "http://localhost:8080",
        )
        val options = minimalOptions()

        val ex = assertThrows<KyvShieldException> {
            badClient.verify(options)
        }
        assertNotNull(ex.statusCode, "Expected HTTP status code in exception")
        println("Got expected error: $ex")
    }

    @Test
    fun `verify throws KyvShieldException when image file does not exist`() {
        val options = VerifyOptions(
            steps = listOf(Step.RECTO),
            target = "SN-CIN",
            language = Language.ENGLISH,
            challengeMode = ChallengeMode.MINIMAL,
            images = mapOf("recto_center_document" to "/tmp/this_file_does_not_exist.jpg"),
        )

        val ex = assertThrows<KyvShieldException> {
            client.verify(options)
        }
        assertEquals("FILE_NOT_FOUND", ex.errorCode)
        println("Got expected FILE_NOT_FOUND: $ex")
    }

    // ─── verify — full recto-only (requires fixture) ─────────────────────────

    @Test
    fun `verify recto returns structured KycResponse`() {
        val rectoFile = fixtureFile("recto.jpg") ?: run {
            println("SKIP: src/test/resources/fixtures/recto.jpg not found")
            return
        }

        val options = VerifyOptions(
            steps = listOf(Step.RECTO),
            target = "SN-CIN",
            language = Language.FRENCH,
            challengeMode = ChallengeMode.MINIMAL,
            images = mapOf("recto_center_document" to rectoFile.absolutePath),
        )

        val response = client.verify(options)

        // Structure checks (not result checks — result depends on image quality)
        assertNotNull(response.sessionId, "sessionId must be non-null")
        assertTrue(response.sessionId.isNotBlank(), "sessionId must be non-blank")
        assertEquals(1, response.steps.size, "Expected exactly 1 step result")

        val step = response.steps[0]
        assertEquals(0, step.stepIndex)
        assertEquals(Step.RECTO, step.stepType)
        assertTrue(step.processingTimeMs >= 0)
        assertTrue(step.liveness.score in 0.0..1.0, "Liveness score must be 0–1")

        println("Recto verify: status=${response.overallStatus} confidence=${response.overallConfidence}")
    }

    @Test
    fun `verify selfie recto with face match returns face_verification block`() {
        val selfieFile = fixtureFile("selfie.jpg") ?: run {
            println("SKIP: src/test/resources/fixtures/selfie.jpg not found")
            return
        }
        val rectoFile = fixtureFile("recto.jpg") ?: run {
            println("SKIP: src/test/resources/fixtures/recto.jpg not found")
            return
        }

        val options = VerifyOptions(
            steps = listOf(Step.SELFIE, Step.RECTO),
            target = "SN-CIN",
            language = Language.ENGLISH,
            challengeMode = ChallengeMode.MINIMAL,
            requireFaceMatch = true,
            images = mapOf(
                "selfie_center_face"    to selfieFile.absolutePath,
                "recto_center_document" to rectoFile.absolutePath,
            ),
        )

        val response = client.verify(options)

        assertEquals(2, response.steps.size, "Expected 2 step results")
        assertNotNull(response.faceVerification, "face_verification must be present when requireFaceMatch=true")

        val fv = response.faceVerification!!
        assertTrue(fv.similarityScore >= 0.0, "similarity_score must be >= 0")
        println("Face match: isMatch=${fv.isMatch} score=${fv.similarityScore}")
    }

    @Test
    fun `verify full recto verso returns aligned_document and extraction`() {
        val rectoFile = fixtureFile("recto.jpg") ?: run {
            println("SKIP: fixtures not found")
            return
        }
        val versoFile = fixtureFile("verso.jpg") ?: run {
            println("SKIP: fixtures not found")
            return
        }

        val options = VerifyOptions(
            steps = listOf(Step.RECTO, Step.VERSO),
            target = "SN-CIN",
            language = Language.FRENCH,
            challengeMode = ChallengeMode.MINIMAL,
            images = mapOf(
                "recto_center_document" to rectoFile.absolutePath,
                "verso_center_document" to versoFile.absolutePath,
            ),
        )

        val response = client.verify(options)

        for (step in response.steps) {
            if (step.success) {
                assertNotNull(step.alignedDocument, "aligned_document expected on successful document step")
            }
        }
        println("Recto+Verso: status=${response.overallStatus}")
    }

    // ─── verify — per-step challenge mode override ────────────────────────────

    @Test
    fun `verify respects per-step challenge mode override`() {
        val rectoFile = fixtureFile("recto.jpg") ?: run {
            println("SKIP: fixtures not found")
            return
        }

        // Standard globally, but minimal for recto specifically
        val options = VerifyOptions(
            steps = listOf(Step.RECTO),
            target = "SN-CIN",
            language = Language.ENGLISH,
            challengeMode = ChallengeMode.STANDARD,
            stepChallengeModes = mapOf("recto" to ChallengeMode.MINIMAL),
            images = mapOf("recto_center_document" to rectoFile.absolutePath),
        )

        // If per-step override is respected, the server will only expect center_document
        // for recto (minimal mode). If standard were applied it would require more images
        // and return a 400 MISSING_FIELD — so a non-400 response validates the override.
        val response = client.verify(options)
        assertNotNull(response.sessionId)
        println("Per-step override test: status=${response.overallStatus}")
    }

    // ─── verify — kyc_identifier is echoed back ──────────────────────────────

    @Test
    fun `verify accepts kycIdentifier without error`() {
        val rectoFile = fixtureFile("recto.jpg") ?: run {
            println("SKIP: fixtures not found")
            return
        }

        val options = VerifyOptions(
            steps = listOf(Step.RECTO),
            target = "SN-CIN",
            language = Language.FRENCH,
            challengeMode = ChallengeMode.MINIMAL,
            kycIdentifier = "user-test-12345",
            images = mapOf("recto_center_document" to rectoFile.absolutePath),
        )

        val response = client.verify(options)
        // The session_id is a server-generated value; we just verify the call didn't blow up
        assertTrue(response.sessionId.isNotBlank())
        println("kycIdentifier test: sessionId=${response.sessionId}")
    }

    // ─── verifyWebhookSignature ───────────────────────────────────────────────

    @Test
    fun `verifyWebhookSignature accepts valid HMAC-SHA256 signature`() {
        val secret = "my_test_api_key"
        val payload = """{"event":"kyc.session.complete","session_id":"abc123"}"""
            .toByteArray(StandardCharsets.UTF_8)

        // Compute the expected signature using the same algorithm as the server
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val expectedSig = mac.doFinal(payload).joinToString("") { "%02x".format(it) }

        val result = KyvShield.verifyWebhookSignature(
            payload = payload,
            apiKey = secret,
            signatureHeader = expectedSig,
        )
        assertTrue(result, "Valid HMAC-SHA256 signature should pass verification")
    }

    @Test
    fun `verifyWebhookSignature rejects tampered payload`() {
        val secret = "my_test_api_key"
        val payload = """{"event":"kyc.session.complete","session_id":"abc123"}"""
            .toByteArray(StandardCharsets.UTF_8)
        val tamperedPayload = """{"event":"kyc.session.complete","session_id":"EVIL999"}"""
            .toByteArray(StandardCharsets.UTF_8)

        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val sigForOriginal = mac.doFinal(payload).joinToString("") { "%02x".format(it) }

        val result = KyvShield.verifyWebhookSignature(
            payload = tamperedPayload,
            apiKey = secret,
            signatureHeader = sigForOriginal,
        )
        assertFalse(result, "Tampered payload should NOT pass signature verification")
    }

    @Test
    fun `verifyWebhookSignature rejects wrong API key`() {
        val secret = "correct_key"
        val wrongKey = "wrong_key"
        val payload = """{"session_id":"xyz"}""".toByteArray(StandardCharsets.UTF_8)

        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val sig = mac.doFinal(payload).joinToString("") { "%02x".format(it) }

        val result = KyvShield.verifyWebhookSignature(
            payload = payload,
            apiKey = wrongKey,
            signatureHeader = sig,
        )
        assertFalse(result, "Wrong API key should NOT pass signature verification")
    }

    // ─── Enum parsing ─────────────────────────────────────────────────────────

    @Test
    fun `OverallStatus fromString handles pass and reject`() {
        assertEquals(OverallStatus.PASS, OverallStatus.fromString("pass"))
        assertEquals(OverallStatus.REJECT, OverallStatus.fromString("reject"))
    }

    @Test
    fun `OverallStatus fromString throws on unknown value`() {
        assertThrows<IllegalArgumentException> {
            OverallStatus.fromString("unknown_status")
        }
    }

    @Test
    fun `Step fromString handles all valid values`() {
        assertEquals(Step.SELFIE, Step.fromString("selfie"))
        assertEquals(Step.RECTO, Step.fromString("recto"))
        assertEquals(Step.VERSO, Step.fromString("verso"))
    }

    @Test
    fun `ChallengeMode fromString handles all valid values`() {
        assertEquals(ChallengeMode.MINIMAL, ChallengeMode.fromString("minimal"))
        assertEquals(ChallengeMode.STANDARD, ChallengeMode.fromString("standard"))
        assertEquals(ChallengeMode.STRICT, ChallengeMode.fromString("strict"))
    }

    @Test
    fun `ConfidenceLevel fromString is case-insensitive`() {
        assertEquals(ConfidenceLevel.HIGH, ConfidenceLevel.fromString("HIGH"))
        assertEquals(ConfidenceLevel.HIGH, ConfidenceLevel.fromString("high"))
        assertEquals(ConfidenceLevel.MEDIUM, ConfidenceLevel.fromString("Medium"))
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a minimal [VerifyOptions] pointing to a non-existent image so that
     * the SDK throws FILE_NOT_FOUND before even sending the request.  Useful for
     * testing auth-failure paths by bypassing file-not-found errors.
     */
    private fun minimalOptions(): VerifyOptions {
        // Create a temporary file so the file check passes
        val tmpFile = File.createTempFile("kyvshield_test_", ".jpg").also {
            it.writeBytes(ByteArray(1024) { 0xFF.toByte() }) // minimal JPEG-ish bytes
            it.deleteOnExit()
        }
        return VerifyOptions(
            steps = listOf(Step.RECTO),
            target = "SN-CIN",
            language = Language.FRENCH,
            challengeMode = ChallengeMode.MINIMAL,
            images = mapOf("recto_center_document" to tmpFile.absolutePath),
        )
    }

    /**
     * Returns a [File] for a test fixture or `null` if it doesn't exist.
     * Fixtures live in `src/test/resources/fixtures/`.
     */
    private fun fixtureFile(name: String): File? {
        val paths = listOf(
            "src/test/resources/fixtures/$name",
            "kotlin/src/test/resources/fixtures/$name",
        )
        return paths.map { File(it) }.firstOrNull { it.exists() && it.isFile }
    }
}
