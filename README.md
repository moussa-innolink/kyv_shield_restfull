# KyvShield REST API SDKs

Server-side SDKs for the KyvShield KYC REST API. Typed, tested, ready to use.

## Install

| Language | Install | Docs |
|----------|---------|------|
| **Node.js / TypeScript** | `npm install @kyvshield/rest-sdk` | [nodejs/](nodejs/) |
| **PHP** | `composer require kyvshield/rest-sdk` | [php/](php/) |
| **Java** | JitPack: `com.github.moussa-innolink.kyv_shield_restfull:java:1.0.0` | [java/](java/) |
| **Kotlin** | JitPack: `com.github.moussa-innolink.kyv_shield_restfull:kotlin:1.0.0` | [kotlin/](kotlin/) |
| **Go** | `go get github.com/moussa-innolink/kyv_shield_restfull/go` | [go/](go/) |

## Quick Start

### Node.js / TypeScript

```typescript
import { KyvShield } from '@kyvshield/rest-sdk';

const kyv = new KyvShield('YOUR_API_KEY');
const result = await kyv.verify({
  steps: ['recto', 'verso'],
  target: 'SN-CIN',
  language: 'fr',
  rectoChallengeMode: 'minimal',
  versoChallengeMode: 'minimal',
  images: {
    recto_center_document: './recto.jpg',
    verso_center_document: './verso.jpg',
  },
});
console.log(result.overall_status); // 'pass' or 'reject'
console.log(result.steps[0].extraction); // OCR fields
```

### PHP (Laravel, Symfony, etc.)

```php
use KyvShield\KyvShield;

$kyv = new KyvShield('YOUR_API_KEY');
$result = $kyv->verify(new VerifyOptions(
    steps: ['recto', 'verso'],
    target: 'SN-CIN',
    language: 'fr',
    challengeMode: 'minimal',
    images: [
        'recto_center_document' => '/path/to/recto.jpg',
        'verso_center_document' => '/path/to/verso.jpg',
    ],
));
echo $result->overallStatus; // 'pass' or 'reject'
```

### Java (Spring Boot)

```java
KyvShield kyv = new KyvShield("YOUR_API_KEY");
KycResponse result = kyv.verify(VerifyOptions.builder()
    .steps(List.of(Step.RECTO, Step.VERSO))
    .target("SN-CIN")
    .language("fr")
    .challengeMode(ChallengeMode.MINIMAL)
    .image("recto_center_document", "/path/to/recto.jpg")
    .image("verso_center_document", "/path/to/verso.jpg")
    .build());
System.out.println(result.getOverallStatus());
```

### Batch Verification (all languages)

```typescript
// Node.js — 10 verifications in parallel
const results = await kyv.verifyBatch([
  { steps: ['recto'], target: 'SN-CIN', ... },
  { steps: ['recto'], target: 'SN-CIN', ... },
  // up to 10
]);
```

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/challenges` | Get available challenges per mode |
| `POST` | `/api/v1/kyc/verify` | Submit KYC verification |
| `POST` | `/api/v1/verify/aml` | Standalone AML/sanctions screening |

## AML/Sanctions Screening

### Inline (during KYC)

Add `requireAml: true` (or `require_aml: true`) to your verify options to screen the extracted identity against international sanctions lists and PEP databases as part of the KYC flow. The `aml_screening` object is included in the response.

### Standalone: POST /api/v1/verify/aml

Screen a person against international sanctions lists and PEP databases without running a full KYC verification.

**Request:**
```json
{
  "first_name": "John",
  "last_name": "Doe",
  "birth_date": "YYYY-MM-DD",
  "nationality": "XX",
  "id_number": "...",
  "id_type": "national_id"
}
```

**Response:**
```json
{
  "status": "clear",
  "risk_level": "low",
  "is_sanctioned": false,
  "is_pep": false,
  "matches": [],
  "screened_against": ["ofac", "un", "eu", "uk", "fr"],
  "screened_at": "2026-04-12T12:00:00Z",
  "total_entries_checked": 75746
}
```

## Features

- Fully typed request and response models
- Batch verification (`verifyBatch`) — up to 10 concurrent
- Per-step challenge mode (minimal / standard / strict)
- Webhook HMAC-SHA256 signature verification
- Face match (selfie vs document photo)
- Multi-document support (SN-CIN, SN-PASSPORT, SN-DRIVER-LICENCE)
- Multi-language (fr, en, wo)
- Zero external dependencies (native HTTP clients only)

## Documentation

- [Developer docs](https://kyvshield.innolink.sn/developer)
- [REST API reference](https://github.com/moussa-innolink/kyv_llm_skill/blob/main/skills/kyvshield-integration/examples/rest-kyc.md)
