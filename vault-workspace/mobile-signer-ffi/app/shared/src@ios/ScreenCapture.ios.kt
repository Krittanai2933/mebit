package com.mebit.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationUserDidTakeScreenshotNotification
import platform.UIKit.UIScreen
import platform.UIKit.UIScreenCapturedDidChangeNotification

// iOS has no public API that blocks a still screenshot, so the app can only hide the words and warn after one.
@Composable
actual fun rememberCaptureGuard(): CaptureGuard {
    var captured by remember { mutableStateOf(UIScreen.mainScreen.captured) }
    var screenshotTaken by remember { mutableStateOf(false) }
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val capture = center.addObserverForName(UIScreenCapturedDidChangeNotification, `object` = null, queue = NSOperationQueue.mainQueue) {
            captured = UIScreen.mainScreen.captured
        }
        val screenshot = center.addObserverForName(UIApplicationUserDidTakeScreenshotNotification, `object` = null, queue = NSOperationQueue.mainQueue) {
            screenshotTaken = true
        }
        onDispose {
            center.removeObserver(capture)
            center.removeObserver(screenshot)
        }
    }
    // Compose reports an inactive scene, as in the app switcher, as STARTED and only an active one as RESUMED.
    return CaptureGuard(CaptureClaim.HidesWhileRecording, hidden = captured || state < Lifecycle.State.RESUMED, screenshotTaken = screenshotTaken)
}
