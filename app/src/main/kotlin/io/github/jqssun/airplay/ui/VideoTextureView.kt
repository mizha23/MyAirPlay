package io.github.jqssun.airplay.ui

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Direct URL playback stays in app composition, avoiding vendor video-overlay geometry. */
@Composable
fun VideoTextureView(
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    aspectRatio: Float = 16f / 9f,
    applyAspectRatio: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val available = rememberUpdatedState(onSurfaceAvailable)
    val destroyed = rememberUpdatedState(onSurfaceDestroyed)
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    val xy = IntArray(2)
                    view.getLocationOnScreen(xy)
                    val visible = android.graphics.Rect()
                    view.getLocalVisibleRect(visible)
                    Log.i("VideoTextureView", "Texture bounds=${view.width}x${view.height} screen=${xy[0]},${xy[1]} clip=${view.clipBounds} visible=$visible")
                }
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        surface = Surface(texture).also { available.value(it) }
                        Log.i("VideoTextureView", "Direct video texture available ${width}x$height")
                    }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        Log.i("VideoTextureView", "Direct video texture resized ${width}x$height")
                    }
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        surface?.let { destroyed.value(it); it.release() }
                        surface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        modifier = if (applyAspectRatio) modifier
            .aspectRatio(aspectRatio, matchHeightConstraintsFirst = aspectRatio < 1f)
            .fillMaxSize() else modifier,
    )
}
