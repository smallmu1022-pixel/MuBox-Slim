package com.mubox.app.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 内置更新弹窗的事件桥：检查方（启动自动检查 / 设置页手动检查）把
 * 「发现新版」结果投递到这里，根界面 SingBoxApp 统一收集并弹窗。
 */
object UpdateUiEvents {
    private val _pending = MutableStateFlow<UpdateCheckResult.UpdateAvailable?>(null)
    val pending: StateFlow<UpdateCheckResult.UpdateAvailable?> = _pending

    fun post(update: UpdateCheckResult.UpdateAvailable) {
        _pending.value = update
    }

    fun clear() {
        _pending.value = null
    }
}
