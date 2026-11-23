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
import kotlinx.coroutines.job
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

    /** 校验失败专用异常：文案直接给用户看（弹窗原样显示）。 */
    class UpdateVerificationException : IOException("安装包校验失败，已删除，请重试")

    /**
     * 下载并校验：VPN 在跑时先走本机代理口，下载报错或校验不过（字节数对不上 /
     * SHA-256 对不上）都删掉重走直连再试一次；直连仍校验不过才抛
     * [UpdateVerificationException]。全局模式下代理通道实测会把更新包下坏，
     * 校验必须在下载层做完、失败要能触发回退（2026-11-22 vc34 真机实测）。
     */
    suspend fun downloadApk(
        context: Context,
        asset: AppUpdateChecker.ReleaseAsset,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File {
        val settings = SettingsRepository.getInstance(context).settings.first()
        val useProxy = VpnStateStore.getActive() && settings.proxyPort > 0
        val attempts: List<Int?> = if (useProxy) listOf(settings.proxyPort, null) else listOf(null)
        var lastError: IOException? = null
        for (proxyPort in attempts) {
            val file = try {
                downloadWithClient(context, asset, proxyPort = proxyPort, onProgress)
            } catch (e: IOException) {
                // 已被取消时 ensureActive 直接抛 CancellationException，
                // 不再把断连的 IOException 当失败去走下一条回退通道。
                currentCoroutineContext().ensureActive()
                lastError = e
                continue
            }
            if (verifyDownloaded(asset, file)) {
                return file
            }
            file.delete()
            lastError = UpdateVerificationException()
        }
        throw lastError ?: UpdateVerificationException()
    }

    /** 字节数与发布摘要双重校验；无摘要时只查字节数，资产没给大小时跳过大小项。 */
    private suspend fun verifyDownloaded(asset: AppUpdateChecker.ReleaseAsset, file: File): Boolean {
        if (asset.size > 0 && file.length() != asset.size) return false
        val expected = expectedSha256(asset) ?: return true
        val actual = withContext(Dispatchers.IO) { sha256Hex(file) }
        return actual.equals(expected, ignoreCase = true)
    }

    /**
     * 清理更新缓存（启动时调用）：已安装的包不等 24 小时——缓存 APK 的
     * versionCode 不高于当前版本即说明已装或已被覆盖，立即删；解析不出的
     * 损坏包、中断的 .part 同样立即删。只有「已下载未安装」的新版包保留
     * （留给用户回头再装），超过 24 小时仍未安装才兜底删除。
     */
    fun clearStaleUpdateFiles(context: Context) {
        val files = File(context.cacheDir, "updates").listFiles() ?: return
        val pm = context.packageManager
        val currentVersionCode = try {
            pm.getPackageInfo(context.packageName, 0).longVersionCode
        } catch (e: Exception) {
            return
        }
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        for (file in files) {
            if (!file.isFile) continue
            if (file.name.endsWith(".part")) {
                file.delete()
                continue
            }
            val archive = try {
                pm.getPackageArchiveInfo(file.absolutePath, 0)
            } catch (e: Exception) {
                null
            }
            val installedOrStale = archive == null ||
                archive.packageName != context.packageName ||
                archive.longVersionCode <= currentVersionCode
            if (installedOrStale || file.lastModified() < cutoff) {
                file.delete()
            }
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
            // 不设总超时（callTimeoutSeconds = null → OkHttp 0 表示不限，
            // 2026-11-23 用户拍板）：下载持续多久由用户决定，要停点「取消
            // 下载」立即断连。Bypass 变体绑物理网络绕开 TUN（全局模式下
            // 普通直连会被自身隧道捕获、与代理通道同路）。
            NetworkClient.createBypassClientWithoutRetry(
                context = context,
                connectTimeoutSeconds = 20,
                readTimeoutSeconds = 60,
                writeTimeoutSeconds = 60,
                callTimeoutSeconds = null
            )
        }
        val request = Request.Builder()
            .url(asset.downloadUrl)
            .header("User-Agent", "MuBox-Android")
            .build()
        val call = client.newCall(request)
        // 协程被取消时联动 Call.cancel() 立即断连：阻塞中的流式读取当场
        // 抛错退出，不再等 60 秒读超时（vc40 实测点取消卡 0% 无反应）。
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
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
        } finally {
            cancelHandle.dispose()
        }
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
