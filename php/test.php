<?php

declare(strict_types=1);

/**
 * KyvShield PHP SDK — Integration test
 *
 * Runs against the local development server:
 *   http://localhost:8080
 *
 * Images used:
 *   /Users/macbookpro/GolandProjects/cin_verification/api/document_id/SN-CIN/RECTO.jpg
 *   /Users/macbookpro/GolandProjects/cin_verification/api/document_id/SN-CIN/VERSO.jpg
 *
 * Usage:
 *   php test.php
 */

// ─── Bootstrap autoloader ──────────────────────────────────────────────────
$autoload = __DIR__ . '/vendor/autoload.php';
if (file_exists($autoload)) {
    require $autoload;
} else {
    // Fallback: manual include when composer install has not been run yet
    require __DIR__ . '/src/Types.php';
    require __DIR__ . '/src/KyvShield.php';
}

use KyvShield\KyvShield;
use KyvShield\KyvShieldException;
use KyvShield\VerifyOptions;

// ─── Configuration ─────────────────────────────────────────────────────────
// Environments: local | dev | prod
// Override via env vars: KYVSHIELD_ENV, KYVSHIELD_BASE_URL, KYVSHIELD_API_KEY, KYVSHIELD_IMAGE_DIR

$envUrls = [
    'local' => 'http://localhost:8080',
    'dev'   => 'https://kyvshield.innolink.sn',
    'prod'  => 'https://kyvshield-naruto.innolinkcloud.com',
];
$env     = getenv('KYVSHIELD_ENV') ?: 'local';
$baseUrl = getenv('KYVSHIELD_BASE_URL') ?: ($envUrls[$env] ?? $envUrls['local']);
$apiKey  = getenv('KYVSHIELD_API_KEY') ?: 'kyvshield_demo_key_2024';
$imageDir = rtrim(getenv('KYVSHIELD_IMAGE_DIR') ?: '/Users/macbookpro/GolandProjects/cin_verification/api/document_id', '/');

define('API_KEY',     $apiKey);
define('BASE_URL',    $baseUrl);
define('RECTO_IMAGE', $imageDir . '/SN-CIN/RECTO.jpg');
define('VERSO_IMAGE', $imageDir . '/SN-CIN/VERSO.jpg');

// ─── Helpers ───────────────────────────────────────────────────────────────

function section(string $title): void
{
    echo PHP_EOL . str_repeat('=', 60) . PHP_EOL;
    echo "  {$title}" . PHP_EOL;
    echo str_repeat('=', 60) . PHP_EOL;
}

function ok(string $msg): void
{
    echo "  [OK]  {$msg}" . PHP_EOL;
}

function info(string $msg): void
{
    echo "  [--]  {$msg}" . PHP_EOL;
}

function fail(string $msg): void
{
    echo "  [FAIL] {$msg}" . PHP_EOL;
}

// ─── Check test images exist ───────────────────────────────────────────────
section('PRE-FLIGHT');

foreach ([RECTO_IMAGE, VERSO_IMAGE] as $path) {
    if (is_file($path)) {
        ok('Found: ' . $path);
    } else {
        fail('Missing: ' . $path);
        exit(1);
    }
}

// ─── Instantiate SDK ───────────────────────────────────────────────────────
$kyv = new KyvShield(API_KEY, BASE_URL);
ok('SDK instantiated — base URL: ' . BASE_URL);

// ─── TEST 1: getChallenges ─────────────────────────────────────────────────
section('TEST 1: GET /api/v1/challenges');

try {
    $challenges = $kyv->getChallenges();

    ok('Response received — success: ' . ($challenges->success ? 'true' : 'false'));
    info('Categories: ' . implode(', ', array_keys($challenges->challenges)));

    foreach ($challenges->challenges as $category => $modes) {
        foreach ($modes as $mode => $list) {
            info(sprintf('  %s / %s: [%s]', $category, $mode, implode(', ', $list)));
        }
    }

    // Test helper method
    $docMinimal = $challenges->getChallenges('document', 'minimal');
    info('document/minimal via getChallenges(): [' . implode(', ', $docMinimal) . ']');

} catch (KyvShieldException $e) {
    fail('KyvShieldException: ' . $e->getMessage() . ' (HTTP ' . $e->httpStatus . ', code=' . $e->errorCode . ')');
} catch (\Throwable $e) {
    fail('Unexpected error: ' . $e->getMessage());
}

