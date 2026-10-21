package com.chandan.assistant.core

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import com.chandan.assistant.a11y.AssistantAccessibilityService
import com.chandan.assistant.core.ai.AiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** Last resort: screenshot -> AI -> normalized box (0-1000, [ymin,xmin,ymax,xmax]) -> pixels -> tap. Android 11+ only. */
object Vision {
    private suspend fun shot(svc: AssistantAccessibilityService): Bitmap? = suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT < 30) {
                if (cont.isActive) cont.resume(null)
            } else {
                try {
                    svc.takeScreenshot(
                        Display.DEFAULT_DISPLAY, svc.mainExecutor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                                val bmp = try {
                                    val hb = result.hardwareBuffer
                                    val b = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                                    hb.close()
                                    b
                                } catch (e: Exception) {
                                    null
                                }
                                if (cont.isActive) cont.resume(bmp)
                            }

                            override fun onFailure(errorCode: Int) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

        private suspend fun locateCoords(svc: AssistantAccessibilityService, ai: AiClient, description: String): Pair<Float, Float>? {
            val bmp = shot(svc) ?: return null
            val w = bmp.width
            val h = bmp.height
            val scale = minOf(1f, 720f / w)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true) else bmp
            val bos = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 70, bos)
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            val r = try {
                ai.locateOnScreen(description, b64)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            }
            if (!r.found || r.box.size != 4) return null
            val (ymin, xmin, ymax, xmax) = r.box
            return ((xmin + xmax) / 2f / 1000f * w) to ((ymin + ymax) / 2f / 1000f * h)
        }

        suspend fun tap(svc: AssistantAccessibilityService, ai: AiClient, description: String): Boolean {
            val (x, y) = locateCoords(svc, ai, description) ?: return false
            return svc.tap(x, y)
        }

        suspend fun longTap(svc: AssistantAccessibilityService, ai: AiClient, description: String): Boolean {
            val (x, y) = locateCoords(svc, ai, description) ?: return false
            return svc.longTap(x, y)
        }
}
