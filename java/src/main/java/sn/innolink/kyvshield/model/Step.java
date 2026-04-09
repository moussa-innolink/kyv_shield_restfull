package sn.innolink.kyvshield.model;

/**
 * Verification step type submitted as part of a KYC request.
 *
 * <ul>
 *   <li>{@link #SELFIE} — live selfie capture.</li>
 *   <li>{@link #RECTO}  — front face of the identity document.</li>
 *   <li>{@link #VERSO}  — back face of the identity document.</li>
 * </ul>
 */
public enum Step {

    /** Live selfie capture. */
    SELFIE("selfie"),

    /** Front face of the identity document. */
    RECTO("recto"),

    /** Back face of the identity document. */
    VERSO("verso");

    private final String value;

    Step(String value) {
        this.value = value;
    }

    /** Returns the wire-format string value sent to the API. */
    public String getValue() {
        return value;
    }

    /**
     * Parses a wire-format string into a {@link Step}.
     *
     * @param value wire value such as {@code "recto"}
     * @return matching enum constant
     * @throws IllegalArgumentException if {@code value} is not recognised
     */
    public static Step fromValue(String value) {
        for (Step step : values()) {
            if (step.value.equalsIgnoreCase(value)) {
                return step;
            }
        }
        throw new IllegalArgumentException("Unknown Step: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
