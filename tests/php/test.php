<?php

declare(strict_types=1);

/**
 * KyvShield PHP SDK — Integration Tests
 *
 * Tests against the production endpoint with the demo API key.
 *
 * Run with:
 *   php tests/php/test.php
 */

require_once __DIR__ . '/../../php/src/Types.php';
require_once __DIR__ . '/../../php/src/KyvShield.php';

use KyvShield\KyvShield;
use KyvShield\KyvShieldException;
use KyvShield\VerifyOptions;

// ─── Configuration ────────────────────────────────────────────────────────────

const API_KEY   = 'kyvshield_demo_key_2024';
const BASE_URL  = 'https://kyvshield-naruto.innolinkcloud.com';
const RECTO_IMG = '/Users/macbookpro/GolandProjects/cin_verification/api/document_id/SN-CIN/RECTO.jpg';
const VERSO_IMG = '/Users/macbookpro/GolandProjects/cin_verification/api/document_id/SN-CIN/VERSO.jpg';

// ─── Helpers ──────────────────────────────────────────────────────────────────

$passed = 0;
$failed = 0;

function pass(string $name): void {
    global $passed;
    $passed++;
    echo "\033[32m[PASS]\033[0m {$name}\n";
}

function fail(string $name, string $reason): void {
    global $failed;
    $failed++;
    echo "\033[31m[FAIL]\033[0m {$name}: {$reason}\n";
}

function section(string $title): void {
    echo "\n\033[1m── {$title} ──\033[0m\n";
}

// ─── SDK Instance ─────────────────────────────────────────────────────────────

$kyv = new KyvShield(API_KEY, BASE_URL);

// ─── Test 1: getChallenges ────────────────────────────────────────────────────

section('TEST 1: getChallenges');
try {
    $challenges = $kyv->getChallenges();

    if ($challenges->success !== true) {
        fail('getChallenges success flag', 'expected true, got ' . var_export($challenges->success, true));
    } else {
        pass('getChallenges returns success=true');
    }

    $docChallenges = $challenges->getChallenges('document', 'minimal');
    if (empty($docChallenges)) {
        fail('document.minimal challenges', 'array is empty');
    } else {
        pass('document.minimal challenges not empty (' . implode(', ', $docChallenges) . ')');
    }

    $selfieChallenges = $challenges->getChallenges('selfie', 'standard');
    if (empty($selfieChallenges)) {
        fail('selfie.standard challenges', 'array is empty');
    } else {
        pass('selfie.standard challenges not empty (' . implode(', ', $selfieChallenges) . ')');
    }
} catch (KyvShieldException $e) {
    fail('getChallenges', $e->getMessage());
}

// ─── Test 2: verify recto + verso (file paths) ───────────────────────────────

section('TEST 2: verify recto + verso (file paths)');

$verifyResponse = null;
try {
    $options = new VerifyOptions(
        steps: ['recto', 'verso'],
        target: 'SN-CIN',
        language: 'fr',
        challengeMode: 'minimal',
        stepChallengeModes: [
            'recto' => 'minimal',
            'verso' => 'minimal',
        ],
        requireFaceMatch: false,
        kycIdentifier: 'php-integration-test-001',
        images: [
            'recto_center_document' => RECTO_IMG,
            'verso_center_document' => VERSO_IMG,
        ],
    );

    $verifyResponse = $kyv->verify($options);

    if (empty($verifyResponse->sessionId)) {
        fail('session_id', 'empty');
    } else {
        pass('session_id is set: ' . $verifyResponse->sessionId);
    }

    if (!in_array($verifyResponse->overallStatus, ['pass', 'reject'], true)) {
        fail('overall_status', 'unexpected value: ' . $verifyResponse->overallStatus);
    } else {
        pass('overall_status is valid: ' . $verifyResponse->overallStatus);
    }

    if ($verifyResponse->overallConfidence < 0.0 || $verifyResponse->overallConfidence > 1.0) {
        fail('overall_confidence', 'out of range [0,1]: ' . $verifyResponse->overallConfidence);
    } else {
        pass('overall_confidence in range: ' . number_format($verifyResponse->overallConfidence, 4));
    }

    if (count($verifyResponse->steps) !== 2) {
        fail('steps count', 'expected 2, got ' . count($verifyResponse->steps));
    } else {
        pass('steps array has 2 elements');
    }

    foreach ($verifyResponse->steps as $i => $step) {
        $label = "step[{$i}] ({$step->stepType})";
        if ($step->liveness === null) {
            fail("{$label} liveness", 'null');
        } else {
            pass("{$label} liveness.is_live = " . ($step->liveness->isLive ? 'true' : 'false')
                . ", score = " . number_format($step->liveness->score, 4));
        }
        if ($step->verification === null) {
            fail("{$label} verification", 'null');
        } else {
            pass("{$label} verification.is_authentic = " . ($step->verification->isAuthentic ? 'true' : 'false'));
        }
    }

    // Print extraction fields with display_priority
    echo "\n  Extraction fields (sorted by display_priority):\n";
    foreach ($verifyResponse->steps as $step) {
        if (!empty($step->extraction)) {
            foreach ($step->extraction as $field) {
                printf("    [%s][priority=%d] %s (%s) = %s\n",
                    $step->stepType,
                    $field->displayPriority,
                    $field->label,
                    $field->key,
                    $field->value
                );
            }
        }
    }

    // AML Screening
    if ($verifyResponse->amlScreening !== null) {
        $aml = $verifyResponse->amlScreening;
        echo "\n  AML Screening:\n";
        echo "    performed: " . ($aml->performed ? 'true' : 'false') . "\n";
        echo "    status: {$aml->status}\n";
        echo "    risk_level: {$aml->riskLevel}\n";
        echo "    total_matches: {$aml->totalMatches}\n";
        echo "    duration_ms: {$aml->durationMs}\n";
        foreach ($aml->matches as $m) {
            echo "      match: {$m->name} (score={$m->score}, datasets=" . implode(',', $m->datasets) . ", topics=" . implode(',', $m->topics) . ")\n";
        }
    }

} catch (KyvShieldException $e) {
    fail('verify recto+verso', $e->getMessage());
}

