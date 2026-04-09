package sn.innolink.kyvshield.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Options for {@code KyvShield.verify()}.
 *
 * <p>Construct instances via the nested {@link Builder}:
 *
 * <pre>{@code
 * VerifyOptions options = new VerifyOptions.Builder()
 *     .steps(Step.RECTO, Step.VERSO)
 *     .target("SN-CIN")
 *     .language("fr")
 *     .rectoChallengeMode(ChallengeMode.MINIMAL)
 *     .versoChallengeMode(ChallengeMode.MINIMAL)
 *     .addImage("recto_center_document", "/path/to/recto.jpg")
 *     .addImage("verso_center_document", "/path/to/verso.jpg")
 *     .build();
 * }</pre>
 */
public final class VerifyOptions {

    // Required
    private final List<Step> steps;
    private final String target;

    // Optional
    private final String language;
    private final ChallengeMode challengeMode;
    private final ChallengeMode selfieChallengeMode;
    private final ChallengeMode rectoChallengeMode;
    private final ChallengeMode versoChallengeMode;
    private final boolean requireFaceMatch;
    private final String kycIdentifier;

    /**
     * Image map where keys follow the pattern {@code {step}_{challenge}}
     * (e.g. {@code "recto_center_document"}) and values are one of:
     * <ul>
     *   <li>absolute or relative filesystem path</li>
     *   <li>{@code http://} / {@code https://} URL (downloaded by the SDK)</li>
     *   <li>{@code data:image/…;base64,…} data URI</li>
     *   <li>bare base64-encoded string</li>
     * </ul>
     * For raw bytes, use {@link #imageBytes} instead.
     */
    private final Map<String, String> images;

    /**
     * Raw-bytes image map. Keys follow the same {@code {step}_{challenge}}
     * pattern; values are byte arrays containing the raw image data.
     * Entries here are merged with (and take priority over) {@link #images}.
     */
    private final Map<String, byte[]> imageBytes;

