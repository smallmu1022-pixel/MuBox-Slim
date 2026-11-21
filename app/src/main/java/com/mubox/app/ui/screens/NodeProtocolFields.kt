package com.mubox.app.ui.screens

import com.google.gson.JsonPrimitive
import com.mubox.app.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.mubox.app.model.ObfsConfig
import com.mubox.app.model.Outbound
import com.mubox.app.model.TlsConfig
import com.mubox.app.ui.components.EditableSelectionItem
import com.mubox.app.ui.components.EditableTextItem
import com.mubox.app.ui.components.SettingSwitchItem

@Composable
internal fun NodeProtocolFields(
    type: String,
    outbound: Outbound,
    editingOutboundState: MutableState<Outbound?>
) {
    var editingOutbound by editingOutboundState
    // --- Protocol Specific Fields ---

    // 2. VLESS
    if (type == "vless") {
        EditableTextItem(
            title = stringResource(R.string.node_detail_uuid),
            value = outbound.uuid ?: "",
            icon = Icons.Rounded.Person,
            onValueChange = { editingOutbound = outbound.copy(uuid = it) }
        )

        if (type == "vless") {
            EditableSelectionItem(
                title = stringResource(R.string.node_detail_flow),
                value = outbound.flow ?: "",
                options = listOf("", "xtls-rprx-vision"),
                icon = Icons.Rounded.Waves,
                onValueChange = { editingOutbound = outbound.copy(flow = it) }
            )
        }

        EditableSelectionItem(
            title = stringResource(R.string.node_detail_packet_encoding),
            value = outbound.packetEncoding ?: "",
            options = listOf("", "xudp", "packet"),
            icon = Icons.Rounded.Layers,
            onValueChange = { editingOutbound = outbound.copy(packetEncoding = if (it.isEmpty()) null else it) }
        )
    }

    // 3. Trojan
    if (type == "trojan") {
        EditableTextItem(
            title = stringResource(R.string.node_detail_password),
            value = outbound.password ?: "",
            icon = Icons.Rounded.Password,
            onValueChange = { editingOutbound = outbound.copy(password = it) }
        )
    }

    // 4. Hysteria 2
    if (type == "hysteria2") {
        EditableTextItem(
            title = stringResource(R.string.node_detail_password),
            value = outbound.password ?: "",
            icon = Icons.Rounded.Password,
            onValueChange = { editingOutbound = outbound.copy(password = it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_ports_jumping),
            value = outbound.serverPorts?.firstOrNull() ?: "",
            icon = Icons.Rounded.Numbers,
            onValueChange = {
                editingOutbound = outbound.copy(
                    serverPorts = if (it.isEmpty()) null else listOf(it)
                )
            }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_obfs_type),
            value = outbound.obfs?.type ?: "",
            icon = Icons.Rounded.Lock,
            onValueChange = {
                val newObfs = if (it.isEmpty()) null else (outbound.obfs?.copy(type = it) ?: ObfsConfig(type = it))
                editingOutbound = outbound.copy(obfs = newObfs)
            }
        )
        if (outbound.obfs?.type == "salamander") {
            EditableTextItem(
                title = stringResource(R.string.node_detail_obfs_password),
                value = outbound.obfs.password ?: "",
                icon = Icons.Rounded.Key,
                onValueChange = { editingOutbound = outbound.copy(obfs = outbound.obfs.copy(password = it)) }
            )
        }
        EditableTextItem(
            title = stringResource(R.string.node_detail_upload_speed),
            value = outbound.upMbps?.toString() ?: "",
            icon = Icons.Rounded.Speed,
            onValueChange = { editingOutbound = outbound.copy(upMbps = it.toIntOrNull()) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_download_speed),
            value = outbound.downMbps?.toString() ?: "",
            icon = Icons.Rounded.Speed,
            onValueChange = { editingOutbound = outbound.copy(downMbps = it.toIntOrNull()) }
        )
    }

    if (type == "hysteria2") {
        SettingSwitchItem(
            title = stringResource(R.string.node_detail_disable_sni),
            checked = outbound.tls?.disableSni == true,
            icon = Icons.Rounded.Fingerprint,
            onCheckedChange = { checked ->
                val currentTls = outbound.tls ?: TlsConfig(enabled = true)
                editingOutbound = outbound.copy(
                    disableSni = null,
                    tls = currentTls.copy(
                        enabled = true,
                        disableSni = if (checked) true else null
                    )
                )
            }
        )
    }

    // 8. AnyTLS
    if (type == "anytls") {
        EditableTextItem(
            title = stringResource(R.string.node_detail_password),
            value = outbound.password ?: "",
            icon = Icons.Rounded.Password,
            onValueChange = { editingOutbound = outbound.copy(password = it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_idle_session_check),
            value = outbound.idleSessionCheckInterval ?: "30s",
            icon = Icons.Rounded.SwapHoriz,
            onValueChange = { editingOutbound = outbound.copy(idleSessionCheckInterval = it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_idle_session_timeout),
            value = outbound.idleSessionTimeout ?: "30s",
            icon = Icons.Rounded.SwapHoriz,
            onValueChange = { editingOutbound = outbound.copy(idleSessionTimeout = it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_min_idle_sessions),
            value = outbound.minIdleSession?.toString() ?: "0",
            icon = Icons.Rounded.Numbers,
            onValueChange = { editingOutbound = outbound.copy(minIdleSession = it.toIntOrNull()) }
        )
    }

    // 9. SOCKS
    if (type == "socks") {
        EditableSelectionItem(
            title = stringResource(R.string.node_detail_socks_version),
            value = outbound.version?.asString ?: "5",
            options = listOf("4", "4a", "5"),
            icon = Icons.Rounded.Tag,
            onValueChange = { editingOutbound = outbound.copy(version = JsonPrimitive(it)) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_username_optional),
            value = outbound.username ?: "",
            icon = Icons.Rounded.Person,
            onValueChange = { editingOutbound = outbound.copy(username = if (it.isEmpty()) null else it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_password_optional),
            value = outbound.password ?: "",
            icon = Icons.Rounded.Password,
            onValueChange = { editingOutbound = outbound.copy(password = if (it.isEmpty()) null else it) }
        )
    }

    // 10. HTTP
    if (type == "http") {
        EditableTextItem(
            title = stringResource(R.string.node_detail_username_optional),
            value = outbound.username ?: "",
            icon = Icons.Rounded.Person,
            onValueChange = { editingOutbound = outbound.copy(username = if (it.isEmpty()) null else it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_password_optional),
            value = outbound.password ?: "",
            icon = Icons.Rounded.Password,
            onValueChange = { editingOutbound = outbound.copy(password = if (it.isEmpty()) null else it) }
        )
    }

}
