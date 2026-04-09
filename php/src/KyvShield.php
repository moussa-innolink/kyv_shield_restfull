<?php

declare(strict_types=1);

namespace KyvShield;

/**
 * KyvShield PHP SDK — REST KYC API client.
 *
 * PHP 8.1+, zero external dependencies (uses curl).
 *
 * Usage:
 *   $kyv = new KyvShield('your-api-key');
 *   $challenges = $kyv->getChallenges();
 *   $result = $kyv->verify(new VerifyOptions(...));
 */
final class KyvShield
{
    private const DEFAULT_BASE_URL = 'https://kyvshield-naruto.innolinkcloud.com';

    /**
     * @param  string  $apiKey   Value sent in the X-API-Key header
     * @param  string  $baseUrl  Override to point at a different environment
     */
    public function __construct(
        private readonly string $apiKey,
        private readonly string $baseUrl = self::DEFAULT_BASE_URL,
    ) {
        if ($this->apiKey === '') {
            throw new KyvShieldException('API key must not be empty.');
        }
        if (!extension_loaded('curl')) {
            throw new KyvShieldException('The curl PHP extension is required by the KyvShield SDK.');
        }
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * GET /api/v1/challenges
     *
     * Returns the challenge configuration (which challenges are available per
     * category and mode).
     *
     * @throws KyvShieldException
     */
    public function getChallenges(): ChallengesResponse
    {
        $data = $this->request('GET', '/api/v1/challenges');

        return ChallengesResponse::fromArray($data);
    }

    /**
     * POST /api/v1/kyc/verify  (multipart/form-data)
     *
     * Runs the full KYC pipeline for the requested steps.
     *
     * @throws KyvShieldException
     */
    public function verify(VerifyOptions $options): KycResponse
    {
        $this->validateVerifyOptions($options);

        $fields = $this->buildFormFields($options);
        $data   = $this->request('POST', '/api/v1/kyc/verify', $fields);

        return KycResponse::fromArray($data);
    }

    /**
     * Submit multiple KYC verification requests in parallel using curl_multi.
     *
     * @param  VerifyOptions[]  $optionsList  Up to 10 VerifyOptions instances
     * @return array<int, array{success: bool, result: KycResponse|null, error: string|null}>
     * @throws KyvShieldException if batch size exceeds 10
     */
    public function verifyBatch(array $optionsList): array
    {
        if (count($optionsList) > 10) {
            throw new KyvShieldException('Batch size cannot exceed 10');
        }

        $multiHandle = curl_multi_init();
        $handles     = [];
        $results     = [];

        foreach ($optionsList as $i => $options) {
            $this->validateVerifyOptions($options);
            $fields = $this->buildFormFields($options);

            $url = rtrim($this->baseUrl, '/') . '/api/v1/kyc/verify';
            $ch  = curl_init($url);
            if ($ch === false) {
                throw new KyvShieldException('curl_init failed for URL: ' . $url);
            }

            $headers = [
                'X-API-Key: ' . $this->apiKey,
                'Accept: application/json',
            ];

            curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
            curl_setopt($ch, CURLOPT_FOLLOWLOCATION, true);
            curl_setopt($ch, CURLOPT_TIMEOUT, 120);
            curl_setopt($ch, CURLOPT_CONNECTTIMEOUT, 10);
            curl_setopt($ch, CURLOPT_POST, true);
            curl_setopt($ch, CURLOPT_POSTFIELDS, $fields);
            curl_setopt($ch, CURLOPT_HTTPHEADER, $headers);

            curl_multi_add_handle($multiHandle, $ch);
            $handles[$i] = $ch;
        }

        // Execute all requests in parallel
        do {
            $status = curl_multi_exec($multiHandle, $active);
            if ($active) {
                curl_multi_select($multiHandle);
            }
        } while ($active && $status === CURLM_OK);

        // Collect results
        foreach ($handles as $i => $ch) {
            $rawBody    = curl_multi_getcontent($ch);
            $errno      = curl_errno($ch);
            $errstr     = curl_error($ch);
            $httpStatus = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);

            curl_multi_remove_handle($multiHandle, $ch);
            curl_close($ch);

            if ($errno !== 0) {
                $results[$i] = ['success' => false, 'result' => null, 'error' => sprintf('curl errno %d: %s', $errno, $errstr)];
                continue;
            }

            if ($rawBody === false || $rawBody === '') {
                $results[$i] = ['success' => false, 'result' => null, 'error' => 'Empty response body (HTTP ' . $httpStatus . ')'];
                continue;
            }

            /** @var array<string,mixed>|null $decoded */
            $decoded = json_decode((string) $rawBody, true);

            if (!is_array($decoded)) {
                $results[$i] = ['success' => false, 'result' => null, 'error' => 'Non-JSON response (HTTP ' . $httpStatus . ')'];
                continue;
            }

            if ($httpStatus >= 400) {
                $errorMsg    = (string) ($decoded['error'] ?? 'Unknown error');
                $results[$i] = ['success' => false, 'result' => null, 'error' => '[' . $httpStatus . '] ' . $errorMsg];
                continue;
            }

            try {
                $results[$i] = ['success' => true, 'result' => KycResponse::fromArray($decoded), 'error' => null];
            } catch (\Throwable $e) {
                $results[$i] = ['success' => false, 'result' => null, 'error' => 'Parse error: ' . $e->getMessage()];
            }
        }

        curl_multi_close($multiHandle);

        return $results;
    }

