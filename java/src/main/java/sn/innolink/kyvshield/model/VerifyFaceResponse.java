package sn.innolink.kyvshield.model;

import org.json.JSONObject;

/**
 * Response from {@code POST /api/v1/verify/face}.
 *
 * <p>Contains the face-match decision and similarity score between the
 * target and source images.
 */
public final class VerifyFaceResponse {

    private final boolean success;
    private final boolean isMatch;
    private final double similarityScore;
    private final String detectionModel;
    private final String recognitionModel;
    private final long processingTimeMs;

    private VerifyFaceResponse(boolean success, boolean isMatch, double similarityScore,
                               String detectionModel, String recognitionModel,
                               long processingTimeMs) {
        this.success          = success;
        this.isMatch          = isMatch;
        this.similarityScore  = similarityScore;
        this.detectionModel   = detectionModel;
        this.recognitionModel = recognitionModel;
        this.processingTimeMs = processingTimeMs;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    /** Returns {@code true} when the API call succeeded. */
    public boolean isSuccess() {
        return success;
    }

    /** Returns {@code true} when the two faces are considered a match. */
    public boolean isMatch() {
        return isMatch;
    }

    /**
     * Returns the cosine similarity score on a {@code 0–100} scale.
     */
    public double getSimilarityScore() {
        return similarityScore;
    }

    /**
     * Returns the face detection model that was used, or {@code null} if not
     * reported by the server.
     */
    public String getDetectionModel() {
        return detectionModel;
    }

    /**
     * Returns the face recognition model that was used, or {@code null} if not
     * reported by the server.
     */
    public String getRecognitionModel() {
        return recognitionModel;
    }

    /** Returns the server-side processing time in milliseconds. */
    public long getProcessingTimeMs() {
        return processingTimeMs;
    }

    // ── Deserialisation ───────────────────────────────────────────────────────

    /**
     * Deserialises a {@link VerifyFaceResponse} from a JSON object.
     *
     * @param json source JSON object
     * @return parsed instance
     */
    public static VerifyFaceResponse fromJson(JSONObject json) {
        boolean success          = json.optBoolean("success", false);
        boolean isMatch          = json.optBoolean("is_match", false);
        double  similarityScore  = json.optDouble("similarity_score", 0.0);
        String  detectionModel   = json.optString("detection_model", null);
        String  recognitionModel = json.optString("recognition_model", null);
        long    processingTimeMs = json.optLong("processing_time_ms", 0L);
        return new VerifyFaceResponse(success, isMatch, similarityScore,
                detectionModel, recognitionModel, processingTimeMs);
    }

    @Override
    public String toString() {
        return "VerifyFaceResponse{success=" + success
                + ", isMatch=" + isMatch
                + ", similarityScore=" + similarityScore
                + ", detectionModel='" + detectionModel + '\''
                + ", recognitionModel='" + recognitionModel + '\''
                + ", processingTimeMs=" + processingTimeMs + '}';
    }
}
