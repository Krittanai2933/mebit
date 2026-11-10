package com.mebit.app.feature.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.Why
import com.mebit.app.getPlatform
import com.mebit.app.data.Cause
import com.mebit.app.data.UnlockResult
import com.mebit.app.navigation.Boot
import com.mebit.app.navigation.WalletGateState
import com.mebit.app.ui.components.BottomActions
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbWhite
import kotlinx.coroutines.launch

/** The reason the OS sheet carries for an unlock, the same as the in-session unlock overlay's. */
private const val UNLOCK_REASON = "ปลดล็อก mebit"

/** The reason the OS sheet carries for the owner check before Forget erases a copy that still opens. */
private const val FORGET_REASON = "ลบกระเป๋าออกจากเครื่องนี้"

/** What the unlock screen shows. */
internal sealed interface UnlockView {
    /** Locked and waiting for the owner: before the first answer, or after a cancelled sheet, which needs no words. */
    data object Ask : UnlockView

    /** Locked after the OS refused the face or the code. */
    data object Failed : UnlockView

    /** Locked after Face ID locked out, so the next sheet asks for the phone's code. */
    data object LockedOut : UnlockView

    /** Locked, and the phone could not ask now. [tryLater]: it may on the next try. */
    data class Unavailable(val tryLater: Boolean) : UnlockView

    /** The saved copy can never open, so only Forget is offered. [forgetFailed]: a Forget on this screen could not erase it. */
    data class Recovery(val cause: Cause, val forgetFailed: Boolean) : UnlockView
}

/**
 * Pure. What the unlock screen shows for the gate's [boot] (Locked or Broken) and its [lastAttempt]. [attemptOnArrival]
 * is what lastAttempt held when the recovery screen appeared. The attempt that led there is no news on it, and the only
 * later one is a Forget whose erase failed, because the recovery screen never asks the OS.
 */
internal fun unlockView(boot: Boot, lastAttempt: UnlockResult?, attemptOnArrival: UnlockResult? = null): UnlockView =
    if (boot is Boot.Broken) {
        UnlockView.Recovery(boot.cause, forgetFailed = lastAttempt != null && lastAttempt !== attemptOnArrival)
    } else {
        when (lastAttempt) {
            UnlockResult.Denied -> UnlockView.Failed
            UnlockResult.LockedOut -> UnlockView.LockedOut
            is UnlockResult.Unavailable -> UnlockView.Unavailable(tryLater = lastAttempt.why == Why.TryLater)
            // The gate never keeps an Opened, a Verified or a Broken as the last attempt.
            null, UnlockResult.Cancelled, UnlockResult.Verified, is UnlockResult.Opened, is UnlockResult.Broken -> UnlockView.Ask
        }
    }

/**
 * The cold start's unlock and recovery screen. WalletGate shows it before the nav host exists. It only asks the gate,
 * so it never holds a record and shows no word, fingerprint or xpub. The Locked states reuse the in-session unlock
 * overlay's tile, failed banner and text. Not a route.
 */
