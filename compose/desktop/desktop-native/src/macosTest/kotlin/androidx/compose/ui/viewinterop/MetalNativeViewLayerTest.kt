/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package androidx.compose.ui.viewinterop

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.unit.IntSize
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.get
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.cValue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import platform.Metal.MTLBufferProtocol
import platform.Metal.MTLClearColor
import platform.Metal.MTLCommandQueueProtocol
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLLoadActionClear
import platform.Metal.MTLOrigin
import platform.Metal.MTLPixelFormatRGBA8Unorm
import platform.Metal.MTLRenderPassDescriptor
import platform.Metal.MTLResourceStorageModeShared
import platform.Metal.MTLSize
import platform.Metal.MTLStorageModePrivate
import platform.Metal.MTLStoreActionStore
import platform.Metal.MTLTextureDescriptor
import platform.Metal.MTLTextureProtocol
import platform.Metal.MTLTextureUsageRenderTarget
import platform.Metal.MTLTextureUsageShaderRead
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Exercises [MetalNativeViewLayer] against a real Metal device. A fake [NativeMetalInterop]
 * replaces the Skia window renderer and copies the texture into a shared buffer instead of drawing.
 */
class MetalNativeViewLayerTest {
    private class FakeTexture(val texture: MTLTextureProtocol, override val width: Int, override val height: Int) :
        NativeMetalTexture {
        var closed = false
        override val handle: COpaquePointer = checkNotNull(interpretCPointer(texture.objcPtr()))
        override val pixelFormat: Long = MTLPixelFormatRGBA8Unorm.toLong()

        override fun close() {
            closed = true
        }
    }

    private class FakeInterop(val mtlDevice: MTLDeviceProtocol, val queue: MTLCommandQueueProtocol) :
        NativeMetalInterop {
        val created = mutableListOf<FakeTexture>()
        var contextCalls = 0
        var drawn: FakeTexture? = null

        override val device: COpaquePointer = checkNotNull(interpretCPointer(mtlDevice.objcPtr()))
        override val commandQueue: COpaquePointer = checkNotNull(interpretCPointer(queue.objcPtr()))
        override val rendererDescription = "fake Metal"

        override fun createTexture(width: Int, height: Int): NativeMetalTexture? {
            val descriptor =
                MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(
                    MTLPixelFormatRGBA8Unorm, width.toULong(), height.toULong(), false
                )
            descriptor.usage = MTLTextureUsageRenderTarget or MTLTextureUsageShaderRead
            descriptor.storageMode = MTLStorageModePrivate
            val texture = checkNotNull(mtlDevice.newTextureWithDescriptor(descriptor))
            return FakeTexture(texture, width, height).also(created::add)
        }

        override fun <T> withContext(block: () -> T): T {
            contextCalls++
            return block()
        }

        override fun draw(texture: NativeMetalTexture, canvas: Canvas) {
            drawn = texture as FakeTexture
        }

        /** Copies the top-left texel of [texture] through [queue], after all earlier work. */
        fun readTopLeft(texture: FakeTexture): List<Int> {
            val buffer: MTLBufferProtocol =
                checkNotNull(mtlDevice.newBufferWithLength(256u, MTLResourceStorageModeShared))
            val commands = checkNotNull(queue.commandBuffer())
            val blit = checkNotNull(commands.blitCommandEncoder())
            blit.copyFromTexture(
                texture.texture,
                0u,
                0u,
                cValue<MTLOrigin> {
                    x = 0u
                    y = 0u
                    z = 0u
                },
                cValue<MTLSize> {
                    width = 1u
                    height = 1u
                    depth = 1u
                },
                buffer, 0u, 256u, 256u,
            )
            blit.endEncoding()
            commands.commit()
            commands.waitUntilCompleted()
            val bytes = checkNotNull(buffer.contents()).reinterpret<UByteVar>()
            return (0 until 4).map { bytes[it].toInt() }
        }
    }

