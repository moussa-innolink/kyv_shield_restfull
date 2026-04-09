package sn.innolink.kyvshield.model;

import org.json.JSONObject;

/**
 * Cross-step face-match result returned when {@code require_face_match} was
 * {@code true} in the verification request.
 *
 * <p>The similarity score is computed using the ONNX face recognition model
 * deployed server-side and is expressed on a {@code 0–100} scale (cosine
 * similarity mapped to a percentage).
 */
public final class FaceVerification {

    private final boolean isMatch;
    private final double similarityScore;

    private FaceVerification(boolean isMatch, double similarityScore) {
        this.isMatch        = isMatch;
        this.similarityScore = similarityScore;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the selfie face matches the portrait photo found
     * on the identity document.
     */
    public boolean isMatch() {
        return isMatch;
    }

    /**
     * Returns the cosine similarity score on a {@code 0–100} scale.
     * Scores above the backend threshold (typically around 65–75) are considered
     * a match.
     */
    public double getSimilarityScore() {
        return similarityScore;
    }

    // ── Deserialisation ───────────────────────────────────────────────────────

    /**
     * Deserialises a {@link FaceVerification} from a JSON object.
     *
     * @param json source JSON object
     * @return parsed instance
     */
    public static FaceVerification fromJson(JSONObject json) {
        boolean isMatch        = json.optBoolean("is_match", false);
        double  similarityScore = json.optDouble("similarity_score", 0.0);
        return new FaceVerification(isMatch, similarityScore);
    }

    @Override
    public String toString() {
        return "FaceVerification{isMatch=" + isMatch
                + ", similarityScore=" + similarityScore + '}';
    }
}
