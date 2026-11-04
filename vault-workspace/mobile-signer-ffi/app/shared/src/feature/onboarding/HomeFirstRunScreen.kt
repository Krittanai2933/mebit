package com.mebit.app.feature.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.data.formatBtcCompact
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_chevron
import com.mebit.app.resources.ic_warning
import com.mebit.app.resources.mebit_logo_dark
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbGreenWash
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbPage
import com.mebit.app.ui.theme.MbSuccess
import com.mebit.app.ui.theme.MbWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

/** One 8dp key mark inside a card's key tile: filled signs, ring is a backup (Penpot 1B.6). */
private data class KeyMark(val x: Int, val backup: Boolean = false)

/**
 * Penpot Organize 1B.6 Home · first run. The hero shows the single-sig balance, or a dash until
 * the chain answers, so an offline start never shows a made-up number. There is no fiat line:
 * the app has no price feed.
 */
@Composable
fun HomeFirstRunScreen(
    session: OnboardingSession,
    wallet: SingleSigWallet,
    onSingleSig: () -> Unit,
    onVault: () -> Unit,
    onBackup: () -> Unit,
    onOpenCatalog: () -> Unit,
) {
    // The snapshot is a blocking network call: keep it off the main thread.
    LaunchedEffect(session.words, session.fingerprint, session.passphrase) {
        if (session.words.isNotEmpty()) withContext(Dispatchers.Default) { wallet.bindSession(session) }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MbPage)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp)
            .testTag("screen_home/first-run"),
    ) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Image(
                painter = painterResource(Res.drawable.mebit_logo_dark),
                contentDescription = "mebit",
                modifier = Modifier.size(width = 74.dp, height = 19.dp),
            )
            Spacer(modifier = Modifier.height(25.dp))
            if (!session.backupConfirmed) {
                BackupNudge(onBackup)
                Spacer(modifier = Modifier.height(14.dp))
            }
            NotSavedNotice(session, Modifier.padding(bottom = 14.dp))
            BalanceHero(heroBalanceText(wallet.balanceSats, wallet.live), zero = wallet.live && wallet.balanceSats == 0L)
            Spacer(modifier = Modifier.height(24.dp))
            Text("จะเริ่มใช้แบบไหน", color = MbNavyInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("เลือกตอนนี้ หรือสร้าง Vault ทีหลังก็ได้", color = MbBody, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(16.dp))
            ChoiceCard(
                title = "กระเป๋ากุญแจเดียว",
                tag = "พร้อมใช้",
                tagBackground = MbGreenWash,
                tagInk = MbSuccess,
                desc = "ใช้กุญแจ iPhone ของฉันที่เพิ่งสร้าง รับ-ส่งได้ทันที เหมาะกับเงินใช้จ่าย",
                marks = listOf(KeyMark(20)),
                testTag = "cta_singlesig",
                onClick = onSingleSig,
            )
            Spacer(modifier = Modifier.height(12.dp))
            ChoiceCard(
                title = "Vault หลายกุญแจ",
                tag = "เหมาะกับเงินก้อน",
                tagBackground = MbIconWash,
                tagInk = MbEyebrow,
                desc = "เพิ่ม Jade, Trezor Safe 7 หรือมือถืออีกเครื่อง กุญแจหายหนึ่งดอกเงินยังปลอดภัย",
                marks = listOf(KeyMark(8), KeyMark(20), KeyMark(32, backup = true)),
                testTag = "cta_vault",
                onClick = onVault,
            )
        }
        TextButton(
            onClick = onOpenCatalog,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.fillMaxWidth().height(22.dp).testTag("open_catalog"),
        ) {
            Text("รายการหน้าทั้งหมด (debug)", color = MbBody, fontSize = 12.sp)
        }
    }
}

/** The hero's amount, every sat that matters, and a dash until the chain answers. [BalanceHero] adds the unit itself. */
internal fun heroBalanceText(sats: Long, live: Boolean): String = if (live) formatBtcCompact(sats) else "—"

/** Penpot 1.3.2: sits above the balance until the 24 words are backed up. */
@Composable
private fun BackupNudge(onBackup: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MbIconWash)
            .clickable(role = Role.Button, onClick = onBackup)
            .padding(horizontal = 16.dp)
            .testTag("cta_backup_now"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(Res.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("ยังไม่ได้สำรองกุญแจ", color = MbNavyInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("ต้องสำรอง 24 คำก่อนรับเงินครั้งแรก", color = MbEyebrow, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        }
        Text("สำรองเลย", color = MbNavy, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
    }
}

/** [zero] shows the baht line only for an empty wallet: with no price feed, zero is the one amount known in baht. */
@Composable
private fun BalanceHero(balance: String, zero: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MbNavy)
            .padding(horizontal = 22.dp, vertical = 20.dp)
            .testTag("home_balance_hero"),
    ) {
        Text("ยอดรวมทุกกระเป๋า", color = MbWhite.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = balance,
                color = MbWhite,
                fontSize = 38.sp,
                lineHeight = 53.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp,
                modifier = Modifier.testTag("home_balance"),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "BTC",
                color = MbWhite.copy(alpha = 0.8f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (zero) Text("≈ ฿0", color = Color(0xFFEEB42A), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChoiceCard(
    title: String,
    tag: String,
    tagBackground: Color,
    tagInk: Color,
    desc: String,
    marks: List<KeyMark>,
    testTag: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .dropShadow(shape, Shadow(radius = 26.dp, color = MbNavy, spread = (-16).dp, offset = DpOffset(0.dp, 12.dp), alpha = 0.18f))
            .clip(shape)
            .background(MbWhite)
            .border(1.dp, MbBorder, shape)
            .clickable(onClick = onClick)
            .padding(16.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(MbIconWash)) {
            marks.forEach { mark ->
                val dot = Modifier.offset(mark.x.dp, 20.dp).size(8.dp)
                Box(
                    if (mark.backup) dot.border(1.5.dp, MbEyebrow.copy(alpha = 0.6f), CircleShape)
                    else dot.background(MbBtcOrange, CircleShape),
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, color = MbNavyInk, fontSize = 16.5.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = tag,
                    color = tagInk,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).background(tagBackground).padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(desc, color = MbBody, fontSize = 12.5.sp, lineHeight = 18.75.sp)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Image(painterResource(Res.drawable.ic_chevron), contentDescription = null, modifier = Modifier.size(width = 10.dp, height = 16.dp))
    }
}
