package com.mubox.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.mubox.app.R
import kotlinx.coroutines.launch
import kotlin.random.Random

@Composable
fun BigToggle(
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Scale animation on press
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 150, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "ScaleAnimation"
    )

    // Use updateTransition for coordinated animations
    val transition = updateTransition(targetState = isRunning, label = "BigToggleTransition")

    val verticalOffset by transition.animateDp(
        transitionSpec = {
            tween(
                durationMillis = 600,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        },
        label = "VerticalOffset"
    ) { running ->
        if (running) 0.dp else 20.dp
    }

    var shakeKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(isRunning) {
        if (isRunning) {
            shakeKey = shakeKey + 1
        }
    }

    val rotation = remember { Animatable(0f) }

    val bounceOffset = remember { Animatable(0f) }

    val floatOffset = remember { Animatable(0f) }

    LaunchedEffect(isRunning) {
        if (!isRunning) {
            while (true) {
                val targetOffset = Random.nextFloat() * 12f - 6f
                val duration = Random.nextInt(1500, 2500)
                floatOffset.animateTo(
                    targetValue = targetOffset,
                    animationSpec = tween(duration, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                )
            }
        } else {
            floatOffset.animateTo(0f, animationSpec = tween(300))
        }
    }

    LaunchedEffect(shakeKey) {
        if (isRunning) {
            bounceOffset.snapTo(0f)
            rotation.snapTo(0f)

            val bounceJob = launch {

                bounceOffset.animateTo(
                    targetValue = -40f,
                    animationSpec = tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                )

                bounceOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }

            val shakeJob = launch {

                if (isRunning) {
                    rotation.animateTo(
                        targetValue = 3f,
                        animationSpec = tween(120, easing = LinearEasing)
                    )
                    rotation.animateTo(
                        targetValue = -3f,
                        animationSpec = tween(240, easing = LinearEasing)
                    )
                    rotation.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(120, easing = LinearEasing)
                    )
                }

                rotation.snapTo(0f)
            }

            bounceJob.join()
            shakeJob.join()
        } else {
            rotation.snapTo(0f)
            bounceOffset.snapTo(0f)
        }
    }

    // 两态区分（2026-11-22 改版，用户定版对比图）：仍共用同一张光晕睡猫原图，
    // 纯代码效果拉开差距——未连接压暗去饱和，已连接高饱和提亮（蓝通道最高）
    // 并叠外发光与绿点。矩阵为「饱和度 × 通道缩放」的合成结果（手算展开）。
    val connectedLift = remember {
        ColorMatrix(
            floatArrayOf(
                1.185f, -0.113f, -0.012f, 0f, 0f,
                -0.035f, 1.126f, -0.012f, 0f, 0f,
                -0.037f, -0.124f, 1.321f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }
    val disconnectedDim = remember {
        ColorMatrix(
            floatArrayOf(
                0.420f, 0.291f, 0.029f, 0f, 0f,
                0.087f, 0.624f, 0.029f, 0f, 0f,
                0.091f, 0.307f, 0.382f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    val touchSurfaceModifier = Modifier
        .size(200.dp)
        .clip(CircleShape)
        .background(Color.Transparent)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.offset { IntOffset(x = 0, y = verticalOffset.roundToPx()) }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .scale(scale)
                    .offset {
                        IntOffset(
                            x = 0,
                            y = (bounceOffset.value + floatOffset.value).dp.roundToPx()
                        )
                    }
            ) {
                AnimatedContent(
                    targetState = isRunning,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(400)) togetherWith
                            fadeOut(animationSpec = tween(400)) using
                            SizeTransform(clip = false)
                    },
                    label = "IconCrossfade"
                ) { running ->
                    // 两态共用同一张定版光晕睡猫图（R.drawable.halo_cat）；
                    // 已连接另叠外发光与右下绿点，未连接压暗去饱和，不新增持续动画
                    Box(
                        modifier = Modifier.size(270.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (running) {
                            Box(
                                modifier = Modifier
                                    .size(296.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(
                                                Color(0x4D7FB2F0),
                                                Color(0x007FB2F0)
                                            )
                                        )
                                    )
                            )
                        }
                        Image(
                            painter = painterResource(id = R.drawable.halo_cat),
                            contentDescription = if (running) "Running" else "Idle",
                            colorFilter = ColorFilter.colorMatrix(
                                if (running) connectedLift else disconnectedDim
                            ),
                            modifier = Modifier
                                .size(270.dp)
                                .graphicsLayer {
                                    rotationZ = rotation.value
                                }
                        )
                        if (running) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 40.dp, bottom = 34.dp)
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2FBF71))
                                    .border(3.dp, Color.White, CircleShape)
                            )
                        }
                    }
                }

                Box(
                    modifier = touchSurfaceModifier
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick
                        )
                )
            }
        }
    }
}
