package com.mebit.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.ui.components.BackChip
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbWhite

/** True when this device has a camera the scan sheet can use. False on the iOS simulator and a device without one, which hides the scan chip. */
@Composable
expect fun rememberCameraAvailable(): Boolean

/**
 * The back camera filling its parent. It asks for the camera when it enters composition, calls [onDenied] when the user
 * refused or the system does not allow it, and calls [onText] on the main thread with the first QR it reads, then reads
 * no more. The camera stops when it leaves composition.
 */
@Composable
expect fun QrCameraView(onText: (String) -> Unit, onDenied: () -> Unit)

/** Opens this app's own page in the system Settings, where its permissions live. */
@Composable
expect fun rememberAppSettingsOpener(): () -> Unit

/** The full-screen scan sheet over Send. It has no board: its shape and words are open item 59's defaults. */
@Composable
fun QrScanSheet(onText: (String) -> Unit, onClose: () -> Unit) {
    var denied by remember { mutableStateOf(false) }
    val openSettings = rememberAppSettingsOpener()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { } }
            .testTag("screen_send/scan"),
    ) {
        if (!denied) QrCameraView(onText, onDenied = { denied = true })
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                BackChip(onClick = onClose, dark = true, tag = "scan_close")
            }
            Spacer(modifier = Modifier.weight(1f))
            if (denied) {
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
            } else {
                Box(modifier = Modifier.align(Alignment.CenterHorizontally).size(240.dp).border(3.dp, MbWhite, RoundedCornerShape(24.dp)))
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "สแกน QR ที่อยู่ผู้รับ",
                    color = MbWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
