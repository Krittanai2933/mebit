package com.mebit.app.feature.send

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.SendDraft
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta

@Composable
fun S1HomeSingleSigScreen(
    wallet: SingleSigWallet,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "SINGLE-SIG",
        title = "กระเป๋าใช้จ่าย",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_send/s1",
    ) {
        Text(
            text = "ยอด · ${wallet.balanceSats} sats (mock chain)",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("ss_balance"),
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("รับ", "cta_s1_receive", onReceive)
        Spacer(modifier = Modifier.height(8.dp))
        SecondaryCta("ส่ง", "cta_s1_send", onSend)
    }
}

@Composable
fun S2ReceiveBackupGateScreen(
    session: OnboardingSession,
    onBackedUp: () -> Unit,
    onGoBackup: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "RECEIVE",
        title = "สำรองกุญแจก่อนรับเงิน",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_send/s2",
    ) {
        if (session.backupConfirmed) {
            Text("สำรองแล้ว · พร้อมรับ", color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(20.dp))
            PrimaryCta("ต่อไป · ที่อยู่รับ", "cta_s2_continue", onBackedUp)
        } else {
            Text(
                text = "backup gate · ต้องยืนยัน 24 คำก่อนแสดงที่อยู่รับ",
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(20.dp))
            PrimaryCta("ไปสำรอง", "cta_s2_backup", onGoBackup)
        }
    }
}

@Composable
fun S3ReceiveScreen(
    wallet: SingleSigWallet,
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "RECEIVE",
        title = "รับ Bitcoin",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_send/s3",
    ) {
        Text("ที่อยู่รับ (mock)", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(wallet.receiveAddress, modifier = Modifier.testTag("ss_receive_address"))
        Spacer(modifier = Modifier.height(16.dp))
        SecondaryCta("สร้างที่อยู่ใหม่", "cta_s3_refresh", { wallet.refreshMock() })
        Spacer(modifier = Modifier.height(8.dp))
        PrimaryCta("เสร็จ", "cta_s3_done", onDone)
    }
}

@Composable
fun S4SendScreen(
    wallet: SingleSigWallet,
    draft: SendDraft,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    var amountText by remember { mutableStateOf(draft.amountSats.toString()) }
    MebitScreen(
        eyebrow = "SEND",
        title = "ส่ง Bitcoin",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_send/s4",
    ) {
        Text("ใช้ได้ ${wallet.balanceSats} sats", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it },
            label = { Text("จำนวน (sats)") },
            modifier = Modifier.fillMaxWidth().testTag("s4_amount"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text("ค่าธรรมเนียม · ${draft.feeSats} sats", modifier = Modifier.testTag("s4_fee"))
        SecondaryCta("fee เร็ว (1200)", "cta_fee_fast", { draft.setFeePreset(1200) })
        SecondaryCta("fee ประหยัด (250)", "cta_fee_eco", { draft.setFeePreset(250) })
        Spacer(modifier = Modifier.height(16.dp))
        PrimaryCta("ตรวจก่อนเซ็น", "cta_s4_review", {
            if (draft.setAmount(amountText)) onContinue()
        })
        draft.lastError?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun S5ReviewSignScreen(
    wallet: SingleSigWallet,
    draft: SendDraft,
    onSigned: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "REVIEW & SIGN",
        title = "ตรวจแล้วเซ็น",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_send/s5",
    ) {
        Text("ถึง · ${draft.destination}", style = MaterialTheme.typography.bodyMedium)
        Text("ส่ง · ${draft.amountSats} · fee · ${draft.feeSats}", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("เซ็นบนเครื่องนี้ (mock)", "cta_s5_sign", {
            if (wallet.broadcastMock(draft.amountSats, draft.feeSats)) onSigned()
        })
        if (draft.amountSats + draft.feeSats > wallet.balanceSats) {
            Text("ยอดไม่พอ", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun S6SentScreen(
    wallet: SingleSigWallet,
    draft: SendDraft,
    variant: String,
    onHome: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val eyebrow = when (variant) {
        "slip" -> "SENT · SLIP"
        "block" -> "SENT · BLOCK"
        "poster" -> "SENT · POSTER"
        else -> "SENT"
    }
    MebitScreen(
        eyebrow = eyebrow,
        title = "ส่งสำเร็จ",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_send/s6_$variant",
    ) {
        Text("txid · ${wallet.lastTxid}", modifier = Modifier.testTag("s6_txid"))
        Text("${draft.amountSats} sats → ${draft.destination.take(20)}…")
        Spacer(modifier = Modifier.height(12.dp))
        Text("รูปแบบแชร์ · $variant", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("กลับหน้า single-sig", "cta_s6_home", onHome)
    }
}
