# KyvShield PHP SDK

Official PHP client for the **KyvShield REST KYC API**.

- PHP 8.1+
- Zero external dependencies (uses `curl` + `json` extensions)
- Fully typed with `readonly` properties (PHP 8.1 style)
- PSR-4 autoloading via Composer

---

## Installation

```bash
composer require kyvshield/php-sdk
```

Or clone and use the local path:

```json
{
    "repositories": [{"type": "path", "url": "../php"}],
    "require": {"kyvshield/php-sdk": "*"}
}
```

---

## Quick Start

```php
<?php
use KyvShield\KyvShield;
use KyvShield\KyvShieldException;
use KyvShield\VerifyOptions;

$kyv = new KyvShield('your-api-key');

// 1. Get challenge configuration
$challenges = $kyv->getChallenges();
$docMinimal = $challenges->getChallenges('document', 'minimal');
// → ['center_document']

// 2. Run KYC verification
try {
    $result = $kyv->verify(new VerifyOptions(
        steps: ['recto', 'verso'],
        target: 'SN-CIN',
        language: 'fr',
        challengeMode: 'minimal',
        requireFaceMatch: false,
        images: [
            'recto_center_document' => '/path/to/recto.jpg',
            'verso_center_document' => '/path/to/verso.jpg',
        ],
    ));

    echo $result->overallStatus;          // 'pass' | 'reject'
    echo $result->overallConfidence;      // 0.0 – 1.0
    echo $result->sessionId;

    foreach ($result->steps as $step) {
        echo $step->stepType;             // 'recto' | 'verso' | 'selfie'
        echo $step->liveness->isLive;
        echo $step->liveness->score;
        echo $step->liveness->confidence; // 'high' | 'medium' | 'low'

        foreach ($step->extraction as $field) {
            echo "{$field->label}: {$field->value}";
        }
    }
} catch (KyvShieldException $e) {
    echo $e->httpStatus;   // HTTP status code
    echo $e->errorCode;    // API error code string (e.g. 'QUOTA_EXCEEDED')
    echo $e->getMessage();
}
```

---

## Constructor

```php
new KyvShield(
    string $apiKey,
    string $baseUrl = 'https://kyvshield-naruto.innolinkcloud.com',
)
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `$apiKey` | `string` | Your API key — sent as `X-API-Key` header |
| `$baseUrl` | `string` | Override for self-hosted / staging environments |

---

## Methods

### `getChallenges(): ChallengesResponse`

`GET /api/v1/challenges`

Returns the configured challenge list per category and mode.

```php
$resp = $kyv->getChallenges();
$resp->success;                              // bool
$resp->challenges;                           // array<string, array<string, string[]>>
$resp->getChallenges('document', 'standard'); // string[]
```

---

### `verify(VerifyOptions $options): KycResponse`

`POST /api/v1/kyc/verify` (multipart/form-data)

#### `VerifyOptions` properties

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `steps` | `string[]` | yes | `['selfie']`, `['recto','verso']`, `['selfie','recto','verso']` |
| `target` | `string` | yes | `SN-CIN` \| `SN-PASSPORT` \| `SN-DRIVER-LICENCE` |
| `language` | `string` | yes | `fr` \| `en` \| `wo` |
| `challengeMode` | `string` | yes | Global fallback: `minimal` \| `standard` \| `strict` |
| `stepChallengeModes` | `array<string,string>` | no | Per-step override, e.g. `['selfie' => 'strict']` |
| `requireFaceMatch` | `bool` | no | Compare selfie vs document photo (default `false`) |
| `kycIdentifier` | `string\|null` | no | Caller-side identifier stored in session |
| `images` | `array<string,string>` | yes | `['recto_center_document' => '/path.jpg']` |

#### Image field naming convention

Image keys follow `{step}_{challenge}`, e.g.:

| Step | Challenge | Field key |
|------|-----------|-----------|
| `recto` | `center_document` | `recto_center_document` |
| `verso` | `center_document` | `verso_center_document` |
| `selfie` | `center_face` | `selfie_center_face` |
| `selfie` | `close_eyes` | `selfie_close_eyes` |
| `recto` | `tilt_left` | `recto_tilt_left` |

Use `getChallenges()` first to discover which challenges are required for your chosen mode and steps.

---

## Response Types

### `KycResponse`

| Property | Type | Description |
|----------|------|-------------|
| `success` | `bool` | `true` if all steps passed |
| `sessionId` | `string` | Unique session identifier |
| `overallStatus` | `string` | `'pass'` \| `'reject'` |
| `overallConfidence` | `float` | Average confidence 0–1 |
| `processingTimeMs` | `int` | Total processing time |
| `steps` | `StepResult[]` | One entry per requested step |
| `faceVerification` | `FaceVerification\|null` | Present only when `requireFaceMatch=true` |

### `StepResult`

| Property | Type | Description |
|----------|------|-------------|
| `stepIndex` | `int` | 0-based step position |
| `stepType` | `string` | `'selfie'` \| `'recto'` \| `'verso'` |
| `success` | `bool` | Whether this step passed |
| `processingTimeMs` | `int` | Time spent on this step |
| `liveness` | `LivenessResult\|null` | Liveness detection outcome |
| `verification` | `VerificationResult\|null` | Authenticity check outcome |
| `userMessages` | `string[]` | Localized user-facing messages |
| `alignedDocument` | `string\|null` | Base64 JPEG of perspective-corrected document |
| `extraction` | `ExtractionField[]` | OCR-extracted document fields |
| `extractedPhotos` | `ExtractedPhoto[]` | Face photos extracted from document |
| `capturedImage` | `string\|null` | Base64 JPEG of captured selfie (selfie step only) |

### `LivenessResult`

| Property | Type | Description |
|----------|------|-------------|
| `isLive` | `bool` | `true` = real document / live face |
| `score` | `float` | Confidence score 0–1 |
| `confidence` | `string` | `'high'` \| `'medium'` \| `'low'` |

### `VerificationResult`

| Property | Type | Description |
|----------|------|-------------|
| `isAuthentic` | `bool` | Document/selfie is genuine |
| `confidence` | `float` | 0–1 |
| `checksPassed` | `string[]` | Challenge names that passed |
| `fraudIndicators` | `string[]` | Detected fraud signals (empty = none) |
| `warnings` | `string[]` | Non-blocking warnings |
| `issues` | `string[]` | Blocking issues |

### `ExtractionField`

| Property | Type | Description |
|----------|------|-------------|
| `key` | `string` | Generic key (e.g. `document_id`, `first_name`) |
| `documentKey` | `string` | Document-specific key (e.g. `numero_carte`) |
| `label` | `string` | Localized display label |
| `value` | `mixed` | Extracted value |
| `displayPriority` | `int` | Display order (lower = first) |
| `icon` | `string\|null` | Icon hint for UI |

### `ExtractedPhoto`

| Property | Type | Description |
|----------|------|-------------|
| `image` | `string` | Base64 JPEG of extracted face |
| `confidence` | `float` | Detection confidence 0–1 |
| `bbox` | `float[]` | Bounding box `[x1, y1, x2, y2]` |
| `area` | `float` | Face area in pixels |
| `width` | `int` | Face crop width |
| `height` | `int` | Face crop height |

### `FaceVerification`

| Property | Type | Description |
|----------|------|-------------|
| `isMatch` | `bool` | `true` = selfie matches document photo |
| `similarityScore` | `float` | 0–100 similarity score |

### `ChallengesResponse`

| Property | Type | Description |
|----------|------|-------------|
| `success` | `bool` | Request succeeded |
| `challenges` | `array<string, array<string, string[]>>` | `category → mode → challenge[]` |

Helper: `getChallenges(string $category, string $mode): string[]`

---

### `static verifyWebhookSignature(string $payload, string $apiKey, string $signatureHeader): bool`

Verify that a webhook notification came from the KyvShield API.

```php
$rawBody   = file_get_contents('php://input');
$signature = $_SERVER['HTTP_X_KYVSHIELD_SIGNATURE'] ?? '';

