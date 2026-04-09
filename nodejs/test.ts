/**
 * KyvShield REST SDK — Integration Test Script
 *
 * Runs against a local KyvShield server (http://localhost:8080) using
 * real SN-CIN test images.  Exercises getChallenges() and verify().
 *
 * Usage:
 *   npx ts-node --esm test.ts
 *   # or after building:
 *   node dist/index.js  (not applicable — test.ts is standalone)
 */

import { KyvShield, KyvShieldError } from './src/index.js';
import type { ChallengesResponse, KycResponse, StepResult } from './src/types.js';
import * as crypto from 'node:crypto';

// ─── Config ───────────────────────────────────────────────────────────────────

// Supported environments
const ENVS: Record<string, string> = {
  local: 'http://localhost:8080',
  dev: 'https://kyvshield.innolink.sn',
  prod: 'https://kyvshield-naruto.innolinkcloud.com',
};

const env = process.env['KYVSHIELD_ENV'] ?? 'local';
const BASE_URL = process.env['KYVSHIELD_BASE_URL'] ?? ENVS[env] ?? ENVS['local']!;
const API_KEY = process.env['KYVSHIELD_API_KEY'] ?? 'kyvshield_demo_key_2024';

const IMAGE_DIR =
  process.env['KYVSHIELD_IMAGE_DIR'] ??
  '/Users/macbookpro/GolandProjects/cin_verification/api/document_id';

const RECTO_PATH = `${IMAGE_DIR}/SN-CIN/RECTO.jpg`;
const VERSO_PATH = `${IMAGE_DIR}/SN-CIN/VERSO.jpg`;

// ─── Helpers ──────────────────────────────────────────────────────────────────

let passed = 0;
let failed = 0;

function ok(label: string): void {
  console.log(`  ✓ ${label}`);
  passed++;
}

function fail(label: string, reason?: unknown): void {
  console.error(`  ✗ ${label}`);
  if (reason !== undefined) console.error('    →', reason);
  failed++;
}

function section(title: string): void {
  console.log(`\n──────────────────────────────────────────`);
  console.log(`  ${title}`);
  console.log(`──────────────────────────────────────────`);
}

// ─── Test: Constructor validation ────────────────────────────────────────────

section('1. Constructor validation');

try {
  // @ts-expect-error — intentional bad call for testing
  new KyvShield('');
  fail('should throw on empty API key');
} catch (e) {
  if (e instanceof KyvShieldError) {
    ok('throws KyvShieldError on empty API key');
  } else {
    fail('wrong error type on empty API key', e);
  }
}

try {
  const kyv = new KyvShield(API_KEY, BASE_URL);
  ok('instantiates with API key and base URL');
  console.log(`    client created: KyvShield @ ${BASE_URL}`);
  void kyv; // suppress unused warning
} catch (e) {
  fail('failed to instantiate client', e);
}

// ─── Test: Webhook signature verification ────────────────────────────────────

section('2. Webhook signature verification');

{
  const testKey = 'test-webhook-key';
  const testPayload = Buffer.from(JSON.stringify({ event: 'kyc.complete', session_id: 'abc123' }));

  const validSig = crypto
    .createHmac('sha256', testKey)
    .update(testPayload)
    .digest('hex');

  const result1 = KyvShield.verifyWebhookSignature(testPayload, testKey, validSig);
  if (result1 === true) {
    ok('accepts correct HMAC-SHA256 signature');
  } else {
    fail('rejected a valid signature');
  }

  const result2 = KyvShield.verifyWebhookSignature(testPayload, testKey, `sha256=${validSig}`);
  if (result2 === true) {
    ok('accepts signature with "sha256=" prefix');
  } else {
    fail('rejected signature with sha256= prefix');
  }

  const result3 = KyvShield.verifyWebhookSignature(testPayload, testKey, 'deadbeef');
  if (result3 === false) {
    ok('rejects tampered signature');
  } else {
    fail('accepted a tampered signature — security issue!');
  }

  const tamperedPayload = Buffer.from('{ "event": "kyc.complete", "session_id": "HACKED" }');
  const result4 = KyvShield.verifyWebhookSignature(tamperedPayload, testKey, validSig);
  if (result4 === false) {
    ok('rejects signature for tampered payload');
  } else {
    fail('accepted signature for tampered payload — security issue!');
  }

  // Empty / null guards
  const result5 = KyvShield.verifyWebhookSignature(Buffer.from(''), testKey, validSig);
  if (result5 === false) {
    ok('rejects empty payload');
  } else {
    fail('accepted empty payload');
  }
}