    /** Clears the lent texture to red on the lent queue, as a wgpu renderer would. */
    private fun clearRed(target: MetalInteropRenderTarget): Boolean {
        val queue: MTLCommandQueueProtocol = interpretObjCPointer(target.commandQueue.rawValue)
        val lent: MTLTextureProtocol = interpretObjCPointer(target.texture.rawValue)
        val pass = MTLRenderPassDescriptor.renderPassDescriptor()
        checkNotNull(pass.colorAttachments.objectAtIndexedSubscript(0u)).apply {
            texture = lent
            loadAction = MTLLoadActionClear
            storeAction = MTLStoreActionStore
            clearColor =
                cValue<MTLClearColor> {
                    red = 1.0
                    green = 0.0
                    blue = 0.0
                    alpha = 1.0
                }
        }
        val commands = checkNotNull(queue.commandBuffer())
        checkNotNull(commands.renderCommandEncoderWithDescriptor(pass)).endEncoding()
        commands.commit()
        return true
    }

    private fun withLayer(
        renderer: (MetalInteropRenderTarget) -> Boolean,
        block: (MetalNativeViewLayer, FakeInterop) -> Unit,
    ) {
        val device = MTLCreateSystemDefaultDevice() ?: return // No Metal on this host.
        val interop = FakeInterop(device, checkNotNull(device.newCommandQueue()))
        val registry = MetalInteropRegistry(SkiaLayer(), interop)
        val view = NativeInteropView.metal(renderer = renderer)
        val layer = registry.create(view) as MetalNativeViewLayer
        try {
            block(layer, interop)
        } finally {
            layer.close()
        }
    }

    private fun canvas() = Canvas(Bitmap().apply { allocN32Pixels(1, 1) }).asComposeCanvas()

    @Test
    fun rendererWritesTheBorrowedTextureOnTheSharedQueue() {
        val targets = mutableListOf<MetalInteropRenderTarget>()
        withLayer({ target -> targets += target; clearRed(target) }) { layer, interop ->
            layer.resize(IntSize(16, 8), 2f)
            assertTrue(layer.draw(canvas()))

            val target = targets.single()
            assertEquals(16, target.width)
            assertEquals(8, target.height)
            assertEquals(2f, target.density)
            assertEquals(MTLPixelFormatRGBA8Unorm.toLong(), target.pixelFormat)
            assertEquals(interop.device, target.device)
            assertEquals(interop.commandQueue, target.commandQueue)
            assertEquals(1, interop.contextCalls)

            val texture = interop.created.single()
            assertEquals(texture.handle, target.texture)
            assertEquals(texture, interop.drawn)
            assertEquals(listOf(255, 0, 0, 255), interop.readTopLeft(texture))
        }
    }

    @Test
    fun textureIsReusedUntilTheSizeChanges() {
        withLayer(::clearRed) { layer, interop ->
            layer.resize(IntSize(4, 4), 1f)
            assertTrue(layer.draw(canvas()))
            layer.requestRender()
            assertTrue(layer.draw(canvas()))
            assertEquals(1, interop.created.size)
            assertEquals(2, interop.contextCalls)

            // Drawing without a new request does not render again.
            assertTrue(layer.draw(canvas()))
            assertEquals(2, interop.contextCalls)

            layer.resize(IntSize(8, 4), 1f)
            assertTrue(layer.draw(canvas()))
            assertEquals(2, interop.created.size)
            assertTrue(interop.created[0].closed)
            assertFalse(interop.created[1].closed)
            assertNotEquals(interop.created[0].handle, interop.created[1].handle)
        }
    }

    @Test
    fun nothingIsDrawnBeforeTheFirstSuccessfulRender() {
        var succeed = false
        withLayer({ target -> succeed && clearRed(target) }) { layer, interop ->
            layer.resize(IntSize(4, 4), 1f)
            assertFalse(layer.draw(canvas()))
            assertEquals(null, interop.drawn)

            succeed = true
            assertTrue(layer.draw(canvas()))
            assertEquals(interop.created.single(), interop.drawn)
        }
    }

    @Test
    fun closeReleasesTheTexture() {
        withLayer(::clearRed) { layer, interop ->
            layer.resize(IntSize(4, 4), 1f)
            assertTrue(layer.draw(canvas()))
            layer.close()
            assertTrue(interop.created.single().closed)
            assertFalse(layer.draw(canvas()))
        }
    }
}
