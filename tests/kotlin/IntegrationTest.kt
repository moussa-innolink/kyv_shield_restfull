/**
 * KyvShield Kotlin SDK — Integration Tests
 *
 * Tests against the production endpoint using the demo API key.
 *
 * Compile and run (from the project root):
 *   cd /Users/macbookpro/PhpstormProjects/kyv_shield_restfull
 *   ./gradlew :kotlin:compileKotlin
 *   kotlinc -cp kotlin/build/libs/kotlin-1.0.0.jar:$(find ~/.gradle -name "json-*.jar" | head -1) \
 *       tests/kotlin/IntegrationTest.kt -include-runtime -d tests/kotlin/integration-test.jar
 *   java -jar tests/kotlin/integration-test.jar
 *
 * Or run directly from a Kotlin script runner with the SDK sources on the classpath.
 */

import sn.innolink.kyvshield.KyvShield
import sn.innolink.kyvshield.KyvShieldException
import sn.innolink.kyvshield.VerifyOptions
import sn.innolink.kyvshield.Step
import sn.innolink.kyvshield.Language
import sn.innolink.kyvshield.ChallengeMode
import java.io.File
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets

// ─── Configuration ────────────────────────────────────────────────────────────

private const val API_KEY   = "kyvshield_demo_key_2024"
private const val BASE_URL  = "https://kyvshield-naruto.innolinkcloud.com"
private const val RECTO_IMG = "/Users/macbookpro/GolandProjects/cin_verification/api/document_id/SN-CIN/RECTO.jpg"
private const val VERSO_IMG = "/Users/macbookpro/GolandProjects/cin_verification/api/document_id/SN-CIN/VERSO.jpg"

// ─── Helpers ──────────────────────────────────────────────────────────────────

private var passed = 0
private var failed = 0

private fun pass(name: String) {
    passed++
    println("\u001B[32m[PASS]\u001B[0m $name")
}

private fun fail(name: String, reason: String) {
    failed++
    println("\u001B[31m[FAIL]\u001B[0m $name: $reason")
}

private fun section(title: String) {
    println("\n\u001B[1m── $title ──\u001B[0m")
}

private fun computeHmacSha256Hex(payload: ByteArray, key: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(payload).joinToString("") { "%02x".format(it) }
}

// ─── Main ─────────────────────────────────────────────────────────────────────