// ─── Test 3: verify recto using buffer/bytes ──────────────────────────────────

section('TEST 3: verify recto with raw bytes in images');
try {
    $rectoBytes = file_get_contents(RECTO_IMG);
    $versoBytes = file_get_contents(VERSO_IMG);

    if ($rectoBytes === false || $versoBytes === false) {
        fail('read test images', 'file_get_contents failed');
    } else {
        // PHP strings are byte strings — pass them directly.
        // The resolveImage() method will detect these as base64 vs path.
        // Since they contain binary data (non-printable) they'll fail base64 detection
        // and file_exists — so we use base64 encoding explicitly.
        $rectoB64 = base64_encode($rectoBytes);
        $versoB64 = base64_encode($versoBytes);

        $options = new VerifyOptions(
            steps: ['recto', 'verso'],
            target: 'SN-CIN',
            language: 'fr',
            challengeMode: 'minimal',
            requireFaceMatch: false,
            kycIdentifier: 'php-integration-test-bytes-001',
            images: [
                'recto_center_document' => $rectoB64,
                'verso_center_document' => $versoB64,
            ],
        );

        $response = $kyv->verify($options);
        pass('verify with base64 strings: overall_status = ' . $response->overallStatus);
    }
} catch (KyvShieldException $e) {
    fail('verify with base64 strings', $e->getMessage());
}

// ─── Test 4: verify recto using data URI ─────────────────────────────────────

section('TEST 4: verify recto with data URI');
try {
    $rectoBytes = file_get_contents(RECTO_IMG);
    $versoBytes = file_get_contents(VERSO_IMG);

    $options = new VerifyOptions(
        steps: ['recto', 'verso'],
        target: 'SN-CIN',
        language: 'fr',
        challengeMode: 'minimal',
        requireFaceMatch: false,
        kycIdentifier: 'php-integration-test-datauri-001',
        images: [
            'recto_center_document' => 'data:image/jpeg;base64,' . base64_encode($rectoBytes),
            'verso_center_document' => 'data:image/jpeg;base64,' . base64_encode($versoBytes),
        ],
    );

    $response = $kyv->verify($options);
    pass('verify with data URI: overall_status = ' . $response->overallStatus);
} catch (KyvShieldException $e) {
    fail('verify with data URI', $e->getMessage());
}

// ─── Test 5: verifyWebhookSignature ──────────────────────────────────────────

section('TEST 5: verifyWebhookSignature');

$payload   = '{"session_id":"test-session","overall_status":"pass"}';
$signature = hash_hmac('sha256', $payload, API_KEY);

if (!KyvShield::verifyWebhookSignature($payload, API_KEY, $signature)) {
    fail('verifyWebhookSignature (bare hex)', 'returned false for valid signature');
} else {
    pass('verifyWebhookSignature validates bare hex signature');
}

if (!KyvShield::verifyWebhookSignature($payload, API_KEY, 'sha256=' . $signature)) {
    fail('verifyWebhookSignature (sha256= prefix)', 'returned false for valid signature');
} else {
    pass('verifyWebhookSignature validates sha256= prefixed signature');
}

if (KyvShield::verifyWebhookSignature($payload, API_KEY, 'deadbeef')) {
    fail('verifyWebhookSignature (wrong sig)', 'returned true for invalid signature');
} else {
    pass('verifyWebhookSignature rejects invalid signature');
}

// ─── Summary ──────────────────────────────────────────────────────────────────

echo "\n\033[1m━━━ Results: {$passed} passed, {$failed} failed ━━━\033[0m\n";
exit($failed > 0 ? 1 : 0);
