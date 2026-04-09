# KyvShield Java SDK

Java 11+ SDK for the [KyvShield KYC REST API](https://kyvshield.innolink.sn).

## Requirements

| Requirement | Version |
|-------------|---------|
| Java        | 11+     |
| Maven       | 3.6+    |
| org.json    | 20240303 (only external dependency) |

## Installation

### Maven

Add the following dependency to your `pom.xml` (once the artifact is published to Maven Central):

```xml
<dependency>
  <groupId>sn.innolink</groupId>
  <artifactId>kyvshield-java-sdk</artifactId>
  <version>1.0.0</version>
</dependency>
```

Until the artifact is published, build and install locally:

```bash
cd java/
mvn install -DskipTests
```

Then add the same `<dependency>` block above.

## Quick Start

```java
import sn.innolink.kyvshield.KyvShield;
import sn.innolink.kyvshield.model.*;

KyvShield kyv = new KyvShield("YOUR_API_KEY");

VerifyOptions options = new VerifyOptions.Builder()
    .steps(Step.RECTO, Step.VERSO)
    .target("SN-CIN")
    .language("fr")
    .rectoChallengeMode(ChallengeMode.MINIMAL)
    .versoChallengeMode(ChallengeMode.MINIMAL)
    .addImage("recto_center_document", "/path/to/recto.jpg")
    .addImage("verso_center_document", "/path/to/verso.jpg")
    .build();

KycResponse result = kyv.verify(options);
System.out.println(result.getOverallStatus());       // PASS or REJECT
System.out.println(result.getOverallConfidence());   // 0.0 – 1.0
System.out.println(result.getSteps().get(0).getExtraction()); // OCR fields
```

## API

### `KyvShield(String apiKey)`

Creates a client using the default production URL (`https://kyvshield-naruto.innolinkcloud.com`).

### `KyvShield(String apiKey, String baseUrl)`

Creates a client with a custom base URL — useful for local development or staging.

```java
KyvShield kyv = new KyvShield("demo_key", "http://localhost:8080");
```

### `ChallengesResponse getChallenges()`

Fetches available challenges from `GET /api/v1/challenges`.

```java
ChallengesResponse challenges = kyv.getChallenges();
System.out.println(challenges.getDocument().getMinimal()); // ["center_document"]
System.out.println(challenges.getSelfie().getStandard());  // ["center_face", "blink"]
```

### `KycResponse verify(VerifyOptions options)`

Submits a KYC verification to `POST /api/v1/kyc/verify` as multipart/form-data.

```java
KycResponse result = kyv.verify(options);

// Overall decision
result.getOverallStatus();      // OverallStatus.PASS or OverallStatus.REJECT
result.getOverallConfidence();  // 0.94
result.getProcessingTimeMs();   // 1847
result.getSessionId();          // "sess_abc123"

// Per-step results
for (StepResult step : result.getSteps()) {
    step.getStepType();           // Step.RECTO
    step.isSuccess();             // true
    step.getLiveness().isLive();  // true
    step.getLiveness().getScore(); // 0.92
    step.getVerification().isAuthentic();      // true
    step.getVerification().getChecksPassed();  // ["reflection_dynamic", ...]
    step.getExtraction();         // List<ExtractionField> (document steps only)
}

// Face match (when requireFaceMatch = true)
if (result.getFaceVerification() != null) {
    result.getFaceVerification().isMatch();          // true
    result.getFaceVerification().getSimilarityScore(); // 87.3
}
```

### `List<BatchResult> verifyBatch(List<VerifyOptions> optionsList)`

Submit up to 10 KYC verifications concurrently using a dedicated `ExecutorService`. Results are returned in the same order as the input list. The executor is shut down in a `finally` block. Images within each verification are compressed in parallel (up to `DEFAULT_MAX_CONCURRENT_COMPRESS = 20`).

```java
KyvShield kyv = new KyvShield("your-api-key");

List<VerifyOptions> batch = List.of(
    new VerifyOptions.Builder()
        .steps(Step.RECTO, Step.VERSO)
        .target("SN-CIN")
        .kycIdentifier("user-001")
        .addImage("recto_center_document", "/path/to/user1_recto.jpg")
        .addImage("verso_center_document", "/path/to/user1_verso.jpg")
        .build(),
    new VerifyOptions.Builder()
        .steps(Step.SELFIE, Step.RECTO)
        .target("SN-CIN")
        .kycIdentifier("user-002")
        .addImage("selfie_center_face",    "/path/to/user2_selfie.jpg")
        .addImage("recto_center_document", "/path/to/user2_recto.jpg")
        .build()
);

List<BatchResult> results = kyv.verifyBatch(batch);

for (int i = 0; i < results.size(); i++) {
    BatchResult r = results.get(i);
    if (r.isSuccess()) {
        System.out.println("[" + i + "] " + r.getResult().getOverallStatus());
    } else {
        System.out.println("[" + i + "] ERROR: " + r.getError());
    }
}
```

---

### `static boolean verifyWebhookSignature(byte[] payload, String apiKey, String signatureHeader)`

Verifies an HMAC-SHA256 webhook signature sent in the `X-KyvShield-Signature` header.

```java
// In your webhook handler:
boolean valid = KyvShield.verifyWebhookSignature(
    requestBodyBytes,
    "YOUR_API_KEY",
    request.getHeader("X-KyvShield-Signature")
);
if (!valid) {
    throw new SecurityException("Invalid webhook signature");
}
```

## VerifyOptions Builder

| Method | Type | Default | Description |
|--------|------|---------|-------------|
| `steps(Step...)` | `Step[]` | required | Steps to execute |
| `target(String)` | `String` | required | Document type (`SN-CIN`, `SN-PASSPORT`, `SN-DRIVER-LICENCE`) |
| `language(String)` | `String` | `"fr"` | Response language (`fr`, `en`, `wo`) |
| `challengeMode(ChallengeMode)` | `ChallengeMode` | server default | Global challenge mode |
| `selfieChallengeMode(ChallengeMode)` | `ChallengeMode` | — | Override for selfie step |
| `rectoChallengeMode(ChallengeMode)` | `ChallengeMode` | — | Override for recto step |
| `versoChallengeMode(ChallengeMode)` | `ChallengeMode` | — | Override for verso step |
| `requireFaceMatch(boolean)` | `boolean` | `false` | Cross-step face match |
| `kycIdentifier(String)` | `String` | — | Caller correlation ID |
| `addImage(String, String)` | key, value | required | Image to submit (path / URL / base64 / data URI) |
| `images(Map<String,String>)` | map | — | Replace entire image map |
| `addImageBytes(String, byte[])` | key, data | — | Submit raw image bytes |
| `imageBytes(Map<String,byte[]>)` | map | — | Replace entire raw-bytes image map |

### Image input formats

Each value in `addImage()` is resolved in this order:

| Priority | Format | Example |
|----------|--------|---------|
| 1 | **URL** (`http://` / `https://`) | `"https://cdn.example.com/recto.jpg"` |
| 2 | **Data URI** (`data:image/…;base64,…`) | `"data:image/jpeg;base64,/9j/4AA…"` |
| 3 | **Base64 string** (long, no path separator) | `Base64.getEncoder().encodeToString(data)` |
| 4 | **File path** (default) | `"/path/to/recto.jpg"` |

For raw bytes already in memory, use `addImageBytes()`:

```java
// File path (original behaviour — unchanged)
.addImage("recto_center_document", "/path/to/recto.jpg")

// Raw bytes
byte[] rectoBytes = Files.readAllBytes(Paths.get("/path/to/recto.jpg"));
.addImageBytes("recto_center_document", rectoBytes)

// URL — downloaded automatically
.addImage("recto_center_document", "https://cdn.example.com/recto.jpg")

// Data URI
String b64 = Base64.getEncoder().encodeToString(rectoBytes);
.addImage("recto_center_document", "data:image/jpeg;base64," + b64)

// Bare base64 string
.addImage("recto_center_document", b64)
```

### Image key format

Image keys follow the pattern `{step}_{challenge}`, for example:

| Key | Description |
|-----|-------------|
| `recto_center_document` | Recto step, center-document challenge |
| `verso_center_document` | Verso step, center-document challenge |
| `recto_tilt_left` | Recto step, tilt-left challenge |
| `selfie_center_face` | Selfie step, center-face challenge |
| `selfie_blink` | Selfie step, blink challenge |

Retrieve valid challenge identifiers per mode using `getChallenges()`.

## Enums

### `ChallengeMode`

| Constant | Wire Value | Description |
|----------|------------|-------------|
| `MINIMAL` | `minimal` | Fewest challenges, fastest UX |
| `STANDARD` | `standard` | Balanced default |
| `STRICT` | `strict` | Most challenges, highest security |

### `Step`

| Constant | Wire Value | Description |
|----------|------------|-------------|
| `SELFIE` | `selfie` | Live selfie capture |
| `RECTO` | `recto` | Front of identity document |
| `VERSO` | `verso` | Back of identity document |

### `OverallStatus`

| Constant | Wire Value | Description |
|----------|------------|-------------|
| `PASS` | `pass` | All steps passed |
| `REJECT` | `reject` | One or more steps failed |

## Error Handling

All API errors throw `KyvShieldException`:

```java
try {
    KycResponse result = kyv.verify(options);
} catch (KyvShieldException e) {
    System.err.println("Status code: " + e.getStatusCode());    // 401, 422, 500, or 0
    System.err.println("Message:     " + e.getMessage());
    System.err.println("Body:        " + e.getResponseBody());  // raw JSON
}
```

`getStatusCode()` returns `0` for network-level errors (no HTTP response received).

## Running the Tests

The integration tests require a running local backend:

```bash
# Start the backend
cd /Users/macbookpro/GolandProjects/cin_verification
./scripts/run-local.sh

# In another terminal, run the tests
cd /Users/macbookpro/PhpstormProjects/kyv_shield_restfull/java
mvn test
```

Unit tests (constructor validation, enum parsing, webhook signature verification) run
without any backend.

## Supported Document Types

| Target | Description |
|--------|-------------|
| `SN-CIN` | Carte Nationale d'Identité CEDEAO (Senegal) |
| `SN-PASSPORT` | Passeport sénégalais |
| `SN-DRIVER-LICENCE` | Permis de conduire sénégalais |

## Supported Languages

| Code | Language |
|------|----------|
| `fr` | French (default) |
| `en` | English |
| `wo` | Wolof |
