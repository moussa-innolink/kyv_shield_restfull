package sn.innolink.kyvshield.model;

/**
 * Options for {@code KyvShield.verifyFace()}.
 *
 * <p>Construct instances via the nested {@link Builder}:
 *
 * <pre>{@code
 * VerifyFaceOptions options = new VerifyFaceOptions.Builder()
 *     .targetImage("/path/to/selfie.jpg")
 *     .sourceImage("/path/to/document_photo.jpg")
 *     .detectionModel("retinaface")
 *     .recognitionModel("arcface")
 *     .build();
 * }</pre>
 */
public final class VerifyFaceOptions {

    private final String targetImage;
    private final byte[] targetImageBytes;
    private final String sourceImage;
    private final byte[] sourceImageBytes;
    private final String detectionModel;
    private final String recognitionModel;

    private VerifyFaceOptions(Builder builder) {
        boolean hasTarget = (builder.targetImage != null && !builder.targetImage.isEmpty())
                || (builder.targetImageBytes != null && builder.targetImageBytes.length > 0);
        boolean hasSource = (builder.sourceImage != null && !builder.sourceImage.isEmpty())
                || (builder.sourceImageBytes != null && builder.sourceImageBytes.length > 0);
        if (!hasTarget) {
            throw new IllegalArgumentException("targetImage must be provided (path, URL, base64, or byte[])");
        }
        if (!hasSource) {
            throw new IllegalArgumentException("sourceImage must be provided (path, URL, base64, or byte[])");
        }
        this.targetImage       = builder.targetImage;
        this.targetImageBytes  = builder.targetImageBytes;
        this.sourceImage       = builder.sourceImage;
        this.sourceImageBytes  = builder.sourceImageBytes;
        this.detectionModel    = builder.detectionModel;
        this.recognitionModel  = builder.recognitionModel;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    /**
     * Returns the target image value (file path, URL, data URI, or base64 string),
     * or {@code null} when raw bytes were provided.
     */
    public String getTargetImage() {
        return targetImage;
    }

    /** Returns the target image raw bytes, or {@code null}. */
    public byte[] getTargetImageBytes() {
        return targetImageBytes;
    }

    /**
     * Returns the source image value (file path, URL, data URI, or base64 string),
     * or {@code null} when raw bytes were provided.
     */
    public String getSourceImage() {
        return sourceImage;
    }

    /** Returns the source image raw bytes, or {@code null}. */
    public byte[] getSourceImageBytes() {
        return sourceImageBytes;
    }

    /**
     * Returns the face detection model name, or {@code null} for server default.
     */
    public String getDetectionModel() {
        return detectionModel;
    }

    /**
     * Returns the face recognition model name, or {@code null} for server default.
     */
    public String getRecognitionModel() {
        return recognitionModel;
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /** Builder for {@link VerifyFaceOptions}. */
    public static final class Builder {

        private String targetImage;
        private byte[] targetImageBytes;
        private String sourceImage;
        private byte[] sourceImageBytes;
        private String detectionModel;
        private String recognitionModel;

        /**
         * Sets the target image (the face to verify) as a file path, URL,
         * data URI, or base64 string.
         *
         * @param targetImage image value
         * @return this builder
         */
        public Builder targetImage(String targetImage) {
            this.targetImage = targetImage;
            return this;
        }

        /**
         * Sets the target image as raw bytes.
         *
         * @param targetImageBytes raw image bytes (JPEG, PNG, etc.)
         * @return this builder
         */
        public Builder targetImageBytes(byte[] targetImageBytes) {
            this.targetImageBytes = targetImageBytes;
            return this;
        }

        /**
         * Sets the source image (the reference face to compare against) as a
         * file path, URL, data URI, or base64 string.
         *
         * @param sourceImage image value
         * @return this builder
         */
        public Builder sourceImage(String sourceImage) {
            this.sourceImage = sourceImage;
            return this;
        }

        /**
         * Sets the source image as raw bytes.
         *
         * @param sourceImageBytes raw image bytes (JPEG, PNG, etc.)
         * @return this builder
         */
        public Builder sourceImageBytes(byte[] sourceImageBytes) {
            this.sourceImageBytes = sourceImageBytes;
            return this;
        }

        /**
         * Sets the face detection model to use on the server side.
         *
         * @param detectionModel model name (e.g. {@code "retinaface"})
         * @return this builder
         */
        public Builder detectionModel(String detectionModel) {
            this.detectionModel = detectionModel;
            return this;
        }

        /**
         * Sets the face recognition model to use on the server side.
         *
         * @param recognitionModel model name (e.g. {@code "arcface"})
         * @return this builder
         */
        public Builder recognitionModel(String recognitionModel) {
            this.recognitionModel = recognitionModel;
            return this;
        }

        /**
         * Builds and returns the {@link VerifyFaceOptions} instance.
         *
         * @throws IllegalArgumentException if target or source image is missing
         */
        public VerifyFaceOptions build() {
            return new VerifyFaceOptions(this);
        }
    }

    @Override
    public String toString() {
        return "VerifyFaceOptions{detectionModel='" + detectionModel
                + "', recognitionModel='" + recognitionModel + "'}";
    }
}
