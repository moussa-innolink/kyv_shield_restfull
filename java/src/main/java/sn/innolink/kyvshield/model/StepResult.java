package sn.innolink.kyvshield.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result for a single verification step (selfie, recto, or verso).
 *
 * <p>Document steps (recto / verso) additionally populate
 * {@link #getAlignedDocument()}, {@link #getExtraction()}, and
 * {@link #getExtractedPhotos()}.
 *
 * <p>The selfie step additionally populates {@link #getCapturedImage()}.
 */
public final class StepResult {

    private final int stepIndex;
    private final Step stepType;
    private final boolean success;
    private final long processingTimeMs;
    private final LivenessResult liveness;
    private final VerificationResult verification;
    private final List<String> userMessages;

    // Document steps only
    private final String alignedDocument;
    private final List<ExtractionField> extraction;
    private final List<ExtractedPhoto> extractedPhotos;

    // Selfie step only
    private final String capturedImage;

    private StepResult(Builder builder) {
        this.stepIndex       = builder.stepIndex;
        this.stepType        = builder.stepType;
        this.success         = builder.success;
        this.processingTimeMs = builder.processingTimeMs;
        this.liveness        = builder.liveness;
        this.verification    = builder.verification;
        this.userMessages    = Collections.unmodifiableList(
                builder.userMessages != null ? builder.userMessages : new ArrayList<>());
        this.alignedDocument = builder.alignedDocument;
        this.extraction      = builder.extraction != null
                ? Collections.unmodifiableList(builder.extraction) : Collections.emptyList();
        this.extractedPhotos = builder.extractedPhotos != null
                ? Collections.unmodifiableList(builder.extractedPhotos) : Collections.emptyList();
        this.capturedImage   = builder.capturedImage;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    /** Returns the zero-based index of this step within the submitted steps array. */
    public int getStepIndex() {
        return stepIndex;
    }

    /** Returns the type of this step. */
    public Step getStepType() {
        return stepType;
    }

    /** Returns {@code true} when this step passed all checks. */
    public boolean isSuccess() {
        return success;
    }

    /** Returns the server-side processing time for this step in milliseconds. */
    public long getProcessingTimeMs() {
        return processingTimeMs;
    }

    /** Returns the liveness detection result for this step. */
    public LivenessResult getLiveness() {
        return liveness;
    }

    /** Returns the authenticity and fraud detection result for this step. */
    public VerificationResult getVerification() {
        return verification;
    }

    /**
     * Returns localised messages intended for display to the end-user.
     * Never {@code null}; may be empty.
     */
    public List<String> getUserMessages() {
        return userMessages;
    }

    /**
     * Returns the Base64-encoded aligned (deskewed) document image.
     * Only populated for document steps (recto / verso); {@code null} otherwise.
     */
    public String getAlignedDocument() {
        return alignedDocument;
    }

    /**
     * Returns the list of OCR-extracted text fields.
     * Only populated for document steps; never {@code null}.
     */
    public List<ExtractionField> getExtraction() {
        return extraction;
    }

    /**
     * Returns photos extracted from the document (e.g. the portrait).
     * Only populated for document steps; never {@code null}.
     */
    public List<ExtractedPhoto> getExtractedPhotos() {
        return extractedPhotos;
    }

    /**
     * Returns the Base64-encoded captured selfie image.
     * Only populated for the selfie step; {@code null} otherwise.
     */
    public String getCapturedImage() {
        return capturedImage;
    }

    // ── Deserialisation ───────────────────────────────────────────────────────

    /**
     * Deserialises a {@link StepResult} from a JSON object.
     *
     * @param json source JSON object
     * @return parsed instance
     */
    public static StepResult fromJson(JSONObject json) {
        Builder builder = new Builder();

        builder.stepIndex       = json.optInt("step_index", 0);
        builder.success         = json.optBoolean("success", false);
        builder.processingTimeMs = json.optLong("processing_time_ms", 0L);
        builder.alignedDocument = json.isNull("aligned_document")
                ? null : json.optString("aligned_document", null);
        builder.capturedImage   = json.isNull("captured_image")
                ? null : json.optString("captured_image", null);

        String stepTypeStr = json.optString("step_type", "recto");
        try {
            builder.stepType = Step.fromValue(stepTypeStr);
        } catch (IllegalArgumentException e) {
            builder.stepType = Step.RECTO;
        }

        JSONObject livenessObj = json.optJSONObject("liveness");
        builder.liveness = livenessObj != null
                ? LivenessResult.fromJson(livenessObj) : null;

        JSONObject verificationObj = json.optJSONObject("verification");
        builder.verification = verificationObj != null
                ? VerificationResult.fromJson(verificationObj) : null;

        builder.userMessages = parseStringList(json.optJSONArray("user_messages"));

        JSONArray extractionArr = json.optJSONArray("extraction");
        if (extractionArr != null) {
            List<ExtractionField> fields = new ArrayList<>();
            for (int i = 0; i < extractionArr.length(); i++) {
                JSONObject fieldObj = extractionArr.optJSONObject(i);
                if (fieldObj != null) {
                    fields.add(ExtractionField.fromJson(fieldObj));
                }
            }
            builder.extraction = fields;
        }

        JSONArray photosArr = json.optJSONArray("extracted_photos");
        if (photosArr != null) {
            List<ExtractedPhoto> photos = new ArrayList<>();
            for (int i = 0; i < photosArr.length(); i++) {
                JSONObject photoObj = photosArr.optJSONObject(i);
                if (photoObj != null) {
                    photos.add(ExtractedPhoto.fromJson(photoObj));
                }
            }
            builder.extractedPhotos = photos;
        }

        return builder.build();
    }

    private static List<String> parseStringList(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                result.add(array.optString(i));
            }
        }
        return result;
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    private static final class Builder {
        int stepIndex;
        Step stepType;
        boolean success;
        long processingTimeMs;
        LivenessResult liveness;
        VerificationResult verification;
        List<String> userMessages;
        String alignedDocument;
        List<ExtractionField> extraction;
        List<ExtractedPhoto> extractedPhotos;
        String capturedImage;

        StepResult build() {
            return new StepResult(this);
        }
    }

    @Override
    public String toString() {
        return "StepResult{stepIndex=" + stepIndex
                + ", stepType=" + stepType
                + ", success=" + success
                + ", processingTimeMs=" + processingTimeMs + '}';
    }
}
