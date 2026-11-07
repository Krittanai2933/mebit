package com.mebit.app.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.SaveState
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbWhite
import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.hotAddressTypeEnabled

private val SelectedRow = Color(0xFFFFF8EF)
private val RadioRing = Color(0xFFB4BAC4)

/** One row of Penpot 1.5.1: the type's name, its BIP and the first characters of its addresses on each network. */
internal class AddressTypeRow(val type: AddressType, val label: String, val bip: String, private val mainnet: String, private val testnet: String) {
    /** The detail line, with the prefixes of [network], so it agrees with the address Receive shows. */
    fun detail(network: KeyNetwork) = "$bip · เริ่มด้วย ${if (network == KeyNetwork.MAIN) mainnet else testnet}"

    /** Whether the core takes this type on [network], by the rule its accounts enforce ([hotAddressTypeEnabled]): on mainnet only Native SegWit. */
    fun enabled(network: KeyNetwork) = hotAddressTypeEnabled(type, network)
}

/** The badge of a row the core does not take on this network, grey, in the default badge's place and style. Draft copy awaiting design review. */
internal const val NOT_ON_MAINNET = "ยังไม่รองรับบน Mainnet"

internal val AddressTypeRows = listOf(
    AddressTypeRow(AddressType.LEGACY, "Legacy", "BIP44", "1", "m หรือ n"),
    AddressTypeRow(AddressType.NESTED_SEGWIT, "Nested SegWit", "BIP49", "3", "2"),
    AddressTypeRow(AddressType.NATIVE_SEGWIT, "Native SegWit", "BIP84", "bc1q", "tb1q"),
    AddressTypeRow(AddressType.TAPROOT, "Taproot", "BIP86", "bc1p", "tb1p"),
)

/** Penpot 1.5.1's eyebrow, by the flow that reached the screen. */
internal fun addressTypeEyebrow(imported: Boolean) = if (imported) "IMPORT WALLET · ADDRESS TYPE" else "NEW WALLET · ADDRESS TYPE"

/**
 * Whether "ต่อไป" shows the new key's fingerprint to write down (1.5.4): a Create with a passphrase whose words were verified since that
 * passphrase was set, because setting one clears the backup. Saved facts, not the back stack, so the screen a restart brings back asks
 * the same.
 */
internal fun createFingerprintDue(session: OnboardingSession): Boolean =
    !session.imported && session.backupConfirmed && session.passphrase.isNotEmpty()

/**
 * Penpot 1.5.1, the same screen at 1.5.3, 1.3.2, 1B.4.5 and 1B.4.6, in first setup after the key is made or imported, and after a
 * restart that left the type unconfirmed. A row tap saves that type as the tentative pick. "ต่อไป" then shows a staged passphrase
 * import's fingerprint to compare (1B.4.4), or a new key's to write down when [createFingerprintDue], and leaving forward confirms the
 * type before [onContinue]. Rejecting the import's fingerprint clears the session and [onRejectImport] goes back to Import Seed;
 * rejecting the new key's goes to [onEditPassphrase] with the type still unconfirmed.
 */
@Composable
fun AddressTypeScreen(
    session: OnboardingSession,
    onContinue: () -> Unit,
    onEditPassphrase: () -> Unit,
    onRejectImport: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    var sheet by remember { mutableStateOf<FingerprintCopy?>(null) }
    val finish = {
        session.confirmAddressType()
        onContinue()
    }
    val next = {
        when {
            session.saved == SaveState.Staged -> sheet = ImportFingerprintCopy
            createFingerprintDue(session) -> sheet = CreateFingerprintCopy
            else -> finish()
        }
    }
    MebitScreen(
        eyebrow = addressTypeEyebrow(session.imported),
        title = "เลือกรูปแบบที่อยู่",
        subtitle = "กำหนดรูปแบบที่อยู่และเส้นทางกุญแจของกระเป๋านี้",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_onboarding/address-type",
        bottom = {
            Spacer(modifier = Modifier.height(12.dp))
            PrimaryCta("ต่อไป", "cta_address_type", next)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "ตัวเลือกนี้กำหนดรูปแบบที่อยู่ของกระเป๋านี้",
                color = MbSlate,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        // Board 1.5.1's first row is at y 225, 11 pt above the first card of Verify Words (board 1.5), which spaces 39.
        Spacer(modifier = Modifier.height(28.dp))
        Column(modifier = Modifier.selectableGroup()) {
            AddressTypeRows.forEachIndexed { i, row ->
                if (i > 0) Spacer(modifier = Modifier.height(8.dp))
                TypeRow(row, session.network, row.type == session.addressType) { session.chooseAddressType(row.type) }
            }
        }
    }
    sheet?.let { copy ->
        FingerprintSheet(
            session = session,
            copy = copy,
            onConfirm = {
                sheet = null
                if (copy == ImportFingerprintCopy) {
                    // Confirmed in memory first, so the import's one write saves the type as final.
                    session.confirmAddressType()
                    session.acceptStagedKey()
                }
                // A save that lost a race to another wallet cleared the session, and Import Seed says why.
                if (session.lastError == null) finish() else onRejectImport()
            },
            onReject = {
                sheet = null
                if (copy == ImportFingerprintCopy) {
                    session.clear()
                    onRejectImport()
                } else {
                    onEditPassphrase()
                }
            },
        )
    }
}

@Composable
private fun TypeRow(row: AddressTypeRow, network: KeyNetwork, selected: Boolean, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    // Read at render, so the rows follow the session's network.
    val enabled = row.enabled(network)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(shape)
            .background(if (selected) SelectedRow else MbWhite)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) MbBtcOrange else MbBorder, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(start = 18.dp, end = 18.dp)
            .testTag("address_type_" + row.type.name.lowercase()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, color = if (enabled) MbNavyInk else MbSlate, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                // The badge marks the default, so it stays on Native SegWit whatever is picked.
                val badge = when {
                    row.type == AddressType.NATIVE_SEGWIT -> "ค่าเริ่มต้น"
                    !enabled -> NOT_ON_MAINNET
                    else -> null
                }
                badge?.let {
                    Spacer(modifier = Modifier.width(11.dp))
                    Text(it, color = if (enabled) MbEyebrow else MbSlate, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(row.detail(network), color = if (enabled) MbBody else MbSlate, fontSize = 11.2.sp)
        }
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(1.5.dp, if (selected) MbBtcOrange else if (enabled) RadioRing else MbBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(MbBtcOrange))
        }
    }
}