if (!KyvShield::verifyWebhookSignature($rawBody, 'your-api-key', $signature)) {
    http_response_code(401);
    exit;
}

$event = json_decode($rawBody, true);
```

The API signs the raw JSON body with HMAC-SHA256 using your API key as the secret.
The header format is `sha256=<hex_digest>`.

---

## Error Handling

All errors throw `KyvShieldException`:

```php
try {
    $result = $kyv->verify($options);
} catch (KyvShieldException $e) {
    $e->getMessage();   // Human-readable message
    $e->httpStatus;     // HTTP status code (0 for network errors)
    $e->errorCode;      // API error code string, e.g. 'QUOTA_EXCEEDED', 'MISSING_FIELD'
}
```

Common API error codes:

| Code | Meaning |
|------|---------|
| `MISSING_FIELD` | A required form field is absent |
| `INVALID_REQUEST` | Field value is invalid (wrong type/enum) |
| `QUOTA_EXCEEDED` | Monthly usage limit reached |
| `DOCUMENT_TYPE_NOT_ALLOWED` | Target document type not enabled for this API key |

---

## Full Example — Selfie + Recto + Verso with Face Match

```php
<?php
use KyvShield\KyvShield;
use KyvShield\KyvShieldException;
use KyvShield\VerifyOptions;

$kyv = new KyvShield('your-api-key');

// Step 1 — discover which image fields standard mode needs
$challenges = $kyv->getChallenges();
$selfieFields   = $challenges->getChallenges('selfie',   'standard'); // ['center_face', 'close_eyes']
$documentFields = $challenges->getChallenges('document', 'standard'); // ['center_document', 'tilt_left']

// Step 2 — build images array (one captured image per challenge)
$images = [];
foreach ($selfieFields   as $ch) { $images["selfie_{$ch}"]  = "/captures/selfie_{$ch}.jpg"; }
foreach ($documentFields as $ch) { $images["recto_{$ch}"]   = "/captures/recto_{$ch}.jpg";  }
foreach ($documentFields as $ch) { $images["verso_{$ch}"]   = "/captures/verso_{$ch}.jpg";  }

// Step 3 — run verification
try {
    $result = $kyv->verify(new VerifyOptions(
        steps: ['selfie', 'recto', 'verso'],
        target: 'SN-CIN',
        language: 'fr',
        challengeMode: 'standard',
        requireFaceMatch: true,
        kycIdentifier: 'customer-42',
        images: $images,
    ));

    if ($result->success) {
        // Access extracted data
        foreach ($result->steps as $step) {
            if ($step->stepType === 'recto') {
                foreach ($step->extraction as $field) {
                    echo "{$field->label}: {$field->value}\n";
                }
            }
        }

        // Face match result
        if ($result->faceVerification !== null) {
            printf("Face match: %s (score: %.1f)\n",
                $result->faceVerification->isMatch ? 'PASS' : 'FAIL',
                $result->faceVerification->similarityScore,
            );
        }
    }

} catch (KyvShieldException $e) {
    error_log("[KYC] Error {$e->errorCode}: {$e->getMessage()}");
}
```

---

## Running the Tests

```bash
# Install dev dependencies
composer install

# Run integration test against local dev server (must be running on :8080)
php test.php
```

---

## Requirements

- PHP >= 8.1
- `ext-curl`
- `ext-json`
