package com.mebit.app.feature.send

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mebit.app.data.KeySigner
import com.mebit.app.data.SavedKey
import com.mebit.app.data.VaultSend
import com.mebit.app.feature.vault.deviceWord
import com.mebit.app.feature.vault.vaultKeyRows
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta
import com.mebit.ffi.signer.FoundWallet
import com.mebit.ffi.signer.JadeRegistration
import com.mebit.ffi.signer.SpendOutput
import com.mebit.ffi.signer.SpendReview
import com.mebit.ffi.signer.WalletKind

/**
 * Penpot 5.3.2 and 5.3.3 Sign on Jade and on Trezor, "ลายเซ็นที่ k จาก m", for the device key [signer] of the payment [send] holds, then 5.4.x
 * and 5.7.x once it signed, and the failure boards when it did not. The page of the one-key sign ([DeviceSignPage]) over
 * [VaultSend.signOnDevice], which looks for the kind's one device ([nearest]), has a Jade register the wallet at its first signature
 * (5.3.2.0's fourth step) and counts the signature toward m like a phone key's. k is the count of signatures when the screen opened.
 * ดำเนินการต่อ ([onContinue]) returns to 5.2. A claim the send refuses, because another signature holds it or the key is no longer needed,
 * returns there at once ([onBack]): nothing reached a device, and 5.2's rows say why. Back and ยกเลิกการเซ็น stop the run and leave; a
 * request the device still holds keeps the send claimed until it answers, which 5.2 says. A [sample] shows one state of the boards with its
 * sample data and does nothing: the debug catalog.
 */
@Composable
fun SignOnDeviceScreen(
    send: VaultSend?,
    signer: KeySigner.Device?,
    keyring: List<SavedKey>,
    nearest: suspend (WalletKind) -> FoundWallet,
    pairingKept: (String) -> Boolean,
    sample: DeviceSample?,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    if (sample != null) {
        val run = remember(sample) { DeviceSignRun(scope, sample.kind, sample.stage, sample.registers) }
        DeviceSignPage(
            run = run,
            word = deviceWord(sample.kind),
            keyName = sample.keyName,
            fingerprint = sample.fingerprint,
            eyebrow = "SIGN · ลายเซ็นที่ 1 จาก 2",
            signedSubtitle = SIGNED_SUBTITLE,
            review = SAMPLE_REVIEW,
            changeTo = SAMPLE_WALLET,
            registration = SignRegistration(SAMPLE_REGISTRATION, "m/48'/1'/0'/2'"),
            tag = "screen_send/m3",
            onBack = onBack,
            onOpenCatalog = onOpenCatalog,
            bottom = { DeviceSignButtons(run, onContinue, { onBack?.invoke() }) },
        )
        return
    }
    val payment = send?.payment
    if (send == null || signer == null || payment == null) {
        MebitScreen(eyebrow = "SIGN", title = "ตรวจสอบบนอุปกรณ์", onBack = onBack, onOpenCatalog = onOpenCatalog, tag = "screen_send/m3") {
            Text(NO_PAYMENT, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("m3_none"))
        }
        return
    }
    val vault = send.vault
    val kind = signer.kind
    // k is fixed when the screen opens: the signature this one adds does not renumber the page that shows it.
    val k = remember { send.signedFingerprints.size + 1 }
    val fingerprint = vault.keyFingerprints[signer.index]
    val run = remember { DeviceSignRun(scope, kind, notCounted = onBack) { codes, steps -> send.signOnDevice(signer, nearest(kind), codes, steps) } }
    val registration = remember { if (kind == WalletKind.JADE) send.jadeRegistration()?.let { SignRegistration(it, send.derivationOf(signer.index)) } else null }
    LaunchedEffect(Unit) { run.start() }
    val leave = onBack?.let { back ->
        {
            run.cancel()
            back()
        }
    }
    DeviceSignPage(
        run = run,
        word = deviceWord(kind),
        keyName = vaultKeyRows(vault, keyring)[signer.index].name,
        fingerprint = fingerprint,
        eyebrow = "SIGN · ลายเซ็นที่ $k จาก ${vault.m}",
        signedSubtitle = SIGNED_SUBTITLE,
        review = payment.review,
        changeTo = vault.name,
        registration = registration,
        pairingKept = remember { pairingKept(fingerprint) },
        tag = "screen_send/m3",
        onBack = leave,
        onOpenCatalog = onOpenCatalog,
        bottom = { DeviceSignButtons(run, onContinue, { leave?.invoke() }) },
    )
}

