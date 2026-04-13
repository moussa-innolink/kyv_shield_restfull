package sn.innolink.kyvshield.model;

/**
 * Options for {@code KyvShield.identify()}.
 *
 * <p>Construct instances via the nested {@link Builder}:
 *
 * <pre>{@code
 * IdentifyOptions options = new IdentifyOptions.Builder()
 *     .image("/path/to/probe.jpg")
 *     .topK(5)
 *     .minScore(0.7)
 *     .build();
 * }</pre>
 */
public final class IdentifyOptions {

    private final String image;
    private final byte[] imageBytes;
    private final int topK;
    private final double minScore;

    private IdentifyOptions(Builder builder) {
        boolean hasImage = (builder.image != null && !builder.image.isEmpty())
                || (builder.imageBytes != null && builder.imageBytes.length > 0);
        if (!hasImage) {
            throw new IllegalArgumentException("image must be provided (path, URL, base64, or byte[])");
        }
        this.image      = builder.image;
        this.imageBytes = builder.imageBytes;
        this.topK       = builder.topK;
        this.minScore   = builder.minScore;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    /**
     * Returns the image value (file path, URL, data URI, or base64 string),
     * or {@code null} when raw bytes were provided via {@link #getImageBytes()}.
     */
    public String getImage() {
        return image;
    }

    /**
     * Returns the raw image bytes, or {@code null} when a string value was
     * provided via {@link #getImage()}.
     */
    public byte[] getImageBytes() {
        return imageBytes;
    }

    /**
     * Returns the maximum number of matches to return. Default: {@code 5}.
     */
    public int getTopK() {
        return topK;
    }

    /**
     * Returns the minimum similarity score threshold (0–1). Matches below this
     * score are excluded. Default: {@code 0.0} (no filtering).
     */
    public double getMinScore() {
        return minScore;
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /** Builder for {@link IdentifyOptions}. */
    public static final class Builder {

        private String image;
        private byte[] imageBytes;
        private int topK = 5;
        private double minScore = 0.0;

        /**
         * Sets the probe image as a file path, URL, data URI, or base64 string.
         *
         * @param image image value
         * @return this builder
         */
        public Builder image(String image) {
            this.image = image;
            return this;
        }

        /**
         * Sets the probe image as raw bytes.
         *
         * @param imageBytes raw image bytes (JPEG, PNG, etc.)
         * @return this builder
         */
        public Builder imageBytes(byte[] imageBytes) {
            this.imageBytes = imageBytes;
            return this;
        }

        /**
         * Sets the maximum number of matches to return.
         *
         * @param topK max matches (default: 5)
         * @return this builder
         */
        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        /**
         * Sets the minimum similarity score threshold (0–1).
         *
         * @param minScore minimum score (default: 0.0)
         * @return this builder
         */
        public Builder minScore(double minScore) {
            this.minScore = minScore;
            return this;
        }

        /**
         * Builds and returns the {@link IdentifyOptions} instance.
         *
         * @throws IllegalArgumentException if no image was provided
         */
        public IdentifyOptions build() {
            return new IdentifyOptions(this);
        }
    }

    @Override
    public String toString() {
        return "IdentifyOptions{topK=" + topK + ", minScore=" + minScore + '}';
    }
}
