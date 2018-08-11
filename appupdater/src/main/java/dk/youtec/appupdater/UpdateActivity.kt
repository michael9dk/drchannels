package dk.youtec.appupdater

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.content.FileProvider
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import kotlinx.coroutines.CommonPool
import kotlinx.coroutines.android.UI
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Request
import okio.Okio
import java.io.File

class UpdateActivity : AppCompatActivity() {
    private val tag = "UpdateActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        findViewById<ProgressBar?>(R.id.progress)?.visibility = View.VISIBLE

        val apkUrl = intent.getStringExtra("apkUrl")

        launch(UI) {
            downloadApk(this@UpdateActivity, apkUrl)?.also {
                installApk(this@UpdateActivity, it)
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }

    private suspend fun downloadApk(context: Context, apkUrl: String): File? =
            withContext(CommonPool) {
                val httpClient = OkHttpClientFactory.getInstance(context)

                val request = Request.Builder()
                        .url(apkUrl)
                        .cacheControl(CacheControl.Builder().noStore().build())
                        .build()
                val response = httpClient.newCall(request).execute()
                val source = response.body()?.source()

                if (source != null) {
                    val cacheDir = context.externalCacheDir ?: context.cacheDir
                    val apkCacheFolder = File(cacheDir, "apk").apply { mkdirs() }
                    val apkFile = File(apkCacheFolder, "app.apk").apply { createNewFile() }
                    Okio.buffer(Okio.sink(apkFile)).apply {
                        writeAll(source)
                        close()
                    }

                    Log.v(tag,
                            "Downloaded APK to ${apkFile.absolutePath} with size ${apkFile.length()}")

                    apkFile
                } else {
                    null
                }
            }

    private fun installApk(context: Context, apkFile: File) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apkFile)
            val type = "application/vnd.android.package-archive"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, type)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(tag, e.message, e)
        }
    }
}