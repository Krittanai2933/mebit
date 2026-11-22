package com.mebit.app.feature.vault

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.mebit.app.NoticeButton
import com.mebit.app.NoticeDialog
import com.mebit.app.PhotoQr
import com.mebit.app.QrCameraView
import com.mebit.app.ScanRoundButton
import com.mebit.app.data.ImportProblem
import com.mebit.app.data.UNREADABLE_FILE
import com.mebit.app.data.problemOf
import com.mebit.app.data.scanCounterText
import com.mebit.app.rememberAppSettingsOpener
import com.mebit.app.rememberQrPhotoPicker
import com.mebit.app.rememberTorchAvailable
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_flashlight
import com.mebit.app.resources.ic_photos
import com.mebit.app.resources.ic_scan_close
import com.mebit.app.resources.ic_scan_frame
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbWhite
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.QrPartsReader
import com.mebit.ffi.signer.QrScanProgress
import com.mebit.ffi.signer.WalletFileRead
import com.mebit.ffi.signer.WalletImport
import com.mebit.ffi.signer.walletFileRead
import org.jetbrains.compose.resources.painterResource

/**
 * Penpot 3.9.2, with 3.9.4 and 3.9.5 over it: the camera or a picked photo feeds every frame to one [QrPartsReader]. Text that is not a UR is the
 * file's text, and a finished UR is too. [onWallet] gets what Rust read from it. A refusal shows the import dialog: สแกนใหม่ starts a new
 * reader, and เข้าใจแล้ว calls [onClose].
 */
@Composable
internal fun WalletQrScanSheet(onWallet: (WalletImport) -> Unit, onClose: () -> Unit) {
    var reader by remember { mutableStateOf(QrPartsReader()) }
    DisposableEffect(reader) { onDispose { reader.close() } }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var problem by remember { mutableStateOf<ImportProblem?>(null) }
    var denied by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    val torchAvailable = rememberTorchAvailable()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { torch = false }
    val openSettings = rememberAppSettingsOpener()
    val readFile = { text: String ->
        val read = runCatching { walletFileRead(text, KeyNetwork.TEST) }.getOrNull()
        if (read is WalletFileRead.Read) {
            done = true
            onWallet(read.wallet)
        } else {
            problem = read?.let(::problemOf) ?: UNREADABLE_FILE
        }
    }
    val feed = { text: String ->
        if (problem == null && !done) {
            when (val p = reader.receive(text)) {
                QrScanProgress.NotUr -> readFile(text)
                is QrScanProgress.Part -> progress = p.received.toInt() to p.expected.toInt()
                is QrScanProgress.Done -> readFile(p.text)
                is QrScanProgress.Refused -> problem = UNREADABLE_FILE
            }
        }
    }
    val pickPhoto = rememberQrPhotoPicker { result ->
        when (result) {
            is PhotoQr.Text -> feed(result.text)
            PhotoQr.None -> if (problem == null && !done) problem = UNREADABLE_FILE
            PhotoQr.Cancelled -> Unit
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MbNavyInk)
            .pointerInput(Unit) { detectTapGestures { } }
            .testTag("screen_vaults/scan"),
    ) {
        if (!denied) {
            QrCameraView(torch = torch, onText = feed, onDenied = { denied = true })
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
                text = "สแกน QR ของ Wallet",
                color = MbWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 24.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().align(Alignment.Center),
            )
        }
        if (denied) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp).padding(top = 52.dp, bottom = 54.dp)) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "เปิดสิทธิ์กล้องในการตั้งค่าเพื่อสแกน",
                    color = MbWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("scan_denied"),
                )
                Spacer(modifier = Modifier.weight(1f))
                PrimaryCta("ไปที่การตั้งค่า", "scan_settings", openSettings, darkSurface = true)
            }
        } else {
            Column(modifier = Modifier.align(Alignment.Center).offset(y = 11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(Res.drawable.ic_scan_frame), contentDescription = null, modifier = Modifier.size(272.dp))
                Spacer(modifier = Modifier.height(36.dp))
                Text(
                    text = "วาง QR ของไฟล์ BSMS หรือ descriptor\nให้อยู่ในกรอบ",
                    color = MbWhite.copy(alpha = 0.85f),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Light,
                    lineHeight = 16.2.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp).testTag("scan_hint"),
                )
                progress?.let { (received, expected) ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = scanCounterText(received, expected),
                        color = MbBtcOrange,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("scan_progress"),
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
                        .clickable(role = Role.Button) { if (problem == null && !done) pickPhoto() }
                        .semantics { contentDescription = "เลือกรูปจากคลังภาพ" }
                        .testTag("scan_photos"),
                ) {
                    Image(painterResource(Res.drawable.ic_photos), contentDescription = null, modifier = Modifier.size(24.dp))
                }
            }
        }
        problem?.let { p ->
            NoticeDialog(tag = "import_problem", title = p.title, body = p.body) {
                NoticeButton("สแกนใหม่", "import_problem_again", filled = true) {
                    reader = QrPartsReader()
                    progress = null
                    problem = null
                }
                Spacer(modifier = Modifier.height(8.dp))
                NoticeButton("เข้าใจแล้ว", "import_problem_ok", filled = false, onClick = onClose)
            }
        }
    }
}
