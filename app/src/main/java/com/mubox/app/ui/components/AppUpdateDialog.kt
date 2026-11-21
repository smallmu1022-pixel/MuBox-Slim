package com.mubox.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mubox.app.utils.AppUpdater
import com.mubox.app.utils.UpdateCheckResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface UpdatePhase {
    object Info : UpdatePhase
    data class Downloading(val downloaded: Long, val total: Long) : UpdatePhase
    object Verifying : UpdatePhase
    data class Ready(val apk: File, val verified: Boolean) : UpdatePhase
    data class Failed(val message: String) : UpdatePhase
}

/**
 * 内置更新弹窗（流程与文案按 2026-11-22 用户确认的预览）：
 * 发现新版 → App 内下载对应架构的包 → SHA-256 校验 → 拉起系统安装器。
 */
@Composable
fun AppUpdateDialog(
    update: UpdateCheckResult.UpdateAvailable,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes = remember(update.releaseNotes) { formatReleaseNotes(update.releaseNotes) }
    var phase by remember { mutableStateOf<UpdatePhase>(UpdatePhase.Info) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var installHint by remember { mutableStateOf(false) }

    fun startDownload() {
        val asset = update.asset
        if (asset == null) {
            phase = UpdatePhase.Failed("该版本没有适配本机的安装包")
            return
        }
        installHint = false
        downloadJob = scope.launch {
            try {
                phase = UpdatePhase.Downloading(0L, asset.size)
                val apk = AppUpdater.downloadApk(context, asset) { downloaded, total ->
                    phase = UpdatePhase.Downloading(downloaded, total)
                }
                phase = UpdatePhase.Verifying
                val expected = AppUpdater.expectedSha256(asset)
                val verified = expected == null ||
                    withContext(Dispatchers.IO) { AppUpdater.sha256Hex(apk) }
                        .equals(expected, ignoreCase = true)
                if (!verified) {
                    apk.delete()
                    phase = UpdatePhase.Failed("安装包校验失败，已删除，请重试")
                } else {
                    phase = UpdatePhase.Ready(apk, expected != null)
                }
            } catch (e: CancellationException) {
                phase = UpdatePhase.Info
            } catch (e: Exception) {
                phase = UpdatePhase.Failed(e.message ?: "下载失败，请稍后再试")
            }
        }
    }

    val busy = phase is UpdatePhase.Downloading || phase is UpdatePhase.Verifying

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("发现新版本") },
        text = {
            when (val p = phase) {
                is UpdatePhase.Info -> {
                    Column {
                        Text(
                            text = "${update.currentVersion} → v${update.latestVersion}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (notes.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(text = notes, style = MaterialTheme.typography.bodyMedium)
                        }
                        update.asset?.let { asset ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "安装包 ${formatSize(asset.size)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                is UpdatePhase.Downloading -> {
                    Column {
                        val fraction = if (p.total > 0) {
                            (p.downloaded.toFloat() / p.total.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        Text(text = "正在下载更新 · ${(fraction * 100).toInt()}%")
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { fraction })
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${formatSize(p.downloaded)} / ${formatSize(p.total)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is UpdatePhase.Verifying -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = "正在校验安装包…")
                    }
                }
                is UpdatePhase.Ready -> {
                    Column {
                        Text(
                            text = if (p.verified) "下载完成，校验通过" else "下载完成",
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "点「安装」打开系统安装界面，确认后覆盖升级，配置和节点不丢。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (installHint) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "已打开系统设置，请允许「安装未知应用」后回来再点安装。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                is UpdatePhase.Failed -> {
                    Text(text = p.message)
                }
            }
        },
        confirmButton = {
            when (val p = phase) {
                is UpdatePhase.Info -> {
                    TextButton(
                        onClick = { startDownload() },
                        enabled = update.asset != null
                    ) { Text("立即更新") }
                }
                is UpdatePhase.Ready -> {
                    TextButton(onClick = {
                        if (!AppUpdater.canRequestInstalls(context)) {
                            installHint = true
                            runCatching {
                                context.startActivity(AppUpdater.unknownSourcesSettingsIntent(context))
                            }
                        } else {
                            runCatching {
                                context.startActivity(AppUpdater.installIntent(context, p.apk))
                            }
                            onDismiss()
                        }
                    }) { Text("安装") }
                }
                is UpdatePhase.Failed -> {
                    TextButton(onClick = { phase = UpdatePhase.Info }) { Text("重试") }
                }
                else -> {}
            }
        },
        dismissButton = {
            when (phase) {
                is UpdatePhase.Downloading -> {
                    TextButton(onClick = { downloadJob?.cancel() }) { Text("取消下载") }
                }
                is UpdatePhase.Verifying -> {}
                else -> {
                    TextButton(onClick = onDismiss) {
                        Text(if (phase is UpdatePhase.Ready) "以后再说" else "稍后")
                    }
                }
            }
        }
    )
}

private fun formatReleaseNotes(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw.lines()
        .map { it.trim().trimStart('#').trim().replace("**", "") }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    return String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
