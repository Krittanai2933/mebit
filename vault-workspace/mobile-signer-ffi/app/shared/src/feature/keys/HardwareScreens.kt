package com.mebit.app.feature.keys

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.EnrollResult
import com.mebit.app.data.HardwareScan
import com.mebit.app.data.KeySourceLabel
import com.mebit.app.data.KeyringStore
import com.mebit.app.data.SavedKey
import com.mebit.app.data.ScanProblem
import com.mebit.app.data.hardwareKind
import com.mebit.app.data.hardwareMonogram
import com.mebit.app.data.hardwareSource
import com.mebit.app.data.oneKeyPath
import com.mebit.app.feature.onboarding.AddressTypeRows
import com.mebit.app.rememberBluetoothSettingsOpener
import com.mebit.app.ui.components.IssueBanner
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.SecondaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbSuccess
import com.mebit.app.ui.theme.MbWarmTint
import com.mebit.app.ui.theme.MbWhite
import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.FoundWallet
import com.mebit.ffi.signer.WalletKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

private val DeviceRowFill = Color(0xFFFFF8EF)
private val RadarOff = Color(0xFFC9CED6)
private val BannerLine = Color(0xFFDFDFDF)
private val BannerKicker = Color(0xFFF8981C)
private val BannerInk = Color(0xFF1E1E1E)

/** The model the debug catalog's "unsupported" button reports, as Penpot 6.1 draws it. */
private const val DEMO_UNSUPPORTED_MODEL = "Trezor Safe 5"

/** What [HardwareWalletScreen] reads: Add Key's multisig key (2.3.1), or the account key of a one-key wallet of [OneKey.type] (2.3.3 to 2.3.6). */
sealed interface DeviceRead {
    data object AddKey : DeviceRead

    /** The key of [key]'s device at [type]'s account path, for a one-key wallet. Only that device's kind is looked for, and only that key is taken. */
    data class OneKey(val key: SavedKey, val type: AddressType) : DeviceRead {
        val kind: WalletKind get() = hardwareKind(key.source)
    }
}

/** What the card shows above a Safe 7's row: the radar, 2.3.5's remembered pairing, or 2.3.4's or 2.3.6's code field. */
internal enum class Safe7Card { Radar, Remembered, FirstPairing, Forgot }

/**
 * Pure. The card of a Safe 7 the screen is about to reach or is reaching. [kept] is a pairing this phone keeps for its key, [asked] that it
 * asked for its code in the connect running now. The code field shows only while it asks: on a first pairing (2.3.4), or when it forgot
 * this phone (2.3.6). A kept pairing it has not asked about is 2.3.5.
 */
internal fun safe7Card(kept: Boolean, asked: Boolean): Safe7Card = when {
    asked && kept -> Safe7Card.Forgot
    asked -> Safe7Card.FirstPairing
    kept -> Safe7Card.Remembered
    else -> Safe7Card.Radar
}

/** Pure. The sub line of a Safe 7's row while it connects, as 2.3.4 to 2.3.6 draw it. */
internal fun safe7RowLine(card: Safe7Card): String = when (card) {
    Safe7Card.Remembered -> "จับคู่ไว้แล้ว · กำลังเชื่อม"
    Safe7Card.Forgot -> "ต้องจับคู่ใหม่"
    Safe7Card.Radar, Safe7Card.FirstPairing -> "กำลังจับคู่ครั้งแรก"
}

/** Pure. The line at the top of the card, over the radar or the code field. 2.3.5's card has none. */
internal fun readInstruction(read: DeviceRead, bluetoothOff: Boolean, card: Safe7Card?): String = when {
    bluetoothOff -> "เปิด Bluetooth บนมือถือ เพื่อให้มองเห็น Jade หรือ Trezor Safe 7"
    read is DeviceRead.OneKey && read.kind == WalletKind.JADE -> "เปิด Jade แล้ววางไว้ใกล้มือถือ จากนั้นใส่ PIN บน Jade (มือถือต้องต่ออินเทอร์เน็ต)"
    read is DeviceRead.OneKey || card == Safe7Card.FirstPairing || card == Safe7Card.Forgot -> "เปิด Trezor Safe 7 ไปที่ Pair new device แล้ววางไว้ใกล้มือถือ"
    else -> "เปิด Jade หรือ Trezor Safe 7 แล้ววางไว้ใกล้มือถือ"
}