@Composable
internal fun UnlockScreen(gate: WalletGateState) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val boot = gate.boot
    val attemptOnArrival = remember(boot) { gate.lastAttempt }
    val view = unlockView(boot, gate.lastAttempt, attemptOnArrival)

    // One attempt at a time, like the overlay, which also greys its button while the OS sheet is up.
    fun ask(attempt: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                attempt()
            } finally {
                busy = false
            }
        }
    }

    // Asks once when it appears. A cancelled sheet leaves the button.
    LaunchedEffect(Unit) { ask { gate.unlock(UNLOCK_REASON) } }

    if (view is UnlockView.Recovery) {
        MebitScreen(
            eyebrow = null,
            title = "เปิดกระเป๋าไม่ได้",
            onBack = null,
            onOpenCatalog = {},
            tag = "screen_unlock",
            bottom = {
                Spacer(modifier = Modifier.height(12.dp))
                PrimaryCta("ใช้ 24 คำแทน", "cta_unlock_forget", { confirming = true })
                // The height of BottomActions' link row, so the button sits where the Locked screen's does, clear of the debug link.
                Spacer(modifier = Modifier.height(38.dp))
            },
        ) {
            Text(
                text = when (view.cause) {
                    Cause.Missing -> "ไม่พบกระเป๋าที่บันทึกไว้ในเครื่องนี้ · นำเข้า 24 คำอีกครั้ง"
                    Cause.KeyGone -> "ระบบล็อกของเครื่องนี้เปิดกระเป๋าที่บันทึกไว้ไม่ได้อีกแล้ว · นำเข้า 24 คำเพื่อกู้คืน"
                    Cause.Corrupt -> "อ่านกระเป๋าที่บันทึกไว้ไม่ได้ · นำเข้า 24 คำเพื่อกู้คืน"
                    Cause.NewerVersion -> "กระเป๋านี้บันทึกโดย mebit เวอร์ชันใหม่กว่า · อัปเดตแอปแล้วเปิดอีกครั้ง"
                    Cause.UnsupportedNetwork -> "แอปเวอร์ชันนี้ยังไม่รองรับเครือข่ายของกระเป๋านี้ · อัปเดตแอปแล้วเปิดอีกครั้ง"
                },
                color = MbBody,
                fontSize = 13.5.sp,
                lineHeight = 20.sp,
                modifier = Modifier.testTag("unlock_broken"),
            )
            if (view.forgetFailed) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "ลบกระเป๋าออกจากเครื่องนี้ไม่สำเร็จ · ลองอีกครั้ง",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.testTag("forget_failed"),
                )
            }
        }
    } else {
        val failed = view == UnlockView.Failed || view == UnlockView.LockedOut
        val words = LockWords(getPlatform().biometricName)
        MebitScreen(
            eyebrow = null,
            title = if (failed) words.failedTitle else "ปลดล็อก mebit",
            // The overlay's counts after one failed result (iOS reports it after two misses) and after a lockout. The OS owns the real counter.
            subtitle = when (view) {
                UnlockView.Failed -> words.failedSubtitle(2)
                UnlockView.LockedOut -> words.failedSubtitle(5)
                else -> words.unlockSubtitle
            },
            onBack = null,
            onOpenCatalog = {},
            tag = "screen_unlock",
            bottom = {
                BottomActions(
                    primaryLabel = when (view) {
                        UnlockView.Failed -> "ลองอีกครั้ง"
                        UnlockView.LockedOut -> words.codeCta
                        else -> words.unlockCta
                    },
                    primaryTag = "cta_unlock",
                    onPrimary = { ask { gate.unlock(UNLOCK_REASON) } },
                    linkLabel = "ใช้ 24 คำแทน",
                    linkTag = "cta_unlock_forget",
                    onLink = { confirming = true },
                    enabled = !busy,
                )
            },
        ) {
            Spacer(modifier = Modifier.height(65.dp))
            FaceIdTile(failed = failed, words = words)
            Spacer(modifier = Modifier.height(50.dp))
            when (view) {
                UnlockView.Failed -> FailedBanner(attemptsLeft = 3, words = words)
                UnlockView.LockedOut -> FailedBanner(attemptsLeft = 0, words = words)
                is UnlockView.Unavailable -> Text(
                    text = if (view.tryLater) "ยังเปิดกระเป๋าไม่ได้ตอนนี้ · ลองอีกครั้ง" else "เครื่องนี้เปิดกระเป๋าไม่ได้ตอนนี้ · ลองอีกครั้ง หรือใช้ 24 คำแทน",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.testTag("lock_unavailable"),
                )
                else -> Unit
            }
        }
    }

    if (confirming) {
        ForgetSheet(
            newerApp = (view as? UnlockView.Recovery)?.cause == Cause.NewerVersion,
            onConfirm = {
                confirming = false
                ask { gate.forget(FORGET_REASON) }
            },
            onCancel = { confirming = false },
        )
    }
}

/** The confirm before Forget, in the style of Import's fingerprint sheet. [newerApp]: a newer mebit could still open this copy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForgetSheet(newerApp: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MbWhite,
        modifier = Modifier.testTag("dialog_forget"),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text("ลบกระเป๋าทั้งหมดออกจากเครื่องนี้ไหม", color = MbNavyInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "กระเป๋าทุกใบ Vault และกุญแจจะถูกลบออกจากเครื่องนี้ · มีแค่ 24 คำที่นำกลับมาได้" +
                    if (newerApp) "\nถ้าอัปเดต mebit ก่อน อาจเปิดกระเป๋านี้ได้" else "",
                color = MbBody,
                fontSize = 13.sp,
                lineHeight = 19.5.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            PrimaryCta("ลบออกจากเครื่องนี้", "cta_forget_confirm", onConfirm)
            TextButton(
                onClick = onCancel,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.fillMaxWidth().height(40.dp).testTag("cta_forget_cancel"),
            ) {
                Text("ยกเลิก", color = MbNavy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
