package sn.innolink.kyvshield

/**
 * Exception thrown by the KyvShield SDK.
 *
 * @param message Human-readable description of the error.
 * @param statusCode HTTP status code when the error originates from an HTTP response, or `null`.
 * @param errorCode Machine-readable error code returned by the API (e.g. `"MISSING_FIELD"`), or `null`.
 * @param responseBody Raw JSON response body as a string, if available.
 * @param cause Underlying exception that triggered this error, if any.
 */
class KyvShieldException(
    message: String,
    val statusCode: Int? = null,
    val errorCode: String? = null,
    val responseBody: String? = null,
    cause: Throwable? = null,
) : RuntimeException(buildMessage(message, statusCode, errorCode), cause) {

    companion object {
        private fun buildMessage(message: String, statusCode: Int?, errorCode: String?): String {
            val parts = mutableListOf(message)
            if (statusCode != null) parts.add("HTTP $statusCode")
            if (errorCode != null) parts.add("code=$errorCode")
            return parts.joinToString(" | ")
        }
    }

    override fun toString(): String =
        "KyvShieldException(message=${message}, statusCode=$statusCode, errorCode=$errorCode)"
}
