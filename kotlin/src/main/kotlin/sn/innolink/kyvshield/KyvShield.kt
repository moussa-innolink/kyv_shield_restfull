package sn.innolink.kyvshield

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * KyvShield REST SDK — Main Client
 *
 * Entry point for all KyvShield KYC REST API operations.
 *
 * ```kotlin
 * val client = KyvShield(apiKey = "kvs_live_xxxx")
 *
 * // 1. Fetch available challenges
 * val challenges = client.getChallenges()
 *
 * // 2. Run a full KYC verification
 * val response = client.verify(
 *     VerifyOptions(
 *         steps           = listOf(Step.SELFIE, Step.RECTO),
 *         target          = "SN-CIN",
 *         language        = Language.FRENCH,
 *         challengeMode   = ChallengeMode.STANDARD,
 *         requireFaceMatch = true,
 *         images          = mapOf(
 *             "selfie_center_face"    to "/tmp/selfie.jpg",
 *             "recto_center_document" to "/tmp/recto.jpg",
 *         ),
 *     )
 * )
 * println("Overall: ${response.overallStatus}  confidence=${response.overallConfidence}")
 * ```
 *
 * @param apiKey Your KyvShield API key (passed as `X-API-Key` header on every request).
 * @param baseUrl Base URL of the KyvShield API. Defaults to the production endpoint.
 * @param timeoutSeconds HTTP request timeout in seconds. Defaults to 120 s.
 */
