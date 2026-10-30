package com.mebit.app.feature.onboarding

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mebit.app.data.OnboardingSession
import com.mebit.app.resources.Res
import com.mebit.app.resources.mebit_logo
import com.mebit.app.ui.components.BackChip
import com.mebit.app.ui.components.CardFan
import com.mebit.app.ui.components.HwChip
import com.mebit.app.ui.components.InfoCard
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.OptionCard
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta
import com.mebit.app.ui.theme.MbGold
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbWhite
import org.jetbrains.compose.resources.painterResource

@Composable
fun WelcomeScreen(
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    // Penpot Organize > 1.1 Welcome: logo and card fan on top, text and CTAs at the bottom, 28dp margins.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MbNavy)
            .safeDrawingPadding()
            .padding(top = 14.dp)
            .testTag("screen_welcome"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            if (onBack != null) {
                BackChip(onClick = onBack, dark = true)
                Spacer(modifier = Modifier.width(12.dp))
            }
            Image(
                painter = painterResource(Res.drawable.mebit_logo),
                contentDescription = "mebit",
                modifier = Modifier.size(width = 122.dp, height = 32.dp),
            )
        }
        // The fan keeps its design size, stays top-aligned and bleeds off the right edge; on a short screen it gives up its shadow tail first.
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp).clipToBounds(),
            contentAlignment = Alignment.TopStart,
        ) {
            CardFan(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true))
        }
        Column(modifier = Modifier.padding(horizontal = 28.dp)) {
            Text(
                text = "กุญแจเดียว หรือหลายกุญแจ · คุณถือเองทุกดอก",
                color = MbGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 17.sp,
                letterSpacing = 0.4.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Bitcoin ของคุณ\nKey อยู่ในมือคุณ",
                color = MbWhite,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 46.sp,
                letterSpacing = (-0.8).sp,
            )
            Spacer(modifier = Modifier.height(34.dp))
            PrimaryCta("สร้างกระเป๋าใหม่", "cta_create", onCreate, darkSurface = true)
            Spacer(modifier = Modifier.height(20.dp))
            SecondaryCta("นำเข้ากระเป๋าเดิม", "cta_import", onImport, darkSurface = true)
        }
        // Penpot leaves 22dp under the CTAs above the home-indicator inset; the debug link lives in it so the CTAs keep their design position.
        TextButton(
            onClick = onOpenCatalog,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.fillMaxWidth().height(22.dp).testTag("open_catalog"),
        ) {
            Text("รายการหน้าทั้งหมด (debug)", color = MbWhite.copy(alpha = 0.5f), fontSize = 12.sp)
        }
    }
}