    private VerifyOptions(Builder builder) {
        if (builder.steps == null || builder.steps.isEmpty()) {
            throw new IllegalArgumentException("steps must contain at least one Step");
        }
        if (builder.target == null || builder.target.isEmpty()) {
            throw new IllegalArgumentException("target must not be null or empty");
        }
        boolean hasImages = (builder.images != null && !builder.images.isEmpty())
                || (builder.imageBytes != null && !builder.imageBytes.isEmpty());
        if (!hasImages) {
            throw new IllegalArgumentException("images must contain at least one entry");
        }

        this.steps               = Collections.unmodifiableList(builder.steps);
        this.target              = builder.target;
        this.language            = builder.language != null ? builder.language : "fr";
        this.challengeMode       = builder.challengeMode;
        this.selfieChallengeMode = builder.selfieChallengeMode;
        this.rectoChallengeMode  = builder.rectoChallengeMode;
        this.versoChallengeMode  = builder.versoChallengeMode;
        this.requireFaceMatch    = builder.requireFaceMatch;
        this.kycIdentifier       = builder.kycIdentifier;
        this.images              = Collections.unmodifiableMap(new HashMap<>(builder.images != null ? builder.images : new HashMap<>()));
        this.imageBytes          = Collections.unmodifiableMap(new HashMap<>(builder.imageBytes != null ? builder.imageBytes : new HashMap<>()));
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    /** Returns the ordered list of steps to execute. */
    public List<Step> getSteps() {
        return steps;
    }

    /** Returns the document type to verify against (e.g. {@code "SN-CIN"}). */
    public String getTarget() {
        return target;
    }

    /** Returns the language for user-facing messages (default: {@code "fr"}). */
    public String getLanguage() {
        return language;
    }

    /**
     * Returns the global fallback challenge mode applied to all steps unless
     * overridden, or {@code null} when not set (server uses its default).
     */
    public ChallengeMode getChallengeMode() {
        return challengeMode;
    }

    /** Returns the per-step challenge mode override for the selfie step, or {@code null}. */
    public ChallengeMode getSelfieChallengeMode() {
        return selfieChallengeMode;
    }

    /** Returns the per-step challenge mode override for the recto step, or {@code null}. */
    public ChallengeMode getRectoChallengeMode() {
        return rectoChallengeMode;
    }

    /** Returns the per-step challenge mode override for the verso step, or {@code null}. */
    public ChallengeMode getVersoChallengeMode() {
        return versoChallengeMode;
    }

    /**
     * Returns {@code true} when a cross-step face match between the selfie and
     * the document portrait should be performed.
     */
    public boolean isRequireFaceMatch() {
        return requireFaceMatch;
    }

    /**
     * Returns the caller-provided identifier for correlating sessions in an
     * external system, or {@code null} when not set.
     */
    public String getKycIdentifier() {
        return kycIdentifier;
    }

    /**
     * Returns the image map: keys are {@code {step}_{challenge}} patterns;
     * values are file-system paths, URLs, data URIs, or base64 strings.
     */
    public Map<String, String> getImages() {
        return images;
    }

    /**
     * Returns the raw-bytes image map: keys are {@code {step}_{challenge}} patterns;
     * values are raw image bytes.
     */
    public Map<String, byte[]> getImageBytes() {
        return imageBytes;
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /** Builder for {@link VerifyOptions}. */
    public static final class Builder {

        private List<Step> steps;
        private String target;
        private String language;
        private ChallengeMode challengeMode;
        private ChallengeMode selfieChallengeMode;
        private ChallengeMode rectoChallengeMode;
        private ChallengeMode versoChallengeMode;
        private boolean requireFaceMatch = false;
        private String kycIdentifier;
        private Map<String, String> images = new HashMap<>();
        private Map<String, byte[]> imageBytes = new HashMap<>();

        /**
         * Sets the ordered list of steps to execute.
         *
         * @param steps one or more {@link Step} values
         * @return this builder
         */
        public Builder steps(Step... steps) {
            this.steps = Arrays.asList(steps);
            return this;
        }

        /**
         * Sets the ordered list of steps to execute.
         *
         * @param steps list of {@link Step} values
         * @return this builder
         */
        public Builder steps(List<Step> steps) {
            this.steps = steps;
            return this;
        }

        /**
         * Sets the document type to verify against.
         *
         * @param target e.g. {@code "SN-CIN"}, {@code "SN-PASSPORT"}, {@code "SN-DRIVER-LICENCE"}
         * @return this builder
         */
        public Builder target(String target) {
            this.target = target;
            return this;
        }

        /**
         * Sets the language for user-facing messages in the response.
         * Accepts {@code "fr"} (default), {@code "en"}, or {@code "wo"}.
         *
         * @param language language code
         * @return this builder
         */
        public Builder language(String language) {
            this.language = language;
            return this;
        }

        /**
         * Sets the global fallback challenge mode applied to all steps unless
         * overridden by a step-specific mode.
         *
         * @param challengeMode global challenge mode
         * @return this builder
         */
        public Builder challengeMode(ChallengeMode challengeMode) {
            this.challengeMode = challengeMode;
            return this;
        }

        /**
         * Overrides the challenge mode for the selfie step only.
         *
         * @param mode challenge mode for the selfie step
         * @return this builder
         */
        public Builder selfieChallengeMode(ChallengeMode mode) {
            this.selfieChallengeMode = mode;
            return this;
        }

        /**
         * Overrides the challenge mode for the recto step only.
         *
         * @param mode challenge mode for the recto step
         * @return this builder
         */
        public Builder rectoChallengeMode(ChallengeMode mode) {
            this.rectoChallengeMode = mode;
            return this;
        }

        /**
         * Overrides the challenge mode for the verso step only.
         *
         * @param mode challenge mode for the verso step
         * @return this builder
         */
        public Builder versoChallengeMode(ChallengeMode mode) {
            this.versoChallengeMode = mode;
            return this;
        }

        /**
         * Sets whether a cross-step face match between the selfie and the
         * document portrait should be performed.
         *
         * @param require {@code true} to enable face matching
         * @return this builder
         */
        public Builder requireFaceMatch(boolean require) {
            this.requireFaceMatch = require;
            return this;
        }

        /**
         * Sets an optional caller-provided identifier for correlating sessions
         * in an external system.
         *
         * @param kycIdentifier caller identifier
         * @return this builder
         */
        public Builder kycIdentifier(String kycIdentifier) {
            this.kycIdentifier = kycIdentifier;
            return this;
        }

        /**
         * Adds a single image entry.
         *
         * @param key      image key following the pattern {@code {step}_{challenge}},
         *                 e.g. {@code "recto_center_document"}
         * @param filePath absolute or relative path to the image file
         * @return this builder
         */
        public Builder addImage(String key, String filePath) {
            this.images.put(key, filePath);
            return this;
        }

        /**
         * Replaces the entire image map.
         *
         * @param images map of image keys to file paths / URLs / base64 strings
         * @return this builder
         */
        public Builder images(Map<String, String> images) {
            this.images = new HashMap<>(images);
            return this;
        }

        /**
         * Adds a single raw-bytes image entry.
         *
         * @param key   image key following the pattern {@code {step}_{challenge}}
         * @param data  raw image bytes (JPEG, PNG, etc.)
         * @return this builder
         */
        public Builder addImageBytes(String key, byte[] data) {
            this.imageBytes.put(key, data);
            return this;
        }

        /**
         * Replaces the entire raw-bytes image map.
         *
         * @param imageBytes map of image keys to raw byte arrays
         * @return this builder
         */
        public Builder imageBytes(Map<String, byte[]> imageBytes) {
            this.imageBytes = new HashMap<>(imageBytes);
            return this;
        }

        /**
         * Builds and returns the {@link VerifyOptions} instance.
         *
         * @throws IllegalArgumentException if required fields are missing
         */
        public VerifyOptions build() {
            return new VerifyOptions(this);
        }
    }

    @Override
    public String toString() {
        return "VerifyOptions{steps=" + steps
                + ", target='" + target + '\''
                + ", language='" + language + '\''
                + ", challengeMode=" + challengeMode
                + ", requireFaceMatch=" + requireFaceMatch
                + ", images=" + images.keySet() + '}';
    }
}