class KyvShield(
    private val apiKey: String,
    private val baseUrl: String = "https://kyvshield-naruto.innolinkcloud.com",
    private val timeoutSeconds: Long = 120L,
) {

    // ─── HTTP client ─────────────────────────────────────────────────────────

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Fetches the challenges configuration from the server.
     *
     * `GET /api/v1/challenges`
     *
     * @return [ChallengesResponse] describing which challenges are required for each
     *         step type and intensity mode.
     * @throws KyvShieldException on HTTP or parsing errors.
     */
    fun getChallenges(): ChallengesResponse {
        val url = "${baseUrl.trimEnd('/')}/api/v1/challenges"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("X-API-Key", apiKey)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .GET()
            .build()

        val raw = executeRequest(request)
        return parseChallengesResponse(raw)
    }

    /**
     * Runs a full KYC verification for the provided options.
     *
     * `POST /api/v1/kyc/verify` (multipart/form-data)
     *
     * @param options Verification parameters — steps, target, images, etc.
     * @return [KycResponse] with per-step results, liveness scores, OCR extraction,
     *         and optional face-match result.
     * @throws KyvShieldException on HTTP or parsing errors, or if an image file
     *         cannot be read.
     */
    fun verify(options: VerifyOptions): KycResponse {
        val url = "${baseUrl.trimEnd('/')}/api/v1/kyc/verify"
        val boundary = generateBoundary()

        val body = buildMultipartBody(options, boundary)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("X-API-Key", apiKey)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

        val raw = executeRequest(request)
        return parseKycResponse(raw)
    }

    /**
     * Runs multiple KYC verifications concurrently using a fixed thread pool.
     *
     * Maximum batch size is 10. Results are returned in the same order as the
     * input list. Each entry's [BatchResult.success] indicates whether that
     * individual verification succeeded.
     *
     * @param optionsList list of [VerifyOptions] (max 10)
     * @return list of [BatchResult] in input order
     */
    fun verifyBatch(optionsList: List<VerifyOptions>): List<BatchResult> {
        require(optionsList.size <= 10) { "Batch size cannot exceed 10" }
        val executor = Executors.newFixedThreadPool(optionsList.size)
        val futures = optionsList.map { opts ->
            CompletableFuture.supplyAsync({
                try {
                    BatchResult(success = true, result = verify(opts), error = null)
                } catch (e: Exception) {
                    BatchResult(success = false, result = null, error = e.message)
                }
            }, executor)
        }
        CompletableFuture.allOf(*futures.toTypedArray()).join()
        executor.shutdown()
        return futures.map { it.join() }
    }

    // ─── Companion object ────────────────────────────────────────────────────

    companion object {

        /**
         * Verifies the HMAC-SHA256 signature of an incoming webhook payload.
         *
         * The KyvShield server signs each webhook body with your API key using
         * HMAC-SHA256 and sends the hex-encoded digest in the `X-KyvShield-Signature`
         * header.
         *
         * ```kotlin
         * val isValid = KyvShield.verifyWebhookSignature(
         *     payload         = requestBody,
         *     apiKey          = "kvs_live_xxxx",
         *     signatureHeader = request.getHeader("X-KyvShield-Signature"),
         * )
         * if (!isValid) throw SecurityException("Invalid webhook signature")
         * ```
         *
         * @param payload Raw request body bytes.
         * @param apiKey Your KyvShield API key used as the HMAC secret.
         * @param signatureHeader Value of the `X-KyvShield-Signature` header.
         * @return `true` if the signature matches, `false` otherwise.
         */
        fun verifyWebhookSignature(
            payload: ByteArray,
            apiKey: String,
            signatureHeader: String,
        ): Boolean {
            return try {
                val received = if (signatureHeader.startsWith("sha256=")) signatureHeader.substring(7) else signatureHeader
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(apiKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
                val computed = mac.doFinal(payload)
                val computedHex = computed.joinToString("") { "%02x".format(it) }
                // Constant-time comparison to prevent timing attacks
                MessageDigest.isEqual(
                    computedHex.toByteArray(StandardCharsets.UTF_8),
                    received.toByteArray(StandardCharsets.UTF_8),
                )
            } catch (e: Exception) {
                false
            }
        }
    }

    // ─── Internal helpers ────────────────────────────────────────────────────

    /**
     * Executes an [HttpRequest] and returns the raw response body string.
     * Throws [KyvShieldException] for non-2xx status codes.
     */
    private fun executeRequest(request: HttpRequest): String {
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            throw KyvShieldException(
                message = "HTTP request failed: ${e.message}",
                cause = e,
            )
        }

        val status = response.statusCode()
        val body = response.body() ?: ""

        if (status !in 200..299) {
            // Try to extract structured error from response body
            val errorCode = runCatching {
                JSONObject(body).optString("code", null)
            }.getOrNull()
            val errorMessage = runCatching {
                JSONObject(body).optString("error", "Request failed")
            }.getOrElse { "Request failed" }

            throw KyvShieldException(
                message = errorMessage,
                statusCode = status,
                errorCode = errorCode,
                responseBody = body,
            )
        }

        return body
    }

    /**
     * Builds the multipart/form-data body bytes for the verify request.
     */
    private fun buildMultipartBody(options: VerifyOptions, boundary: String): ByteArray {
        val out = ByteArrayOutputStream()
        val crlf = "\r\n"
        val dash = "--"

        fun writeField(name: String, value: String) {
            out.write("$dash$boundary$crlf".toByteArray())
            out.write("Content-Disposition: form-data; name=\"$name\"$crlf$crlf".toByteArray())
            out.write(value.toByteArray(StandardCharsets.UTF_8))
            out.write(crlf.toByteArray())
        }

        fun writeFileField(name: String, filePath: String) {
            val file = File(filePath)
            if (!file.exists() || !file.isFile) {
                throw KyvShieldException(
                    message = "Image file not found or not a file: $filePath (field: $name)",
                    errorCode = "FILE_NOT_FOUND",
                )
            }
            val bytes = file.readBytes()
            val mimeType = when {
                filePath.lowercase().endsWith(".png")  -> "image/png"
                filePath.lowercase().endsWith(".webp") -> "image/webp"
                filePath.lowercase().endsWith(".gif")  -> "image/gif"
                else -> "image/jpeg"
            }
            out.write("$dash$boundary$crlf".toByteArray())
            out.write(
                "Content-Disposition: form-data; name=\"$name\"; filename=\"${file.name}\"$crlf".toByteArray()
            )
            out.write("Content-Type: $mimeType$crlf$crlf".toByteArray())
            out.write(bytes)
            out.write(crlf.toByteArray())
        }

        // steps — JSON array
        val stepsJson = JSONArray().apply {
            options.steps.forEach { put(it.value) }
        }.toString()
        writeField("steps", stepsJson)

        // target
        writeField("target", options.target)

        // language
        writeField("language", options.language.value)

        // challenge_mode
        writeField("challenge_mode", options.challengeMode.value)

        // per-step challenge modes
        options.stepChallengeModes?.forEach { (step, mode) ->
            writeField("${step}_challenge_mode", mode.value)
        }

        // require_face_match
        writeField("require_face_match", options.requireFaceMatch.toString())

        // kyc_identifier (optional)
        options.kycIdentifier?.let { writeField("kyc_identifier", it) }

        // image files
        options.images.forEach { (fieldName, filePath) ->
            writeFileField(fieldName, filePath)
        }

        // terminal boundary
        out.write("$dash$boundary$dash$crlf".toByteArray())

        return out.toByteArray()
    }

    /** Generates a unique MIME multipart boundary string. */
    private fun generateBoundary(): String {
        val random = java.util.UUID.randomUUID().toString().replace("-", "")
        return "KyvShield_${random}"
    }

    // ─── JSON parsers ────────────────────────────────────────────────────────

    private fun parseChallengesResponse(json: String): ChallengesResponse {
        return try {
            val root = JSONObject(json)
            val success = root.optBoolean("success", false)
            val challengesObj = root.getJSONObject("challenges")
            ChallengesResponse(
                success = success,
                challenges = ChallengesBlock(
                    document = parseChallengeModeMap(challengesObj.getJSONObject("document")),
                    selfie = parseChallengeModeMap(challengesObj.getJSONObject("selfie")),
                ),
            )
        } catch (e: KyvShieldException) {
            throw e
        } catch (e: Exception) {
            throw KyvShieldException(
                message = "Failed to parse challenges response: ${e.message}",
                responseBody = json,
                cause = e,
            )
        }
    }

    private fun parseChallengeModeMap(obj: JSONObject): ChallengeModeMap = ChallengeModeMap(
        minimal = obj.getJSONArray("minimal").toStringList(),
        standard = obj.getJSONArray("standard").toStringList(),
        strict = obj.getJSONArray("strict").toStringList(),
    )

    private fun parseKycResponse(json: String): KycResponse {
        return try {
            val root = JSONObject(json)
            val success = root.optBoolean("success", false)
            val sessionId = root.optString("session_id", "")
            val overallStatus = OverallStatus.fromString(root.optString("overall_status", "reject"))
            val overallConfidence = root.optDouble("overall_confidence", 0.0)
            val processingTimeMs = root.optInt("processing_time_ms", 0)

            val faceVerification: FaceVerification? = if (root.has("face_verification")) {
                val fv = root.getJSONObject("face_verification")
                FaceVerification(
                    isMatch = fv.optBoolean("is_match", false),
                    similarityScore = fv.optDouble("similarity_score", 0.0),
                )
            } else null

            val stepsArray = root.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until stepsArray.length()).map { i ->
                parseStepResult(stepsArray.getJSONObject(i))
            }

            KycResponse(
                success = success,
                sessionId = sessionId,
                overallStatus = overallStatus,
                overallConfidence = overallConfidence,
                processingTimeMs = processingTimeMs,
                faceVerification = faceVerification,
                steps = steps,
            )
        } catch (e: KyvShieldException) {
            throw e
        } catch (e: Exception) {
            throw KyvShieldException(
                message = "Failed to parse KYC response: ${e.message}",
                responseBody = json,
                cause = e,
            )
        }
    }

    private fun parseStepResult(obj: JSONObject): StepResult {
        val stepIndex = obj.optInt("step_index", 0)
        val stepType = Step.fromString(obj.optString("step_type", "recto"))
        val success = obj.optBoolean("success", false)
        val processingTimeMs = obj.optInt("processing_time_ms", 0)

        val livenessObj = obj.optJSONObject("liveness")
        val liveness = if (livenessObj != null) {
            LivenessResult(
                isLive = livenessObj.optBoolean("is_live", false),
                score = livenessObj.optDouble("score", 0.0),
                confidence = ConfidenceLevel.fromString(livenessObj.optString("confidence", "LOW")),
            )
        } else {
            LivenessResult(isLive = false, score = 0.0, confidence = ConfidenceLevel.LOW)
        }

        val verificationObj = obj.optJSONObject("verification")
        val verification = if (verificationObj != null) {
            VerificationResult(
                isAuthentic = verificationObj.optBoolean("is_authentic", false),
                confidence = verificationObj.optDouble("confidence", 0.0),
                checksPassed = verificationObj.optJSONArray("checks_passed")?.toStringList() ?: emptyList(),
                fraudIndicators = verificationObj.optJSONArray("fraud_indicators")?.toStringList() ?: emptyList(),
                warnings = verificationObj.optJSONArray("warnings")?.toStringList() ?: emptyList(),
                issues = verificationObj.optJSONArray("issues")?.toStringList() ?: emptyList(),
            )
        } else {
            VerificationResult(
                isAuthentic = false,
                confidence = 0.0,
                checksPassed = emptyList(),
                fraudIndicators = emptyList(),
                warnings = emptyList(),
                issues = emptyList(),
            )
        }

        val userMessages = obj.optJSONArray("user_messages")?.toStringList() ?: emptyList()

        val alignedDocument: String? = obj.optString("aligned_document", null)
            .takeUnless { it.isNullOrEmpty() }

        val capturedImage: String? = obj.optString("captured_image", null)
            .takeUnless { it.isNullOrEmpty() }

        val extraction: List<ExtractionField>? = obj.optJSONArray("extraction")?.let { arr ->
            (0 until arr.length()).map { i ->
                val f = arr.getJSONObject(i)
                ExtractionField(
                    key = f.optString("key", ""),
                    documentKey = f.optString("document_key", ""),
                    label = f.optString("label", ""),
                    value = f.optString("value", ""),
                    displayPriority = f.optInt("display_priority", 0),
                    icon = f.optString("icon", null).takeUnless { it.isNullOrEmpty() },
                )
            }
        }

        val extractedPhotos: List<ExtractedPhoto>? = obj.optJSONArray("extracted_photos")?.let { arr ->
            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                ExtractedPhoto(
                    image = p.optString("image", ""),
                    confidence = p.optDouble("confidence", 0.0),
                    bbox = p.optJSONArray("bbox")?.toDoubleList() ?: emptyList(),
                    area = p.optDouble("area", 0.0),
                    width = p.optInt("width", 0),
                    height = p.optInt("height", 0),
                )
            }
        }

        return StepResult(
            stepIndex = stepIndex,
            stepType = stepType,
            success = success,
            processingTimeMs = processingTimeMs,
            liveness = liveness,
            verification = verification,
            userMessages = userMessages,
            alignedDocument = alignedDocument,
            extraction = extraction,
            extractedPhotos = extractedPhotos,
            capturedImage = capturedImage,
        )
    }

    // ─── JSONArray extension helpers ─────────────────────────────────────────

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }

    private fun JSONArray.toDoubleList(): List<Double> =
        (0 until length()).map { getDouble(it) }
}
