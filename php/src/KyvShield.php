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

    private readonly int $imageMaxWidth;
    private readonly int $imageQuality;
    private readonly bool $enableLog;

    /** Default maximum image width in pixels before resize. */
    public const DEFAULT_IMAGE_MAX_WIDTH = 1280;

    /** Default JPEG compression quality (0–100). */
    public const DEFAULT_IMAGE_QUALITY = 90;

    /** Maximum number of images compressed concurrently (PHP is single-threaded; documented only). */
    public const DEFAULT_MAX_CONCURRENT_COMPRESS = 20;

    /**
     * @param  string  $apiKey        Value sent in the X-API-Key header
     * @param  string  $baseUrl       Override to point at a different environment
     * @param  string  $language      Default language for responses (default: 'fr')
     * @param  string  $challengeMode Default challenge mode (default: 'standard')
     * @param  int     $imageMaxWidth Maximum image width in pixels (default: 1280)
     * @param  int     $imageQuality  JPEG compression quality 0–100 (default: 90)
     * @param  bool    $enableLog     Enable [KyvShield] tagged logging (default: true)
     */
    public function __construct(
        private readonly string $apiKey,
        private readonly string $baseUrl = self::DEFAULT_BASE_URL,
        private readonly string $language = 'fr',
        private readonly string $challengeMode = 'standard',
        int $imageMaxWidth = 1280,
        int $imageQuality = 90,
        bool $enableLog = true,
    ) {
        if ($this->apiKey === '') {
            throw new KyvShieldException('API key must not be empty.');
        }
        if (!extension_loaded('curl')) {
            throw new KyvShieldException('The curl PHP extension is required by the KyvShield SDK.');
        }
        if (!extension_loaded('gd')) {
            throw new KyvShieldException('PHP GD extension is required. Install: apt-get install php-gd');
        }
        $this->imageMaxWidth = $imageMaxWidth;
        $this->imageQuality  = $imageQuality;
        $this->enableLog     = $enableLog;
        $this->log('info', sprintf(
            'Initialized (baseUrl=%s, imageMaxWidth=%d, imageQuality=%d)',
            $this->baseUrl,
            $this->imageMaxWidth,
            $this->imageQuality,
        ));
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
        $this->log('info', 'GET /api/v1/challenges...');
        $data     = $this->request('GET', '/api/v1/challenges');
        $response = ChallengesResponse::fromArray($data);

        $selfieCount = 0;
        $docCount    = 0;
        if (isset($data['challenges']['selfie']) && is_array($data['challenges']['selfie'])) {
            foreach ($data['challenges']['selfie'] as $modes) {
                $selfieCount += is_array($modes) ? count($modes) : 0;
            }
        }
        if (isset($data['challenges']['document']) && is_array($data['challenges']['document'])) {
            foreach ($data['challenges']['document'] as $modes) {
                $docCount += is_array($modes) ? count($modes) : 0;
            }
        }
        $this->log('info', sprintf('Challenges loaded (%d selfie modes, %d document modes)', $selfieCount, $docCount));

        return $response;
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

        $imageCount = count($options->images);
        $stepsStr   = implode(',', $options->steps);
        $this->log('info', sprintf(
            'POST /api/v1/kyc/verify (steps=[%s], target=%s, images=%d)',
            $stepsStr,
            $options->target,
            $imageCount,
        ));
        $startMs = (int) (microtime(true) * 1000);

        $tmpFiles = [];
        try {
            $fields = $this->buildFormFields($options, $tmpFiles);
            $data   = $this->request('POST', '/api/v1/kyc/verify', $fields);
        } finally {
            foreach ($tmpFiles as $tmpFile) {
                if (is_file($tmpFile)) {
                    @unlink($tmpFile);
                }
            }
        }

        $response  = KycResponse::fromArray($data);
        $elapsed   = (int) (microtime(true) * 1000) - $startMs;
        $status    = $data['overall_status'] ?? 'unknown';
        $confidence = number_format((float) ($data['overall_confidence'] ?? 0), 2);
        $this->log('info', sprintf('Verify complete: %s (%s) in %dms', $status, $confidence, $elapsed));

        return $response;
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
        $this->log('info', sprintf('Batch verify: %d requests...', count($optionsList)));

        $multiHandle = curl_multi_init();
        $handles     = [];
        $results     = [];

        $allTmpFiles = [];
        foreach ($optionsList as $i => $options) {
            $this->validateVerifyOptions($options);
            $tmpFiles = [];
            $fields = $this->buildFormFields($options, $tmpFiles);
            $allTmpFiles = array_merge($allTmpFiles, $tmpFiles);

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

        // Clean up all temp files created during this batch
        foreach ($allTmpFiles as $tmpFile) {
            if (is_file($tmpFile)) {
                @unlink($tmpFile);
            }
        }

        $passed  = count(array_filter($results, static fn($r) => $r['success'] === true));
        $rejected = count($results) - $passed;
        $this->log('info', sprintf('Batch complete: %d pass, %d reject', $passed, $rejected));

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

    /**
     * POST /api/v1/identify  (multipart/form-data)
     *
     * Search for matching identities in the enrolled database using a face image.
     *
     * @param  string  $image    File path, URL, base64 string, or data URI
     * @param  array{top_k?: int, min_score?: float}  $options
     * @return IdentifyResponse
     * @throws KyvShieldException
     */
    public function identify(string $image, array $options = []): IdentifyResponse
    {
        $topK     = (int) ($options['top_k'] ?? 3);
        $minScore = (float) ($options['min_score'] ?? 0.6);

        $this->log('info', sprintf('POST /api/v1/identify (top_k=%d, min_score=%.2f)', $topK, $minScore));
        $startMs = (int) (microtime(true) * 1000);

        $tmpFiles = [];
        try {
            $bytes    = $this->resolveImage($image, 'identify_image');
            $bytes    = $this->processImage($bytes, 'identify_image');
            $tmpFile  = tempnam(sys_get_temp_dir(), 'kyv_');
            if ($tmpFile === false) {
                throw new KyvShieldException('Could not create temporary file for identify image');
            }
            $tmpFiles[] = $tmpFile;
            file_put_contents($tmpFile, $bytes);
            $mimeType = $this->sniffMimeType($bytes);

            $fields = [
                'image'     => new \CURLFile($tmpFile, $mimeType, 'image.jpg'),
                'top_k'     => (string) $topK,
                'min_score'  => (string) $minScore,
            ];

            $data = $this->request('POST', '/api/v1/identify', $fields);
        } finally {
            foreach ($tmpFiles as $f) {
                if (is_file($f)) {
                    @unlink($f);
                }
            }
        }

        $response = IdentifyResponse::fromArray($data);
        $elapsed  = (int) (microtime(true) * 1000) - $startMs;
        $count    = count($response->candidates);
        $this->log('info', sprintf('Identify complete: %d candidate(s) in %dms', $count, $elapsed));

        return $response;
    }

    /**
     * POST /api/v1/verify/face  (multipart/form-data)
     *
     * Compare two face images (1:1 verification) and return a similarity score.
     *
     * @param  string  $targetImage  File path, URL, base64 string, or data URI
     * @param  string  $sourceImage  File path, URL, base64 string, or data URI
     * @param  array{detection_model?: string, recognition_model?: string}  $options
     * @return VerifyFaceResponse
     * @throws KyvShieldException
     */
    public function verifyFace(string $targetImage, string $sourceImage, array $options = []): VerifyFaceResponse
    {
        $detectionModel   = isset($options['detection_model']) ? (string) $options['detection_model'] : null;
        $recognitionModel = isset($options['recognition_model']) ? (string) $options['recognition_model'] : null;

        $this->log('info', sprintf(
            'POST /api/v1/verify/face (detection=%s, recognition=%s)',
            $detectionModel ?? 'default',
            $recognitionModel ?? 'default',
        ));
        $startMs = (int) (microtime(true) * 1000);

        $tmpFiles = [];
        try {
            // Resolve target image
            $targetBytes = $this->resolveImage($targetImage, 'target_image');
            $targetBytes = $this->processImage($targetBytes, 'target_image');
            $targetTmp   = tempnam(sys_get_temp_dir(), 'kyv_');
            if ($targetTmp === false) {
                throw new KyvShieldException('Could not create temporary file for target image');
            }
            $tmpFiles[] = $targetTmp;
            file_put_contents($targetTmp, $targetBytes);

            // Resolve source image
            $sourceBytes = $this->resolveImage($sourceImage, 'source_image');
            $sourceBytes = $this->processImage($sourceBytes, 'source_image');
            $sourceTmp   = tempnam(sys_get_temp_dir(), 'kyv_');
            if ($sourceTmp === false) {
                throw new KyvShieldException('Could not create temporary file for source image');
            }
            $tmpFiles[] = $sourceTmp;
            file_put_contents($sourceTmp, $sourceBytes);

            $fields = [
                'target_image' => new \CURLFile($targetTmp, $this->sniffMimeType($targetBytes), 'target.jpg'),
                'source_image' => new \CURLFile($sourceTmp, $this->sniffMimeType($sourceBytes), 'source.jpg'),
            ];

            if ($detectionModel !== null) {
                $fields['detection_model'] = $detectionModel;
            }
            if ($recognitionModel !== null) {
                $fields['recognition_model'] = $recognitionModel;
            }

            $data = $this->request('POST', '/api/v1/verify/face', $fields);
        } finally {
            foreach ($tmpFiles as $f) {
                if (is_file($f)) {
                    @unlink($f);
                }
            }
        }

        $response = VerifyFaceResponse::fromArray($data);
        $elapsed  = (int) (microtime(true) * 1000) - $startMs;
        $this->log('info', sprintf(
            'VerifyFace complete: %s (score=%.2f) in %dms',
            $response->isMatch ? 'MATCH' : 'NO_MATCH',
            $response->similarityScore,
            $elapsed,
        ));

        return $response;
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    /**
     * Tagged logger. All messages are prefixed with [KyvShield].
     * Uses error_log() for warn/error, and echo to stdout for debug/info.
     * Does nothing when enableLog is false.
     *
     * @param  string  $level  debug|info|warn|error
     * @param  string  $message
     */
    private function log(string $level, string $message): void
    {
        if (!$this->enableLog) {
            return;
        }
        $line = '[KyvShield] ' . strtoupper($level) . ': ' . $message;
        if ($level === 'warn' || $level === 'error') {
            error_log($line);
        } else {
            echo $line . PHP_EOL;
        }
    }

    /**
     * Resize and compress an image using the GD extension.
     *
     * If the image width exceeds $maxWidth, it is scaled down maintaining
     * the aspect ratio and re-encoded as JPEG at the given quality.
     * If the GD extension is not available, the original bytes are returned
     * unchanged (with a warning log).
     *
     * @param  string  $bytes    Raw image bytes
     * @param  string  $label    Image label for logging
     * @return string            Processed image bytes
     */
    private function processImage(string $bytes, string $label = 'image'): string
    {
        $originalKb = (int) (strlen($bytes) / 1024);

        // Detect image format from magic bytes
        $isPng = strlen($bytes) >= 4 && str_starts_with($bytes, "\x89PNG");

        $src = @imagecreatefromstring($bytes);
        if ($src === false) {
            $this->log('warn', sprintf('Image %s: could not decode for processing — using original (%dKB)', $label, $originalKb));
            return $bytes;
        }

        $origWidth  = imagesx($src);
        $origHeight = imagesy($src);

        if ($origWidth <= $this->imageMaxWidth) {
            // No resize needed — re-encode preserving original format
            ob_start();
            if ($isPng) {
                // PNG quality is 0–9 (compression level), convert from 0–100 scale
                $pngQuality = (int) round((100 - $this->imageQuality) / 11);
                imagepng($src, null, $pngQuality);
            } else {
                imagejpeg($src, null, $this->imageQuality);
            }
            $result = ob_get_clean();
            imagedestroy($src);
            if ($result === false || $result === '') {
                return $bytes;
            }
            $finalKb = (int) (strlen($result) / 1024);
            $this->log('debug', sprintf(
                'Image %s: %dKB → compressed %d%% → %dKB',
                $label,
                $originalKb,
                $this->imageQuality,
                $finalKb,
            ));
            return $result;
        }

        // Resize maintaining aspect ratio
        $newWidth  = $this->imageMaxWidth;
        $newHeight = (int) round($origHeight * $newWidth / $origWidth);
        $dst = imagecreatetruecolor($newWidth, $newHeight);
        if ($dst === false) {
            imagedestroy($src);
            return $bytes;
        }

        // Preserve alpha channel for PNG
        if ($isPng) {
            imagealphablending($dst, false);
            imagesavealpha($dst, true);
        }

        imagecopyresampled($dst, $src, 0, 0, 0, 0, $newWidth, $newHeight, $origWidth, $origHeight);
        imagedestroy($src);

        ob_start();
        if ($isPng) {
            $pngQuality = (int) round((100 - $this->imageQuality) / 11);
            imagepng($dst, null, $pngQuality);
        } else {
            imagejpeg($dst, null, $this->imageQuality);
        }
        $result = ob_get_clean();
        imagedestroy($dst);

        if ($result === false || $result === '') {
            return $bytes;
        }

        $finalKb = (int) (strlen($result) / 1024);
        $this->log('info', sprintf(
            'Image %s: %dKB → resized %dpx → compressed %d%% → %dKB',
            $label,
            $originalKb,
            $newWidth,
            $this->imageQuality,
            $finalKb,
        ));

        return $result;
    }

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
            // Detect known base64 image prefixes first — JPEG base64 starts with /9j/ which
            // contains '/', so checking for path separators alone is not reliable.
            if (str_starts_with($value, '/9j/')    // JPEG base64
                || str_starts_with($value, 'iVBOR') // PNG base64
                || str_starts_with($value, 'UklGR') // WebP base64
                || str_starts_with($value, 'R0lGO') // GIF base64
            ) {
                continue; // Base64 string — decoded at send time
            }
            // Fallback heuristic: long string with no path separators and no file extension
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
     * @param  string[]  $tmpFiles  Reference to array collecting temp file paths for cleanup
     * @return array<string,mixed>
     * @throws KyvShieldException
     */
    private function buildFormFields(VerifyOptions $options, array &$tmpFiles = []): array
    {
        $fields = [
            'steps'            => json_encode($options->steps, JSON_THROW_ON_ERROR),
            'target'           => $options->target,
            'language'         => $options->language,
            'challenge_mode'   => $options->challengeMode,
            'require_face_match' => $options->requireFaceMatch ? 'true' : 'false',
            'require_aml' => $options->requireAml ? 'true' : 'false',
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
            // Resolve to raw bytes (always — so processImage can be applied)
            $bytes    = $this->resolveImage($value, $fieldName);
            // Resize + compress via GD
            $bytes    = $this->processImage($bytes, $fieldName);
            $tmpFile  = tempnam(sys_get_temp_dir(), 'kyv_');
            if ($tmpFile === false) {
                throw new KyvShieldException('Could not create temporary file for image field "' . $fieldName . '"');
            }
            $tmpFiles[] = $tmpFile;
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
    private function resolveImage(string $value, string $label = 'image'): string
    {
        // 1. URL
        if (str_starts_with($value, 'http://') || str_starts_with($value, 'https://')) {
            $this->log('debug', 'Downloading image from ' . $value);
            $ch = curl_init($value);
            if ($ch === false) {
                throw new KyvShieldException('curl_init failed for image URL: ' . $value);
            }
            curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
            curl_setopt($ch, CURLOPT_FOLLOWLOCATION, false);
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
            $sizeKb = (int) (strlen($b64) * 0.75 / 1024);
            $this->log('debug', sprintf('Decoding base64 image %s (%dKB)', $label, $sizeKb));
            $bytes = base64_decode($b64, true);
            if ($bytes === false) {
                throw new KyvShieldException('Failed to decode base64 data URI');
            }
            return $bytes;
        }

        // 3. Bare base64 string
        // Detect known base64 image prefixes first — JPEG base64 starts with /9j/ which
        // contains '/', so checking for path separators alone is not reliable.
        $isKnownB64Prefix = str_starts_with($value, '/9j/')    // JPEG base64
            || str_starts_with($value, 'iVBOR') // PNG base64
            || str_starts_with($value, 'UklGR') // WebP base64
            || str_starts_with($value, 'R0lGO'); // GIF base64
        $isFallbackB64 = !str_contains($value, '/') && !str_contains($value, '\\')
            && strlen($value) > 64 && !preg_match('/\.\w{2,5}$/', $value);
        if ($isKnownB64Prefix || $isFallbackB64) {
            $sizeKb = (int) (strlen($value) * 0.75 / 1024);
            $this->log('debug', sprintf('Decoding base64 image %s (%dKB)', $label, $sizeKb));
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
     * Detect MIME type from the first bytes of image data.
     *
     * @param  string  $bytes  Raw image bytes
     * @return string          MIME type (defaults to image/jpeg)
     */
    private function sniffMimeType(string $bytes): string
    {
        if (strlen($bytes) >= 4) {
            $magic = substr($bytes, 0, 4);
            if (str_starts_with($magic, "\x89PNG")) {
                return 'image/png';
            }
            if (str_starts_with($magic, 'RIFF')) {
                return 'image/webp';
            }
        }
        return 'image/jpeg';
    }

}