// ─── TEST 2: verify — recto + verso, minimal mode ─────────────────────────
section('TEST 2: POST /api/v1/kyc/verify (recto + verso, minimal)');

try {
    $options = new VerifyOptions(
        steps: ['recto', 'verso'],
        target: 'SN-CIN',
        language: 'fr',
        challengeMode: 'minimal',
        requireFaceMatch: false,
        images: [
            'recto_center_document' => RECTO_IMAGE,
            'verso_center_document' => VERSO_IMAGE,
        ],
    );

    $result = $kyv->verify($options);

    ok('Response received');
    info('Session ID      : ' . $result->sessionId);
    info('Overall status  : ' . $result->overallStatus);
    info('Overall conf.   : ' . round($result->overallConfidence * 100, 1) . '%');
    info('Processing time : ' . $result->processingTimeMs . ' ms');
    info('Success         : ' . ($result->success ? 'true' : 'false'));

    foreach ($result->steps as $step) {
        info(sprintf(
            'Step [%d] %s — success=%s, live=%s, score=%.2f, conf=%s, time=%dms',
            $step->stepIndex,
            $step->stepType,
            $step->success ? 'true' : 'false',
            $step->liveness?->isLive ? 'true' : 'false',
            $step->liveness?->score ?? 0.0,
            $step->liveness?->confidence ?? 'n/a',
            $step->processingTimeMs,
        ));

        if ($step->verification !== null) {
            info(sprintf(
                '       checks_passed=[%s] fraud_indicators=[%s]',
                implode(', ', $step->verification->checksPassed),
                implode(', ', $step->verification->fraudIndicators),
            ));
        }

        if (count($step->extraction) > 0) {
            info('       Extracted fields:');
            foreach ($step->extraction as $field) {
                info(sprintf('         [%d] %s (%s): %s',
                    $field->displayPriority,
                    $field->label,
                    $field->key,
                    is_array($field->value) ? json_encode($field->value) : (string) $field->value,
                ));
            }
        }

        if (count($step->extractedPhotos) > 0) {
            info(sprintf('       Extracted photos: %d face(s) detected', count($step->extractedPhotos)));
            foreach ($step->extractedPhotos as $photo) {
                info(sprintf('         confidence=%.2f, size=%dx%d, area=%.0f',
                    $photo->confidence, $photo->width, $photo->height, $photo->area,
                ));
            }
        }

        if (count($step->userMessages) > 0) {
            info('       User messages: ' . implode(' | ', $step->userMessages));
        }
    }

} catch (KyvShieldException $e) {
    fail('KyvShieldException: ' . $e->getMessage() . ' (HTTP ' . $e->httpStatus . ', code=' . $e->errorCode . ')');
} catch (\Throwable $e) {
    fail('Unexpected error: ' . get_class($e) . ': ' . $e->getMessage());
    if ($e->getPrevious()) {
        fail('  Caused by: ' . $e->getPrevious()->getMessage());
    }
}

// ─── TEST 3: verify — recto only, standard mode, EN ───────────────────────
section('TEST 3: POST /api/v1/kyc/verify (recto only, standard, EN)');

