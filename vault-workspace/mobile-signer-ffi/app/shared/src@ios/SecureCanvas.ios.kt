package com.mebit.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGRectEqualToRect
import platform.Foundation.NSStringFromClass
import platform.UIKit.UIApplication
import platform.UIKit.UITextField
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth

// Apple calls the secure-text-field trick unsupported, so retest it on each iOS major release. Compose sheets and dialogs and the system keyboard sit outside it, see docs/04-open-items.md.
private const val SECURE_LAYER = true

// Moves the app into the private canvas of a secure text field, which captures leave out.
internal object SecureLayer {
    private val scope = MainScope()
    private var canvas: SecureCanvas? = null
    private var uninstalling: Job? = null

    var installed by mutableStateOf(false)
        private set

    fun install() {
        uninstalling?.cancel()
        refresh()
    }

    // After a pause, so Backup Words handing over to Verify Words keeps the layer instead of rebuilding it.
    fun uninstall() {
        uninstalling = scope.launch {
            delay(500)
            canvas?.restore()
            canvas = null
            installed = false
        }
    }

    // A broken canvas is restored and installed again on the next call.
    fun refresh() {
        val current = canvas
        when {
            current == null -> if (SECURE_LAYER) canvas = SecureCanvas.create()
            current.intact -> current.fit()
            else -> {
                println("SECURECANVAS structure broken, restoring")
                current.restore()
                canvas = null
            }
        }
        installed = canvas?.intact == true
    }
}

@OptIn(ExperimentalForeignApi::class)
private class SecureCanvas(
    // Never read, but the field owns the layer mask that keeps its canvas out of captures, so it must outlive the canvas.
    private val textField: UITextField,
    private val canvas: UIView,
    private val root: UIView,
    private val slot: UIView,
) {
    val intact: Boolean get() = canvas.window != null && root.superview == canvas

    fun fit() {
        val bounds = slot.bounds()
        if (!CGRectEqualToRect(canvas.frame(), bounds)) {
            canvas.setFrame(bounds)
            root.setFrame(canvas.bounds())
        }
    }

    fun restore() {
        // Only while the root view is still in the canvas, or this would pull back a view UIKit has since placed elsewhere.
        if (root.superview == canvas) {
            slot.insertSubview(root, atIndex = 0)
            root.setFrame(slot.bounds())
        }
        canvas.removeFromSuperview()
    }

    companion object {
        fun create(): SecureCanvas? {
            val root = UIApplication.sharedApplication.keyWindow?.rootViewController?.view
            val slot = root?.superview ?: return failed("no key window with a root view in it")
            val textField = UITextField()
            textField.setSecureTextEntry(true)
            textField.layoutIfNeeded()
            val subviews = textField.subviews.filterIsInstance<UIView>()
            // By name, because iOS 17 reordered the field's subviews.
            val canvas = subviews.firstOrNull { "CanvasView" in it.className() } ?: return failed("no CanvasView subview among ${subviews.joinToString { it.className() }}")
            canvas.userInteractionEnabled = true
            canvas.setFrame(root.frame())
            canvas.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
            // Into the window first, so the root view changes superview without ever leaving the window.
            slot.insertSubview(canvas, aboveSubview = root)
            canvas.addSubview(root)
            root.setFrame(canvas.bounds())
            return SecureCanvas(textField, canvas, root, slot)
        }

        private fun failed(reason: String): SecureCanvas? {
            println("SECURECANVAS install failed, $reason")
            return null
        }
    }
}

@OptIn(BetaInteropApi::class)
private fun UIView.className(): String = `class`()?.let { NSStringFromClass(it) }.orEmpty()
