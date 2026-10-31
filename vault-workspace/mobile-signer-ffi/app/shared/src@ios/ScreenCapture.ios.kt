package com.mebit.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationUserDidTakeScreenshotNotification
import platform.UIKit.UISceneCaptureStateActive
import platform.UIKit.UIScreen
import platform.UIKit.UIScreenCapturedDidChangeNotification

// iOS has no public API that blocks a still screenshot, so the app can only hide the words and warn after one.
@Composable
actual fun rememberCaptureGuard(): CaptureGuard {
    var captured by remember { mutableStateOf(isCaptured()) }
    var screenshotTaken by remember { mutableStateOf(false) }
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val capture = center.addObserverForName(UIScreenCapturedDidChangeNotification, `object` = null, queue = NSOperationQueue.mainQueue) {
            captured = isCaptured()
        }
        val screenshot = center.addObserverForName(UIApplicationUserDidTakeScreenshotNotification, `object` = null, queue = NSOperationQueue.mainQueue) {
            screenshotTaken = true
        }
        onDispose {
            center.removeObserver(capture)
            center.removeObserver(screenshot)
        }
    }
    LaunchedEffect(state) { captured = isCaptured() }
    // The notification alone left the words showing during a recording on iOS 27, and sceneCaptureState has no notification.
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            captured = isCaptured()
        }
    }
    // Compose reports an inactive scene, as in the app switcher, as STARTED and only an active one as RESUMED.
    return CaptureGuard(CaptureClaim.HidesWhileRecording, hidden = captured || state < Lifecycle.State.RESUMED, screenshotTaken = screenshotTaken)
}

private fun isCaptured(): Boolean =
    UIScreen.mainScreen.captured || UIApplication.sharedApplication.keyWindow?.traitCollection?.sceneCaptureState == UISceneCaptureStateActive
