package com.mubox.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mubox.app.ipc.VpnStateStore
import com.mubox.app.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * 内置更新的下载与安装：按检查阶段选好的资产下载 APK（VPN 在跑时先走本机
 * 代理口、失败回退直连），按发布摘要校验 SHA-256，再经 FileProvider 拉起
 * 系统安装器。最后的「安装」确认由系统界面完成，不做静默安装。
 */
object AppUpdater {

    private fun updateDir(context: Context): File {
        return File(context.cacheDir, "updates").apply { mkdirs() }
    }

    suspend fun downloadApk(
        context: Context,
        asset: AppUpdateChecker.ReleaseAsset,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File {
        val settings = SettingsRepository.getInstance(context).settings.first()
        val useProxy = VpnStateStore.getActive() && settings.proxyPort > 0
        return try {
            downloadWithClient(context, asset, proxyPort = if (useProxy) settings.proxyPort else null, onProgress)
        } catch (e: IOException) {
            if (!useProxy) throw e
            // 代理口下载失败时回退直连再试一次
            downloadWithClient(context, asset, proxyPort = null, onProgress)
        }
    }

    private suspend fun downloadWithClient(
        context: Context,
        asset: AppUpdateChecker.ReleaseAsset,
        proxyPort: Int?,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = updateDir(context)
        val target = File(dir, asset.name)
        val partial = File(dir, asset.name + ".part")
        val client = if (proxyPort != null) {
            NetworkClient.createClientWithProxy(
                proxyPort = proxyPort,
                connectTimeoutSeconds = 20,
                readTimeoutSeconds = 60,
                writeTimeoutSeconds = 60,
                callTimeoutSeconds = null
            )
        } else {
            // 用 WithoutRetry 变体显式给足总时长：WithTimeout 传 null 会继承
            // 基础客户端的 60 秒 callTimeout，慢网下 18 MB 包会被中途掐断。
            NetworkClient.createClientWithoutRetry(
                connectTimeoutSeconds = 20,
                readTimeoutSeconds = 60,
                writeTimeoutSeconds = 60,
                callTimeoutSeconds = 600
            )
        }
        val request = Request.Builder()
            .url(asset.downloadUrl)
            .header("User-Agent", "MuBox-Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty download body")
            val total = if (asset.size > 0) asset.size else body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(partial).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        }
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        target
    }

    /** 发布资产在 GitHub API 中的摘要（sha256:…），无摘要时返回 null。 */
    fun expectedSha256(asset: AppUpdateChecker.ReleaseAsset): String? {
        val digest = asset.digest?.trim().orEmpty()
        if (digest.isEmpty()) return null
        return digest.removePrefix("sha256:").lowercase()
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun canRequestInstalls(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    fun unknownSourcesSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
