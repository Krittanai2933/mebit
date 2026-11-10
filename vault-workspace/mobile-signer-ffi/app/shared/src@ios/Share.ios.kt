package com.mebit.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.URLByAppendingPathComponent
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImage
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
private object IosSharer : Sharer {
    override fun text(text: String) {
        present(text)
    }

    override fun image(png: ByteArray, name: String) {
        val image = UIImage.imageWithData(png.toNSData()) ?: return
        present(image)
    }

    /** False when no screen can show the sheet. [completion] runs when the sheet closes, with whether the user chose a target and the error text, if any. */
    fun present(item: Any, completion: ((Boolean, String?) -> Unit)? = null): Boolean {
        val top = topViewController() ?: return false
        val sheet = UIActivityViewController(activityItems = listOf(item), applicationActivities = null)
        if (completion != null) sheet.completionWithItemsHandler = { _, completed, _, error -> completion(completed, error?.localizedDescription) }
        // iPad shows the sheet as a popover, and presenting a popover with no anchor raises an exception.
        sheet.popoverPresentationController?.let { popover ->
            val bounds = top.view.bounds()
            popover.sourceView = top.view
            popover.sourceRect = CGRectMake(CGRectGetMidX(bounds), CGRectGetMidY(bounds), 0.0, 0.0)
        }
        top.presentViewController(sheet, animated = true, completion = null)
        return true
    }
}

internal fun topViewController(): UIViewController? {
    val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
    return generateSequence(root) { it.presentedViewController }.last()
}

@Composable
actual fun rememberSharer(): Sharer = IosSharer

@Composable
actual fun rememberTextFileExport(onResult: (ExportOutcome) -> Unit): TextFileExport {
    val current by rememberUpdatedState(onResult)
    // The picker holds its delegate weakly, so this reference keeps it alive.
    val delegate = remember { ExportPickerDelegate { current(it) } }
    return remember(delegate) { IosTextFileExport(delegate) { current(it) } }
}

private class IosTextFileExport(private val delegate: ExportPickerDelegate, private val onResult: (ExportOutcome) -> Unit) : TextFileExport {
    override fun save(name: String, text: String) {
        val url = writeExportFile(name, text) ?: return onResult(ExportOutcome.Failed("write"))
        val top = topViewController() ?: return onResult(ExportOutcome.Failed("no screen"))
        // asCopy keeps the temp file in place, so the same file can be saved or shared again.
        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
        picker.delegate = delegate
        top.presentViewController(picker, animated = true, completion = null)
    }

    override fun share(name: String, text: String) {
        val url = writeExportFile(name, text) ?: return onResult(ExportOutcome.Failed("write"))
        if (!IosSharer.present(url) { completed, error -> onResult(exportOutcome(completed, error)) }) onResult(ExportOutcome.Failed("no screen"))
    }
}

/** A pick is Done and Cancel is Cancelled. The picker closes itself either way, so nothing waits on this. */
private class ExportPickerDelegate(private val onResult: (ExportOutcome) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) = onResult(ExportOutcome.Done)

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onResult(ExportOutcome.Cancelled)
}

/** Writes [text] to `tmp/export/<name>`, replacing an older copy, and returns its URL, or null when it cannot. The URL's last component is the file name the user sees. */
@OptIn(ExperimentalForeignApi::class)
internal fun writeExportFile(name: String, text: String): NSURL? {
    val files = NSFileManager.defaultManager
    val folder = NSURL.fileURLWithPath(NSTemporaryDirectory(), isDirectory = true).URLByAppendingPathComponent("export", isDirectory = true) ?: return null
    if (!files.createDirectoryAtURL(folder, withIntermediateDirectories = true, attributes = null, error = null)) return null
    val file = folder.URLByAppendingPathComponent(name) ?: return null
    // An absent older copy is the usual case, so the answer is not read.
    files.removeItemAtURL(file, error = null)
    val bytes = text.encodeToByteArray()
    val data = if (bytes.isEmpty()) NSData() else bytes.toNSData()
    return if (data.writeToURL(file, atomically = true)) file else null
}

actual fun ImageBitmap.encodePng(): ByteArray {
    val data = Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
    return checkNotNull(data) { "Skia could not encode the bitmap as PNG" }.bytes
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData = usePinned { NSData.dataWithBytes(it.addressOf(0), size.convert()) }