    /**
     * Verify that an incoming webhook was signed by the KyvShield API.
     *
     * The API signs webhook payloads with HMAC-SHA256 using the caller's raw
     * API key.  The signature is delivered in the X-KyvShield-Signature header
     * as "sha256=<hex>".
     *
     * @param  string  $payload           Raw request body (do not parse before calling this)
     * @param  string  $apiKey            Your API key (used as the HMAC secret)
     * @param  string  $signatureHeader   Value of the X-KyvShield-Signature header
     */
    public static function verifyWebhookSignature(
        string $payload,
        string $apiKey,
        string $signatureHeader,
    ): bool {
        // Strip prefix if present, accept bare hex too
        if (str_starts_with($signatureHeader, 'sha256=')) {
            $received = substr($signatureHeader, 7);
        } else {
            $received = $signatureHeader;
        }

        $expectedHex = hash_hmac('sha256', $payload, $apiKey);

        return hash_equals($expectedHex, $received);
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    /**
     * Execute an HTTP request using curl.
     *
     * For POST requests $body should be a flat array where each value is either
     * a scalar (form field) or a CURLFile (file upload).
     *
     * @param  array<string,mixed>|null  $body
     * @return array<string,mixed>
     * @throws KyvShieldException
     */
    private function request(string $method, string $path, ?array $body = null): array
    {
        $url = rtrim($this->baseUrl, '/') . $path;

        $ch = curl_init($url);
        if ($ch === false) {
            throw new KyvShieldException('curl_init failed for URL: ' . $url);
        }

        $headers = [
            'X-API-Key: ' . $this->apiKey,
            'Accept: application/json',
        ];

        curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
        curl_setopt($ch, CURLOPT_FOLLOWLOCATION, true);
        curl_setopt($ch, CURLOPT_TIMEOUT, 120);        // 2-minute timeout for AI analysis
        curl_setopt($ch, CURLOPT_CONNECTTIMEOUT, 10);

        if ($method === 'POST') {
            curl_setopt($ch, CURLOPT_POST, true);

            if ($body !== null && count($body) > 0) {
                // Multipart if any value is a CURLFile, otherwise urlencoded
                $hasFile = false;
                foreach ($body as $v) {
                    if ($v instanceof \CURLFile) {
                        $hasFile = true;
                        break;
                    }
                }

                if ($hasFile) {
                    // curl handles multipart automatically when given an array with CURLFile
                    curl_setopt($ch, CURLOPT_POSTFIELDS, $body);
                    // Do NOT set Content-Type manually — curl sets the boundary automatically
                } else {
                    curl_setopt($ch, CURLOPT_POSTFIELDS, http_build_query($body));
                    $headers[] = 'Content-Type: application/x-www-form-urlencoded';
                }
            }
        }

        curl_setopt($ch, CURLOPT_HTTPHEADER, $headers);

        $rawBody    = curl_exec($ch);
        $errno      = curl_errno($ch);
        $errstr     = curl_error($ch);
        $httpStatus = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);

        curl_close($ch);

        if ($errno !== 0) {
            throw new KyvShieldException(
                sprintf('Network error (curl errno %d): %s', $errno, $errstr),
            );
        }

        if ($rawBody === false || $rawBody === '') {
            throw new KyvShieldException(
                sprintf('Empty response body from %s %s (HTTP %d).', $method, $path, $httpStatus),
                $httpStatus,
            );
        }

        /** @var array<string,mixed>|null $decoded */
        $decoded = json_decode((string) $rawBody, true);

        if (!is_array($decoded)) {
            throw new KyvShieldException(
                sprintf('Non-JSON response from %s %s (HTTP %d): %s', $method, $path, $httpStatus, substr((string) $rawBody, 0, 300)),
                $httpStatus,
            );
        }

        if ($httpStatus >= 400) {
            $errorMsg  = (string) ($decoded['error'] ?? 'Unknown error');
            $errorCode = isset($decoded['code']) ? (string) $decoded['code'] : null;

            throw new KyvShieldException(
                sprintf('[%d] %s', $httpStatus, $errorMsg),
                $httpStatus,
                $errorCode,
            );
        }

        return $decoded;
    }

