package sn.innolink.kyvshield.model;

/**
 * Aggregated KYC decision returned by the API after processing all steps.
 *
 * <ul>
 *   <li>{@link #PASS}   — all verification steps passed.</li>
 *   <li>{@link #REJECT} — one or more steps failed or fraud was detected.</li>
 * </ul>
 */
public enum OverallStatus {

    /** All verification steps passed — identity confirmed. */
    PASS("pass"),

    /** One or more steps failed or a fraud indicator was detected. */
    REJECT("reject");

    private final String value;

    OverallStatus(String value) {
        this.value = value;
    }

    /** Returns the wire-format string value returned by the API. */
    public String getValue() {
        return value;
    }

    /**
     * Parses a wire-format string into an {@link OverallStatus}.
     *
     * @param value wire value such as {@code "pass"}
     * @return matching enum constant, or {@link #REJECT} for unrecognised values
     */
    public static OverallStatus fromValue(String value) {
        if (value != null) {
            for (OverallStatus status : values()) {
                if (status.value.equalsIgnoreCase(value)) {
                    return status;
                }
            }
        }
        return REJECT;
    }

    @Override
    public String toString() {
        return value;
    }
}
