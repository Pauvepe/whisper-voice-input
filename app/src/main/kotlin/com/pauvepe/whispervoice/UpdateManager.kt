package com.pauvepe.whispervoice

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class UpdateManager(private val context: Context) {

    companion object {
        private const val API_URL =
            "https://api.github.com/repos/Pauvepe/whisper-voice-input/releases/latest"
        private const val APK_NAME = "whisper-voice-update.apk"
    }

    suspend fun checkAndUpdate(): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(API_URL).openConnection()
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            val json = connection.getInputStream().bufferedReader().readText()
            val release = JSONObject(json)

            val tagName = release.getString("tag_name") // e.g. "v5"
            val remoteVersion = tagName.removePrefix("v").toIntOrNull() ?: return@withContext false

            val currentVersion = try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode
                }
            } catch (_: Exception) {
                0
            }

            if (remoteVersion <= currentVersion) return@withContext false

            // Get APK download URL
            val assets = release.getJSONArray("assets")
            if (assets.length() == 0) return@withContext false
            val apkUrl = assets.getJSONObject(0).getString("browser_download_url")

            // Download on main thread context
            withContext(Dispatchers.Main) {
                downloadAndInstall(apkUrl)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun downloadAndInstall(url: String) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Whisper Voice Update")
                .setDescription("Downloading update...")
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, APK_NAME)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setMimeType("application/vnd.android.package-archive")

            val downloadId = dm.enqueue(request)

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        try {
                            context.unregisterReceiver(this)
                        } catch (_: Exception) {}
                        val uri = dm.getUriForDownloadedFile(downloadId)
                        if (uri != null) {
                            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(installIntent)
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }

            Toast.makeText(context, "Downloading update...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
