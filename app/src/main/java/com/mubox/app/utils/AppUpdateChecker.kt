package com.mubox.app.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.mubox.app.R
import com.mubox.app.ipc.VpnStateStore
import com.mubox.app.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object AppUpdateChecker {
    private const val TAG = "AppUpdateChecker"

    private const val GITHUB_API_URL = "https://api.github.com/repos/smallmu1022-pixel/MuBox-Slim/releases/latest"
    private const val CHANNEL_ID = "app_update"
    private const val NOTIFICATION_ID = 1001

    private const val PREFS_NAME = "app_update_prefs"
    private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"

    // 发布说明正文里以 HTML 注释携带目标 versionCode（页面渲染不可见），
    // 使同名热修（versionName 不变、versionCode 递增）也能被检查到。
    private val VERSION_CODE_COMMENT = Regex("""<!--\s*vc:\s*(\d+)\s*-->""")

    private val gson = Gson()

    data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("name") val name: String,
        @SerializedName("body") val body: String?,
        @SerializedName("html_url") val htmlUrl: String,
        @SerializedName("published_at") val publishedAt: String?,
        @SerializedName("assets") val assets: List<ReleaseAsset>?
    )

    data class ReleaseAsset(
        @SerializedName("name") val name: String,
        @SerializedName("browser_download_url") val downloadUrl: String,
        @SerializedName("size") val size: Long,
        @SerializedName("digest") val digest: String?
    )

    /**
     * 只做检查并返回结果，不发通知。界面层据此弹内置更新弹窗。
     */
    suspend fun check(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val currentVersion = getCurrentVersion(context)
            val currentVersionCode = getCurrentVersionCode(context)
            Log.d(TAG, "Current version: $currentVersion ($currentVersionCode)")

            val release = fetchLatestReleaseWithFallback(context)
            if (release == null) {
                Log.w(TAG, "Failed to fetch latest release")
                return@withContext UpdateCheckResult.Error("Failed to fetch release info")
            }

            val latestVersion = release.tagName.removePrefix("v")
            val releaseVersionCode = parseReleaseVersionCode(release.body)
            Log.d(TAG, "Latest version: $latestVersion ($releaseVersionCode)")

            // 正文带 vc 注释时按 versionCode 判断（可识别同名热修），否则回退版本名比较。
            val isNewer = if (releaseVersionCode != null) {
                releaseVersionCode > currentVersionCode
            } else {
                isNewerVersion(latestVersion, currentVersion)
            }

            if (isNewer) {
                Log.i(TAG, "New version available: $latestVersion")
                return@withContext UpdateCheckResult.UpdateAvailable(
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    latestVersionCode = releaseVersionCode,
                    releaseUrl = release.htmlUrl,
                    releaseNotes = cleanReleaseNotes(release.body),
                    asset = selectAssetForDevice(release.assets)
                )
            } else {
                Log.d(TAG, "Already on latest version")
                return@withContext UpdateCheckResult.UpToDate(currentVersion)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
            return@withContext UpdateCheckResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * 检查并在有新版时发一条通知（旧入口，保留兼容）。
     */
    suspend fun checkAndNotify(
        context: Context,
        forceNotify: Boolean = false
    ): UpdateCheckResult {
        val result = check(context)
        if (result is UpdateCheckResult.UpdateAvailable) {
            val notifyKey = result.latestVersion + ":" + (result.latestVersionCode?.toString() ?: "")
            val lastNotifiedVersion = getLastNotifiedVersion(context)
            if (forceNotify || lastNotifiedVersion != notifyKey) {
                if (showUpdateNotification(context, result.latestVersion, result.releaseNotes, result.releaseUrl)) {
                    setLastNotifiedVersion(context, notifyKey)
                }
            } else {
                Log.d(TAG, "Already notified for version ${result.latestVersion}, skipping")
            }
        }
        return result
    }

    private fun getCurrentVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "0.0.0"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current version", e)
            "0.0.0"
        }
    }

    private fun getCurrentVersionCode(context: Context): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current version code", e)
            0L
        }
    }

    private fun parseReleaseVersionCode(body: String?): Long? {
        if (body.isNullOrEmpty()) return null
        return VERSION_CODE_COMMENT.find(body)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun cleanReleaseNotes(body: String?): String? {
        if (body.isNullOrEmpty()) return null
        return body.replace(VERSION_CODE_COMMENT, "").trim().ifEmpty { null }
    }

    /**
     * 按本机支持的 ABI 顺序挑对应的 APK 资产（arm64 机型拿 arm64 包，v7a 拿 v7a 包）。
     */
    private fun selectAssetForDevice(assets: List<ReleaseAsset>?): ReleaseAsset? {
        if (assets.isNullOrEmpty()) return null
        for (abi in Build.SUPPORTED_ABIS) {
            val match = assets.firstOrNull { it.name.endsWith(".apk") && it.name.contains(abi) }
            if (match != null) return match
        }
        return assets.firstOrNull { it.name.endsWith(".apk") }
    }

    private suspend fun fetchLatestReleaseWithFallback(context: Context): GitHubRelease? {
        val request = Request.Builder()
            .url(GITHUB_API_URL)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "MuBox-Android")
            .build()

        val settings = SettingsRepository.getInstance(context).settings.first()
        val proxyResult = tryProxyRequest(request, settings)

        return proxyResult ?: tryDirectRequest(request)
    }

    private fun tryProxyRequest(request: Request, settings: com.mubox.app.model.AppSettings): GitHubRelease? {
        val proxyClient = getProxyClient(settings) ?: return null
        return try {
            proxyClient.newCall(request).execute().use { response ->
                parseReleaseResponse(response, "Proxy")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Proxy request failed: ${e.message}")
            null
        }
    }

    private fun tryDirectRequest(request: Request): GitHubRelease? {
        return try {
            getDirectClient().newCall(request).execute().use { response ->
                parseReleaseResponse(response, "Direct")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct request also failed", e)
            null
        }
    }

    private fun parseReleaseResponse(response: Response, source: String): GitHubRelease? {
        if (!response.isSuccessful) {
            Log.w(TAG, "$source request failed with ${response.code}")
            return null
        }
        val json = response.body.string()
        Log.d(TAG, "$source request succeeded for update check")
        return gson.fromJson(json, GitHubRelease::class.java)
    }

    private fun getDirectClient(): OkHttpClient {
        return NetworkClient.createClientWithTimeout(
            connectTimeoutSeconds = 15,
            readTimeoutSeconds = 20,
            writeTimeoutSeconds = 20,
            callTimeoutSeconds = 30
        )
    }

    private fun getProxyClient(settings: com.mubox.app.model.AppSettings): OkHttpClient? {
        if (!VpnStateStore.getActive() || settings.proxyPort <= 0) {
            return null
        }
        return NetworkClient.createClientWithProxy(
            proxyPort = settings.proxyPort,
            connectTimeoutSeconds = 15,
            readTimeoutSeconds = 20,
            writeTimeoutSeconds = 20,
            callTimeoutSeconds = 30
        )
    }

    private fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        try {
            val newParts = parseVersion(newVersion)
            val currentParts = parseVersion(currentVersion)

            for (i in 0 until maxOf(newParts.size, currentParts.size)) {
                val newPart = newParts.getOrElse(i) { 0 }
                val currentPart = currentParts.getOrElse(i) { 0 }

                if (newPart > currentPart) return true
                if (newPart < currentPart) return false
            }

            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compare versions: $newVersion vs $currentVersion", e)
            return false
        }
    }

    private fun parseVersion(version: String): List<Int> {

        val cleanVersion = version
            .removePrefix("v")
            .split("-")[0]

        return cleanVersion.split(".").mapNotNull { it.toIntOrNull() }
    }

    private fun showUpdateNotification(
        context: Context,
        version: String,
        body: String?,
        htmlUrl: String
    ): Boolean {
        if (!canPostNotifications(context)) {
            Log.w(TAG, "Notification permission not granted, update notification skipped")
            return false
        }

        createNotificationChannel(context)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl))
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(R.string.update_notification_title)
        val content = context.getString(R.string.update_notification_content, version)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                buildString {
                    append(content)
                    body?.let { notes ->
                        append("\n\n")

                        val truncatedNotes = if (notes.length > 200) {
                            notes.take(200) + "..."
                        } else {
                            notes
                        }
                        append(truncatedNotes)
                    }
                }
            ))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)

        Log.i(TAG, "Update notification shown for version $version")
        return true
    }

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.update_channel_name)
            val description = context.getString(R.string.update_channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT

            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                this.description = description
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun getLastNotifiedVersion(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_NOTIFIED_VERSION, null)
    }

    private fun setLastNotifiedVersion(context: Context, version: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_NOTIFIED_VERSION, version).apply()
    }

    fun clearLastNotifiedVersion(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_LAST_NOTIFIED_VERSION).apply()
    }
}

sealed class UpdateCheckResult {
    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val latestVersionCode: Long?,
        val releaseUrl: String,
        val releaseNotes: String?,
        val asset: AppUpdateChecker.ReleaseAsset?
    ) : UpdateCheckResult()

    data class UpToDate(val currentVersion: String) : UpdateCheckResult()

    data class Error(val message: String) : UpdateCheckResult()
}
