package dev.housecontrol.android.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The HTTP clients the platforms share. */
object Http {
    val json = "application/json".toMediaType()

    /** For requests that should answer within seconds. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        // Home Assistant and the Hue Bridge live on the home network: never through a proxy.
        .proxy(java.net.Proxy.NO_PROXY)
        .build()

    fun body(text: String) = text.toRequestBody(json)

    fun request(url: String): Request.Builder = Request.Builder().url(url)
}

/** The body of an answer as text, read off the main thread; the call is cancelled with the coroutine. */
data class HttpAnswer(val code: Int, val text: String)

suspend fun OkHttpClient.fetch(request: Request): HttpAnswer = withContext(Dispatchers.IO) {
    val call = newCall(request)
    val response = call.await()
    response.use { HttpAnswer(it.code, it.body?.string() ?: "") }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
}

/** An exception's message for people, never the class name alone. */
val Throwable.readable: String
    get() = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