    /**
     * Validate VerifyOptions before sending the request.
     *
     * @throws KyvShieldException
     */
    private function validateVerifyOptions(VerifyOptions $options): void
    {
        if (empty($options->target) || trim($options->target) === '') {
            throw new KyvShieldException('target must not be empty.');
        }

        if (empty($options->images)) {
            throw new KyvShieldException('images must contain at least one entry.');
        }

        $validSteps = ['selfie', 'recto', 'verso'];
        foreach ($options->steps as $step) {
            if (!in_array($step, $validSteps, true)) {
                throw new KyvShieldException(sprintf(
                    'Invalid step "%s". Valid values: %s.',
                    $step,
                    implode(', ', $validSteps),
                ));
            }
        }

        $validModes = ['minimal', 'standard', 'strict'];
        if (!in_array($options->challengeMode, $validModes, true)) {
            throw new KyvShieldException(sprintf(
                'Invalid challengeMode "%s". Valid values: %s.',
                $options->challengeMode,
                implode(', ', $validModes),
            ));
        }

        foreach ($options->stepChallengeModes as $step => $mode) {
            if (!in_array($mode, $validModes, true)) {
                throw new KyvShieldException(sprintf(
                    'Invalid challenge mode "%s" for step "%s". Valid values: %s.',
                    $mode,
                    $step,
                    implode(', ', $validModes),
                ));
            }
        }

        $validLangs = ['fr', 'en', 'wo'];
        if (!in_array($options->language, $validLangs, true)) {
            throw new KyvShieldException(sprintf(
                'Invalid language "%s". Valid values: %s.',
                $options->language,
                implode(', ', $validLangs),
            ));
        }

        foreach ($options->images as $fieldName => $value) {
            // Only validate plain filesystem paths; URLs, data URIs, and base64
            // strings are resolved lazily when building the multipart body.
            if (str_starts_with($value, 'http://') || str_starts_with($value, 'https://')) {
                continue; // URL — resolved at send time
            }
            if (str_starts_with($value, 'data:image/')) {
                continue; // Data URI — decoded at send time
            }
            // Heuristic for a bare base64 string: no path separators, very long, no extension
            if (!str_contains($value, '/') && !str_contains($value, '\\')
                && strlen($value) > 64 && !preg_match('/\.\w{2,5}$/', $value)) {
                continue; // Base64 string — decoded at send time
            }
            if (!is_file($value)) {
                throw new KyvShieldException(sprintf(
                    'Image file not found for field "%s": %s',
                    $fieldName,
                    $value,
                ));
            }
            if (!is_readable($value)) {
                throw new KyvShieldException(sprintf(
                    'Image file is not readable for field "%s": %s',
                    $fieldName,
                    $value,
                ));
            }
        }
    }