private val DefaultSteps = listOf("ค้นหาอุปกรณ์ผ่าน Bluetooth", "ยืนยันรหัสจับคู่บนหน้าจออุปกรณ์", "อ่านกุญแจสาธารณะ (xpub)")

/** Pure. The three steps under the card: 2.3.3's for a one-key wallet's Jade, with the type and the path it reads, 2.3.5's for a remembered Safe 7. */
internal fun readSteps(read: DeviceRead, card: Safe7Card?): List<String> = when {
    read is DeviceRead.OneKey && read.kind == WalletKind.JADE -> listOf(
        "ค้นหาอุปกรณ์ผ่าน Bluetooth",
        "ใส่ PIN บน Jade",
        "อ่านกุญแจ ${AddressTypeRows.first { it.type == read.type }.label} (${oneKeyPath(read.type)})",
    )
    card == Safe7Card.Remembered -> listOf("ค้นหาอุปกรณ์ผ่าน Bluetooth", "กด Allow บนหน้าจอ Trezor", "อ่านกุญแจสาธารณะ (xpub)")
    else -> DefaultSteps
}

/**
 * Penpot 2.3.1 Hardware Wallet, with 2.3.2 (Bluetooth off) as a state of it: a live scan list with one row and one
 * connect button per wallet found. [onUnsupported] gets the model's name, or null when the wallet does not say.
 * The demo block under the board is for an emulator or simulator, which has no Bluetooth, and only Add Key ([DeviceRead.AddKey]) has it.
 * For a one-key wallet ([DeviceRead.OneKey]) it is 2.3.3 to 2.3.6: only that kind is looked for, and a Safe 7's card says whether this
 * phone remembers its pairing ([safe7Card]). A Safe 7's code field shows only once it asks, and a connect to it can be cancelled.
 */
