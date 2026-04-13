# KyvShield Kotlin SDK

Fully-typed Kotlin SDK for the [KyvShield](https://kyvshield-naruto.innolinkcloud.com) REST KYC API.

## Requirements

| Dependency | Version |
|---|---|
| Kotlin | 1.9+ |
| JDK | 17+ |
| org.json | 20240303 |

The SDK uses only `java.net.http.HttpClient` (JDK 11+) — no external HTTP library.

---

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("sn.innolink:kyvshield-sdk:1.0.0")
    implementation("org.json:json:20240303")
}
```

### Build from source

```bash
cd kotlin/
./gradlew build          # compile + test
./gradlew fatJar         # build an all-in-one JAR → build/libs/kyvshield-*-all.jar
```

---

## Quick Start

```kotlin
import sn.innolink.kyvshield.*

fun main() {
    val client = KyvShield(apiKey = "kvs_live_your_key_here")

    // 1 — Fetch available challenges
    val challenges = client.getChallenges()
    println("Document minimal: ${challenges.challenges.document.minimal}")

    // 2 — Run a full KYC verification
    val response = client.verify(
        VerifyOptions(
            steps            = listOf(Step.SELFIE, Step.RECTO, Step.VERSO),
            target           = "SN-CIN",
            language         = Language.FRENCH,
            challengeMode    = ChallengeMode.STANDARD,
            requireFaceMatch = true,
            images = mapOf(
                "selfie_center_face"    to "/path/to/selfie.jpg",
                "recto_center_document" to "/path/to/recto.jpg",
                "verso_center_document" to "/path/to/verso.jpg",
            ),
        )
    )

    println("Status   : ${response.overallStatus}")
    println("Confidence: ${response.overallConfidence}")
    println("Session  : ${response.sessionId}")

    response.steps.forEach { step ->
        println("  [${step.stepType}] success=${step.success} score=${step.liveness.score}")
        step.extraction?.forEach { field ->
            println("    ${field.label}: ${field.value}")
        }
    }

    response.faceVerification?.let { fv ->
        println("Face match: ${fv.isMatch} (score=${fv.similarityScore})")
    }
}
```

---

## API Reference

### `KyvShield(apiKey, baseUrl?, timeoutSeconds?)`

| Parameter | Type | Default | Description |
|---|---|---|---|
| `apiKey` | `String` | required | Your API key sent as `X-API-Key` |
| `baseUrl` | `String` | `https://kyvshield-naruto.innolinkcloud.com` | Override for local dev / staging |
| `timeoutSeconds` | `Long` | `120` | Per-request HTTP timeout |

---

### `getChallenges(): ChallengesResponse`

```
GET /api/v1/challenges
```

Returns the server's challenges configuration — which challenge images are required
for each step type (`document` / `selfie`) at each intensity (`minimal` / `standard` / `strict`).

**Example**

```kotlin
val resp = client.getChallenges()
// resp.challenges.document.minimal → ["center_document"]
// resp.challenges.document.standard → ["center_document", "tilt_left", "tilt_right"]
```

---

### `verify(options): KycResponse`

```
POST /api/v1/kyc/verify  (multipart/form-data)
```

Runs the full KYC pipeline synchronously and returns a structured result.

#### `VerifyOptions`

| Field | Type | Required | Description |
|---|---|---|---|
| `steps` | `List<Step>` | yes | Ordered steps to execute, e.g. `[SELFIE, RECTO, VERSO]` |
| `target` | `String` | yes | Document target, e.g. `"SN-CIN"`, `"SN-PASSPORT"` |
| `language` | `Language` | no (default `FRENCH`) | Language for user-facing messages |
| `challengeMode` | `ChallengeMode` | no (default `STANDARD`) | Global challenge intensity |
| `stepChallengeModes` | `Map<String, ChallengeMode>?` | no | Per-step overrides, e.g. `mapOf("selfie" to ChallengeMode.MINIMAL)` |
| `requireFaceMatch` | `Boolean` | no (default `false`) | Cross-step selfie ↔ document face match |
| `requireAml` | `Boolean` | no (default `false`) | AML sanctions screening |
| `kycIdentifier` | `String?` | no | Caller-provided correlation ID |
| `images` | `Map<String, Any>` | yes | `{step}_{challenge}` → image value (see below) |

