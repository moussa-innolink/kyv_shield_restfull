package sn.innolink.kyvshield.model;

/**
 * Challenge intensity mode used for liveness detection.
 *
 * <ul>
 *   <li>{@link #MINIMAL}  — fewest challenges, fastest UX.</li>
 *   <li>{@link #STANDARD} — balanced default.</li>
 *   <li>{@link #STRICT}   — most challenges, highest security.</li>
 * </ul>
 */
public enum ChallengeMode {

    /** Fewest challenges — fastest user experience. */
    MINIMAL("minimal"),

    /** Balanced default for most use-cases. */
    STANDARD("standard"),

    /** Maximum challenges for highest fraud resistance. */
    STRICT("strict");

    private final String value;

    ChallengeMode(String value) {
        this.value = value;
    }

    /** Returns the wire-format string value sent to the API. */
    public String getValue() {
        return value;
    }

    /**
     * Parses a wire-format string into a {@link ChallengeMode}.
     *
     * @param value wire value such as {@code "minimal"}
     * @return matching enum constant
     * @throws IllegalArgumentException if {@code value} is not recognised
     */
    public static ChallengeMode fromValue(String value) {
        for (ChallengeMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown ChallengeMode: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
