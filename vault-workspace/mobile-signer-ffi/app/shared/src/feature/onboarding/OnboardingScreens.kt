package com.mebit.app.feature.onboarding

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mebit.app.data.OnboardingSession
import com.mebit.app.ui.components.HwChip
import com.mebit.app.ui.components.InfoCard
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.OptionCard
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta

@Composable
fun WelcomeScreen(
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "WELCOME",
        title = "Bitcoin ของคุณ\nKey อยู่ในมือคุณ",
        subtitle = "กุญแจเดียว หรือหลายกุญแจ · คุณถือเองทุกดอก",
        dark = true,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_welcome",
    ) {
        PrimaryCta("สร้างกระเป๋าใหม่", "cta_create", onCreate, darkSurface = true)
        Spacer(modifier = Modifier.height(8.dp))
        SecondaryCta("นำเข้ากระเป๋าเดิม", "cta_import", onImport, darkSurface = true)
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
fun ImportSeedScreen(
    session: OnboardingSession,
    onRestored: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    MebitScreen(
        eyebrow = "IMPORT SEED",
        title = "นำเข้า 24 คำ",
        subtitle = "พิมพ์หรือวาง 24 คำตามลำดับ แล้วกู้กุญแจเข้าเครื่องนี้",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_onboarding/import-seed",
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().testTag("seed_input"),
            minLines = 4,
            label = { Text("24 คำ") },
        )
        Spacer(modifier = Modifier.height(16.dp))
        PrimaryCta("กู้กุญแจ", "cta_restore", {
            val words = draft.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            session.restoreFromWords(words)
            if (session.lastError == null && session.words.isNotEmpty()) onRestored()
        })
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
fun AppLockScreen(
    session: OnboardingSession,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "APP LOCK",
        title = "ล็อกแอป",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        subtitle = "เปิด Face ID หรือรหัสผ่านเครื่องเพื่อเปิด mebit (demo เปิดทันทีบนหน้านี้)",
        tag = "screen_onboarding/app-lock",
    ) {
        PrimaryCta("เปิดการล็อก", "cta_enable_lock", {
            session.markAppLockEnabled()
            onContinue()
        })
        Spacer(modifier = Modifier.height(8.dp))
        SecondaryCta("ข้ามตอนนี้", "cta_skip_lock", onContinue)
    }
}

@Composable
fun HomeFirstRunScreen(
    session: OnboardingSession,
    onSingleSig: () -> Unit,
    onVault: () -> Unit,
    onLater: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "HOME",
        title = "จะเริ่มใช้แบบไหน",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        subtitle = "เลือกตอนนี้ หรือสร้าง Vault ทีหลังก็ได้",
        tag = "screen_home/first-run",
    ) {
        if (!session.backupConfirmed) {
            Text(
                text = "ยังไม่ได้สำรองกุญแจ · ต้องสำรอง 24 คำก่อนรับเงินครั้งแรก",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        OptionCard(
            title = "กระเป๋ากุญแจเดียว",
            body = "พร้อมใช้ · รับ-ส่งได้ทันที",
            tag = "cta_singlesig",
            onClick = onSingleSig,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OptionCard(
            title = "Vault หลายกุญแจ",
            body = "เพิ่ม Jade, Trezor Safe 7 หรือมือถืออีกเครื่อง",
            tag = "cta_vault",
            chips = listOf("Jade", "Trezor Safe 7"),
            onClick = onVault,
        )
        Spacer(modifier = Modifier.height(12.dp))
        SecondaryCta("ไว้ทีหลัง", "cta_later", onLater)
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
