package com.example.dsaiapp.ui.live2d

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.dsaiapp.live2d.Live2DGLView

/**
 * 把 Live2D 的 GLSurfaceView 包成 Compose 组件。
 *
 * <p>生命周期必须手动转给 GLSurfaceView：它自己不知道 Compose 的宿主
 * 是何时 onPause/onResume 的，不转发的话切后台回来会黑屏。
 */
@Composable
fun Live2DStage(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    onReady: (Live2DGLView) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val glView = remember { Live2DGLView(context) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> glView.onResume()
                Lifecycle.Event.ON_PAUSE -> glView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glView.onPause()
        }
    }

    Box(modifier = modifier.background(backgroundColor)) {
        AndroidView(
            factory = {
                glView.also { onReady(it) }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
