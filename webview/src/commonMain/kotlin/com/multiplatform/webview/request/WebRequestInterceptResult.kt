package com.multiplatform.webview.request

/**
 * Created By Kevin Zou On 2023/11/30
 */
sealed interface WebRequestInterceptResult {
    data object Allow : WebRequestInterceptResult

    data object Reject : WebRequestInterceptResult

    class Modify(
        val request: WebRequest,
    ) : WebRequestInterceptResult

    /**
     * Respond with custom data instead of making a network request.
     * This allows implementing custom URL schemes or serving local content.
     *
     * Note: This result type requires a custom URL scheme handler to be registered
     * on iOS (via WKURLSchemeHandler) or Android (via shouldInterceptRequest).
     *
     * @param data The response body as a byte array
     * @param mimeType The MIME type of the response (e.g., "text/html", "application/json")
     * @param statusCode The HTTP status code (default: 200)
     * @param headers Optional response headers
     */
    class Respond(
        val data: ByteArray,
        val mimeType: String,
        val statusCode: Int = 200,
        val headers: Map<String, String> = emptyMap(),
    ) : WebRequestInterceptResult
}