// ─── Test: VerifyOptions validation ──────────────────────────────────────────

section('3. VerifyOptions validation (local, no network)');

const kyv = new KyvShield(API_KEY, BASE_URL);

async function testOptionsValidation(): Promise<void> {
  // Missing steps
  try {
    await kyv.verify({ steps: [], target: 'SN-CIN', images: { recto_center_document: RECTO_PATH } });
    fail('should throw on empty steps array');
  } catch (e) {
    if (e instanceof KyvShieldError) ok('throws on empty steps array');
    else fail('wrong error type for empty steps', e);
  }

  // Missing target
  try {
    await kyv.verify({ steps: ['recto'], target: '', images: { recto_center_document: RECTO_PATH } });
    fail('should throw on empty target');
  } catch (e) {
    if (e instanceof KyvShieldError) ok('throws on empty target');
    else fail('wrong error type for empty target', e);
  }

  // Missing images map
  try {
    await kyv.verify({ steps: ['recto'], target: 'SN-CIN', images: {} });
    fail('should throw on empty images map');
  } catch (e) {
    if (e instanceof KyvShieldError) ok('throws on empty images map');
    else fail('wrong error type for empty images', e);
  }

  // Missing file on disk
  try {
    await kyv.verify({
      steps: ['recto'],
      target: 'SN-CIN',
      images: { recto_center_document: '/nonexistent/path/image.jpg' },
    });
    fail('should throw when image file does not exist');
  } catch (e) {
    if (e instanceof KyvShieldError) ok('throws when image file not found on disk');
    else fail('wrong error type for missing file', e);
  }
}

await testOptionsValidation();

// ─── Test: getChallenges (network) ────────────────────────────────────────────

section('4. GET /api/v1/challenges (network)');

let challenges: ChallengesResponse | undefined;

try {
  challenges = await kyv.getChallenges();

  if (challenges && typeof challenges === 'object') {
    ok('received response object');
  } else {
    fail('response is not an object');
  }

  if (challenges?.challenges?.document && typeof challenges.challenges.document === 'object') {
    ok('challenges.document is present');
  } else {
    fail('challenges.document is missing or wrong type');
  }

  if (challenges?.challenges?.selfie && typeof challenges.challenges.selfie === 'object') {
    ok('challenges.selfie is present');
  } else {
    fail('challenges.selfie is missing or wrong type');
  }

  const modes = ['minimal', 'standard', 'strict'] as const;
  for (const mode of modes) {
    const docChallenges = challenges?.challenges?.document?.[mode];
    if (Array.isArray(docChallenges) && docChallenges.length > 0) {
      ok(`document.${mode} has ${docChallenges.length} challenge(s): [${docChallenges.join(', ')}]`);
    } else {
      fail(`document.${mode} is missing or empty`);
    }
  }

  for (const mode of modes) {
    const selfieChallenges = challenges?.challenges?.selfie?.[mode];
    if (Array.isArray(selfieChallenges) && selfieChallenges.length > 0) {
      ok(`selfie.${mode} has ${selfieChallenges.length} challenge(s): [${selfieChallenges.join(', ')}]`);
    } else {
      fail(`selfie.${mode} is missing or empty`);
    }
  }

  console.log('\n  Full challenges payload:');
  console.log(JSON.stringify(challenges, null, 4).split('\n').map(l => '    ' + l).join('\n'));

} catch (e) {
  if (e instanceof KyvShieldError && e.statusCode) {
    fail(`API error ${e.statusCode} — is the backend running at ${BASE_URL}?`, e.message);
  } else {
    fail('Network error — is the backend running at ' + BASE_URL + '?', e);
  }
}

// ─── Test: verify() — recto only (network) ───────────────────────────────────

section('5. POST /api/v1/kyc/verify — recto only (network)');

let verifyResult: KycResponse | undefined;