#### Image input formats

Each value in the `images` map is resolved automatically. Accepted types:

| Priority | Type / Format | Example |
|----------|---------------|---------|
| 1 | **ByteArray** (raw bytes) | `File("/path/to/recto.jpg").readBytes()` |
| 2 | **URL string** (`http://`/`https://`) | `"https://cdn.example.com/recto.jpg"` |
| 3 | **Data URI** (`data:image/…;base64,…`) | `"data:image/jpeg;base64,/9j/4AA…"` |
| 4 | **Base64 string** (long, no path sep.) | `Base64.getEncoder().encodeToString(bytes)` |
| 5 | **File path** (default) | `"/path/to/recto.jpg"` |

```kotlin
// 1. Raw bytes
images = mapOf(
    "recto_center_document" to File("/path/to/recto.jpg").readBytes(),
)

// 2. URL — downloaded automatically
images = mapOf(
    "recto_center_document" to "https://cdn.example.com/recto.jpg",
)

// 3. Data URI
val b64 = Base64.getEncoder().encodeToString(File("/path/to/recto.jpg").readBytes())
images = mapOf(
    "recto_center_document" to "data:image/jpeg;base64,$b64",
)

// 4. Base64 string
images = mapOf(
    "recto_center_document" to b64,
)

// 5. File path (original behaviour — unchanged)
images = mapOf(
    "recto_center_document" to "/path/to/recto.jpg",
)
```

#### Image key format

Keys must follow the pattern `{step}_{challenge}`:

| Step | Challenge | Key |
|---|---|---|
| selfie | center_face | `selfie_center_face` |
| recto | center_document | `recto_center_document` |
| recto | tilt_left | `recto_tilt_left` |
| verso | center_document | `verso_center_document` |

Call `getChallenges()` first to know which images the server expects for your
chosen `challengeMode`.

#### `KycResponse`

| Field | Type | Description |
|---|---|---|
| `success` | `Boolean` | HTTP-level success |
| `sessionId` | `String` | Unique session identifier |
| `overallStatus` | `OverallStatus` | `PASS` or `REJECT` |
| `overallConfidence` | `Double` | Average confidence (0–1) |
| `processingTimeMs` | `Int` | Total server-side time |
| `faceVerification` | `FaceVerification?` | Present when `requireFaceMatch=true` |
| `steps` | `List<StepResult>` | Per-step results in submission order |

#### `StepResult`

| Field | Type | Present for |
|---|---|---|
| `stepIndex` | `Int` | All |
| `stepType` | `Step` | All |
| `success` | `Boolean` | All |
| `processingTimeMs` | `Int` | All |
| `liveness` | `LivenessResult` | All |
| `verification` | `VerificationResult` | All |
| `userMessages` | `List<String>` | All |
| `alignedDocument` | `String?` (base64) | `recto`, `verso` |
| `extraction` | `List<ExtractionField>?` | `recto`, `verso` |
| `extractedPhotos` | `List<ExtractedPhoto>?` | `recto` |
| `capturedImage` | `String?` (base64) | `selfie` |

---

### `client.identify(options: IdentifyOptions): IdentifyResponse`

Identifies a probe face against enrolled subjects via `POST /api/v1/identify` (multipart/form-data). Returns up to `topK` matches ranked by descending similarity score.

```kotlin
val result = client.identify(
    IdentifyOptions(
        image    = "/path/to/probe.jpg",   // file path, URL, base64, data URI, or ByteArray
        topK     = 5,                      // max matches (default: 5)
        minScore = 0.7,                    // minimum score 0–1 (default: 0.0)
    )
)

println("Matches: ${result.matches.size}")
result.matches.forEach { match ->
    println("  ${match.subjectId} score=${match.score}")
}
```