fun main() {
    val client = KyvShield(apiKey = API_KEY, baseUrl = BASE_URL)

    // ── Test 1: getChallenges ─────────────────────────────────────────────────

    section("TEST 1: getChallenges")
    try {
        val challenges = client.getChallenges()

        if (!challenges.success) {
            fail("getChallenges success flag", "expected true")
        } else {
            pass("getChallenges returns success=true")
        }

        if (challenges.challenges.document.minimal.isEmpty()) {
            fail("document.minimal challenges", "empty list")
        } else {
            pass("document.minimal challenges: ${challenges.challenges.document.minimal}")
        }

        if (challenges.challenges.selfie.standard.isEmpty()) {
            fail("selfie.standard challenges", "empty list")
        } else {
            pass("selfie.standard challenges: ${challenges.challenges.selfie.standard}")
        }
    } catch (e: KyvShieldException) {
        fail("getChallenges", e.message ?: "unknown error")
    }

    // ── Test 2: verify recto + verso (file paths) ─────────────────────────────

    section("TEST 2: verify recto + verso (file paths)")
    try {
        val options = VerifyOptions(
            steps = listOf(Step.RECTO, Step.VERSO),
            target = "SN-CIN",
            language = Language.FRENCH,
            challengeMode = ChallengeMode.MINIMAL,
            stepChallengeModes = mapOf(
                "recto" to ChallengeMode.MINIMAL,
                "verso" to ChallengeMode.MINIMAL,
            ),
            requireFaceMatch = false,
            kycIdentifier = "kotlin-integration-test-001",
            images = mapOf(
                "recto_center_document" to RECTO_IMG,
                "verso_center_document" to VERSO_IMG,
            ),
        )

        val resp = client.verify(options)

        if (resp.sessionId.isEmpty()) {
            fail("session_id", "empty")
        } else {
            pass("session_id: ${resp.sessionId}")
        }

        if (resp.overallStatus.value !in setOf("pass", "reject")) {
            fail("overall_status", "unexpected: ${resp.overallStatus}")
        } else {
            pass("overall_status: ${resp.overallStatus}")
        }

        if (resp.overallConfidence < 0.0 || resp.overallConfidence > 1.0) {
            fail("overall_confidence", "out of range: ${resp.overallConfidence}")
        } else {
            pass("overall_confidence: ${"%.4f".format(resp.overallConfidence)}")
        }

        if (resp.steps.size != 2) {
            fail("steps count", "expected 2, got ${resp.steps.size}")
        } else {
            pass("steps array has ${resp.steps.size} elements")
        }

        resp.steps.forEachIndexed { i, step ->
            val label = "step[$i] (${step.stepType})"
            pass("$label liveness: is_live=${step.liveness.isLive} score=${"%.4f".format(step.liveness.score)}")
            pass("$label verification: is_authentic=${step.verification.isAuthentic}")
        }

        // Print extraction fields with display_priority
        println("\n  Extraction fields (sorted by display_priority):")
        resp.steps.forEach { step ->
            step.extraction?.sortedBy { it.displayPriority }?.forEach { field ->
                println("    [${step.stepType}][priority=${field.displayPriority}] ${field.label} (${field.key}) = ${field.value}")
            }
        }
    } catch (e: KyvShieldException) {
        fail("verify recto+verso", e.message ?: "unknown error")
    }

    // ── Test 3: verify with raw bytes (ByteArray in images map) ──────────────

    section("TEST 3: verify with raw bytes (ByteArray)")
    try {
        val rectoBytes = File(RECTO_IMG).readBytes()
        val versoBytes = File(VERSO_IMG).readBytes()

        val options3 = VerifyOptions(
            steps = listOf(Step.RECTO, Step.VERSO),
            target = "SN-CIN",
            language = Language.FRENCH,
            challengeMode = ChallengeMode.MINIMAL,
            kycIdentifier = "kotlin-integration-test-bytes-001",
            images = mapOf(
                "recto_center_document" to rectoBytes,
                "verso_center_document" to versoBytes,
            ),
        )
        val resp3 = client.verify(options3)
        pass("verify with ByteArray: overall_status = ${resp3.overallStatus}")
    } catch (e: Exception) {
        fail("verify with ByteArray", e.message ?: "unknown error")
    }

    // ── Test 4: verify with base64 strings ───────────────────────────────────

    section("TEST 4: verify with base64 strings")
    try {
        val rectoB64 = Base64.getEncoder().encodeToString(File(RECTO_IMG).readBytes())
        val versoB64 = Base64.getEncoder().encodeToString(File(VERSO_IMG).readBytes())

        val options4 = VerifyOptions(
            steps = listOf(Step.RECTO, Step.VERSO),
            target = "SN-CIN",
            language = Language.FRENCH,
            challengeMode = ChallengeMode.MINIMAL,
            kycIdentifier = "kotlin-integration-test-b64-001",
            images = mapOf(
                "recto_center_document" to rectoB64,
                "verso_center_document" to versoB64,
            ),
        )
        val resp4 = client.verify(options4)
        pass("verify with base64 strings: overall_status = ${resp4.overallStatus}")
    } catch (e: Exception) {
        fail("verify with base64 strings", e.message ?: "unknown error")
    }

    // ── Test 5: verify with data URI ─────────────────────────────────────────

    section("TEST 5: verify with data URI")
    try {
        val rectoB64 = Base64.getEncoder().encodeToString(File(RECTO_IMG).readBytes())
        val versoB64 = Base64.getEncoder().encodeToString(File(VERSO_IMG).readBytes())

        val options5 = VerifyOptions(
            steps = listOf(Step.RECTO, Step.VERSO),
            target = "SN-CIN",
            language = Language.FRENCH,
            challengeMode = ChallengeMode.MINIMAL,
            kycIdentifier = "kotlin-integration-test-datauri-001",
            images = mapOf(
                "recto_center_document" to "data:image/jpeg;base64,$rectoB64",
                "verso_center_document" to "data:image/jpeg;base64,$versoB64",
            ),
        )
        val resp5 = client.verify(options5)
        pass("verify with data URI: overall_status = ${resp5.overallStatus}")
    } catch (e: Exception) {
        fail("verify with data URI", e.message ?: "unknown error")
    }

    // ── Test 6: verifyWebhookSignature ────────────────────────────────────────

    section("TEST 6: verifyWebhookSignature")
    val webhookPayload = """{"session_id":"test-session","overall_status":"pass"}""".toByteArray()
    val expectedSig = computeHmacSha256Hex(webhookPayload, API_KEY)

    if (!KyvShield.verifyWebhookSignature(webhookPayload, API_KEY, expectedSig)) {
        fail("verifyWebhookSignature (bare hex)", "returned false for valid signature")
    } else {
        pass("verifyWebhookSignature validates bare hex signature")
    }

    if (!KyvShield.verifyWebhookSignature(webhookPayload, API_KEY, "sha256=$expectedSig")) {
        fail("verifyWebhookSignature (sha256= prefix)", "returned false for valid signature")
    } else {
        pass("verifyWebhookSignature validates sha256= prefixed signature")
    }

    if (KyvShield.verifyWebhookSignature(webhookPayload, API_KEY, "deadbeef00112233")) {
        fail("verifyWebhookSignature (wrong sig)", "returned true for invalid signature")
    } else {
        pass("verifyWebhookSignature rejects invalid signature")
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    println("\n\u001B[1m━━━ Results: $passed passed, $failed failed ━━━\u001B[0m")
    if (failed > 0) System.exit(1)
}
