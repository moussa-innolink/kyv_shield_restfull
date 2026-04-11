package sn.innolink.kyvshield.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AML/sanctions screening result returned alongside the KYC response.
 *
 * <p>Present in {@link KycResponse} only when AML screening was configured
 * for the application.
 */
public final class AMLScreening {

    private final boolean performed;
    private final String status;
    private final String riskLevel;
    private final int totalMatches;
    private final List<AMLMatch> matches;
    private final String screenedAt;
    private final int durationMs;

    private AMLScreening(boolean performed, String status, String riskLevel,
                         int totalMatches, List<AMLMatch> matches,
                         String screenedAt, int durationMs) {
        this.performed    = performed;
        this.status       = status;
        this.riskLevel    = riskLevel;
        this.totalMatches = totalMatches;
        this.matches      = Collections.unmodifiableList(matches);
        this.screenedAt   = screenedAt;
        this.durationMs   = durationMs;
    }

    /** Whether AML screening was performed. */
    public boolean isPerformed()       { return performed; }

    /** Screening status: "clear", "match", "error", or "disabled". */
    public String getStatus()          { return status; }

    /** Risk level: "low", "medium", "high", or "critical". */
    public String getRiskLevel()       { return riskLevel; }

    /** Total number of matches found. */
    public int getTotalMatches()       { return totalMatches; }

    /** List of matched entities. Never {@code null}. */
    public List<AMLMatch> getMatches() { return matches; }

    /** ISO 8601 timestamp when screening was performed, or {@code null}. */
    public String getScreenedAt()      { return screenedAt; }

    /** Processing duration in milliseconds. */
    public int getDurationMs()         { return durationMs; }

    /** Whether the screening result is clear (no matches). */
    public boolean isClear()           { return "clear".equals(status); }

    /** Whether matches were found. */
    public boolean hasMatches()        { return "match".equals(status) && totalMatches > 0; }

    public static AMLScreening fromJson(JSONObject json) {
        boolean performed  = json.optBoolean("performed", false);
        String  status     = json.optString("status", "disabled");
        String  riskLevel  = json.optString("risk_level", "low");
        int     totalMatch = json.optInt("total_matches", 0);
        String  screenedAt = json.optString("screened_at", null);
        int     durationMs = json.optInt("duration_ms", 0);

        List<AMLMatch> matches = new ArrayList<>();
        JSONArray arr = json.optJSONArray("matches");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject mObj = arr.optJSONObject(i);
                if (mObj != null) {
                    matches.add(AMLMatch.fromJson(mObj));
                }
            }
        }

        return new AMLScreening(performed, status, riskLevel, totalMatch,
                matches, screenedAt, durationMs);
    }

    @Override
    public String toString() {
        return "AMLScreening{performed=" + performed
                + ", status='" + status + '\''
                + ", riskLevel='" + riskLevel + '\''
                + ", totalMatches=" + totalMatches
                + ", durationMs=" + durationMs + '}';
    }
}