#### `IdentifyOptions`

| Field | Type | Required | Description |
|---|---|---|---|
| `image` | `Any` | yes | Probe image — `ByteArray`, URL, data URI, base64, or file path |
| `topK` | `Int` | no (default `5`) | Maximum number of matches to return |
| `minScore` | `Double` | no (default `0.0`) | Minimum similarity score threshold (0–1) |

---

### `client.verifyFace(options: VerifyFaceOptions): VerifyFaceResponse`

Compares two face images (1:1 verification) via `POST /api/v1/verify/face` (multipart/form-data).

```kotlin
val result = client.verifyFace(
    VerifyFaceOptions(
        targetImage      = "/path/to/selfie.jpg",
        sourceImage      = "/path/to/document_photo.jpg",
        detectionModel   = "retinaface",     // optional
        recognitionModel = "arcface",         // optional
    )
)

println("Match: ${result.isMatch}")              // true/false
println("Score: ${result.similarityScore}")       // 0–100
println("Time:  ${result.processingTimeMs}ms")
```

#### `VerifyFaceOptions`

| Field | Type | Required | Description |
|---|---|---|---|
| `targetImage` | `Any` | yes | Face to verify — `ByteArray`, URL, data URI, base64, or file path |
| `sourceImage` | `Any` | yes | Reference face — same formats as `targetImage` |
| `detectionModel` | `String?` | no | Face detection model name (server default if null) |
| `recognitionModel` | `String?` | no | Face recognition model name (server default if null) |

#### `VerifyFaceResponse`

| Field | Type | Description |
|---|---|---|
| `success` | `Boolean` | Whether the API call succeeded |
| `isMatch` | `Boolean` | Whether the two faces match |
| `similarityScore` | `Double` | Cosine similarity score (0–100) |
| `detectionModel` | `String?` | Detection model used by server |
| `recognitionModel` | `String?` | Recognition model used by server |
| `processingTimeMs` | `Int` | Server-side processing time |

---

### `client.verifyBatch(optionsList: List<VerifyOptions>): List<BatchResult>`

Submit up to 10 KYC verifications concurrently using a dedicated thread pool. Results are returned in the same order as the input list. Images within each verification are compressed in parallel (up to `DEFAULT_MAX_CONCURRENT_COMPRESS = 20`). The executor is shut down in a `finally` block.

```kotlin
val kyv = KyvShield(apiKey = "your-api-key")

val results = kyv.verifyBatch(
    listOf(
        VerifyOptions(
            steps         = listOf(Step.RECTO, Step.VERSO),
            target        = "SN-CIN",
            kycIdentifier = "user-001",
            images        = mapOf(
                "recto_center_document" to "/path/to/user1_recto.jpg",
                "verso_center_document" to "/path/to/user1_verso.jpg",
            ),
        ),
        VerifyOptions(
            steps         = listOf(Step.SELFIE, Step.RECTO),
            target        = "SN-CIN",
            kycIdentifier = "user-002",
            images        = mapOf(
                "selfie_center_face"    to "/path/to/user2_selfie.jpg",
                "recto_center_document" to "/path/to/user2_recto.jpg",
            ),
        ),
    )
)

results.forEachIndexed { i, r ->
    if (r.success) {
        println("[$i] ${r.result?.overallStatus} — confidence ${r.result?.overallConfidence}")
    } else {
        println("[$i] ERROR: ${r.error}")
    }
}
```

---

### `KyvShield.verifyWebhookSignature(payload, apiKey, signatureHeader): Boolean`

Verifies the HMAC-SHA256 signature of an incoming webhook notification.

```kotlin
val isValid = KyvShield.verifyWebhookSignature(
    payload         = requestBodyBytes,
    apiKey          = "kvs_live_xxxx",
    signatureHeader = request.getHeader("X-KyvShield-Signature"),
)
if (!isValid) throw SecurityException("Webhook signature mismatch")
```

