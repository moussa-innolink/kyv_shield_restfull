package sn.innolink.kyvshield.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Response from {@code GET /api/v1/challenges}.
 *
 * <p>Contains available challenge identifiers grouped by intensity mode for
 * both document steps and selfie steps.
 */
public final class ChallengesResponse {

    private final ChallengeModeMap document;
    private final ChallengeModeMap selfie;

    private ChallengesResponse(ChallengeModeMap document, ChallengeModeMap selfie) {
        this.document = document;
        this.selfie   = selfie;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    /**
     * Returns challenge identifiers grouped by mode for document steps
     * (recto / verso).
     */
    public ChallengeModeMap getDocument() {
        return document;
    }

    /**
     * Returns challenge identifiers grouped by mode for the selfie step.
     */
    public ChallengeModeMap getSelfie() {
        return selfie;
    }

    // ── Deserialisation ───────────────────────────────────────────────────────

    /**
     * Deserialises a {@link ChallengesResponse} from a JSON object.
     *
     * @param json source JSON object (the top-level response body)
     * @return parsed instance
     */
    public static ChallengesResponse fromJson(JSONObject json) {
        JSONObject challengesObj = json.optJSONObject("challenges");
        if (challengesObj == null) {
            // Tolerate a flat response structure
            challengesObj = json;
        }

        ChallengeModeMap document = ChallengeModeMap.fromJson(
                challengesObj.optJSONObject("document"));
        ChallengeModeMap selfie = ChallengeModeMap.fromJson(
                challengesObj.optJSONObject("selfie"));

        return new ChallengesResponse(document, selfie);
    }

    @Override
    public String toString() {
        return "ChallengesResponse{document=" + document + ", selfie=" + selfie + '}';
    }

    // ── Nested: ChallengeModeMap ──────────────────────────────────────────────

    /**
     * Available challenge identifiers grouped by intensity mode for a single
     * asset type (document or selfie).
     */
    public static final class ChallengeModeMap {

        private final List<String> minimal;
        private final List<String> standard;
        private final List<String> strict;

        private ChallengeModeMap(
                List<String> minimal,
                List<String> standard,
                List<String> strict) {
            this.minimal  = Collections.unmodifiableList(minimal);
            this.standard = Collections.unmodifiableList(standard);
            this.strict   = Collections.unmodifiableList(strict);
        }

        /** Returns the challenge identifiers available in {@link ChallengeMode#MINIMAL} mode. */
        public List<String> getMinimal() {
            return minimal;
        }

        /** Returns the challenge identifiers available in {@link ChallengeMode#STANDARD} mode. */
        public List<String> getStandard() {
            return standard;
        }

        /** Returns the challenge identifiers available in {@link ChallengeMode#STRICT} mode. */
        public List<String> getStrict() {
            return strict;
        }

        static ChallengeModeMap fromJson(JSONObject json) {
            if (json == null) {
                return new ChallengeModeMap(
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList());
            }
            return new ChallengeModeMap(
                    parseStringList(json.optJSONArray("minimal")),
                    parseStringList(json.optJSONArray("standard")),
                    parseStringList(json.optJSONArray("strict")));
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
            return "ChallengeModeMap{minimal=" + minimal
                    + ", standard=" + standard
                    + ", strict=" + strict + '}';
        }
    }
}
