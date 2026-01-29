package com.multiplatform.webview.request

import com.multiplatform.webview.util.KLogger
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.HTTPMethod
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.allHTTPHeaderFields
import platform.Foundation.create
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKWebView
import platform.darwin.NSObject

/**
 * WKURLSchemeHandler implementation for custom URL schemes.
 * This allows intercepting requests with custom schemes (e.g., "app://", "local://")
 * and providing custom responses.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class WKSchemeHandler(
    private val navigator: WebViewNavigator,
) : NSObject(), WKURLSchemeHandlerProtocol {

    private val activeTasks = mutableMapOf<Int, Boolean>()

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
        val taskId = startURLSchemeTask.hashCode()
        activeTasks[taskId] = true

        val request = startURLSchemeTask.request
        val url = request.URL?.absoluteString ?: ""

        KLogger.info { "WKSchemeHandler: Intercepting request for $url" }

        // Build WebRequest
        val headerMap = mutableMapOf<String, String>()
        request.allHTTPHeaderFields?.forEach {
            headerMap[it.key.toString()] = it.value.toString()
        }

        val webRequest = WebRequest(
            url = url,
            headers = headerMap,
            isForMainFrame = true,
            isRedirect = false,
            method = request.HTTPMethod ?: "GET",
        )

        // Check if we have an interceptor
        val interceptor = navigator.requestInterceptor
        if (interceptor == null) {
            KLogger.w { "WKSchemeHandler: No request interceptor set, failing request" }
            failTask(startURLSchemeTask, "No request interceptor configured")
            activeTasks.remove(taskId)
            return
        }

        // Call the interceptor
        val result = interceptor.onInterceptUrlRequest(webRequest, navigator)

        // Check if task was cancelled
        if (activeTasks[taskId] != true) {
            KLogger.info { "WKSchemeHandler: Task was cancelled" }
            return
        }

        when (result) {
            is WebRequestInterceptResult.Respond -> {
                respondWithData(startURLSchemeTask, result, url)
            }
            is WebRequestInterceptResult.Reject -> {
                failTask(startURLSchemeTask, "Request rejected by interceptor")
            }
            else -> {
                // For Allow and Modify, we can't actually make the request
                // because this is a custom scheme. Return an error.
                failTask(startURLSchemeTask, "Custom scheme requires Respond result")
            }
        }

        activeTasks.remove(taskId)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) {
        val taskId = stopURLSchemeTask.hashCode()
        activeTasks[taskId] = false
        KLogger.info { "WKSchemeHandler: Task stopped" }
    }

    private fun respondWithData(
        task: WKURLSchemeTaskProtocol,
        result: WebRequestInterceptResult.Respond,
        url: String,
    ) {
        try {
            // Build response headers
            val headerFields = mutableMapOf<Any?, Any?>()
            headerFields["Content-Type"] = result.mimeType
            headerFields["Content-Length"] = result.data.size.toString()
            result.headers.forEach { (key, value) ->
                headerFields[key] = value
            }

            // Create HTTP response
            val response = NSHTTPURLResponse(
                uRL = NSURL.URLWithString(url)!!,
                statusCode = result.statusCode.toLong(),
                HTTPVersion = "HTTP/1.1",
                headerFields = headerFields,
            )

            // Send response
            task.didReceiveResponse(response!!)

            // Send data
            if (result.data.isNotEmpty()) {
                result.data.usePinned { pinned ->
                    val nsData = NSData.create(
                        bytes = pinned.addressOf(0),
                        length = result.data.size.toULong(),
                    )
                    task.didReceiveData(nsData)
                }
            }

            // Finish
            task.didFinish()

            KLogger.info { "WKSchemeHandler: Successfully responded with ${result.data.size} bytes" }
        } catch (e: Exception) {
            KLogger.e { "WKSchemeHandler: Error responding: ${e.message}" }
            failTask(task, e.message ?: "Unknown error")
        }
    }

    private fun failTask(task: WKURLSchemeTaskProtocol, message: String) {
        try {
            val error = platform.Foundation.NSError.errorWithDomain(
                domain = "WKSchemeHandler",
                code = -1,
                userInfo = mapOf("NSLocalizedDescriptionKey" to message),
            )
            task.didFailWithError(error)
        } catch (e: Exception) {
            KLogger.e { "WKSchemeHandler: Error failing task: ${e.message}" }
        }
    }
}
