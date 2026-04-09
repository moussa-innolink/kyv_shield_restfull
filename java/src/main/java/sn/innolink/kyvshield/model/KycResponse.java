package sn.innolink.kyvshield.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Top-level response from {@code POST /api/v1/kyc/verify}.
 *
 * <p>Contains the aggregated pass/reject decision, an ordered list of per-step
 * results, and optionally a face-match result when {@code requireFaceMatch} was
 * {@code true} in the request.
 */
public final class KycResponse {

    private final boolean success;
    private final String sessionId;
    private final OverallStatus overallStatus;
    private final double overallConfidence;
    private final long processingTimeMs;
    private final FaceVerification faceVerification;
    private final List<StepResult> steps;

    private KycResponse(
            boolean success,
            String sessionId,
            OverallStatus overallStatus,
            double overallConfidence,
            long processingTimeMs,
            FaceVerification faceVerification,
            List<StepResult> steps) {
        this.success           = success;
        this.sessionId         = sessionId;
        this.overallStatus     = overallStatus;
        this.overallConfidence = overallConfidence;
        this.processingTimeMs  = processingTimeMs;
        this.faceVerification  = faceVerification;
        this.steps             = Collections.unmodifiableList(steps);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    /** Returns {@code true} when the API call itself succeeded at the HTTP level. */
    public boolean isSuccess() {
        return success;
    }

    /** Returns the unique session identifier for this verification run. */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Returns the aggregated pass / reject decision across all submitted steps.
     */
    public OverallStatus getOverallStatus() {
        return overallStatus;
    }

    /**
     * Returns the aggregated confidence score in the range {@code [0, 1]}.
     */
    public double getOverallConfidence() {
        return overallConfidence;
    }

    /**
     * Returns the total server-side processing time across all steps in
     * milliseconds.
     */
    public long getProcessingTimeMs() {
        return processingTimeMs;
    }

    /**
     * Returns the cross-step face-match result when {@code requireFaceMatch} was
     * {@code true}. Returns {@code null} when face matching was not requested.
     */
    public FaceVerification getFaceVerification() {
        return faceVerification;
    }

    /**
     * Returns per-step results in the order they were submitted.
     * Never {@code null}.
     */
    public List<StepResult> getSteps() {
        return steps;
    }

    // ── Deserialisation ───────────────────────────────────────────────────────

    /**
     * Deserialises a {@link KycResponse} from a JSON object.
     *
     * @param json source JSON object (the raw API response body)
     * @return parsed instance
     */
    public static KycResponse fromJson(JSONObject json) {
        boolean success           = json.optBoolean("success", false);
        String  sessionId         = json.optString("session_id", "");
        double  overallConfidence = json.optDouble("overall_confidence", 0.0);
        long    processingTimeMs  = json.optLong("processing_time_ms", 0L);

        OverallStatus overallStatus = OverallStatus.fromValue(
                json.optString("overall_status", "reject"));

        FaceVerification faceVerification = null;
        JSONObject faceObj = json.optJSONObject("face_verification");
        if (faceObj != null) {
            faceVerification = FaceVerification.fromJson(faceObj);
        }

        List<StepResult> steps = new ArrayList<>();
        JSONArray stepsArr = json.optJSONArray("steps");
        if (stepsArr != null) {
            for (int i = 0; i < stepsArr.length(); i++) {
                JSONObject stepObj = stepsArr.optJSONObject(i);
                if (stepObj != null) {
                    steps.add(StepResult.fromJson(stepObj));
                }
            }
        }

        return new KycResponse(success, sessionId, overallStatus, overallConfidence,
                processingTimeMs, faceVerification, steps);
    }

    @Override
    public String toString() {
        return "KycResponse{success=" + success
                + ", sessionId='" + sessionId + '\''
                + ", overallStatus=" + overallStatus
                + ", overallConfidence=" + overallConfidence
                + ", processingTimeMs=" + processingTimeMs
                + ", steps=" + steps.size() + '}';
    }
}