/** 5.4.2's line under the title once a several-key wallet's key signed. */
private const val SIGNED_SUBTITLE = "ลายเซ็นถูกส่งกลับมาที่ mebit แล้ว ตรวจรายการด้านล่างอีกครั้งก่อนไปต่อ"

/** The bottom of 5.3.2 to 5.7.3: ดำเนินการต่อ once signed, ลองอีกครั้ง over ยกเลิกการเซ็น after a failure, else ยกเลิกการเซ็น alone. */
@Composable
private fun ColumnScope.DeviceSignButtons(run: DeviceSignRun, onContinue: () -> Unit, onCancel: () -> Unit) {
    Spacer(modifier = Modifier.height(12.dp))
    when (run.stage) {
        SignStage.Signed -> PrimaryCta("ดำเนินการต่อ", "cta_m3_continue", onContinue)
        is SignStage.Failed -> {
            PrimaryCta("ลองอีกครั้ง", "cta_m3_retry", run::start)
            Spacer(modifier = Modifier.height(16.dp))
            SecondaryCta("ยกเลิกการเซ็น", "cta_m3_cancel", onCancel)
        }
        else -> SecondaryCta("ยกเลิกการเซ็น", "cta_m3_cancel", onCancel)
    }
    Spacer(modifier = Modifier.height(38.dp))
}

/**
 * One state of the sign boards with the boards' sample data, for the debug catalog: a screen to compare with its board without a device.
 * [tag] is the catalog entry's test tag suffix.
 */
enum class DeviceSample(
    val tag: String,
    val label: String,
    internal val kind: WalletKind,
    internal val stage: SignStage,
    internal val registers: Boolean = false,
) {
    JadeShowing("jade_showing", "5.3.2 Sign on Jade", WalletKind.JADE, SignStage.OnDevice),
    TrezorShowing("trezor_showing", "5.3.3 Sign on Trezor", WalletKind.TREZOR_SAFE7, SignStage.OnDevice),
    JadeConfirmed("jade_confirmed", "5.4.2 Sign on Jade · confirmed", WalletKind.JADE, SignStage.Signed),
    TrezorConfirmed("trezor_confirmed", "5.4.3 Sign on Trezor · confirmed", WalletKind.TREZOR_SAFE7, SignStage.Signed),
    JadeRegistering("jade_registering", "5.3.2.0 register Wallet", WalletKind.JADE, SignStage.Registering, registers = true),
    JadeRejected("jade_rejected", "5.3.2.1 Jade rejected", WalletKind.JADE, SignStage.Failed(DeviceFailure.Rejected, SignStep.Showing)),
    TrezorLost("trezor_lost", "5.3.3.1 Trezor connection lost", WalletKind.TREZOR_SAFE7, SignStage.Failed(DeviceFailure.Lost, SignStep.Showing)),
    RegistrationDeclined("registration_declined", "5.3.2.2 registration declined", WalletKind.JADE, SignStage.Failed(DeviceFailure.RegistrationDeclined, SignStep.Registering), registers = true),
    CannotRegister("cannot_register", "5.3.2.3 Jade cannot register", WalletKind.JADE, SignStage.Failed(DeviceFailure.RegistrationRefused, SignStep.Registering), registers = true),
    JadeNotReady("jade_not_ready", "5.3.2.4 Jade not ready", WalletKind.JADE, SignStage.Failed(DeviceFailure.NotReady, SignStep.Connecting)),
    AnswerRefused("answer_refused", "5.3.x.5 answer refused", WalletKind.JADE, SignStage.Failed(DeviceFailure.BadAnswer, SignStep.Showing)),
    TwoDevices("two_devices", "5.3.x.6 two devices nearby", WalletKind.JADE, SignStage.Failed(DeviceFailure.Several, SignStep.Connecting)),
    ;

    internal val keyName: String get() = if (kind == WalletKind.JADE) "Jade ของฉัน" else "Trezor ในตู้เซฟ"
    internal val fingerprint: String
        get() = when {
            kind == WalletKind.TREZOR_SAFE7 -> "c04d88e1"
            this == JadeRegistering -> "73c5da0a"
            else -> "7f2ca91b"
        }
}

private const val SAMPLE_WALLET = "Wallet ครอบครัว"
private val SAMPLE_REVIEW = SpendReview(
    "",
    "",
    listOf(SpendOutput("bc1q9h7a2kxv7tq35zcdw0lfp8xr4e8sm4kz", 5_000_000uL)),
    79_198_400uL,
    1_600uL,
    emptyList(),
    2u,
)
private val SAMPLE_REGISTRATION = JadeRegistration("mebit2of3-3f1d3", 2u, listOf("0d9250da", "61d264e9", "73c5da0a"))
