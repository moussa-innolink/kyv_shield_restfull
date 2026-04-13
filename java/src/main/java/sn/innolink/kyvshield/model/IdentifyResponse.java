package sn.innolink.kyvshield.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Response from {@code POST /api/v1/identify}.
 *
 * <p>Contains a list of {@link IdentifyMatch} entries ranked by descending
 * similarity score.
 */
public final class IdentifyResponse {

    private final boolean success;
    private final List<IdentifyMatch> matches;
    private final long processingTimeMs;

    private IdentifyResponse(boolean success, List<IdentifyMatch> matches, long processingTimeMs) {
        this.success          = success;
        this.matches          = Collections.unmodifiableList(matches);
        this.processingTimeMs = processingTimeMs;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    /** Returns {@code true} when the API call succeeded. */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns the list of matches ranked by descending similarity score.
     * May be empty when no match exceeds the minimum score threshold.
     */
    public List<IdentifyMatch> getMatches() {
        return matches;
    }

    /** Returns the server-side processing time in milliseconds. */
    public long getProcessingTimeMs() {
        return processingTimeMs;
    }

    // ── Deserialisation ───────────────────────────────────────────────────────

    /**
     * Deserialises an {@link IdentifyResponse} from a JSON object.
     *
     * @param json source JSON object
     * @return parsed instance
     */
    public static IdentifyResponse fromJson(JSONObject json) {
        boolean success          = json.optBoolean("success", false);
        long    processingTimeMs = json.optLong("processing_time_ms", 0L);

        List<IdentifyMatch> matches = new ArrayList<>();
        JSONArray matchesArr = json.optJSONArray("matches");
        if (matchesArr != null) {
            for (int i = 0; i < matchesArr.length(); i++) {
                JSONObject matchObj = matchesArr.optJSONObject(i);
                if (matchObj != null) {
                    matches.add(IdentifyMatch.fromJson(matchObj));
                }
            }
        }

        return new IdentifyResponse(success, matches, processingTimeMs);
    }

    @Override
    public String toString() {
        return "IdentifyResponse{success=" + success
                + ", matches=" + matches.size()
                + ", processingTimeMs=" + processingTimeMs + '}';
    }
}
