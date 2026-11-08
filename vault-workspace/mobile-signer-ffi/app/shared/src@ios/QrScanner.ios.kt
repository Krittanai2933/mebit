package com.mebit.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.hasTorch
import platform.AVFoundation.isTorchModeSupported
import platform.AVFoundation.requestAccessForMediaType
import platform.AVFoundation.torchMode
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIDetector
import platform.CoreImage.CIDetectorAccuracy
import platform.CoreImage.CIDetectorAccuracyHigh
import platform.CoreImage.CIDetectorTypeQRCode
import platform.CoreImage.CIImage
import platform.CoreImage.CIQRCodeFeature
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIView
import platform.UIKit.modalInPresentation
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberCameraAvailable(): Boolean = remember { AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) != null }

@Composable
actual fun rememberTorchAvailable(): Boolean = remember { AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)?.hasTorch == true }

@Composable
actual fun rememberAppSettingsOpener(): () -> Unit = remember {
    val open: () -> Unit = {
        NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let { url ->
            UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
        }
    }
    open
}

@Composable
actual fun QrCameraView(torch: Boolean, onText: (String) -> Unit, onDenied: () -> Unit) {
    var granted by remember { mutableStateOf(false) }
    val currentOnDenied by rememberUpdatedState(onDenied)
    LaunchedEffect(Unit) {
        val allowed = when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> true
            AVAuthorizationStatusNotDetermined -> suspendCancellableCoroutine { answer ->
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { ok -> answer.resume(ok) }
            }
            else -> false
        }
        if (allowed) granted = true else currentOnDenied()
    }
    if (!granted) return
    val currentOnText by rememberUpdatedState(onText)
    val camera = remember { QrCaptureView { currentOnText(it) } }
    UIKitView(factory = { camera }, modifier = Modifier.fillMaxSize())
    LaunchedEffect(camera, torch) { camera.setTorch(torch) }
    DisposableEffect(camera) {
        camera.start()
        onDispose { camera.stop() }
    }
}

/** A view whose layer shows the back camera, reporting every QR the session reads on the main queue. */
@OptIn(ExperimentalForeignApi::class)
private class QrCaptureView(private val onText: (String) -> Unit) :
    UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)), AVCaptureMetadataOutputObjectsDelegateProtocol {
    private val session = AVCaptureSession()
    private val preview = AVCaptureVideoPreviewLayer(session = session).apply { videoGravity = AVLayerVideoGravityResizeAspectFill }
    private val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)

    init {
        layer.addSublayer(preview)
        val input = device?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, error = null) }
        val output = AVCaptureMetadataOutput()
        if (input != null && session.canAddInput(input) && session.canAddOutput(output)) {
            session.addInput(input)
            session.addOutput(output)
            output.setMetadataObjectsDelegate(this, queue = dispatch_get_main_queue())
            // Only after addOutput does the output know which types the session can read.
            output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
        }
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        preview.frame = bounds
    }

    // startRunning blocks for a while, so Apple says to call it off the main queue.
    fun start() = dispatch_async(dispatch_get_global_queue(0L, 0u)) { session.startRunning() }

    fun stop() {
        setTorch(false)
        dispatch_async(dispatch_get_global_queue(0L, 0u)) { session.stopRunning() }
    }

    fun setTorch(on: Boolean) {
        val light = device ?: return
        val mode = if (on) AVCaptureTorchModeOn else AVCaptureTorchModeOff
        if (!light.hasTorch || !light.isTorchModeSupported(mode)) return
        if (light.lockForConfiguration(null)) {
            light.torchMode = mode
            light.unlockForConfiguration()
        }
    }

    override fun captureOutput(output: AVCaptureOutput, didOutputMetadataObjects: List<*>, fromConnection: AVCaptureConnection) {
        val text = didOutputMetadataObjects.filterIsInstance<AVMetadataMachineReadableCodeObject>().firstNotNullOfOrNull { it.stringValue } ?: return
        onText(text)
    }
}

@Composable
actual fun rememberQrPhotoPicker(onResult: (PhotoQr) -> Unit): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    // The picker holds its delegate weakly, so this reference keeps it alive.
    val delegate = remember { PhotoPickerDelegate { currentOnResult(it) } }
    return remember(delegate) {
        val open: () -> Unit = {
            val config = PHPickerConfiguration()
            config.filter = PHPickerFilter.imagesFilter
            config.selectionLimit = 1
            val picker = PHPickerViewController(configuration = config)
            picker.delegate = delegate
            // Only Cancel or a pick can close it, so the delegate always answers and the sheet never waits in Picking.
            picker.modalInPresentation = true
            val top = topViewController()
            if (top == null) currentOnResult(PhotoQr.Cancelled) else top.presentViewController(picker, animated = true, completion = null)
        }
        open
    }
}

private class PhotoPickerDelegate(private val onResult: (PhotoQr) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider
        if (provider == null) {
            onResult(PhotoQr.Cancelled)
            return
        }
        provider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
            val text = data?.let(::qrTextIn)
            dispatch_async(dispatch_get_main_queue()) { onResult(if (text == null) PhotoQr.None else PhotoQr.Text(text)) }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun qrTextIn(data: NSData): String? {
    val image = CIImage.imageWithData(data) ?: return null
    val detector = CIDetector.detectorOfType(CIDetectorTypeQRCode, context = null, options = mapOf(CIDetectorAccuracy to CIDetectorAccuracyHigh))
    return detector?.featuresInImage(image)?.filterIsInstance<CIQRCodeFeature>()?.firstNotNullOfOrNull { it.messageString }
}
