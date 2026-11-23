package androidx.compose.ui.viewinterop

import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer

internal expect val SkiaLayer.nativeRendererDescription: String

internal expect fun <T> SkiaLayer.withNativeOpenGlContext(block: () -> T): T

internal expect fun SkiaLayer.drawNativeOpenGlTexture(
    textureId: Int,
    width: Int,
    height: Int,
    canvas: Canvas,
)

/**
 * Metal interop of a window whose Skia layer renders with Metal. Only macOS provides one; every
 * method runs on the UI thread.
 */
internal interface NativeMetalInterop {
    /** `id<MTLDevice>` that Skia renders with. */
    val device: kotlinx.cinterop.COpaquePointer

    /** `id<MTLCommandQueue>` on which Skia submits. */
    val commandQueue: kotlinx.cinterop.COpaquePointer

    val rendererDescription: String

    /** Allocates a private RGBA8 texture usable as render target and for sampling. */
    fun createTexture(width: Int, height: Int): NativeMetalTexture?

    /** Commits pending Skia work to [commandQueue] and runs [block]. */
    fun <T> withContext(block: () -> T): T

    /** Draws [texture] at the origin of [canvas] without readback. */
    fun draw(texture: NativeMetalTexture, canvas: Canvas)
}

internal interface NativeMetalTexture : AutoCloseable {
    /** Borrowed `id<MTLTexture>`, valid until [close]. */
    val handle: kotlinx.cinterop.COpaquePointer
    val width: Int
    val height: Int

    /** `MTLPixelFormat` raw value. */
    val pixelFormat: Long
}

/** Returns the Metal interop of this layer, or null when it does not render with Metal. */
internal expect fun SkiaLayer.nativeMetalInterop(): NativeMetalInterop?
