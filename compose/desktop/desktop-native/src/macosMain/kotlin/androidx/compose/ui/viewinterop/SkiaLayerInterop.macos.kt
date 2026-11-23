@file:OptIn(
    org.jetbrains.skiko.InternalSkikoApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package androidx.compose.ui.viewinterop

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.objcPtr
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLPixelFormatRGBA8Unorm
import platform.Metal.MTLStorageModePrivate
import platform.Metal.MTLTextureDescriptor
import platform.Metal.MTLTextureProtocol
import platform.Metal.MTLTextureUsagePixelFormatView
import platform.Metal.MTLTextureUsageRenderTarget
import platform.Metal.MTLTextureUsageShaderRead
import platform.Metal.MTLTextureUsageShaderWrite

internal actual val SkiaLayer.nativeRendererDescription: String
    get() = rendererDescription

internal actual fun <T> SkiaLayer.withNativeOpenGlContext(block: () -> T): T =
    withOpenGlContext(block)

internal actual fun SkiaLayer.drawNativeOpenGlTexture(
    textureId: Int,
    width: Int,
    height: Int,
    canvas: Canvas,
) {
    drawOpenGlTexture(textureId, width, height, canvas)
}

internal actual fun SkiaLayer.nativeMetalInterop(): NativeMetalInterop? {
    if (renderApi != GraphicsApi.METAL) return null
    val device = metalDevicePtr ?: return null
    val queue = metalCommandQueuePtr ?: return null
    return MacosMetalInterop(this, device, queue)
}

private class MacosMetalInterop(
    private val layer: SkiaLayer,
    devicePointer: NativePointer,
    queuePointer: NativePointer,
) : NativeMetalInterop {
    private val mtlDevice: MTLDeviceProtocol = interpretObjCPointer(devicePointer)

    override val device: COpaquePointer = checkNotNull(interpretCPointer(devicePointer))
    override val commandQueue: COpaquePointer = checkNotNull(interpretCPointer(queuePointer))
    override val rendererDescription: String
        get() = layer.rendererDescription

    override fun createTexture(width: Int, height: Int): NativeMetalTexture? {
        if (width <= 0 || height <= 0) return null
        val descriptor =
            MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(
                MTLPixelFormatRGBA8Unorm,
                width.toULong(),
                height.toULong(),
                false,
            )
        descriptor.usage =
            MTLTextureUsageRenderTarget or
                MTLTextureUsageShaderRead or
                MTLTextureUsageShaderWrite or
                MTLTextureUsagePixelFormatView
        descriptor.storageMode = MTLStorageModePrivate
        val texture = mtlDevice.newTextureWithDescriptor(descriptor) ?: return null
        texture.label = "Compose NativeView Metal interop"
        return MacosMetalTexture(texture, width, height)
    }

    override fun <T> withContext(block: () -> T): T = layer.withMetalContext(block)

    override fun draw(texture: NativeMetalTexture, canvas: Canvas) {
        val metalTexture = texture as MacosMetalTexture
        layer.drawMetalTexture(
            metalTexture.texture.objcPtr(),
            texture.width,
            texture.height,
            MTLPixelFormatRGBA8Unorm,
            canvas,
        )
    }
}

private class MacosMetalTexture(
    texture: MTLTextureProtocol,
    override val width: Int,
    override val height: Int,
) : NativeMetalTexture {
    // Strong reference; Skia retains the texture separately while it samples it.
    private var current: MTLTextureProtocol? = texture

    val texture: MTLTextureProtocol
        get() = checkNotNull(current) { "Metal interop texture is closed" }

    override val handle: COpaquePointer
        get() = checkNotNull(interpretCPointer(texture.objcPtr()))

    override val pixelFormat: Long = MTLPixelFormatRGBA8Unorm.toLong()

    override fun close() {
        current = null
    }
}