try {
  verifyResult = await kyv.verify({
    steps: ['recto'],
    target: 'SN-CIN',
    language: 'fr',
    challengeMode: 'minimal',
    requireFaceMatch: false,
    images: {
      recto_center_document: RECTO_PATH,
    },
  });

  if (verifyResult && typeof verifyResult === 'object') {
    ok('received response object');
  } else {
    fail('response is not an object');
  }

  if (typeof verifyResult?.session_id === 'string' && verifyResult.session_id.length > 0) {
    ok(`session_id present: ${verifyResult.session_id}`);
  } else {
    fail('session_id missing or empty');
  }

  if (verifyResult?.overall_status === 'pass' || verifyResult?.overall_status === 'reject') {
    ok(`overall_status is "${verifyResult.overall_status}"`);
  } else {
    fail('overall_status is not pass|reject', verifyResult?.overall_status);
  }

  if (typeof verifyResult?.overall_confidence === 'number') {
    ok(`overall_confidence = ${verifyResult.overall_confidence.toFixed(3)}`);
  } else {
    fail('overall_confidence is not a number');
  }

  if (typeof verifyResult?.processing_time_ms === 'number') {
    ok(`processing_time_ms = ${verifyResult.processing_time_ms}ms`);
  } else {
    fail('processing_time_ms is not a number');
  }

  if (Array.isArray(verifyResult?.steps) && verifyResult.steps.length > 0) {
    ok(`steps array has ${verifyResult.steps.length} element(s)`);
  } else {
    fail('steps array is missing or empty');
  }

  const rectoStep: StepResult | undefined = verifyResult?.steps?.[0];
  if (rectoStep?.step_type === 'recto') {
    ok('steps[0].step_type === "recto"');
  } else {
    fail(`steps[0].step_type expected "recto", got "${rectoStep?.step_type}"`);
  }

  if (typeof rectoStep?.liveness?.is_live === 'boolean') {
    ok(`liveness.is_live = ${rectoStep.liveness.is_live}`);
  } else {
    fail('liveness.is_live is missing');
  }

  if (typeof rectoStep?.verification?.is_authentic === 'boolean') {
    ok(`verification.is_authentic = ${rectoStep.verification.is_authentic}`);
  } else {
    fail('verification.is_authentic is missing');
  }

} catch (e) {
  if (e instanceof KyvShieldError && e.statusCode) {
    fail(`API error ${e.statusCode}`, e.message);
    console.error('    Response body:', e.body);
  } else {
    fail('Network or unexpected error', e);
  }
}

// ─── Test: verify() — recto + verso (network) ────────────────────────────────

section('6. POST /api/v1/kyc/verify — recto + verso (network)');

try {
  const result = await kyv.verify({
    steps: ['recto', 'verso'],
    target: 'SN-CIN',
    language: 'fr',
    challengeMode: 'minimal',
    requireFaceMatch: false,
    kycIdentifier: 'test-run-nodejs-sdk',
    images: {
      recto_center_document: RECTO_PATH,
      verso_center_document: VERSO_PATH,
    },
  });

  if (Array.isArray(result.steps) && result.steps.length === 2) {
    ok(`2 steps returned`);
  } else {
    fail(`expected 2 steps, got ${result.steps?.length}`);
  }

  const types = result.steps?.map((s: StepResult) => s.step_type) ?? [];
  if (types.includes('recto') && types.includes('verso')) {
    ok('both recto and verso steps present');
  } else {
    fail('missing recto or verso in steps', types);
  }

  const rectoStep = result.steps?.find((s: StepResult) => s.step_type === 'recto');
  if (Array.isArray(rectoStep?.extraction)) {
    ok(`recto.extraction has ${rectoStep!.extraction!.length} field(s)`);
    if (rectoStep!.extraction!.length > 0) {
      const first = rectoStep!.extraction![0]!;
      console.log(`    First extracted field: ${first.label} = ${first.value}`);
    }
  } else {
    // extraction may be null if liveness fails — not a hard failure
    console.log('    recto.extraction is absent (may be expected if liveness failed)');
  }

  console.log('\n  Summary:');
  console.log(`    session_id:          ${result.session_id}`);
  console.log(`    overall_status:      ${result.overall_status}`);
  console.log(`    overall_confidence:  ${result.overall_confidence.toFixed(3)}`);
  console.log(`    processing_time_ms:  ${result.processing_time_ms}ms`);

} catch (e) {
  if (e instanceof KyvShieldError && e.statusCode) {
    fail(`API error ${e.statusCode}`, e.message);
  } else {
    fail('Network or unexpected error', e);
  }
}

// ─── Summary ──────────────────────────────────────────────────────────────────

section('Test Summary');
console.log(`  Passed: ${passed}`);
console.log(`  Failed: ${failed}`);
console.log(`  Total:  ${passed + failed}`);
console.log('');

if (failed > 0) {
  console.error(`  ${failed} test(s) failed.`);
  process.exit(1);
} else {
  console.log('  All tests passed.');
  process.exit(0);
}
