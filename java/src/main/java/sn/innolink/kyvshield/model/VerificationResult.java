package sn.innolink.kyvshield.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Authenticity and fraud detection sub-result for a single verification step.
 *
 * <p>Contains the authenticity decision, detected fraud indicators, non-blocking
 * warnings, and blocking issues found during AI analysis.
 */
public final class VerificationResult {

    private final boolean isAuthentic;
    private final double confidence;
    private final List<String> checksPassed;
    private final List<String> fraudIndicators;
    private final List<String> warnings;
    private final List<String> issues;

    private VerificationResult(
            boolean isAuthentic,
            double confidence,
            List<String> checksPassed,
            List<String> fraudIndicators,
            List<String> warnings,
            List<String> issues) {
        this.isAuthentic     = isAuthentic;
        this.confidence      = confidence;
        this.checksPassed    = Collections.unmodifiableList(checksPassed);
        this.fraudIndicators = Collections.unmodifiableList(fraudIndicators);
        this.warnings        = Collections.unmodifiableList(warnings);
        this.issues          = Collections.unmodifiableList(issues);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the document or selfie is considered authentic
     * (no fraud indicators that would block the verification).
     */
    public boolean isAuthentic() {
        return isAuthentic;
    }

    /**
     * Returns the confidence of the authenticity decision in the range {@code [0, 1]}.
     */
    public double getConfidence() {
        return confidence;
    }

    /**
     * Returns the list of security checks that passed (e.g. {@code "reflection_dynamic"}).
     * Never {@code null}; may be empty.
     */
    public List<String> getChecksPassed() {
        return checksPassed;
    }

    /**
     * Returns detected fraud indicators (e.g. {@code "printed_document"}).
     * Never {@code null}; may be empty.
     */
    public List<String> getFraudIndicators() {
        return fraudIndicators;
    }

    /**
     * Returns non-blocking warnings that do not by themselves cause rejection.
     * Never {@code null}; may be empty.
     */
    public List<String> getWarnings() {
        return warnings;
    }

    /**
     * Returns blocking issues that contributed to a rejection decision.
     * Never {@code null}; may be empty.
     */
    public List<String> getIssues() {
        return issues;
    }

    // ── Deserialisation ───────────────────────────────────────────────────────

    /**
     * Deserialises a {@link VerificationResult} from a JSON object.
     *
     * @param json source JSON object
     * @return parsed instance
     */
    public static VerificationResult fromJson(JSONObject json) {
        boolean isAuthentic     = json.optBoolean("is_authentic", false);
        double  confidence      = json.optDouble("confidence", 0.0);
        List<String> checksPassed    = parseStringList(json.optJSONArray("checks_passed"));
        List<String> fraudIndicators = parseStringList(json.optJSONArray("fraud_indicators"));
        List<String> warnings        = parseStringList(json.optJSONArray("warnings"));
        List<String> issues          = parseStringList(json.optJSONArray("issues"));
        return new VerificationResult(isAuthentic, confidence, checksPassed,
                fraudIndicators, warnings, issues);
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

    @Override
    public String toString() {
        return "VerificationResult{isAuthentic=" + isAuthentic
                + ", confidence=" + confidence
                + ", checksPassed=" + checksPassed
                + ", fraudIndicators=" + fraudIndicators
                + ", warnings=" + warnings
                + ", issues=" + issues + '}';
    }
}
