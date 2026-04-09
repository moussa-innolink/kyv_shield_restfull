package sn.innolink.kyvshield.model;

/**
 * Holds the outcome of a single entry in a {@code verifyBatch()} call.
 */
public final class BatchResult {

    private final boolean success;
    private final KycResponse result;
    private final String error;

    public BatchResult(boolean success, KycResponse result, String error) {
        this.success = success;
        this.result  = result;
        this.error   = error;
    }

    /** @return {@code true} if this individual verification succeeded */
    public boolean isSuccess() {
        return success;
    }

    /** @return the {@link KycResponse} when {@link #isSuccess()} is {@code true}, otherwise {@code null} */
    public KycResponse getResult() {
        return result;
    }

    /** @return the error message when {@link #isSuccess()} is {@code false}, otherwise {@code null} */
    public String getError() {
        return error;
    }

    @Override
    public String toString() {
        return "BatchResult{success=" + success
                + (error != null ? ", error=" + error : "")
                + '}';
    }
}
