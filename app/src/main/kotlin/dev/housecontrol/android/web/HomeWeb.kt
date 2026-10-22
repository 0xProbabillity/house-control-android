package dev.housecontrol.android.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewAssetLoader
import dev.housecontrol.android.BuildConfig
import dev.housecontrol.android.core.toJson
import dev.housecontrol.android.core.toJsonText
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * A WebView showing the web UI, plus the JSON bridge in both directions: the 3D home and
 * its panel ("panel", the same page the Mac shows in its menu bar popover) or the home
 * editor ("editor", the Mac's editor window laid out for a phone).
 *
 * The page is served from the app's assets under https://appassets.androidplatform.net/, so
 * ES modules, fetch and localStorage behave as they would on a normal origin. The boot data
 * (which plan, the safe areas) goes into index.html itself, ahead of the page's own scripts.
 */
class HomeWeb(context: Context, val mode: String = "panel") {
    var onMessage: ((JSONObject) -> Unit)? = null
    private val appContext = context.applicationContext
    private val host = MutableContextWrapper(appContext)
    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    private var boot: (() -> JSONObject)? = null
    /** Set by the page's "ready" message; a load that never gets there is retried. */
    var ready = false
        private set
    private var retries = 0
    private val watchdog = Runnable {
        if (!ready && retries < 2) {
            retries++
            reload()
        }
    }
    /** The system bars and the camera cutout (top, bottom, left, right), in CSS pixels, for the page's safe areas. */
    private var insets = listOf(0f, 0f, 0f, 0f)
    /** Goes up when the WebView was replaced (its renderer died), so the screen shows the new one. */
    var generation by mutableIntStateOf(0)
        private set

    private val assets = WebViewAssetLoader.Builder()
        .addPathHandler("/", PageHandler())
        .build()

    /** The web view, made the first time a screen shows it. */
    fun webView(activity: Context): WebView {
        host.baseContext = activity
        view?.let { return it }
        val webView = makeView()
        view = webView
        if (boot != null) reload()
        return webView
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeView(): WebView = WebView(host).apply {
        // Without explicit sizes a WebView measures its page as "wrap content", and the page's
        // `height: 100%` comes out as nothing.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.parseColor("#0a0e1a"))
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            textZoom = 100 // the page sets its own type sizes
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        addJavascriptInterface(Bridge(), "HouseAndroid")
        webViewClient = Client()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        isHapticFeedbackEnabled = true
    }

    /** Loads the page now if the web view is on screen, else as soon as it is. */
    fun load(boot: () -> JSONObject) {
        this.boot = boot
        if (view != null) reload()
    }

    fun reload() {
        val view = view ?: return
        ready = false
        view.loadUrl("$ORIGIN/index.html?mode=$mode")
        // Right after a cold start the renderer can be slow enough to drop the first load;
        // a page that has not reported in is loaded again, a couple of times at most.
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 12_000)
    }

    /** Calls `window.house.<function>(…args)` in the page. */
    fun call(function: String, vararg args: Any?) {
        val list = args.joinToString(",") { literal(it) }
        val script = "window.house && window.house.$function && window.house.$function($list);"
        main.post { view?.evaluateJavascript(script, null) }
    }

    fun reply(message: JSONObject, result: JSONObject) {
        if (!message.has("callId")) return
        call("reply", message.get("callId"), result)
    }

    /** The system bars changed (rotation, gesture navigation): the page's safe areas follow. */
    fun setInsets(top: Float, bottom: Float, left: Float, right: Float) {
        val next = listOf(top, bottom, left, right)
        if (insets == next) return
        insets = next
        main.post { view?.evaluateJavascript(insetScript(), null) }
    }

    /**
     * Android's back: the page goes back a step if it can (a device sheet, a room, the
     * editor's panels). `done` gets false when there is nothing to go back from.
     */
    fun back(done: (Boolean) -> Unit) {
        val view = view
        if (view == null || !ready) { done(false); return }
        view.evaluateJavascript("(window.house && window.house.back) ? window.house.back() : false") { result ->
            done(result == "true")
        }
    }

    /** Pauses the page's rendering while it is off screen. */
    fun setVisible(visible: Boolean) {
        val view = view ?: return
        if (visible) {
            view.onResume()
            view.resumeTimers()
        } else {
            view.onPause()
        }
    }

    /** A tap the person can feel. */
    fun haptic(kind: String) {
        val view = view ?: return
        val constant = when (kind) {
            "tick" -> HapticFeedbackConstants.CLOCK_TICK
            "success" -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            "error" -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            "medium" -> HapticFeedbackConstants.KEYBOARD_TAP
            else -> HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(constant)
    }

    /** Stops the page from talking back and frees the web view. */
    fun destroy() {
        main.removeCallbacks(watchdog)
        onMessage = null
        view?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            v.stopLoading()
            v.removeJavascriptInterface("HouseAndroid")
            v.destroy()
        }
        view = null
    }

    private fun insetScript() = buildString {
        for ((edge, value) in listOf("top", "bottom", "left", "right").zip(insets)) {
            append("document.documentElement.style.setProperty('--safe-$edge-preview','${value}px');")
        }
        // The page sizes its 3D view for these: tell it they changed.
        append("window.dispatchEvent(new Event('resize'));")
    }

    /** What the page posts through `window.HouseAndroid.postMessage(json)`; runs on a binder thread. */
    inner class Bridge {
        @JavascriptInterface
        fun postMessage(text: String) {
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            main.post {
                when (message.optString("type")) {
                    "log" -> {
                        Log.e("HouseControl", "web-$mode: ${message.optString("text")}")
                        return@post
                    }
                    "ready" -> {
                        ready = true
                        retries = 0
                        main.removeCallbacks(watchdog)
                        // The bars may have been measured while the page was still loading.
                        view?.evaluateJavascript(insetScript(), null)
                    }
                    "haptic" -> {
                        haptic(message.optString("kind", "light"))
                        return@post
                    }
                }
                onMessage?.invoke(message)
            }
        }
    }

    /** index.html with the boot data in it; every other file as it is in the assets. */
    private inner class PageHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val file = path.ifEmpty { "index.html" }
            if (file.contains("..")) return null
            val bytes = runCatching { appContext.assets.open("web/$file").use { it.readBytes() } }.getOrNull()
                ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            val data = if (file == "index.html") inject(String(bytes, Charsets.UTF_8)).toByteArray() else bytes
            val type = MIME[file.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
            return WebResourceResponse(type, if (type.startsWith("text/") || type.endsWith("json")) "utf-8" else null,
                200, "OK", mapOf("Cache-Control" to "no-cache"), ByteArrayInputStream(data))
        }

        private fun inject(html: String): String {
            val payload = boot?.invoke() ?: JSONObject()
            payload.put("mode", mode)
            payload.put("platform", "android")
            val script = "<script>window.__HOUSE_BOOT__ = ${payload.toJsonText().replace("</", "<\\/")};${insetScript()}$ERROR_REPORTER</script>"
            return html.replaceFirst("<head>", "<head>$script")
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            assets.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.host == Uri.parse(ORIGIN).host) return false
            // The UI never navigates away; anything external opens in the browser.
            if (url.scheme == "http" || url.scheme == "https") {
                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            return true
        }

        /** The renderer was killed (memory pressure in the background): a new web view takes over. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view !== this@HomeWeb.view) return true
            destroyQuietly()
            this@HomeWeb.view = makeView()
            generation++
            reload()
            return true
        }
    }

    private fun destroyQuietly() {
        view?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            runCatching { v.destroy() }
        }
        view = null
    }

    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"

        private val MIME = mapOf(
            "html" to "text/html", "js" to "text/javascript", "mjs" to "text/javascript", "css" to "text/css",
            "json" to "application/json", "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg",
            "woff2" to "font/woff2", "map" to "application/json",
        )

        /** Script errors in the page end up in the device log (`adb logcat -s HouseControl`). */
        private const val ERROR_REPORTER = """(() => {
  const tell = (text) => window.HouseAndroid && window.HouseAndroid.postMessage(JSON.stringify({ type: 'log', text: String(text) }));
  window.addEventListener('error', (e) => tell(e.message + ' (' + (e.filename || '').split('/').pop() + ':' + e.lineno + ')'), true);
  window.addEventListener('unhandledrejection', (e) => tell('unhandled: ' + (e.reason && e.reason.message || e.reason)));
})();"""

        /** `value` as JavaScript source: an object, an array or a scalar. */
        fun literal(value: Any?): String = when (val json = value.toJson()) {
            JSONObject.NULL -> "null"
            is String -> JSONObject.quote(json).replace("\\/", "/")
            is JSONObject, is org.json.JSONArray -> json.toJsonText()
            is Number -> JSONObject.numberToString(json)
            else -> json.toString()
        }
    }
}