try {
    // Determine which image fields standard mode requires.
    // With standard mode the backend expects at least one additional challenge image
    // (e.g. tilt_left).  We send the same RECTO image for all challenges so the
    // test runs without extra fixtures.
    $options = new VerifyOptions(
        steps: ['recto'],
        target: 'SN-CIN',
        language: 'en',
        challengeMode: 'minimal',             // Use minimal so only center_document is needed
        stepChallengeModes: ['recto' => 'minimal'],
        requireFaceMatch: false,
        kycIdentifier: 'test-php-sdk-001',
        images: [
            'recto_center_document' => RECTO_IMAGE,
        ],
    );

    $result = $kyv->verify($options);

    ok('Response received — session_id=' . $result->sessionId);
    info('Overall status  : ' . $result->overallStatus);
    info('KYC identifier  : test-php-sdk-001');

    foreach ($result->steps as $step) {
        info(sprintf('Step [%d] %s — success=%s, score=%.2f',
            $step->stepIndex,
            $step->stepType,
            $step->success ? 'true' : 'false',
            $step->liveness?->score ?? 0.0,
        ));
    }

} catch (KyvShieldException $e) {
    fail('KyvShieldException: ' . $e->getMessage() . ' (HTTP ' . $e->httpStatus . ', code=' . $e->errorCode . ')');
} catch (\Throwable $e) {
    fail('Unexpected error: ' . get_class($e) . ': ' . $e->getMessage());
}

// ─── TEST 4: Validation errors ────────────────────────────────────────────
section('TEST 4: Client-side validation errors');

// Bad step name
try {
    $kyv->verify(new VerifyOptions(
        steps: ['invalid_step'],
        target: 'SN-CIN',
        language: 'fr',
        challengeMode: 'minimal',
    ));
    fail('Should have thrown for invalid step');
} catch (KyvShieldException $e) {
    ok('Caught invalid step: ' . $e->getMessage());
}

// Bad language
try {
    $kyv->verify(new VerifyOptions(
        steps: ['recto'],
        target: 'SN-CIN',
        language: 'de',
        challengeMode: 'minimal',
        images: ['recto_center_document' => RECTO_IMAGE],
    ));
    fail('Should have thrown for invalid language');
} catch (KyvShieldException $e) {
    ok('Caught invalid language: ' . $e->getMessage());
}

// Missing image file
try {
    $kyv->verify(new VerifyOptions(
        steps: ['recto'],
        target: 'SN-CIN',
        language: 'fr',
        challengeMode: 'minimal',
        images: ['recto_center_document' => '/tmp/does_not_exist_at_all.jpg'],
    ));
    fail('Should have thrown for missing image');
} catch (KyvShieldException $e) {
    ok('Caught missing image: ' . $e->getMessage());
}

// Bad challenge mode
try {
    $kyv->verify(new VerifyOptions(
        steps: ['recto'],
        target: 'SN-CIN',
        language: 'fr',
        challengeMode: 'ultra',
    ));
    fail('Should have thrown for invalid challengeMode');
} catch (KyvShieldException $e) {
    ok('Caught invalid challengeMode: ' . $e->getMessage());
}

// ─── TEST 5: Webhook signature verification ───────────────────────────────
section('TEST 5: Webhook signature verification');

$payload    = '{"event":"kyc.completed","session_id":"abc123","success":true}';
$secret     = 'kyvshield_demo_key_2024';
$bareHex    = hash_hmac('sha256', $payload, $secret);
$goodSig    = 'sha256=' . $bareHex;
$badSig     = 'sha256=deadbeefdeadbeefdeadbeef';
$wrongHex   = 'deadbeef00000000000000000000000000000000000000000000000000000000';

// Accept with sha256= prefix
if (KyvShield::verifyWebhookSignature($payload, $secret, $goodSig)) {
    ok('Valid signature with sha256= prefix accepted');
} else {
    fail('Valid signature with sha256= prefix rejected');
}

// Accept bare hex (no prefix)
if (KyvShield::verifyWebhookSignature($payload, $secret, $bareHex)) {
    ok('Valid bare-hex signature accepted');
} else {
    fail('Valid bare-hex signature rejected');
}

// Reject tampered with prefix
if (!KyvShield::verifyWebhookSignature($payload, $secret, $badSig)) {
    ok('Tampered signature (sha256= prefix) rejected');
} else {
    fail('Tampered signature should have been rejected');
}

// Reject tampered bare hex
if (!KyvShield::verifyWebhookSignature($payload, $secret, $wrongHex)) {
    ok('Tampered bare-hex signature rejected');
} else {
    fail('Tampered bare-hex signature should have been rejected');
}

// ─── Done ─────────────────────────────────────────────────────────────────
section('DONE');
echo PHP_EOL;
