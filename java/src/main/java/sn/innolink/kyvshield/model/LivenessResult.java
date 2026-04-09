package sn.innolink.kyvshield.model;

import org.json.JSONObject;

/**
 * Liveness detection sub-result for a single verification step.
 *
 * <p>Encapsulates whether the subject or document was considered physically present
 * (not a printed copy or screen replay), together with an associated confidence score.
 */
public final class LivenessResult {

    private final boolean isLive;
    private final double score;
    private final String confidence;

    private LivenessResult(boolean isLive, double score, String confidence) {
        this.isLive = isLive;
        this.score = score;
        this.confidence = confidence;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the document or selfie is considered physically
     * live (not a printout, photo of a screen, or replay attack).
     */
    public boolean isLive() {
        return isLive;
    }

    /**
     * Returns the liveness score in the range {@code [0, 1]}, where {@code 1.0}
     * means maximum confidence in liveness.
     */
    public double getScore() {
        return score;
    }

    /**
     * Returns a qualitative confidence descriptor: {@code "HIGH"}, {@code "MEDIUM"},
     * or {@code "LOW"}.
     */
    public String getConfidence() {
        return confidence;
    }

    // ── Deserialisation ───────────────────────────────────────────────────────

    /**
     * Deserialises a {@link LivenessResult} from a JSON object.
     *
     * @param json source JSON object
     * @return parsed instance
     */
    public static LivenessResult fromJson(JSONObject json) {
        boolean isLive     = json.optBoolean("is_live", false);
        double  score      = json.optDouble("score", 0.0);
        String  confidence = json.optString("confidence", "LOW");
        return new LivenessResult(isLive, score, confidence);
    }

    @Override
    public String toString() {
        return "LivenessResult{isLive=" + isLive
                + ", score=" + score
                + ", confidence='" + confidence + "'}";
    }
}
