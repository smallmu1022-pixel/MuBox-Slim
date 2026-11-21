package com.mubox.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.mubox.app.R
import com.mubox.app.manager.VpnServiceManager

class ShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        moveTaskToBack(true)

        setContentView(R.layout.activity_none)

        if (intent?.action == ACTION_TOGGLE) {
            VpnServiceManager.toggleVpn(this)
        }

        finish()
    }

    companion object {
        const val ACTION_TOGGLE = "com.mubox.app.action.TOGGLE"
    }
}
