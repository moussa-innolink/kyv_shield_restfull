package sn.innolink.kyvshield;

import org.json.JSONObject;
import sn.innolink.kyvshield.model.ChallengesResponse;
import sn.innolink.kyvshield.model.KycResponse;
import sn.innolink.kyvshield.model.Step;
import sn.innolink.kyvshield.model.VerifyOptions;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import sn.innolink.kyvshield.model.BatchResult;

/**
 * KyvShield KYC REST API client.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * KyvShield kyv = new KyvShield("your_api_key");
 *
 * // Get available challenges
 * ChallengesResponse challenges = kyv.getChallenges();
 *
 * // Submit a KYC verification
 * VerifyOptions options = new VerifyOptions.Builder()
 *     .steps(Step.RECTO, Step.VERSO)
 *     .target("SN-CIN")
 *     .language("fr")
 *     .rectoChallengeMode(ChallengeMode.MINIMAL)
 *     .versoChallengeMode(ChallengeMode.MINIMAL)
 *     .addImage("recto_center_document", "/path/to/recto.jpg")
 *     .addImage("verso_center_document", "/path/to/verso.jpg")
 *     .build();
 *
 * KycResponse result = kyv.verify(options);
 * System.out.println(result.getOverallStatus()); // PASS or REJECT
 * }</pre>
 *
 * <p>Requires Java 11+ (uses {@link java.net.http.HttpClient}).
 * The only external dependency is {@code org.json}.
 */
public final class KyvShield {

    /** Default production base URL. */
    public static final String DEFAULT_BASE_URL = "https://kyvshield-naruto.innolinkcloud.com";

    /** Default maximum image width in pixels before resize. */
    public static final int DEFAULT_IMAGE_MAX_WIDTH = 1280;

    /** Default JPEG compression quality (0–100). */
    public static final int DEFAULT_IMAGE_QUALITY = 90;

    /** Maximum number of images compressed in parallel. */
    public static final int DEFAULT_MAX_CONCURRENT_COMPRESS = 20;

