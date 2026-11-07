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

private val SelectedRow = Color(0xFFFFF8EF)
private val RadioRing = Color(0xFFB4BAC4)

/** One row of Penpot 1.5.1: the type's name, its BIP and the first characters of its addresses on each network. */
internal class AddressTypeRow(val type: AddressType, val label: String, val bip: String, private val mainnet: String, private val testnet: String) {
    /** The detail line, with the prefixes of [network], so it agrees with the address Receive shows. */
    fun detail(network: KeyNetwork) = "$bip · เริ่มด้วย ${if (network == KeyNetwork.MAIN) mainnet else testnet}"
}

internal val AddressTypeRows = listOf(
    AddressTypeRow(AddressType.LEGACY, "Legacy", "BIP44", "1", "m หรือ n"),
    AddressTypeRow(AddressType.NESTED_SEGWIT, "Nested SegWit", "BIP49", "3", "2"),
    AddressTypeRow(AddressType.NATIVE_SEGWIT, "Native SegWit", "BIP84", "bc1q", "tb1q"),
    AddressTypeRow(AddressType.TAPROOT, "Taproot", "BIP86", "bc1p", "tb1p"),
)

/** Penpot 1.5.1's eyebrow, by the flow that reached the screen. */
internal fun addressTypeEyebrow(imported: Boolean) = if (imported) "IMPORT WALLET · ADDRESS TYPE" else "NEW WALLET · ADDRESS TYPE"

/**
 * Penpot 1.5.1, the same screen at 1.5.3, 1.3.2, 1B.4.5 and 1B.4.6, in first setup after the key is made or imported. "ต่อไป"
 * saves the choice, then a staged passphrase import shows its fingerprint to compare (1B.4.4), and a new key with a passphrase
 * after Verify Words shows its fingerprint to write down (1.5.4), before [onContinue]. Rejecting the import's fingerprint clears
 * the session and [onRejectImport] goes back to Import Seed; rejecting the new key's goes to [onEditPassphrase].
 */
@Composable
fun AddressTypeScreen(
    session: OnboardingSession,
    afterVerify: Boolean,
    onContinue: () -> Unit,
    onEditPassphrase: () -> Unit,
    onRejectImport: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    var selected by remember { mutableStateOf(session.addressType) }
    var sheet by remember { mutableStateOf<FingerprintCopy?>(null) }
    val next = {
        session.chooseAddressType(selected)
        when {
            session.saved == SaveState.Staged -> sheet = ImportFingerprintCopy
            afterVerify && session.passphrase.isNotEmpty() -> sheet = CreateFingerprintCopy
            else -> onContinue()
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
                TypeRow(row, session.network, row.type == selected) { selected = row.type }
            }
        }
    }
    sheet?.let { copy ->
        FingerprintSheet(
            session = session,
            copy = copy,
            onConfirm = {
                sheet = null
                if (copy == ImportFingerprintCopy) session.acceptStagedKey()
                // A save that lost a race to another wallet cleared the session, and Import Seed says why.
                if (session.lastError == null) onContinue() else onRejectImport()
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(shape)
            .background(if (selected) SelectedRow else MbWhite)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) MbBtcOrange else MbBorder, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(start = 18.dp, end = 18.dp)
            .testTag("address_type_" + row.type.name.lowercase()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, color = MbNavyInk, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                // The badge marks the default, so it stays on Native SegWit whatever is picked.
                if (row.type == AddressType.NATIVE_SEGWIT) {
                    Spacer(modifier = Modifier.width(11.dp))
                    Text("ค่าเริ่มต้น", color = MbEyebrow, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(row.detail(network), color = MbBody, fontSize = 11.2.sp)
        }
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(1.5.dp, if (selected) MbBtcOrange else RadioRing, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(MbBtcOrange))
        }
    }
}
