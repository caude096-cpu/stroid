package app.stroid

import android.annotation.SuppressLint
import android.os.Bundle
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
    }
}
