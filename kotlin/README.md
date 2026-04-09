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
| `kycIdentifier` | `String?` | no | Caller-provided correlation ID |
| `images` | `Map<String, String>` | yes | `{step}_{challenge}` → absolute file path |

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
