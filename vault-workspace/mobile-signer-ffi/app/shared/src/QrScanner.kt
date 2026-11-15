package com.mebit.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_flashlight
import com.mebit.app.resources.ic_photos
import com.mebit.app.resources.ic_scan_close
import com.mebit.app.resources.ic_scan_frame
import com.mebit.app.resources.ic_warning_round
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbWhite
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource

/** True when this device has a camera the scan sheet can use. False on the iOS simulator and a device without one, which hides the scan chip. */
@Composable
expect fun rememberCameraAvailable(): Boolean

/** True when the back camera has a light the scan sheet can switch on. */
@Composable
expect fun rememberTorchAvailable(): Boolean

/**
 * The back camera filling its parent. It asks for the camera when it enters composition, calls [onDenied] when the user
 * refused or the system does not allow it, and calls [onText] on the main thread with every QR it reads, again and
 * again while one stays in view. The caller decides which reads to act on. [torch] turns the camera's light on or off
 * while the camera runs, and its current value applies when the camera starts. The camera stops when it leaves composition.
 */
@Composable
expect fun QrCameraView(torch: Boolean, onText: (String) -> Unit, onDenied: () -> Unit)

/** What the system photo picker gave back. */
sealed interface PhotoQr {
    /** The user closed the picker without choosing. */
    data object Cancelled : PhotoQr

    /** The picked image holds no QR that could be read, or could not be loaded. */
    data object None : PhotoQr

    data class Text(val text: String) : PhotoQr
}

/**
 * The returned function opens the system photo picker for one image, which needs no permission. [onResult] runs on the
 * main thread once per open.
 */
@Composable
expect fun rememberQrPhotoPicker(onResult: (PhotoQr) -> Unit): () -> Unit

/** Opens this app's own page in the system Settings, where its permissions live. */
@Composable
expect fun rememberAppSettingsOpener(): () -> Unit

private enum class ScanStep { Reading, Picking, Failed, Resuming, Done }

/**
 * The full-screen scan sheet over Send, Penpot 4.4.2, with 4.4.8 (scan failed) as its dialog. A QR from the camera or
 * from a picked photo that [accepts] returns false for opens the dialog. [onAddress] gets the text of one that it accepts.
 */
@Composable
fun QrScanSheet(accepts: (String) -> Boolean, onAddress: (String) -> Unit, onClose: () -> Unit) {
    var denied by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(ScanStep.Reading) }
    var torch by remember { mutableStateOf(false) }
    val openSettings = rememberAppSettingsOpener()
    val torchAvailable = rememberTorchAvailable()
    val judge = { text: String ->
        if (accepts(text)) {
            step = ScanStep.Done
            onAddress(text)
        } else {
            step = ScanStep.Failed
        }
    }
    val pickPhoto = rememberQrPhotoPicker { result ->
        when (result) {
            is PhotoQr.Text -> judge(result.text)
            PhotoQr.None -> step = ScanStep.Failed
            PhotoQr.Cancelled -> step = ScanStep.Reading
        }
    }
    // A rejected QR still in view would reopen the dialog at once.
    LaunchedEffect(step) {
        if (step == ScanStep.Resuming) {
            delay(1_500)
            step = ScanStep.Reading
        }
    }
    // Android's photo picker and leaving the app stop the camera and its light.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { torch = false }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MbNavyInk)
            .pointerInput(Unit) { detectTapGestures { } }
            .testTag("screen_send/scan"),
    ) {
        if (!denied) {
            QrCameraView(
                torch = torch,
                onText = { if (step == ScanStep.Reading) judge(it) },
                onDenied = { denied = true },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 12.dp)
                .padding(horizontal = 20.dp)
                .height(40.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MbWhite.copy(alpha = 0.10f))
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = "ปิด" }
                    .testTag("scan_close"),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(Res.drawable.ic_scan_close), contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(
                text = "สแกน QR",
                color = MbWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 24.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().align(Alignment.Center),
            )
        }
        if (denied) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 20.dp)
                    .padding(top = 52.dp, bottom = 16.dp),
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "เปิดสิทธิ์กล้องในการตั้งค่าเพื่อสแกน",
                    color = MbWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("scan_denied"),
                )
                Spacer(modifier = Modifier.weight(1f))
                PrimaryCta("ไปที่การตั้งค่า", "scan_settings", openSettings, darkSurface = true)
                Spacer(modifier = Modifier.height(9.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().height(29.dp).clickable(role = Role.Button, onClick = onClose).testTag("scan_type_instead"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("พิมพ์หรือวางแทน", color = MbWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(38.dp))
            }
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).offset(y = 11.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(painterResource(Res.drawable.ic_scan_frame), contentDescription = null, modifier = Modifier.size(272.dp))
                Spacer(modifier = Modifier.height(36.dp))
                Box(modifier = Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "วาง QR code ของที่อยู่ Bitcoin\nให้อยู่ในกรอบ",
                        color = MbWhite.copy(alpha = 0.85f),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Light,
                        lineHeight = 16.2.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 32.dp)
                    .padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (torchAvailable) {
                    ScanRoundButton(
                        fill = if (torch) MbWhite else MbWhite.copy(alpha = 0.12f),
                        modifier = Modifier
                            .toggleable(value = torch, role = Role.Switch, onValueChange = { torch = it })
                            .semantics { contentDescription = "ไฟฉาย" }
                            .testTag("scan_torch"),
                    ) {
                        Image(
                            painterResource(Res.drawable.ic_flashlight),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            colorFilter = if (torch) ColorFilter.tint(MbNavyInk) else null,
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(56.dp))
                }
                ScanRoundButton(
                    fill = MbWhite.copy(alpha = 0.12f),
                    modifier = Modifier
                        .clickable(role = Role.Button) {
                            if (step == ScanStep.Reading || step == ScanStep.Resuming) {
                                step = ScanStep.Picking
                                pickPhoto()
                            }
                        }
                        .semantics { contentDescription = "เลือกรูปจากคลังภาพ" }
                        .testTag("scan_photos"),
                ) {
                    Image(painterResource(Res.drawable.ic_photos), contentDescription = null, modifier = Modifier.size(24.dp))
                }
            }
        }
        if (step == ScanStep.Failed) {
            ScanFailedDialog(onOk = { step = ScanStep.Resuming })
        }
    }
}

@Composable
private fun ScanRoundButton(fill: Color, modifier: Modifier, icon: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, MbWhite.copy(alpha = 0.18f), CircleShape)
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

/** Penpot 4.4.8. The backdrop swallows taps, so only เข้าใจแล้ว leaves it. */
@Composable
private fun ScanFailedDialog(onOk: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .pointerInput(Unit) { detectTapGestures { } }
            .testTag("scan_failed"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MbWhite)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MbIconWash), contentAlignment = Alignment.Center) {
                    Image(painterResource(Res.drawable.ic_warning_round), contentDescription = null, modifier = Modifier.size(32.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "QR code ไม่ถูกต้อง",
                    color = MbNavyInk,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 21.6.sp,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    text = "QR นี้ไม่ใช่ที่อยู่ Bitcoin ที่รองรับ\nโปรดใช้ QR ของที่อยู่ Bitcoin\nแล้วลองสแกนอีกครั้ง",
                    color = MbBody,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Light,
                    lineHeight = 16.2.sp,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MbNavy)
                    .clickable(role = Role.Button, onClick = onOk)
                    .testTag("scan_failed_ok"),
                contentAlignment = Alignment.Center,
            ) {
                Text("เข้าใจแล้ว", color = MbWhite, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