    /**
     * Build the flat multipart field array for curl.
     *
     * @return array<string,mixed>
     * @throws KyvShieldException
     */
    private function buildFormFields(VerifyOptions $options): array
    {
        $fields = [
            'steps'            => json_encode($options->steps, JSON_THROW_ON_ERROR),
            'target'           => $options->target,
            'language'         => $options->language,
            'challenge_mode'   => $options->challengeMode,
            'require_face_match' => $options->requireFaceMatch ? 'true' : 'false',
        ];

        if ($options->kycIdentifier !== null && $options->kycIdentifier !== '') {
            $fields['kyc_identifier'] = $options->kycIdentifier;
        }

        // Per-step challenge mode overrides
        foreach ($options->stepChallengeModes as $step => $mode) {
            $fields[$step . '_challenge_mode'] = $mode;
        }

        // Image files — each keyed as {step}_{challenge}, e.g. recto_center_document
        foreach ($options->images as $fieldName => $value) {
            // Check if this is a plain file path that we can pass directly as a CURLFile
            // (saves memory for large files compared to reading into a string first).
            $isPlainFilePath = !str_starts_with($value, 'http://')
                && !str_starts_with($value, 'https://')
                && !str_starts_with($value, 'data:image/')
                && (str_contains($value, '/') || str_contains($value, '\\') || preg_match('/\.\w{2,5}$/', $value))
                && is_file($value);

            if ($isPlainFilePath) {
                $mimeType = $this->detectMimeType($value);
                $fields[$fieldName] = new \CURLFile($value, $mimeType, basename($value));
            } else {
                // Resolve to raw bytes and write to a temp file so curl can send it
                $bytes    = $this->resolveImage($value);
                $tmpFile  = tempnam(sys_get_temp_dir(), 'kyv_');
                if ($tmpFile === false) {
                    throw new KyvShieldException('Could not create temporary file for image field "' . $fieldName . '"');
                }
                file_put_contents($tmpFile, $bytes);
                $mimeType = 'image/jpeg'; // default; sniff first bytes if needed
                if (strlen($bytes) >= 4) {
                    $magic = substr($bytes, 0, 4);
                    if (str_starts_with($magic, "\x89PNG")) {
                        $mimeType = 'image/png';
                    } elseif (str_starts_with($magic, 'RIFF')) {
                        $mimeType = 'image/webp';
                    }
                }
                $fields[$fieldName] = new \CURLFile($tmpFile, $mimeType, $fieldName . '.jpg');
            }
        }

        return $fields;
    }

    /**
     * Resolve an image value to raw bytes.
     *
     * Accepted formats (checked in order):
     *  1. `http://` / `https://` URL   → downloaded via curl
     *  2. `data:image/…;base64,…`      → base64 decoded
     *  3. bare base64 string            → base64 decoded
     *  4. filesystem path               → read from disk
     *
     * @throws KyvShieldException on download failure or unreadable file
     */
    private function resolveImage(string $value): string
    {
        // 1. URL
        if (str_starts_with($value, 'http://') || str_starts_with($value, 'https://')) {
            $ch = curl_init($value);
            if ($ch === false) {
                throw new KyvShieldException('curl_init failed for image URL: ' . $value);
            }
            curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
            curl_setopt($ch, CURLOPT_FOLLOWLOCATION, true);
            curl_setopt($ch, CURLOPT_TIMEOUT, 30);
            $bytes = curl_exec($ch);
            $errno = curl_errno($ch);
            $error = curl_error($ch);
            curl_close($ch);
            if ($errno !== 0 || $bytes === false) {
                throw new KyvShieldException('Failed to download image from "' . $value . '": ' . $error);
            }
            return (string) $bytes;
        }

        // 2. Data URI
        if (str_starts_with($value, 'data:image/')) {
            $commaPos = strpos($value, ',');
            if ($commaPos === false) {
                throw new KyvShieldException('Invalid data URI for image field');
            }
            $b64   = substr($value, $commaPos + 1);
            $bytes = base64_decode($b64, true);
            if ($bytes === false) {
                throw new KyvShieldException('Failed to decode base64 data URI');
            }
            return $bytes;
        }

        // 3. Bare base64 string (no path separators, very long, no extension)
        if (!str_contains($value, '/') && !str_contains($value, '\\')
            && strlen($value) > 64 && !preg_match('/\.\w{2,5}$/', $value)) {
            $bytes = base64_decode($value, true);
            if ($bytes === false) {
                throw new KyvShieldException('Failed to decode base64 image string');
            }
            return $bytes;
        }

        // 4. Filesystem path
        if (!is_file($value) || !is_readable($value)) {
            throw new KyvShieldException('Image file not found or not readable: ' . $value);
        }
        $bytes = file_get_contents($value);
        if ($bytes === false) {
            throw new KyvShieldException('Failed to read image file: ' . $value);
        }
        return $bytes;
    }

    /**
     * Detect MIME type for an image file (JPEG or PNG).
     */
    private function detectMimeType(string $filePath): string
    {
        $ext = strtolower(pathinfo($filePath, PATHINFO_EXTENSION));

        return match($ext) {
            'jpg', 'jpeg' => 'image/jpeg',
            'png'         => 'image/png',
            'webp'        => 'image/webp',
            default       => 'application/octet-stream',
        };
    }
}
