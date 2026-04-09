package sn.innolink.kyvshield;

/**
 * Exception thrown by the KyvShield SDK when an API call fails or the
 * response cannot be parsed.
 */
public class KyvShieldException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    /**
     * Creates an exception with a human-readable message only (no HTTP context).
     *
     * @param message description of the error
     */
    public KyvShieldException(String message) {
        super(message);
        this.statusCode = 0;
        this.responseBody = null;
    }

    /**
     * Creates an exception wrapping another throwable.
     *
     * @param message description of the error
     * @param cause   underlying cause
     */
    public KyvShieldException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.responseBody = null;
    }

    /**
     * Creates an exception with the HTTP status code and raw response body.
     *
     * @param message      description of the error
     * @param statusCode   HTTP status code returned by the server
     * @param responseBody raw response body as a string
     */
    public KyvShieldException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /**
     * Returns the HTTP status code associated with this error, or {@code 0} if the
     * error did not originate from an HTTP response.
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Returns the raw HTTP response body, or {@code null} if not available.
     */
    public String getResponseBody() {
        return responseBody;
    }

    @Override
    public String toString() {
        if (statusCode != 0) {
            return "KyvShieldException(statusCode=" + statusCode + "): " + getMessage();
        }
        return "KyvShieldException: " + getMessage();
    }
}