@Composable
fun KeySourceScreen(
    session: OnboardingSession,
    onPhone: () -> Unit,
    onHardware: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "NEW WALLET",
        title = "เก็บกุญแจดอกแรกไว้ที่ไหน",
        subtitle = "กุญแจคือสิ่งที่ใช้เซ็นอนุมัติการจ่ายเงิน เพิ่มดอกอื่นหรือยกระดับเป็น Vault ได้ทีหลัง",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_onboarding/key-source",
    ) {
        OptionCard(
            title = "มือถือเครื่องนี้",
            body = "สร้างกุญแจใหม่ในชิปของเครื่อง สำรองด้วย 24 คำ",
            tag = "cta_phone",
            onClick = {
                session.generateThisDevice()
                if (session.lastError == null && session.words.isNotEmpty()) onPhone()
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        OptionCard(
            title = "Hardware wallet",
            body = "กุญแจอยู่บนอุปกรณ์ตลอด มือถือแค่ดูยอดและส่งให้อุปกรณ์เซ็น · เชื่อมผ่าน Bluetooth",
            tag = "cta_hardware",
            chips = listOf("Jade", "Trezor Safe 7"),
            onClick = onHardware,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "ไม่แน่ใจ? เริ่มจากมือถือก่อน แล้วค่อยเพิ่ม Jade หรือ Trezor เป็นกุญแจดอกที่สองตอนยกระดับเป็น Vault",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        session.lastError?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error"))
        }
    }
}

@Composable
fun KeyCreatedScreen(
    session: OnboardingSession,
    onBackup: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "KEY CREATED",
        title = "สร้างกุญแจแล้ว",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        subtitle = "กุญแจดอกนี้เก็บอยู่ในชิปความปลอดภัยของเครื่องนี้เท่านั้น ไม่ถูกส่งออกไปที่ไหน รวมถึง mebit",
        tag = "screen_onboarding/key-created",
    ) {
        Text(
            text = "Fingerprint · " + session.fingerprint.ifBlank { "—" },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("fingerprint"),
        )
        Text(
            text = session.derivationPath.ifBlank { "m/48'/1'/0'/2'" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        InfoCard(
            eyebrow = "BACKUP",
            title = "สำรอง 24 คำก่อนรับเงินครั้งแรก",
            body = "ถ้าเครื่องหายหรือพัง 24 คำนี้คือทางเดียวที่จะกู้กระเป๋าคืน mebit กู้ให้ไม่ได้ เพราะเราไม่ได้ถือกุญแจของคุณ",
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("สำรองเลย", "cta_backup", onBackup)
    }
}

@Composable
fun BackupWordsScreen(
    session: OnboardingSession,
    onVerifiedReady: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "BACKUP",
        title = "จด 24 คำสำรอง",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        subtitle = "เรียงตามลำดับเลข ใครได้คำเหล่านี้ไปจะใช้เงินในกระเป๋าได้ทันที ห้ามถ่ายภาพหน้าจอหรือเก็บในแชต",
        tag = "screen_onboarding/backup-words",
    ) {
        session.words.forEachIndexed { index, word ->
            Text(
                text = "${index + 1}. $word",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("word_$index"),
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("ฉันจดแล้ว · ยืนยันคำ", "cta_to_verify", onVerifiedReady, enabled = session.words.size >= 12)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VerifyWordsScreen(
    session: OnboardingSession,
    onVerified: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val targets = remember(session.words) {
        listOf(0, 7, 15, 23).filter { it < session.words.size }
    }
    var step by remember { mutableStateOf(0) }
    var wrong by remember { mutableStateOf(false) }
    val pool = remember(session.words, step) {
        if (targets.isEmpty()) emptyList()
        else {
            val answer = session.words[targets[step]]
            (session.words.shuffled().take(5) + answer).distinct().shuffled()
        }
    }
    MebitScreen(
        eyebrow = "VERIFY WORDS",
        title = if (targets.isEmpty()) "ไม่มีคำให้ยืนยัน" else "เลือกคำที่ ${targets[step] + 1}",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_onboarding/verify-words",
    ) {
        if (targets.isEmpty()) {
            PrimaryCta("ต่อไป", "cta_skip_verify", onVerified)
            return@MebitScreen
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pool.forEach { word ->
                FilterChip(
                    selected = false,
                    onClick = {
                        if (word == session.words[targets[step]]) {
                            wrong = false
                            if (step + 1 >= targets.size) {
                                session.markBackupConfirmed()
                                onVerified()
                            } else {
                                step += 1
                            }
                        } else {
                            wrong = true
                        }
                    },
                    label = { Text(word) },
                    modifier = Modifier.testTag("chip_$word"),
                )
            }
        }
        if (wrong) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("ไม่ถูก · ลองใหม่", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("verify_error"))
        }
    }
}

@Composable
fun HwBackupConfirmScreen(
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "HW BACKUP",
        title = "ยืนยันสำรองบนอุปกรณ์",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        subtitle = "Jade / Trezor Safe 7 เก็บ seed บนเครื่อง · mebit เชื่อมผ่าน Bluetooth เท่านั้น (ไม่มีกล้อง)",
        tag = "screen_onboarding/hw-backup-confirm",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HwChip("Jade")
            HwChip("Trezor Safe 7")
        }
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("เข้าใจแล้ว", "cta_hw_backup_done", onDone)
    }
}
