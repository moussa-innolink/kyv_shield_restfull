import { KyvShield } from '@kyvshield/rest-sdk';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const IMAGE_DIR = '/Users/macbookpro/GolandProjects/cin_verification/api/document_id';

const API_KEY = 'kyvshield_demo_key_2024';
const BASE_URL = 'https://kyvshield-naruto.innolinkcloud.com';

const kyv = new KyvShield(API_KEY, BASE_URL);

console.log('=== KyvShield REST SDK — npm install test ===');
console.log(`Base URL: ${BASE_URL}`);
console.log();

// Test 1: getChallenges
console.log('[TEST 1] getChallenges...');
try {
  const challenges = await kyv.getChallenges();
  console.log('  selfie modes:', Object.keys(challenges.challenges.selfie));
  console.log('  document modes:', Object.keys(challenges.challenges.document));
  console.log('  [PASS] getChallenges\n');
} catch (e) {
  console.log(`  [FAIL] ${e.message}\n`);
}

// Test 2: verify recto+verso SN-CIN
console.log('[TEST 2] verify recto+verso SN-CIN...');
try {
  const result = await kyv.verify({
    steps: ['recto', 'verso'],
    target: 'SN-CIN',
    language: 'fr',
    challengeMode: 'minimal',
    rectoChallengeMode: 'minimal',
    versoChallengeMode: 'minimal',
    images: {
      recto_center_document: path.join(IMAGE_DIR, 'SN-CIN', 'RECTO.jpg'),
      verso_center_document: path.join(IMAGE_DIR, 'SN-CIN', 'VERSO.jpg'),
    },
  });

  console.log(`  session_id: ${result.session_id}`);
  console.log(`  overall_status: ${result.overall_status}`);
  console.log(`  overall_confidence: ${result.overall_confidence}`);
  console.log(`  steps: ${result.steps.length}`);
  console.log(`  processing_time_ms: ${result.processing_time_ms}`);

  for (const step of result.steps) {
    console.log(`  --- ${step.step_type} ---`);
    console.log(`    success: ${step.success}`);
    console.log(`    liveness: score=${step.liveness?.score}, confidence=${step.liveness?.confidence}`);
    console.log(`    extraction: ${step.extraction?.length || 0} fields`);
    if (step.extraction) {
      for (const f of step.extraction) {
        console.log(`      ${f.label}: ${f.value} (priority=${f.display_priority})`);
      }
    }
    console.log(`    user_messages: ${JSON.stringify(step.user_messages)}`);
  }

  // AML Screening
  if (result.aml_screening) {
    console.log(`  --- AML Screening ---`);
    console.log(`    performed: ${result.aml_screening.performed}`);
    console.log(`    status: ${result.aml_screening.status}`);
    console.log(`    risk_level: ${result.aml_screening.risk_level}`);
    console.log(`    total_matches: ${result.aml_screening.total_matches}`);
    console.log(`    duration_ms: ${result.aml_screening.duration_ms}`);
    if (result.aml_screening.matches?.length > 0) {
      for (const m of result.aml_screening.matches) {
        console.log(`      match: ${m.name} (score=${m.score}, datasets=${m.datasets?.join(',')}, topics=${m.topics?.join(',')})`);
      }
    }
  }

  console.log(`  [${result.success ? 'PASS' : 'REJECT'}] verify\n`);
} catch (e) {
  console.log(`  [FAIL] ${e.message}\n`);
}

// Test 3: webhook signature
console.log('[TEST 3] verifyWebhookSignature...');
const payload = Buffer.from('{"event":"test"}');
const crypto = await import('crypto');
const sig = 'sha256=' + crypto.createHmac('sha256', API_KEY).update(payload).digest('hex');
const valid = KyvShield.verifyWebhookSignature(payload, API_KEY, sig);
console.log(`  valid signature: ${valid}`);
console.log(`  [${valid ? 'PASS' : 'FAIL'}] webhook\n`);

console.log('=== Done ===');
