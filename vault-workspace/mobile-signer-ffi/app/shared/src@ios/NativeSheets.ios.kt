package com.mebit.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.mebit.app.ui.components.LocalCovered
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectGetMidX
import platform.CoreGraphics.CGRectGetMidY
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

/**
 * The app's UIKit sheets: the share sheet and the document and photo pickers. UIKit draws a presented controller above the Compose
 * screen, so above both blocks, and [CloseNativeSheetsWhileCovered] dismisses it here. CoverableWindowsRuleTest fails on a
 * presentViewController anywhere else.
 */
internal object NativeSheets {
    private val open = OpenSheet<UIViewController>()

    /**
     * Shows [controller] over the top screen; false when no screen can show it. [onCovered] reports a cancel to whoever waits for the
     * controller's answer, and runs instead of that answer when a block covers the app first. Each of the controller's callbacks
     * starts with [answered].
     */
    @OptIn(ExperimentalForeignApi::class)
    fun present(controller: UIViewController, onCovered: () -> Unit): Boolean {
        val top = topViewController() ?: return false
        // iPad shows a share sheet as a popover, and presenting a popover with no anchor raises an exception.
        controller.popoverPresentationController?.let { popover ->
            val bounds = top.view.bounds()
            popover.sourceView = top.view
            popover.sourceRect = CGRectMake(CGRectGetMidX(bounds), CGRectGetMidY(bounds), 0.0, 0.0)
        }
        open.opened(controller, onCovered)
        top.presentViewController(controller, animated = true, completion = null)
        return true
    }

    /** True when [controller]'s answer is the one its caller hears; false once a block closed it and the caller heard a cancel. */
    fun answered(controller: UIViewController?): Boolean = open.answered(controller)

    // Not animated: the sheet would slide down above the block.
    fun cover() = open.cover { it.presentingViewController?.dismissViewControllerAnimated(false, completion = null) }

    private fun topViewController(): UIViewController? {
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
        return generateSequence(root) { it.presentedViewController }.last()
    }
}

@Composable
actual fun CloseNativeSheetsWhileCovered() {
    val covered = LocalCovered.current
    LaunchedEffect(covered) { if (covered) NativeSheets.cover() }
}