The server computes `HMAC-SHA256(body, apiKey)` and sends the hex digest in the
`X-KyvShield-Signature` header. This method performs a constant-time comparison
to prevent timing-based attacks.

---

## AML/Sanctions Screening

### Inline (during KYC)

Add `requireAml = true` to your verify options:

```kotlin
val response = client.verify(
    VerifyOptions(
        steps            = listOf(Step.SELFIE, Step.RECTO, Step.VERSO),
        target           = "SN-CIN",
        requireFaceMatch = true,
        requireAml       = true,
        images = mapOf(
            "selfie_center_face"    to "/path/to/selfie.jpg",
            "recto_center_document" to "/path/to/recto.jpg",
            "verso_center_document" to "/path/to/verso.jpg",
        ),
    )
)

response.amlScreening?.let { aml ->
    if (aml.status == "hit") println("AML match found! ${aml.matches}")
}
```

### Standalone: POST /api/v1/verify/aml

Screen a person against international sanctions lists and PEP databases without running a full KYC verification.

```kotlin
val httpClient = java.net.http.HttpClient.newHttpClient()
val json = """{"first_name":"John","last_name":"Doe","birth_date":"1990-01-15","nationality":"US"}"""
val request = java.net.http.HttpRequest.newBuilder()
    .uri(java.net.URI.create("https://kyvshield-naruto.innolinkcloud.com/api/v1/verify/aml"))
    .header("X-API-Key", "YOUR_API_KEY")
    .header("Content-Type", "application/json")
    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
    .build()
val response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
// Parse response.body() — status: "clear" | "hit" | "error"
```

---

## Error Handling

All errors are thrown as `KyvShieldException`:

```kotlin
try {
    val response = client.verify(options)
} catch (e: KyvShieldException) {
    println("Status   : ${e.statusCode}")   // HTTP status, e.g. 400, 401, 402, 403
    println("Code     : ${e.errorCode}")    // e.g. "MISSING_FIELD", "QUOTA_EXCEEDED"
    println("Message  : ${e.message}")
    println("Body     : ${e.responseBody}") // raw JSON string
}
```

Common error codes returned by the API:

| Code | HTTP | Meaning |
|---|---|---|
| `MISSING_FIELD` | 400 | Required form field or image missing |
| `INVALID_REQUEST` | 400 | Malformed field value |
| `UNAUTHORIZED` | 401 | Invalid or missing API key |
| `DOCUMENT_TYPE_NOT_ALLOWED` | 403 | Document type not enabled for this API key |
| `QUOTA_EXCEEDED` | 402 | Monthly usage limit reached |
| `INTERNAL_ERROR` | 500 | Server-side error |

---

## Enums

```kotlin
enum class Step            { SELFIE, RECTO, VERSO }
enum class ChallengeMode   { MINIMAL, STANDARD, STRICT }
enum class Language        { FRENCH, ENGLISH, WOLOF }
enum class OverallStatus   { PASS, REJECT }
enum class ConfidenceLevel { HIGH, MEDIUM, LOW }
enum class DocumentTarget  { SN_CIN, SN_PASSPORT, SN_DRIVER_LICENCE }
```

All enums expose a `.value` property returning the exact JSON string (`"minimal"`, `"pass"`, etc.)
and a companion `fromString()` factory.

---

## Running Tests

```bash
# Start the backend first
cd /path/to/cin_verification && ./scripts/run-local.sh

# Run SDK tests
cd kotlin/
KYVSHIELD_TEST_API_KEY=your_test_key ./gradlew test
```

Place test fixture images in `src/test/resources/fixtures/`:

```
src/test/resources/fixtures/
├── selfie.jpg
├── recto.jpg
└── verso.jpg
```

Tests that cannot find their fixture files are automatically skipped (not failed).
