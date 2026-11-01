package com.mebit.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGRectGetMidX
import platform.CoreGraphics.CGRectGetMidY
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.popoverPresentationController

@OptIn(ExperimentalForeignApi::class)
private object IosSharer : Sharer {
    override fun text(text: String) = present(text)

    override fun image(png: ByteArray, name: String) {
        val image = UIImage.imageWithData(png.toNSData()) ?: return
        present(image)
    }

    private fun present(item: Any) {
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return
        val top = generateSequence(root) { it.presentedViewController }.last()
        val sheet = UIActivityViewController(activityItems = listOf(item), applicationActivities = null)
        // iPad shows the sheet as a popover, and presenting a popover with no anchor raises an exception.
        sheet.popoverPresentationController?.let { popover ->
            val bounds = top.view.bounds()
            popover.sourceView = top.view
            popover.sourceRect = CGRectMake(CGRectGetMidX(bounds), CGRectGetMidY(bounds), 0.0, 0.0)
        }
        top.presentViewController(sheet, animated = true, completion = null)
    }
}

@Composable
actual fun rememberSharer(): Sharer = IosSharer

actual fun ImageBitmap.encodePng(): ByteArray {
    val data = Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
    return checkNotNull(data) { "Skia could not encode the bitmap as PNG" }.bytes
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData = usePinned { NSData.dataWithBytes(it.addressOf(0), size.convert()) }