    private static final String CHALLENGES_PATH = "/api/v1/challenges";
    private static final String VERIFY_PATH     = "/api/v1/kyc/verify";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /** Logger used for all [KyvShield] tagged output. */
    private static final Logger LOGGER = Logger.getLogger("KyvShield");

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final int imageMaxWidth;
    private final int imageQuality;
    private final boolean enableLog;

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Creates a client pointing at the default production endpoint.
     *
     * @param apiKey your KyvShield API key ({@code X-API-Key} header)
     */
    public KyvShield(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL, 1280, 90, true);
    }

    /**
     * Creates a client pointing at a custom base URL (useful for local development
     * or staging environments).
     *
     * @param apiKey  your KyvShield API key
     * @param baseUrl base URL without trailing slash, e.g. {@code "http://localhost:8080"}
     */
    public KyvShield(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, 1280, 90, true);
    }

    /**
     * Creates a fully configured client.
     *
     * @param apiKey        your KyvShield API key
     * @param baseUrl       base URL, e.g. {@code "http://localhost:8080"}
     * @param imageMaxWidth maximum image width in pixels before resize (default: 1280)
     * @param imageQuality  JPEG quality 0–100 (default: 90)
     * @param enableLog     enable {@code [KyvShield]} tagged logging (default: true)
     */
    public KyvShield(String apiKey, String baseUrl, int imageMaxWidth, int imageQuality, boolean enableLog) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be null or blank");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be null or blank");
        }
        this.apiKey = apiKey;
        // Strip any trailing slash for consistency
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.imageMaxWidth = imageMaxWidth;
        this.imageQuality  = imageQuality;
        this.enableLog     = enableLog;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log("Initialized (baseUrl=" + this.baseUrl + ", imageMaxWidth=" + imageMaxWidth + ", imageQuality=" + imageQuality + ")");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Retrieves available challenges from the server.
     *
     * <p>Calls {@code GET /api/v1/challenges}.
     *
     * @return parsed {@link ChallengesResponse}
     * @throws KyvShieldException if the request fails or the response cannot be parsed
     */
    public ChallengesResponse getChallenges() {
        log("GET " + CHALLENGES_PATH + "...");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + CHALLENGES_PATH))
                .header("X-API-Key", apiKey)
                .header("Accept", "application/json")
                .GET()
                .timeout(DEFAULT_TIMEOUT)
                .build();

        String body = executeRequest(request);
        try {
            ChallengesResponse result = ChallengesResponse.fromJson(new JSONObject(body));
            log("Challenges loaded");
            return result;
        } catch (Exception e) {
            throw new KyvShieldException("Failed to parse challenges response: " + e.getMessage(), e);
        }
    }

    /**
     * Submits a KYC verification request.
     *
     * <p>Calls {@code POST /api/v1/kyc/verify} as a multipart/form-data request.
     * Image files are read from the file paths specified in {@link VerifyOptions#getImages()}.
     *
     * @param options verification options (steps, target, images, etc.)
     * @return parsed {@link KycResponse}
     * @throws KyvShieldException if any image cannot be read, the request fails,
     *                            or the response cannot be parsed
     */
    public KycResponse verify(VerifyOptions options) {
        int imageCount = options.getImages().size() + options.getImageBytes().size();
        String stepsStr = options.getSteps().stream().map(Step::getValue).collect(Collectors.joining(","));
        log("POST " + VERIFY_PATH + " (steps=[" + stepsStr + "], target=" + options.getTarget() + ", images=" + imageCount + ")");
        long startMs = System.currentTimeMillis();

        String boundary = "KyvShield-" + UUID.randomUUID().toString().replace("-", "");

        byte[] body = buildMultipartBody(options, boundary);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + VERIFY_PATH))
                .header("X-API-Key", apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(DEFAULT_TIMEOUT)
                .build();

        String responseBody = executeRequest(request);
        try {
            KycResponse result = KycResponse.fromJson(new JSONObject(responseBody));
            long elapsed = System.currentTimeMillis() - startMs;
            log("Verify complete: " + result.getOverallStatus() + " in " + elapsed + "ms");
            return result;
        } catch (Exception e) {
            throw new KyvShieldException(
                    "Failed to parse KYC verify response: " + e.getMessage(), e);
        }
    }

    // ── Batch Verification ────────────────────────────────────────────────────

    /**
     * Submits multiple KYC verification requests concurrently.
     *
     * <p>Maximum batch size is 10. Each request runs in parallel using
     * {@link CompletableFuture#supplyAsync}. Results are returned in the same
     * order as the input list.
     *
     * @param optionsList list of verification options (max 10)
     * @return list of {@link BatchResult} in input order
     * @throws KyvShieldException if batch size exceeds 10
     */
    public List<BatchResult> verifyBatch(List<VerifyOptions> optionsList) {
        if (optionsList.size() > 10) {
            throw new KyvShieldException("Batch size cannot exceed 10");
        }
        log("Batch verify: " + optionsList.size() + " requests...");

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.max(optionsList.size(), 1));
        try {
            List<CompletableFuture<BatchResult>> futures = optionsList.stream()
                    .map(opts -> CompletableFuture.supplyAsync(() -> {
                        try {
                            KycResponse result = verify(opts);
                            return new BatchResult(true, result, null);
                        } catch (Exception e) {
                            return new BatchResult(false, null, e.getMessage());
                        }
                    }, executor))
                    .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<BatchResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());

            long passed = results.stream().filter(BatchResult::isSuccess).count();
            long rejected = results.size() - passed;
            log("Batch complete: " + passed + " pass, " + rejected + " reject");

            return results;
        } finally {
            executor.shutdown();
        }
    }

    // ── Webhook Signature Verification ────────────────────────────────────────

    /**
     * Verifies an HMAC-SHA256 webhook signature.
     *
     * <p>When the KyvShield backend delivers a webhook, it signs the raw request
     * body using {@code HMAC-SHA256} with your API key as the secret and sends the
     * result as a hex-encoded string in the {@code X-KyvShield-Signature} header.
     *
     * <p>Example:
     * <pre>{@code
     * boolean valid = KyvShield.verifyWebhookSignature(
     *     requestBody,
     *     "your_api_key",
     *     request.getHeader("X-KyvShield-Signature")
     * );
     * }</pre>
     *
     * @param payload         raw webhook request body bytes
     * @param apiKey          your KyvShield API key used as the HMAC secret
     * @param signatureHeader value of the {@code X-KyvShield-Signature} header
     * @return {@code true} when the computed signature matches the header value
     */
    public static boolean verifyWebhookSignature(
            byte[] payload,
            String apiKey,
            String signatureHeader) {
        if (payload == null || apiKey == null || signatureHeader == null) {
            return false;
        }
        try {
            String received = signatureHeader.startsWith("sha256=")
                    ? signatureHeader.substring(7)
                    : signatureHeader;
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    apiKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] computed = mac.doFinal(payload);
            String computedHex = bytesToHex(computed);
            return java.security.MessageDigest.isEqual(
                    computedHex.getBytes(StandardCharsets.UTF_8),
                    received.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    /**
     * Tagged logger. Logs to {@code System.out} when {@code enableLog} is {@code true}.
     * All messages are prefixed with {@code [KyvShield]}.
     */
    private void log(String message) {
        if (enableLog) {
            System.out.println("[KyvShield] " + message);
        }
    }

    /**
     * Resize and compress an image using standard Java2D / ImageIO (no external deps).
     *
     * <p>If the image width exceeds {@code imageMaxWidth}, the image is scaled down
     * maintaining the aspect ratio. The result is always re-encoded as JPEG at
     * {@code imageQuality} / 100.
     *
     * @param data   raw image bytes
     * @param label  field name used in log messages
     * @return processed image bytes, or the original bytes if processing fails
     */
    private byte[] processImage(byte[] data, String label) {
        int originalKb = data.length / 1024;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
            if (src == null) {
                log("Image " + label + ": could not decode for processing — using original (" + originalKb + "KB)");
                return data;
            }

            int origWidth  = src.getWidth();
            int origHeight = src.getHeight();
            BufferedImage toEncode;

            if (origWidth > imageMaxWidth) {
                int newWidth  = imageMaxWidth;
                int newHeight = (int) Math.round((double) origHeight * newWidth / origWidth);
                BufferedImage dst = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = dst.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.drawImage(src, 0, 0, newWidth, newHeight, null);
                g2d.dispose();
                toEncode = dst;
            } else {
                // No resize needed — convert to RGB for JPEG encoding
                if (src.getType() != BufferedImage.TYPE_INT_RGB) {
                    BufferedImage rgb = new BufferedImage(origWidth, origHeight, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2d = rgb.createGraphics();
                    g2d.drawImage(src, 0, 0, null);
                    g2d.dispose();
                    toEncode = rgb;
                } else {
                    toEncode = src;
                }
            }

            // Encode as JPEG with quality parameter
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                log("Image " + label + ": no JPEG writer found — using original");
                return data;
            }
            ImageWriter writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(imageQuality / 100.0f);
            writer.setOutput(new MemoryCacheImageOutputStream(baos));
            writer.write(null, new IIOImage(toEncode, null, null), param);
            writer.dispose();

            byte[] result = baos.toByteArray();
            int finalKb = result.length / 1024;

            if (origWidth > imageMaxWidth) {
                log("Image " + label + ": " + originalKb + "KB → resized " + imageMaxWidth + "px → compressed " + imageQuality + "% → " + finalKb + "KB");
            } else {
                log("Image " + label + ": " + originalKb + "KB → compressed " + imageQuality + "% → " + finalKb + "KB");
            }
            return result;
        } catch (Exception e) {
            log("Image " + label + ": processing failed (" + e.getMessage() + ") — using original");
            return data;
        }
    }

    /**
     * Executes an HTTP request and returns the response body as a string.
     * Throws {@link KyvShieldException} for non-2xx responses or network errors.
     */
    private String executeRequest(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new KyvShieldException("Network error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KyvShieldException("Request interrupted", e);
        }

        int status = response.statusCode();
        String body = response.body();

        if (status < 200 || status >= 300) {
            String message = "API returned HTTP " + status;
            // Try to extract an error message from the JSON body
            try {
                JSONObject json = new JSONObject(body);
                String errMsg = json.optString("message",
                        json.optString("error", json.optString("detail", null)));
                if (errMsg != null && !errMsg.isEmpty()) {
                    message = message + ": " + errMsg;
                }
            } catch (Exception ignored) {
                // Body is not JSON — use raw body excerpt in the message
                if (body != null && !body.isEmpty()) {
                    message = message + ": " + body.substring(0, Math.min(200, body.length()));
                }
            }
            throw new KyvShieldException(message, status, body);
        }

        return body;
    }

    /**
     * Builds the multipart/form-data body for the {@code POST /api/v1/kyc/verify} endpoint.
     */
    private byte[] buildMultipartBody(VerifyOptions options, String boundary) {
        List<byte[]> parts = new ArrayList<>();
        String crlf = "\r\n";
        String dashBoundary = "--" + boundary + crlf;
        String finalBoundary = "--" + boundary + "--" + crlf;

        // ── Text fields ──────────────────────────────────────────────────────

        // steps — JSON array string e.g. '["recto","verso"]'
        StringBuilder stepsJson = new StringBuilder("[");
        List<Step> steps = options.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            stepsJson.append('"').append(steps.get(i).getValue()).append('"');
            if (i < steps.size() - 1) stepsJson.append(',');
        }
        stepsJson.append(']');
        parts.add(textPart(boundary, "steps", stepsJson.toString()));

        parts.add(textPart(boundary, "target", options.getTarget()));
        parts.add(textPart(boundary, "language", options.getLanguage()));

        if (options.getChallengeMode() != null) {
            parts.add(textPart(boundary, "challenge_mode",
                    options.getChallengeMode().getValue()));
        }
        if (options.getSelfieChallengeMode() != null) {
            parts.add(textPart(boundary, "selfie_challenge_mode",
                    options.getSelfieChallengeMode().getValue()));
        }
        if (options.getRectoChallengeMode() != null) {
            parts.add(textPart(boundary, "recto_challenge_mode",
                    options.getRectoChallengeMode().getValue()));
        }
        if (options.getVersoChallengeMode() != null) {
            parts.add(textPart(boundary, "verso_challenge_mode",
                    options.getVersoChallengeMode().getValue()));
        }

        parts.add(textPart(boundary, "require_face_match",
                options.isRequireFaceMatch() ? "true" : "false"));

        if (options.getKycIdentifier() != null && !options.getKycIdentifier().isEmpty()) {
            parts.add(textPart(boundary, "kyc_identifier", options.getKycIdentifier()));
        }

        // ── Image files (string: path / URL / base64 / data URI) ─────────────
        // Compress in parallel (max DEFAULT_MAX_CONCURRENT_COMPRESS concurrent)

        if (!options.getImages().isEmpty()) {
            List<Map.Entry<String, String>> imageEntries = new ArrayList<>(options.getImages().entrySet());
            ExecutorService compressPool = Executors.newFixedThreadPool(
                    Math.min(imageEntries.size(), DEFAULT_MAX_CONCURRENT_COMPRESS));
            Semaphore sem = new Semaphore(DEFAULT_MAX_CONCURRENT_COMPRESS);
            try {
                List<CompletableFuture<byte[]>> imgFutures = imageEntries.stream()
                        .map(entry -> CompletableFuture.supplyAsync(() -> {
                            try {
                                sem.acquire();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new KyvShieldException("Interrupted during image compression", e);
                            }
                            try {
                                byte[] raw = resolveImage(entry.getValue(), entry.getKey());
                                return processImage(raw, entry.getKey());
                            } finally {
                                sem.release();
                            }
                        }, compressPool))
                        .collect(Collectors.toList());
                CompletableFuture.allOf(imgFutures.toArray(new CompletableFuture[0])).join();
                for (int i = 0; i < imageEntries.size(); i++) {
                    String fieldName = imageEntries.get(i).getKey();
                    String value = imageEntries.get(i).getValue();
                    byte[] imageBytes = imgFutures.get(i).join();
                    String filename = deriveFilename(value, fieldName);
                    String contentType = inferContentType(filename);
                    String header = dashBoundary
                            + "Content-Disposition: form-data; name=\"" + fieldName
                            + "\"; filename=\"" + filename + "\"" + crlf
                            + "Content-Type: " + contentType + crlf
                            + crlf;
                    parts.add(concat(header.getBytes(StandardCharsets.UTF_8),
                            imageBytes,
                            crlf.getBytes(StandardCharsets.UTF_8)));
                }
            } finally {
                compressPool.shutdown();
            }
        }

        // ── Raw-bytes image map ───────────────────────────────────────────────

        for (Map.Entry<String, byte[]> entry : options.getImageBytes().entrySet()) {
            String fieldName = entry.getKey();
            byte[] imageBytes = processImage(entry.getValue(), fieldName);
            String filename   = fieldName + ".jpg";
            String contentType = inferContentTypeFromBytes(imageBytes);

            String header = dashBoundary
                    + "Content-Disposition: form-data; name=\"" + fieldName
                    + "\"; filename=\"" + filename + "\"" + crlf
                    + "Content-Type: " + contentType + crlf
                    + crlf;

            parts.add(concat(header.getBytes(StandardCharsets.UTF_8),
                    imageBytes,
                    crlf.getBytes(StandardCharsets.UTF_8)));
        }

        // ── Final boundary ───────────────────────────────────────────────────
        parts.add(finalBoundary.getBytes(StandardCharsets.UTF_8));

        return concatAll(parts);
    }

    /** Builds a single text form-data part. */
    private static byte[] textPart(String boundary, String name, String value) {
        String crlf = "\r\n";
        String part = "--" + boundary + crlf
                + "Content-Disposition: form-data; name=\"" + name + "\"" + crlf
                + crlf
                + value + crlf;
        return part.getBytes(StandardCharsets.UTF_8);
    }

    /** Concatenates multiple byte arrays into one. */
    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    /** Concatenates a list of byte arrays into one. */
    private static byte[] concatAll(List<byte[]> arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    /**
     * Resolves an image string value to raw bytes.
     *
     * Supported formats (checked in order):
     * <ol>
     *   <li>{@code http://} / {@code https://} URL — fetched via HttpClient</li>
     *   <li>{@code data:image/…;base64,…} data URI — base64 decoded</li>
     *   <li>Bare base64 string (no path separator, length &gt; 64, no extension) — decoded</li>
     *   <li>Filesystem path — read with {@link Files#readAllBytes}</li>
     * </ol>
     */
    private byte[] resolveImage(String value, String label) {
        // 1. URL
        if (value.startsWith("http://") || value.startsWith("https://")) {
            log("Downloading image from " + value);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(value))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();
            try {
                HttpResponse<byte[]> res = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() < 200 || res.statusCode() >= 300) {
                    throw new KyvShieldException("Failed to download image from URL \"" + value
                            + "\": HTTP " + res.statusCode());
                }
                return res.body();
            } catch (IOException e) {
                throw new KyvShieldException("Network error downloading image from \"" + value + "\": " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KyvShieldException("Interrupted while downloading image from \"" + value + "\"", e);
            }
        }

        // 2. Data URI
        if (value.startsWith("data:image/")) {
            int commaIdx = value.indexOf(',');
            if (commaIdx == -1) {
                throw new KyvShieldException("Invalid data URI for image");
            }
            String b64 = value.substring(commaIdx + 1);
            int sizeKb = (int) (b64.length() * 0.75 / 1024);
            log("Decoding base64 image " + label + " (" + sizeKb + "KB)");
            try {
                return Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                throw new KyvShieldException(
                        "Failed to decode base64 data URI for image \"" + label + "\": " + e.getMessage(), e);
            }
        }

        // 3. Bare base64 (no path separators, long, no extension)
        if (!value.contains("/") && !value.contains("\\")
                && value.length() > 64 && !value.matches(".*\\.\\w{2,5}$")) {
            int sizeKb = (int) (value.length() * 0.75 / 1024);
            log("Decoding base64 image " + label + " (" + sizeKb + "KB)");
            try {
                return Base64.getDecoder().decode(value);
            } catch (IllegalArgumentException e) {
                throw new KyvShieldException(
                        "Failed to decode base64 image string for \"" + label + "\": " + e.getMessage(), e);
            }
        }

        // 4. Filesystem path
        Path path = Paths.get(value);
        if (!Files.exists(path)) {
            throw new KyvShieldException("Image file not found: " + value);
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new KyvShieldException("Cannot read image file: " + value, e);
        }
    }

    /** Derives a sensible filename from an image value (for multipart content-disposition). */
    private static String deriveFilename(String value, String fieldName) {
        if (value.startsWith("http://") || value.startsWith("https://")) {
            // Extract last path segment from URL, strip query string
            String path = URI.create(value).getPath();
            if (path != null && !path.isEmpty()) {
                int slash = path.lastIndexOf('/');
                String seg = (slash >= 0) ? path.substring(slash + 1) : path;
                if (!seg.isEmpty()) return seg;
            }
            return fieldName + ".jpg";
        }
        if (value.startsWith("data:image/") || (!value.contains("/") && !value.contains("\\")
                && value.length() > 64 && !value.matches(".*\\.\\w{2,5}$"))) {
            return fieldName + ".jpg";
        }
        // Filesystem path — use basename
        Path p = Paths.get(value);
        return p.getFileName().toString();
    }

    /** Infers the MIME content type from the first bytes of an image (magic bytes). */
    private static String inferContentTypeFromBytes(byte[] bytes) {
        if (bytes.length >= 4) {
            if (bytes[0] == (byte)0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
                return "image/png";
            }
            if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
                return "image/webp";
            }
        }
        return "image/jpeg"; // default
    }

    /** Infers the MIME content type from a file name extension. */
    private static String inferContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg"; // default
    }

    /** Converts a byte array to a lowercase hex string. */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

}