@Composable
fun HardwareWalletScreen(
    store: KeyringStore,
    onEnrolled: () -> Unit,
    onUnsupported: (model: String?) -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
    read: DeviceRead = DeviceRead.AddKey,
) {
    val scope = rememberCoroutineScope()
    val openBluetoothSettings = rememberBluetoothSettingsOpener()
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var connectJob by remember { mutableStateOf<Job?>(null) }
    var connecting by remember { mutableStateOf<FoundWallet?>(null) }
    val oneKey = read as? DeviceRead.OneKey
    val kinds = oneKey?.let { listOf(it.kind) } ?: listOf(WalletKind.JADE, WalletKind.TREZOR_SAFE7)
    // Read once: a pairing is kept or dropped only by a connect, which then says so itself (KeyringStore.pairingKept).
    val keptAtOpen = remember(oneKey) { oneKey?.kind == WalletKind.TREZOR_SAFE7 && store.keepsPairing(oneKey.key.fingerprint) }
    val rescan: () -> Unit = { if (scanJob?.isActive != true) scanJob = scope.launch { store.scanHardware(kinds) } }
    LaunchedEffect(Unit) { rescan() }
    val connect: (FoundWallet) -> Unit = connect@{ wallet ->
        if (connecting != null) return@connect
        connecting = wallet
        connectJob = scope.launch {
            try {
                // Connecting ends a scan that is still looking, so the two never run at once.
                scanJob?.cancelAndJoin()
                val result = if (oneKey == null) store.enrollHardware(wallet) else store.enrollHardware(wallet, oneKeyPath(oneKey.type), expect = oneKey.key)
                when (result) {
                    EnrollResult.Enrolled -> onEnrolled()
                    EnrollResult.UnsupportedModel -> onUnsupported(null)
                    EnrollResult.Failed -> Unit
                }
            } finally {
                connecting = null
            }
        }
    }

    val scan = store.hardwareScan
    val busy = connecting != null || store.hardwareBusy
    val found: List<FoundWallet> = when (scan) {
        HardwareScan.Idle -> emptyList()
        is HardwareScan.Scanning -> scan.found
        is HardwareScan.Done -> scan.found
    }.filter { it.kind in kinds }
    val problem = (scan as? HardwareScan.Done)?.problem
    val searching = scan !is HardwareScan.Done
    val bluetoothOff = problem == ScanProblem.BluetoothOff
    // A scan that found nothing and failed cannot say Bluetooth is on, or that the room is empty.
    val unavailable = bluetoothOff || problem is ScanProblem.PermissionDenied || (problem is ScanProblem.Failed && found.isEmpty())
    val demoDisabled = store.hardwareBusy
    val safe7Connecting = connecting?.kind == WalletKind.TREZOR_SAFE7
    val card = when {
        oneKey?.kind == WalletKind.TREZOR_SAFE7 -> safe7Card(kept = if (safe7Connecting) store.pairingKept else keptAtOpen, asked = safe7Connecting && store.pairingAsked)
        safe7Connecting -> safe7Card(kept = store.pairingKept, asked = store.pairingAsked)
        else -> null
    }

    MebitScreen(
        eyebrow = "HARDWARE WALLET",
        title = "เชื่อมต่ออุปกรณ์",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = if (oneKey == null) "screen_keys/hardware" else "screen_vault/hw-read-key",
    ) {
        // MebitScreen starts content 54 dp under the title's top and the board starts the pill 60 dp under it; measured on a simulator, 4 dp here lines them up.
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            when {
                safe7Connecting -> StatusPill("Bluetooth เปิดอยู่ · พบ Trezor", "hw_busy", warn = false)
                busy -> StatusPill("กำลังเชื่อมต่อ…", "hw_busy", warn = false)
                bluetoothOff -> StatusPill("Bluetooth ปิดอยู่ · เปิดเพื่อค้นหา", "hw_status", warn = true)
                unavailable -> StatusPill("Bluetooth · ค้นหาไม่ได้", "hw_status", warn = true)
                searching -> StatusPill("Bluetooth เปิดอยู่ · กำลังค้นหา…", "hw_status", warn = false)
                found.isEmpty() -> StatusPill("Bluetooth เปิดอยู่ · ไม่พบอุปกรณ์", "hw_status", warn = false)
                else -> StatusPill("Bluetooth เปิดอยู่ · พบ ${found.size} เครื่อง", "hw_status", warn = false)
            }
            if (!busy && !searching) {
                Box(
                    Modifier.height(34.dp).clickable(role = Role.Button, onClick = rescan).padding(horizontal = 8.dp).testTag("cta_hw_rescan"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("ค้นหาอีกครั้ง", color = MbNavy, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        ScanCard {
            CardTop(
                card = card ?: Safe7Card.Radar,
                instruction = readInstruction(read, bluetoothOff, card),
                radarCore = if (unavailable) RadarOff else MbBtcOrange,
                code = store.pairingCode,
                onCode = { store.pairingCode = it },
            )
            DeviceList {
                when {
                    bluetoothOff -> DeviceRow(
                        monogram = null,
                        title = "Bluetooth ปิดอยู่",
                        sub = "แตะ เปิด เพื่อค้นหาอุปกรณ์",
                        action = "เปิด",
                        actionTag = "cta_hw_open_bluetooth",
                        enabled = true,
                        onAction = openBluetoothSettings,
                    )
                    found.isNotEmpty() -> found.forEach { wallet ->
                        val reaching = connecting == wallet && wallet.kind == WalletKind.TREZOR_SAFE7
                        DeviceRow(
                            monogram = hardwareMonogram(hardwareSource(wallet.kind)),
                            title = wallet.name,
                            sub = if (reaching && card != null) safe7RowLine(card) else "พบใกล้ ๆ",
                            action = if (reaching) "ยกเลิก" else "เชื่อม",
                            actionTag = when {
                                reaching -> "cta_hw_cancel"
                                wallet.kind == WalletKind.JADE -> "cta_hw_jade"
                                else -> "cta_hw_trezor"
                            },
                            enabled = reaching || !busy,
                            onAction = { if (reaching) connectJob?.cancel() else connect(wallet) },
                        )
                    }
                    searching || unavailable -> Unit
                    else -> Text(
                        "ไม่พบ Jade หรือ Trezor Safe 7 ใกล้ ๆ · เปิดอุปกรณ์แล้วกดค้นหาอีกครั้ง",
                        color = MbBody,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Light,
                        lineHeight = 17.sp,
                        modifier = Modifier.testTag("hw_none_found"),
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        StepsCard(readSteps(read, card))
        when (problem) {
            ScanProblem.BluetoothOff -> {
                Spacer(Modifier.height(17.dp))
                StateBanner(
                    kicker = "BLUETOOTH OFF",
                    message = "มือถือยังมองไม่เห็นอุปกรณ์ เปิด Bluetooth ในตั้งค่า แล้วกลับมาลองใหม่ ไม่มีข้อมูลถูกส่งออก",
                    tag = "hw_bt_off_banner",
                )
            }
            is ScanProblem.PermissionDenied -> {
                Spacer(Modifier.height(17.dp))
                // The OS text names an internal class, so the banner says what to do instead.
                IssueBanner("ไม่ได้รับอนุญาตให้ใช้ Bluetooth", "เปิดสิทธิ์ Bluetooth ให้ mebit ในตั้งค่าของเครื่อง แล้วกดค้นหาอีกครั้ง", "hw_scan_issue")
            }
            is ScanProblem.Failed -> {
                Spacer(Modifier.height(17.dp))
                IssueBanner("ค้นหาไม่สำเร็จ", problem.message, "hw_scan_issue")
            }
            null -> Unit
        }
        store.lastError?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error"))
        }
        if (oneKey != null) return@MebitScreen
        // The board leaves the rest of the first viewport empty; simulator controls remain below it in the scroll.
        Spacer(Modifier.height(240.dp))
        Text("โหมดจำลอง · สำหรับ emulator / simulator ไม่ได้เชื่อมต่ออุปกรณ์", color = MbBody, fontSize = 11.5.sp)
        Spacer(Modifier.height(8.dp))
        SecondaryCta("จำลอง Jade", "cta_hw_demo_jade", {
            if (!demoDisabled) {
                store.startHardwareMock(KeySourceLabel.Jade)
                if (store.pending != null) onEnrolled()
            }
        })
        Spacer(Modifier.height(8.dp))
        SecondaryCta("จำลอง Trezor Safe 7", "cta_hw_demo_trezor", {
            if (!demoDisabled) {
                store.startHardwareMock(KeySourceLabel.TrezorSafe7)
                if (store.pending != null) onEnrolled()
            }
        })
        Spacer(Modifier.height(8.dp))
        SecondaryCta("จำลองรุ่นอื่น (Unsupported)", "cta_hw_unsupported", { if (!demoDisabled) onUnsupported(DEMO_UNSUPPORTED_MODEL) })
    }
}

/**
 * Penpot 6.1 Connect HW, the same card as Hardware Wallet with the one wallet it found marked unsupported.
 * [model] is that wallet's name, or null when it does not say.
 */
@Composable
fun T13UnsupportedModelScreen(
    model: String?,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val name = model ?: "รุ่นที่ไม่รองรับ"
    val close: () -> Unit = { onBack?.invoke() }
    MebitScreen(
        eyebrow = "UNSUPPORTED MODEL",
        title = "รุ่นนี้ไม่รองรับ",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_critical/t13",
    ) {
        Spacer(Modifier.height(4.dp))
        StatusPill(name, "t13_model", warn = false)
        Spacer(Modifier.height(24.dp))
        ScanCard {
            CardTop(
                card = Safe7Card.Radar,
                instruction = if (model != null) {
                    "พบ $model — mebit รองรับเฉพาะ Trezor Safe 7 ผ่าน Bluetooth"
                } else {
                    "พบอุปกรณ์ที่ไม่รองรับ — mebit รองรับเฉพาะ Jade และ Trezor Safe 7 ผ่าน Bluetooth"
                },
                radarCore = MbBtcOrange,
                code = "",
                onCode = {},
            )
            DeviceList {
                DeviceRow(
                    monogram = if (model?.startsWith("Trezor") == true) hardwareMonogram(KeySourceLabel.TrezorSafe7) else "?",
                    title = name,
                    sub = "ไม่รองรับ",
                    action = "ปิด",
                    actionTag = "cta_t13_close",
                    enabled = true,
                    onAction = close,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        StepsCard(DefaultSteps)
        Spacer(Modifier.height(19.dp))
        Box(
            Modifier.fillMaxWidth().height(54.dp).clip(CircleShape).background(MbWhite).border(1.5.dp, MbNavy, CircleShape)
                .clickable(role = Role.Button, onClick = close).testTag("cta_t13_back"),
            contentAlignment = Alignment.Center,
        ) {
            Text("เลือกอุปกรณ์ที่รองรับ", color = MbNavy, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatusPill(text: String, tag: String, warn: Boolean) {
    Row(
        Modifier.widthIn(min = 223.dp).height(34.dp).clip(CircleShape).background(if (warn) MbIconWash else MbChip).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BluetoothGlyph(MbNavy, 1.8.dp, 8.dp, 14.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            color = if (warn) MbEyebrow else MbNavy,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 18.sp,
            maxLines = 1,
            modifier = Modifier.testTag(tag),
        )
    }
}

/** The card every board of the hardware screen shares: [CardTop], then the rows. */
@Composable
private fun ScanCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MbWhite).border(1.dp, MbBorder, shape)
            .padding(start = 18.dp, top = 17.dp, end = 18.dp, bottom = 18.dp),
        content = content,
    )
}

/**
 * The card above the rows, as tall on every board so the rows never move: the instruction and the radar (2.3.1, 2.3.3), 2.3.5's check
 * and its two lines, or 2.3.4's and 2.3.6's code field under the instruction, which 2.3.6 heads in orange.
 */
@Composable
private fun CardTop(card: Safe7Card, instruction: String, radarCore: Color, code: String, onCode: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().height(191.dp)) {
        if (card != Safe7Card.Remembered) {
            // The board gives the instruction a slot that holds two lines, so what is under it stays put for one.
            Box(Modifier.fillMaxWidth().height(55.dp)) {
                Text(instruction, color = MbBody, fontSize = 12.5.sp, fontWeight = FontWeight.Light, lineHeight = 17.sp, maxLines = 2, modifier = Modifier.testTag("hw_instruction"))
            }
        }
        when (card) {
            Safe7Card.Radar -> Radar(radarCore)
            Safe7Card.Remembered -> Column(Modifier.fillMaxWidth().testTag("hw_pairing_kept"), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(31.dp))
                Box(Modifier.size(46.dp).background(MbSuccess, CircleShape), contentAlignment = Alignment.Center) {
                    Tick(Modifier.size(15.dp, 11.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text("จำการจับคู่กับ Trezor นี้ไว้แล้ว", color = MbNavyInk, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, lineHeight = 17.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("กด Allow บน Trezor เพื่อเชื่อมต่อ ไม่ต้องใส่รหัส", color = MbSlate, fontSize = 12.5.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
            }
            Safe7Card.FirstPairing, Safe7Card.Forgot -> PairingCodeBlock(forgot = card == Safe7Card.Forgot, code = code, onCode = onCode)
        }
    }
}

/** 2.3.4's code field under its bold line, or 2.3.6's under its orange one when the Safe 7 forgot this phone, and the line under the field. */
@Composable
internal fun PairingCodeBlock(forgot: Boolean, code: String, onCode: (String) -> Unit) {
    Text(
        if (forgot) "Trezor ลืมการจับคู่กับเครื่องนี้แล้ว" else "ใส่รหัส 6 หลักที่แสดงบน Trezor",
        color = if (forgot) MbEyebrow else MbNavyInk,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 17.sp,
        modifier = Modifier.testTag(if (forgot) "hw_pairing_forgot" else "hw_pairing_first"),
    )
    Spacer(Modifier.height(10.dp))
    CodeField(code, onCode)
    Spacer(Modifier.height(6.dp))
    Text(
        if (forgot) {
            "เช่น หลังล้างเครื่อง ใส่รหัส 6 หลักใหม่ที่แสดงบน Trezor แล้ว mebit จะจำการจับคู่ใหม่ไว้แทน"
        } else {
            "จับคู่ครั้งแรกเท่านั้น ครั้งต่อไป mebit จำการจับคู่ไว้ให้ กด Allow บน Trezor อย่างเดียว"
        },
        color = MbSlate,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        maxLines = 2,
    )
}

/** 2.3.4's warm field for the six digits the Safe 7 shows, its dashes until the first digit. Takes digits only, six at most. */
@Composable
private fun CodeField(code: String, onCode: (String) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val style = TextStyle(color = MbNavyInk, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, textAlign = TextAlign.Center)
    BasicTextField(
        value = code,
        onValueChange = { typed -> onCode(typed.filter(Char::isDigit).take(6)) },
        singleLine = true,
        textStyle = style,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth().height(44.dp).clip(shape).background(MbWarmTint).border(1.5.dp, MbBtcOrange, shape).semantics { contentDescription = "รหัส 6 หลัก" }.testTag("hw_pairing_code"),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (code.isEmpty()) Text("_  _  _  _  _  _", style = style.copy(letterSpacing = 0.sp))
                inner()
            }
        },
    )
}

/** A white check drawn corner to corner of its box: down to the low point a third in, then up to the far corner. */
@Composable
private fun Tick(modifier: Modifier) {
    Canvas(modifier) {
        val tick = Path().apply {
            moveTo(0f, size.height * 0.5f)
            lineTo(size.width * 0.35f, size.height)
            lineTo(size.width, 0f)
        }
        drawPath(tick, MbWhite, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun Radar(core: Color) {
    Box(
        Modifier.fillMaxWidth().height(124.dp).clip(RoundedCornerShape(18.dp)).background(MbNavy),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            listOf(90.dp to 0.08f, 60.dp to 0.12f, 32.dp to 0.2f).forEach { (radius, alpha) ->
                drawCircle(MbWhite.copy(alpha = alpha), radius.toPx(), style = Stroke(1.5.dp.toPx()))
            }
        }
        Box(Modifier.size(40.dp).background(core, CircleShape), contentAlignment = Alignment.Center) {
            BluetoothGlyph(MbWhite, 2.dp, 8.dp, 15.8.dp)
        }
    }
}

/** Draws the Bluetooth rune in a box of [width] by [height] plus [stroke], so the line never crosses the edge. */
@Composable
private fun BluetoothGlyph(color: Color, stroke: Dp, width: Dp, height: Dp) {
    Canvas(Modifier.size(width + stroke, height + stroke)) {
        val inset = stroke.toPx() / 2
        val w = width.toPx()
        val h = height.toPx()
        val rune = Path().apply {
            moveTo(inset, inset + h / 4)
            lineTo(inset + w, inset + h * 3 / 4)
            lineTo(inset + w / 2, inset + h)
            lineTo(inset + w / 2, inset)
            lineTo(inset + w, inset + h / 4)
            lineTo(inset, inset + h * 3 / 4)
        }
        drawPath(rune, color, style = Stroke(stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The rows under the radar. The board's slot is one row high, so a scan with nothing to list keeps the card the same size. */
@Composable
private fun DeviceList(rows: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = rows)
}

/** A null [monogram] draws the Bluetooth rune in its place. */
@Composable
private fun DeviceRow(
    monogram: String?,
    title: String,
    sub: String,
    action: String,
    actionTag: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(shape).background(DeviceRowFill).border(1.5.dp, MbBtcOrange, shape).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).background(MbIconWash, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            if (monogram != null) {
                Text(monogram, color = MbEyebrow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            } else {
                BluetoothGlyph(MbNavy, 1.8.dp, 8.dp, 14.dp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MbNavyInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.4.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(1.6.dp))
            Text(sub, color = MbSlate, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, lineHeight = 15.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(width = 64.dp, height = 32.dp).clip(CircleShape).background(if (enabled) MbNavy else MbChip)
                .clickable(enabled = enabled, role = Role.Button, onClick = onAction).testTag(actionTag),
            contentAlignment = Alignment.Center,
        ) {
            Text(action, color = if (enabled) MbWhite else MbBody, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp, maxLines = 1)
        }
    }
}

/** The three connection steps. The board marks the first as the current one on every state. */
@Composable
private fun StepsCard(steps: List<String>) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MbWhite).border(1.dp, MbBorder, shape)
            .padding(start = 18.dp, top = 13.dp, end = 18.dp, bottom = 18.dp).testTag("hw_steps"),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        steps.forEachIndexed { index, step ->
            val current = index == 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(if (current) MbNavy else MbWhite)
                        .then(if (current) Modifier else Modifier.border(1.dp, MbBorder, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", color = if (current) MbWhite else MbSlate, fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 13.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text(step, color = if (current) MbNavyInk else MbSlate, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }
    }
}

/** Penpot 2.3.2's banner. Same box as the Face ID banner on the lock screen. */
@Composable
private fun StateBanner(kicker: String, message: String, tag: String) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().height(96.dp).clip(shape).background(MbWhite).border(1.dp, BannerLine, shape)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp).testTag(tag),
    ) {
        Text(kicker, color = BannerKicker, fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp)
        Spacer(Modifier.height(8.dp))
        Text(message, color = BannerInk, fontSize = 13.sp, lineHeight = 16.sp)
    }
}
