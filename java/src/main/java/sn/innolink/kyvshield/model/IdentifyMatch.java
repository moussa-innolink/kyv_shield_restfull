package sn.innolink.kyvshield.model;

import org.json.JSONObject;

/**
 * A single match returned by the {@code POST /api/v1/identify} endpoint.
 *
 * <p>Each match represents a previously enrolled face that resembles the
 * probe image, ranked by descending similarity score.
 */
public final class IdentifyMatch {

    private final String subjectId;
    private final double score;
    private final JSONObject metadata;

    private IdentifyMatch(String subjectId, double score, JSONObject metadata) {
        this.subjectId = subjectId;
        this.score     = score;
        this.metadata  = metadata;
    }

    /**
     * Returns the unique identifier of the matched subject.
     */
    public String getSubjectId() {
        return subjectId;
    }

    /**
     * Returns the similarity score between the probe image and this match
     * (0–100 scale).
     */
    public double getScore() {
        return score;
    }

    /**
     * Returns any additional metadata associated with the matched subject,
     * or {@code null} if none was stored.
     */
    public JSONObject getMetadata() {
        return metadata;
    }

    // ── Deserialisation ───────────────────────────────────────────────────────

    /**
     * Deserialises an {@link IdentifyMatch} from a JSON object.
     *
     * @param json source JSON object
     * @return parsed instance
     */
    public static IdentifyMatch fromJson(JSONObject json) {
        String subjectId  = json.optString("subject_id", "");
        double score      = json.optDouble("score", 0.0);
        JSONObject metadata = json.optJSONObject("metadata");
        return new IdentifyMatch(subjectId, score, metadata);
    }

    @Override
    public String toString() {
        return "IdentifyMatch{subjectId='" + subjectId + "', score=" + score + '}';
    }
}
