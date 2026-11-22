package app.stroid

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var root: File
    private var session: TerminalSession? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = File(filesDir, "projects").apply { mkdirs() }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                loader.shouldInterceptRequest(request.url)
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/editor.html")
    }

    override fun onDestroy() {
        session?.stop()
        super.onDestroy()
    }

    private fun js(code: String) {
        web.post { web.evaluateJavascript(code, null) }
    }

    inner class Bridge {
        private fun safe(path: String): File {
            val f = File(root, path).canonicalFile
            require(f.path.startsWith(root.canonicalPath + File.separator)) { "bad path" }
            return f
        }

        @JavascriptInterface
        fun list(): String = runCatching {
            root.walkTopDown().filter { it.isFile }
                .joinToString("\n") { it.relativeTo(root).path }
        }.getOrDefault("")

        @JavascriptInterface
        fun read(path: String): String = runCatching { safe(path).readText() }.getOrDefault("")

        @JavascriptInterface
        fun write(path: String, content: String): Boolean = runCatching {
            val f = safe(path)
            f.parentFile?.mkdirs()
            f.writeText(content)
            true
        }.getOrDefault(false)

        @JavascriptInterface
        fun delete(path: String): Boolean = runCatching { safe(path).delete() }.getOrDefault(false)

        @JavascriptInterface
        fun termStart(cols: Int, rows: Int) {
            if (session?.alive == true) return
            val s = TerminalSession(
                this@MainActivity,
                root,
                emit = { b -> js("termWrite('" + Base64.encodeToString(b, Base64.NO_WRAP) + "')") },
                onEnd = { js("termEnded()") }
            )
            session = s
            s.start(cols.coerceIn(20, 300), rows.coerceIn(5, 100))
        }

        @JavascriptInterface
        fun termInput(b64: String) {
            session?.write(Base64.decode(b64, Base64.DEFAULT))
        }
    }
}
