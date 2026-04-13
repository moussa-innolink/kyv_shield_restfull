# @kyvshield/rest-sdk

Fully typed Node.js / TypeScript SDK for the [KyvShield](https://kyvshield.com) KYC REST API.

Requires **Node.js 18+** (uses native `fetch` and `crypto`).

---

## Installation

```bash
npm install @kyvshield/rest-sdk
# or
yarn add @kyvshield/rest-sdk
# or
pnpm add @kyvshield/rest-sdk
```

---

## Quick start

```typescript
import { KyvShield } from '@kyvshield/rest-sdk';

const kyv = new KyvShield('your-api-key');

const result = await kyv.verify({
  steps: ['selfie', 'recto', 'verso'],
  target: 'SN-CIN',
  language: 'fr',
  challengeMode: 'standard',
  requireFaceMatch: true,
  images: {
    // key = {step}_{challenge}
    selfie_center_face:    './images/selfie.jpg',
    selfie_close_eyes:     './images/selfie_eyes_closed.jpg',
    recto_center_document: './images/recto.jpg',
    recto_tilt_left:       './images/recto_tilt_left.jpg',
    verso_center_document: './images/verso.jpg',
  },
});

if (result.overall_status === 'pass') {
  console.log('KYC passed!', result.session_id);
} else {
  console.warn('KYC rejected:', result.steps.map(s => s.user_messages).flat());
}
```

---

## API Reference

### `new KyvShield(apiKey, baseUrl?)`

| Parameter | Type     | Default                                         | Description                    |
|-----------|----------|-------------------------------------------------|--------------------------------|
| `apiKey`  | `string` | —                                               | Your KyvShield API key         |
| `baseUrl` | `string` | `https://kyvshield-naruto.innolinkcloud.com`    | Override for staging/local use |

```typescript
// Production (default)
const kyv = new KyvShield('your-api-key');

// Local development
const kyv = new KyvShield('your-api-key', 'http://localhost:8080');
```

---

### `kyv.getChallenges()`

Returns the available challenge names grouped by type (`document` / `selfie`) and mode (`minimal` / `standard` / `strict`).

Use this to discover which image keys you need to provide for a given `challengeMode`.

```typescript
const { challenges } = await kyv.getChallenges();

// challenges.document.standard => ['center_document', 'tilt_left', 'tilt_right']
// challenges.selfie.minimal    => ['center_face', 'close_eyes']
```

**Return type:** [`ChallengesResponse`](#challengesresponse)

---

### `kyv.verify(options)`

Submit a KYC verification request. Images can be provided in any of four formats — see [Image input formats](#image-input-formats) for details.

```typescript
const result = await kyv.verify({
  steps: ['selfie', 'recto'],
  target: 'SN-CIN',
  language: 'en',
  challengeMode: 'minimal',
  requireFaceMatch: true,
  kycIdentifier: 'user-ref-42',
  images: {
    selfie_center_face:    './selfie.jpg',
    recto_center_document: './recto.jpg',
  },
});
```

---

## Image input formats

Each value in the `images` map is resolved automatically in this order:

| Priority | Format | Example |
|----------|--------|---------|
| 1 | **Buffer** (raw bytes) | `Buffer.from(fs.readFileSync('./recto.jpg'))` |
| 2 | **URL** (`http://` / `https://`) | `'https://example.com/recto.jpg'` |
| 3 | **Data URI** (`data:image/…;base64,…`) | `'data:image/jpeg;base64,/9j/4AA…'` |
| 4 | **Base64 string** (long, no path separators) | `'<base64 data>'` |
| 5 | **File path** (default) | `'./recto.jpg'` or `/abs/path/recto.jpg` |

```typescript
import { readFileSync } from 'node:fs';

// 1. Raw Buffer
images: {
  recto_center_document: readFileSync('./recto.jpg'),  // Buffer
}

// 2. URL — downloaded with a 30-second timeout
images: {
  recto_center_document: 'https://cdn.example.com/recto.jpg',
}

// 3. Data URI
images: {
  recto_center_document: 'data:image/jpeg;base64,/9j/4AAQSkZJRg…',
}

// 4. Bare base64 string
const b64 = readFileSync('./recto.jpg').toString('base64');
images: {
  recto_center_document: b64,
}

// 5. File path (original behaviour — unchanged)
images: {
  recto_center_document: './recto.jpg',
}
```

**Options:** [`VerifyOptions`](#verifyoptions)

**Return type:** [`KycResponse`](#kycresponse)

---

### `kyv.verifyBatch(optionsList)`

Submit up to 10 KYC verifications concurrently using `Promise.all`. Results are returned in the same order as the input array. Each entry is a `PromiseSettledResult` — check `status` to distinguish fulfilled from rejected entries. Images within each call are resolved in parallel (up to `DEFAULT_MAX_CONCURRENT_COMPRESS = 20` at a time).

```typescript
import { KyvShield } from '@kyvshield/rest-sdk';

const kyv = new KyvShield('your-api-key');

const results = await kyv.verifyBatch([
  {
    steps: ['recto', 'verso'],
    target: 'SN-CIN',
    kycIdentifier: 'user-001',
    images: {
      recto_center_document: './user1_recto.jpg',
      verso_center_document: './user1_verso.jpg',
    },
  },
  {
    steps: ['selfie', 'recto'],
    target: 'SN-CIN',
    kycIdentifier: 'user-002',
    images: {
      selfie_center_face:    './user2_selfie.jpg',
      recto_center_document: './user2_recto.jpg',
    },
  },
]);

for (const [i, result] of results.entries()) {
  if (result.status === 'fulfilled') {
    console.log(`[${i}] ${result.value.overall_status} — session ${result.value.session_id}`);
  } else {
    console.error(`[${i}] ERROR:`, result.reason);
  }
}
```

---

### `kyv.identify(image, options?)`

Search for a face across enrolled identities (1:N identification). The image accepts the same formats as `verify` images (file path, URL, Buffer, base64, data URI).

```typescript
const result = await kyv.identify('./probe.jpg', { topK: 3, minScore: 0.6 });

console.log(`Found ${result.results_count} match(es)`);
for (const match of result.matches) {
  console.log(`${match.full_name} — score ${match.score} (${match.document_type})`);
}
```

| Parameter         | Type               | Default | Description                               |
|-------------------|--------------------|---------|-------------------------------------------|
| `image`           | `string \| Buffer` | —       | Probe image (file path, URL, Buffer, base64, data URI) |
| `options.topK`    | `number`           | `5`     | Maximum number of matches to return       |
| `options.minScore`| `number`           | `0.5`   | Minimum similarity score threshold (0–1)  |

**Options:** [`IdentifyOptions`](#identifyoptions)

**Return type:** [`IdentifyResponse`](#identifyresponse)

---

### `kyv.verifyFace(targetImage, sourceImage, options?)`

Compare two face images directly (1:1 face verification) without running a full KYC flow. Both images accept the same formats as `verify` images.

```typescript
const result = await kyv.verifyFace('./selfie.jpg', './document_photo.jpg');

if (result.is_match) {
  console.log(`Faces match! Score: ${result.similarity_score}, Confidence: ${result.confidence}`);
} else {
  console.log(`Faces do not match. Score: ${result.similarity_score}`);
}
```

With custom models:

```typescript
const result = await kyv.verifyFace(
  './selfie.jpg',
  './document_photo.jpg',
  { detectionModel: 'yolov8', recognitionModel: 'arcface' },
);
```

| Parameter                  | Type               | Default        | Description                                  |
|----------------------------|--------------------|----------------|----------------------------------------------|
| `targetImage`              | `string \| Buffer` | —              | Reference / target face image                |
| `sourceImage`              | `string \| Buffer` | —              | Probe / source face image                    |
| `options.detectionModel`   | `string`           | server default | Face detection model to use                  |
| `options.recognitionModel` | `string`           | server default | Face recognition model to use                |

**Options:** [`FaceVerifyOptions`](#faceverifyoptions)

**Return type:** [`FaceVerifyResponse`](#faceverifyresponse)

---

### `KyvShield.verifyWebhookSignature(payload, apiKey, signatureHeader)`

Static method. Validates an incoming KyvShield webhook using HMAC-SHA256.

```typescript
// Express example
import express from 'express';
import { KyvShield } from '@kyvshield/rest-sdk';

const app = express();

app.post('/webhook', express.raw({ type: '*/*' }), (req, res) => {
  const valid = KyvShield.verifyWebhookSignature(
    req.body,                                        // Buffer
    process.env.KYVSHIELD_API_KEY!,
    req.headers['x-kyvshield-signature'] as string,
  );

  if (!valid) return res.status(401).send('Invalid signature');

  const event = JSON.parse(req.body.toString());
  console.log('Webhook event:', event);
  res.sendStatus(200);
});
```

| Parameter           | Type     | Description                                           |
|---------------------|----------|-------------------------------------------------------|
| `payload`           | `Buffer` | Raw request body                                      |
| `apiKey`            | `string` | Your KyvShield API key                                |
| `signatureHeader`   | `string` | Value of `X-KyvShield-Signature` from the HTTP header |

Returns `true` if the signature is valid, `false` otherwise.

---

## Types Reference

### `VerifyOptions`

```typescript
interface VerifyOptions {
  /** Steps to execute in order. */
  steps: Step[];                          // e.g. ['selfie', 'recto', 'verso']

  /** Document type. */
  target: DocumentTarget;                 // e.g. 'SN-CIN'

  /** Response language. Default: 'fr' */
  language?: Language;                    // 'fr' | 'en' | 'wo'

  /** Global challenge intensity. Default: 'standard' */
  challengeMode?: ChallengeMode;          // 'minimal' | 'standard' | 'strict'

  /** Per-step overrides */
  selfieChallengeMode?: ChallengeMode;
  rectoChallengeMode?: ChallengeMode;
  versoChallengeMode?: ChallengeMode;

  /** Whether to match selfie face against document photo. Default: false */
  requireFaceMatch?: boolean;

  /** Whether to screen extracted identity against AML sanctions lists. Default: false */
  requireAml?: boolean;

  /** Your internal reference ID, echoed back in the response. */
  kycIdentifier?: string;

  /**
   * Map of images to submit.
   * Key format: `{step}_{challenge}`, e.g. 'recto_center_document'
   *
   * Each value can be one of **four formats** (auto-detected):
   * - `Buffer`                            — raw bytes, used directly
   * - `'https://…'` / `'http://…'`        — URL, downloaded automatically (30s timeout)
   * - `'data:image/jpeg;base64,…'`        — data URI, base64 decoded
   * - long base64 string (no path sep.)   — decoded as base64
   * - any other string                    — treated as a filesystem path
   */
  images: Record<string, string | Buffer>;
}
```

### `KycResponse`

```typescript
interface KycResponse {
  success: boolean;
  session_id: string;
  overall_status: 'pass' | 'reject';
  overall_confidence: number;        // 0–1
  processing_time_ms: number;
  face_verification?: FaceVerification;
  steps: StepResult[];
}
```

### `StepResult`

```typescript
interface StepResult {
  step_index: number;
  step_type: 'selfie' | 'recto' | 'verso';
  success: boolean;
  processing_time_ms: number;

  liveness: {
    is_live: boolean;
    score: number;                    // 0–1
    confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  };

  verification: {
    is_authentic: boolean;
    confidence: number;               // 0–1
    checks_passed: string[];
    fraud_indicators: string[];
    warnings: string[];
    issues: string[];
  };

  user_messages: string[];

  // Document steps (recto / verso) only:
  aligned_document?: string;          // base64 JPEG
  extraction?: ExtractionField[];
  extracted_photos?: ExtractedPhoto[];

  // Selfie step only:
  captured_image?: string;            // base64 JPEG
}
```

### `ExtractionField`

```typescript
interface ExtractionField {
  key: string;
  document_key: string;
  label: string;
  value: string;
  display_priority: number;
  icon?: string;
}
```

### `ExtractedPhoto`

```typescript
interface ExtractedPhoto {
  image: string;          // base64 JPEG
  confidence: number;     // 0–1
  bbox: number[];         // [x, y, width, height]
  area: number;
  width: number;
  height: number;
}
```

### `FaceVerification`

```typescript
interface FaceVerification {
  is_match: boolean;
  similarity_score: number;   // 0–100
}
```

### `IdentifyOptions`

```typescript
interface IdentifyOptions {
  topK?: number;       // max matches to return (default: 5)
  minScore?: number;   // minimum similarity threshold 0–1 (default: 0.5)
}
```

### `IdentifyResponse`

```typescript
interface IdentifyResponse {
  success: boolean;
  search_id: string;
  results_count: number;
  matches: IdentifyMatch[];
  top_k: number;
  min_score: number;
  processing_time_ms: number;
}

interface IdentifyMatch {
  identity_id: string;
  score: number;                        // 0–1
  full_name: string;
  identifier_key: string;
  identifier_value: string;
  document_type: string;
  country: string;
  estimated_age: number;
  predicted_gender: string;
  extraction: Record<string, unknown>;
  created_at: string;                   // ISO 8601
}
```

### `FaceVerifyOptions`

```typescript
interface FaceVerifyOptions {
  detectionModel?: string;    // face detection model (server default if omitted)
  recognitionModel?: string;  // face recognition model (server default if omitted)
}
```

### `FaceVerifyResponse`

```typescript
interface FaceVerifyResponse {
  success: boolean;
  is_match: boolean;
  similarity_score: number;       // 0–100
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  detection_model: string;
  recognition_model: string;
  processing_time_ms: number;
}
```

### `AmlScreening`

```typescript
interface AmlScreening {
  status: 'clear' | 'hit' | 'error';
  risk_level: 'low' | 'medium' | 'high' | 'critical';
  is_sanctioned: boolean;
  is_pep: boolean;
  matches: AmlMatch[];
  screened_against: string[];   // e.g. ['ofac', 'un', 'eu', 'uk', 'fr']
  screened_at: string;          // ISO 8601
  total_entries_checked: number;
}

interface AmlMatch {
  source: string;               // 'ofac' | 'un' | 'eu' | 'uk' | 'fr'
  name_matched: string;
  match_score: number;          // 0–1
  programs: string[];
  listed_on: string;            // ISO 8601
}
```

### `ChallengesResponse`

```typescript
interface ChallengesResponse {
  challenges: {
    document: { minimal: string[]; standard: string[]; strict: string[] };
    selfie:   { minimal: string[]; standard: string[]; strict: string[] };
  };
}
```

---

## AML/Sanctions Screening

### Inline (during KYC)

Add `requireAml: true` to your verify options to automatically screen the extracted identity:

```typescript
const result = await kyv.verify({
  steps: ['selfie', 'recto', 'verso'],
  target: 'SN-CIN',
  requireFaceMatch: true,
  requireAml: true,
  images: { ... },
});

if (result.aml_screening?.status === 'hit') {
  console.warn('AML match found!', result.aml_screening.matches);
}
```

### Standalone: POST /api/v1/verify/aml

Screen a person against international sanctions lists and PEP databases without running a full KYC verification.

```typescript
const resp = await fetch('https://kyvshield-naruto.innolinkcloud.com/api/v1/verify/aml', {
  method: 'POST',
  headers: {
    'X-API-Key': 'YOUR_API_KEY',
    'Content-Type': 'application/json',
  },
  body: JSON.stringify({
    first_name: 'John',
    last_name: 'Doe',
    birth_date: '1990-01-15',
    nationality: 'US',
    id_number: '123456789',
    id_type: 'national_id',
  }),
});
const aml = await resp.json(); // AmlScreening
```

---

## Error handling

All API errors throw a `KyvShieldError`:

```typescript
import { KyvShield, KyvShieldError } from '@kyvshield/rest-sdk';

try {
  const result = await kyv.verify({ ... });
} catch (e) {
  if (e instanceof KyvShieldError) {
    console.error('Status code:', e.statusCode);   // e.g. 401, 422, 500
    console.error('Body:', e.body);                // parsed JSON body when available
    console.error('Message:', e.message);
  }
}
```

The SDK also throws `KyvShieldError` (without a `statusCode`) for:
- Empty API key in the constructor
- Empty `steps` array
- Empty `target` string
- Empty `images` map
- Image file path that does not exist on disk

---

## Building

```bash
npm install
npm run build      # outputs to ./dist
npm run lint       # type-check only, no emit
npm run build:watch
```

---

## Running the test suite

Ensure the KyvShield backend is running locally on port 8080, then:

```bash
npx ts-node --esm test.ts
```

The test script covers:
- Constructor validation
- Webhook signature verification (HMAC-SHA256, timing-safe comparison)
- `VerifyOptions` validation (no network)
- `getChallenges()` (network)
- `verify()` with a single recto step (network)
- `verify()` with recto + verso steps (network)

---

## Challenge modes

| Mode       | Document challenges                                                        | Selfie challenges                                               |
|------------|----------------------------------------------------------------------------|-----------------------------------------------------------------|
| `minimal`  | `center_document`                                                          | `center_face`, `close_eyes`                                     |
| `standard` | `center_document`, `tilt_left`, `tilt_right`                               | `center_face`, `close_eyes`, `turn_left`, `turn_right`          |
| `strict`   | `center_document`, `tilt_left`, `tilt_right`, `tilt_forward`, `tilt_back` | `center_face`, `close_eyes`, `turn_left`, `turn_right`, `smile`, `look_up`, `look_down` |

The image map key for each image is `{step}_{challenge}`, e.g.:
- `recto_center_document`
- `recto_tilt_left`
- `selfie_center_face`
- `selfie_turn_right`

---

## Supported document targets

| Value                 | Document                        |
|-----------------------|---------------------------------|
| `SN-CIN`              | Carte d'Identité Nationale (SN) |
| `SN-PASSPORT`         | Passeport sénégalais            |
| `SN-DRIVER-LICENCE`   | Permis de conduire (SN)         |

Custom target strings are also accepted.

---

## License

MIT
