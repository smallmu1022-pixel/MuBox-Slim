package com.mubox.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mubox.app.R
import com.mubox.app.model.FilterMode
import com.mubox.app.model.NodeFilter
import com.mubox.app.ui.theme.Destructive
import com.mubox.app.ui.theme.LiquidGlassDialogEffect
import com.mubox.app.ui.theme.isLiquidGlassTheme
import com.mubox.app.ui.theme.liquidGlassButtonColors
import com.mubox.app.ui.theme.liquidGlassButtonContentColor
import com.mubox.app.ui.theme.liquidGlassButtonPanel
import com.mubox.app.ui.theme.liquidGlassDialogPanel
import com.mubox.app.ui.theme.liquidGlassOutlinedTextFieldColors
import com.mubox.app.ui.theme.liquidGlassPanel
import com.mubox.app.ui.theme.liquidGlassPressFeedback
import com.mubox.app.ui.theme.liquidGlassTextButtonColors
import com.mubox.app.ui.theme.liquidGlassTextButtonContentColor
import com.mubox.app.ui.theme.liquidGlassTextButtonPanel
import com.mubox.app.ui.theme.liquidGlassTextFieldBorderColor
import com.mubox.app.ui.theme.liquidGlassTextFieldContainerColor
import com.mubox.app.ui.theme.liquidGlassTextFieldPanel

@Composable
private fun Modifier.nodeSelectionDialogPanel(shape: RoundedCornerShape = RoundedCornerShape(28.dp)): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassDialogPanel(shape = shape, shadowElevation = 24.dp)
    } else {
        background(MaterialTheme.colorScheme.surface, shape)
    }
}

@Composable
private fun Modifier.nodeFilterModePanel(isSelected: Boolean): Modifier {
    val shape = RoundedCornerShape(12.dp)
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, selected = isSelected, shadowElevation = 5.dp)
    } else {
        background(
            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent,
            shape
        )
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val githubUrl = "https://github.com/smallmu1022-pixel/MuBox-Slim"
    val linkColor = MaterialTheme.colorScheme.primary

    val appVersion = remember { com.mubox.app.utils.VersionInfo.getAppVersionName(context) }
    val appVersionCode = remember { com.mubox.app.utils.VersionInfo.getAppVersionCode(context) }

    val kernelLoadingMsg = stringResource(R.string.about_kernel_loading)
    val kernelBuiltinMsg = stringResource(R.string.about_kernel_builtin)
    var singBoxVersion by remember { mutableStateOf(kernelLoadingMsg) }
    LaunchedEffect(Unit) {
        singBoxVersion = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                com.mubox.app.core.SingBoxCore.ensureLibboxSetup(context)
                val version = io.nekohasekai.libbox.Libbox.version()

                when {
                    version.isNullOrBlank() -> kernelBuiltinMsg
                    version.equals("unknown", ignoreCase = true) -> kernelBuiltinMsg
                    else -> version
                }
            } catch (t: Throwable) {
                "sing-box (·告劕鎳愰悿?"
            }
        }
    }

    val aboutAddressMsg = stringResource(R.string.about_address)
    val aboutBasedOnMsg = stringResource(R.string.about_based_on)

    val annotatedString = buildAnnotatedString {
        append("MuBox for Android\n\n")
        append(stringResource(R.string.about_version, appVersion, appVersionCode))
        append("\n")
        append(stringResource(R.string.about_kernel_version, singBoxVersion))
        append("\n\n")
        append("$aboutAddressMsg ")
        pushStringAnnotation(tag = "URL", annotation = githubUrl)
        withStyle(
            style = SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline
            )
        ) {
            append("MuBoxForAndroid")
        }
        pop()
        append("\n\n$aboutBasedOnMsg")
    }

    Dialog(onDismissRequest = onDismiss) {
        LiquidGlassDialogEffect()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .nodeSelectionDialogPanel()
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_about_mubox),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))

            ClickableText(
                text = annotatedString,
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                onClick = { offset ->
                    annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))
                            context.startActivity(intent)
                        }
                }
            )
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .liquidGlassButtonPanel(shape = RoundedCornerShape(25.dp)),
                colors = liquidGlassButtonColors(
                    defaultContainerColor = MaterialTheme.colorScheme.primary,
                    defaultContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(25.dp)
            ) {
                Text(
                    text = stringResource(R.string.common_ok),
                    fontWeight = FontWeight.Bold,
                    color = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                )
            }
        }
    }
}

@Composable
fun NodeFilterDialog(
    currentFilter: NodeFilter,
    onConfirm: (NodeFilter) -> Unit,
    onDismiss: () -> Unit
) {
    var filterMode by remember { mutableStateOf(currentFilter.filterMode) }

    var includeKeywordsText by remember {
        mutableStateOf(currentFilter.effectiveIncludeKeywords.joinToString(", "))
    }
    var excludeKeywordsText by remember {
        mutableStateOf(currentFilter.effectiveExcludeKeywords.joinToString(", "))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        LiquidGlassDialogEffect()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .nodeSelectionDialogPanel()
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.node_filter_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.node_filter_mode),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .nodeFilterModePanel(filterMode == FilterMode.NONE)
                    .liquidGlassPressFeedback(
                        label = "liquid_glass_node_filter_none_scale"
                    ) {
                        filterMode = FilterMode.NONE
                    }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (filterMode == FilterMode.NONE) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (filterMode == FilterMode.NONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.node_filter_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (filterMode == FilterMode.NONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .nodeFilterModePanel(filterMode == FilterMode.INCLUDE)
                    .liquidGlassPressFeedback(
                        label = "liquid_glass_node_filter_include_scale"
                    ) {
                        filterMode = FilterMode.INCLUDE
                    }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (filterMode == FilterMode.INCLUDE) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (filterMode == FilterMode.INCLUDE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.node_filter_include),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (filterMode == FilterMode.INCLUDE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .nodeFilterModePanel(filterMode == FilterMode.EXCLUDE)
                    .liquidGlassPressFeedback(
                        label = "liquid_glass_node_filter_exclude_scale"
                    ) {
                        filterMode = FilterMode.EXCLUDE
                    }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (filterMode == FilterMode.EXCLUDE) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (filterMode == FilterMode.EXCLUDE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.node_filter_exclude),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (filterMode == FilterMode.EXCLUDE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }

            if (filterMode != FilterMode.NONE) {
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = if (filterMode == FilterMode.INCLUDE) {
                        stringResource(R.string.node_filter_include)
                    } else {
                        stringResource(R.string.node_filter_exclude)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                val activeKeywords = if (filterMode == FilterMode.INCLUDE) {
                    includeKeywordsText
                } else {
                    excludeKeywordsText
                }

                val keywordFieldShape = RoundedCornerShape(16.dp)
                OutlinedTextField(
                    value = activeKeywords,
                    onValueChange = { newValue ->
                        if (filterMode == FilterMode.INCLUDE) {
                            includeKeywordsText = newValue
                        } else {
                            excludeKeywordsText = newValue
                        }
                    },
                    placeholder = { Text(stringResource(R.string.node_filter_keywords_hint), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlassTextFieldPanel(shape = keywordFieldShape),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 3,
                    colors = liquidGlassOutlinedTextFieldColors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.primary),
                        unfocusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.outline),
                        focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                        unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = keywordFieldShape
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.node_filter_keywords_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                TextButton(
                    onClick = {
                        filterMode = FilterMode.NONE
                        includeKeywordsText = ""
                        excludeKeywordsText = ""
                    },

                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .liquidGlassTextButtonPanel(shape = RoundedCornerShape(25.dp)),
                    colors = liquidGlassTextButtonColors(
                        contentColor = liquidGlassTextButtonContentColor(
                            defaultColor = Destructive,
                            liquidColor = Destructive
                        )
                    )
                ) {
                    Text(stringResource(R.string.common_clear))
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .liquidGlassTextButtonPanel(shape = RoundedCornerShape(25.dp)),
                    colors = liquidGlassTextButtonColors(
                        contentColor = liquidGlassTextButtonContentColor(
                            defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                ) {
                    Text(stringResource(R.string.common_cancel))
                }

                Button(
                    onClick = {

                        val includeKeywords = includeKeywordsText
                            .split(",", "，")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        val excludeKeywords = excludeKeywordsText
                            .split(",", "，")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }

                        onConfirm(NodeFilter(
                            filterMode = filterMode,
                            includeKeywords = includeKeywords,
                            excludeKeywords = excludeKeywords
                        ))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .liquidGlassButtonPanel(shape = RoundedCornerShape(25.dp)),
                    colors = liquidGlassButtonColors(
                        defaultContainerColor = MaterialTheme.colorScheme.primary,
                        defaultContentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Text(
                        text = stringResource(R.string.common_ok),
                        fontWeight = FontWeight.Bold,
                        color = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                    )
                }
            }
        }
    }
}
