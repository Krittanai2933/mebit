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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.PairedDevice
import com.mebit.app.data.SaveState
import com.mebit.app.data.StartNotice
import com.mebit.app.resources.Res
import com.mebit.app.resources.mebit_logo
import com.mebit.app.ui.components.BackChip
import com.mebit.app.ui.components.CardFan
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.OptionCard
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta
import com.mebit.app.ui.theme.MbGold
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbWhite
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import com.mebit.app.data.VerifyQuestion
import com.mebit.app.data.verifyQuestions
import com.mebit.app.resources.ic_tick_white
import com.mebit.app.ui.components.BottomActions
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbGreenWash
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbMuted
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbPage
import com.mebit.app.ui.theme.MbPick
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbSuccess
import com.mebit.app.ui.theme.MbBody
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.semantics
import com.mebit.app.CaptureClaim
import com.mebit.app.hideWhen
import com.mebit.app.rememberCaptureGuard
import com.mebit.app.resources.ic_warning
import com.mebit.app.ui.components.IssueBanner

/** [notice]: the one-time line after this launch's start erased or lost a wallet, null on every other launch. */
@Composable
fun WelcomeScreen(
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
    notice: StartNotice? = null,
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
            if (notice != null) {
                Text(
                    text = when (notice) {
                        StartNotice.OrphanWiped -> "กระเป๋าจากการติดตั้งครั้งก่อนถูกลบแล้ว · นำเข้า 24 คำเพื่อกู้คืน"
                        StartNotice.WalletVanished -> "เครื่องนี้ไม่มีกระเป๋าบันทึกไว้แล้ว · นำเข้า 24 คำเพื่อกู้คืน"
                    },
                    color = MbWhite.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.testTag("welcome_notice"),
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
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
            // Penpot 1.1 draws both CTAs 56dp tall, not the 54dp default.
            PrimaryCta("สร้างกระเป๋าใหม่", "cta_create", onCreate, darkSurface = true, height = 56.dp)
            Spacer(modifier = Modifier.height(20.dp))
            SecondaryCta("นำเข้ากระเป๋าเดิม", "cta_import", onImport, darkSurface = true, height = 56.dp)
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
        bottom = {
            Text(
                text = "ไม่แน่ใจ? เริ่มจากมือถือก่อน แล้วค่อยเพิ่ม Jade หรือ Trezor เป็นกุญแจดอกที่สองตอนยกระดับเป็น Vault",
                color = MbSlate,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Spacer(modifier = Modifier.height(18.dp))
        },
    ) {
        Spacer(modifier = Modifier.height(9.dp))
        OptionCard(
            title = "มือถือเครื่องนี้",
            body = "สร้างกุญแจใหม่ในชิปของเครื่อง สำรองด้วย 24 คำ",
            tag = "cta_phone",
            leading = {
                Box(Modifier.size(44.dp)) {
                    Box(Modifier.offset(15.dp, 10.dp).size(15.dp, 23.dp).border(2.dp, MbNavy, RoundedCornerShape(4.dp)))
                }
            },
            onClick = {
                session.generateThisDevice()
                if (session.lastError == null && session.words.isNotEmpty()) onPhone()
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        OptionCard(
            title = "Hardware wallet",
            body = "กุญแจอยู่บนอุปกรณ์ตลอด มือถือแค่ดูยอดและส่งให้อุปกรณ์เซ็น",
            tag = "cta_hardware",
            chips = listOf("Jade", "Trezor Safe 7"),
            chipsTrailing = "เชื่อมผ่าน Bluetooth",
            leading = {
                Box(Modifier.size(44.dp)) {
                    Box(Modifier.offset(11.dp, 16.dp).size(22.dp, 14.dp).border(2.dp, MbNavy, RoundedCornerShape(3.dp)))
                    Box(Modifier.offset(27.dp, 21.dp).size(5.dp).background(MbBtcOrange, CircleShape))
                }
            },
            onClick = onHardware,
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
    onSkip: () -> Unit,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "NEW WALLET · ขั้น 1 / 3",
        title = "สร้างกุญแจแล้ว",
        // The key exists now, so there is nothing to go back to.
        onBack = null,
        onOpenCatalog = onOpenCatalog,
        subtitle = "กุญแจดอกนี้เก็บอยู่ในชิปความปลอดภัยของเครื่องนี้เท่านั้น ไม่ถูกส่งออกไปที่ไหน รวมถึง mebit",
        tag = "screen_onboarding/key-created",
        bottom = { BottomActions("สำรองตอนนี้", "cta_backup", onBackup, "ทำทีหลัง", "cta_skip_backup", onSkip) },
    ) {
        Spacer(modifier = Modifier.height(19.dp))
        KeyCard(
            monogram = "iOS",
            monogramInk = MbNavy,
            title = "iPhone ของฉัน",
            meta = "mebit บนเครื่องนี้ · " + session.fingerprint.chunked(4).joinToString(" ").ifBlank { "—" },
            metaTag = "fingerprint",
            badge = "ยังไม่สำรอง",
            badgeInk = MbEyebrow,
            badgeBackground = MbIconWash,
        )
        Spacer(modifier = Modifier.height(20.dp))
        NextStepCard()
        // Not on board 1.3: an iOS reinstall wipes the wallet (the user's decision), so say so where it is made.
        DeleteWarning(session, "delete_warning")
        NotSavedNotice(session, Modifier.padding(top = 16.dp))
    }
}

/** Said where the key is made and where its words are read: once the wallet is on the phone, deleting the app erases it and the words are the only way back. */
@Composable
private fun DeleteWarning(session: OnboardingSession, tag: String) {
    if (session.saved != SaveState.Saved) return
    Text(
        text = "ลบแอปแล้วกระเป๋าในเครื่องนี้จะหาย · กู้คืนได้ด้วย 24 คำเท่านั้น",
        color = MbBody,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier.padding(top = 16.dp).testTag(tag),
    )
}

/** Penpot 1.3 and 1.2.1 key card: monogram tile, name, one meta line and a status pill. */
@Composable
private fun KeyCard(
    monogram: String,
    monogramInk: Color,
    title: String,
    meta: String,
    metaTag: String,
    badge: String,
    badgeInk: Color,
    badgeBackground: Color,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .shadow(4.dp, shape, clip = false)
            .clip(shape)
            .background(MbWhite)
            .border(1.dp, MbBorder, shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MbIconWash),
            contentAlignment = Alignment.Center,
        ) {
            Text(monogram, color = monogramInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f).padding(bottom = 4.dp)) {
            Text(title, color = MbNavyInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(meta, color = MbSlate, fontSize = 12.5.sp, modifier = Modifier.testTag(metaTag))
        }
        Text(
            text = badge,
            color = badgeInk,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).background(badgeBackground).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun NextStepCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MbChip)
            .padding(start = 20.dp, end = 20.dp, top = 15.dp),
    ) {
        Text("ต่อไป · สำรองกุญแจ 24 คำ", color = MbEyebrow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "ถ้าเครื่องหายหรือพัง 24 คำนี้คือทางเดียวที่จะกู้กระเป๋าคืน mebit กู้ให้ไม่ได้ เพราะเราไม่ได้ถือกุญแจของคุณ",
            color = MbMuted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Bullet("ใช้เวลาราว 5 นาที", MbNavy, MbNavyInk, FontWeight.Medium)
        Spacer(modifier = Modifier.height(6.dp))
        Bullet("เตรียมกระดาษกับปากกา ห้ามถ่ายภาพหน้าจอ", MbNavy, MbNavyInk, FontWeight.Medium)
        Spacer(modifier = Modifier.height(6.dp))
        Bullet("ข้ามได้ แต่ต้องสำรองก่อนรับเงินครั้งแรก", MbBtcOrange, MbEyebrow, FontWeight.SemiBold)
    }
}

@Composable
private fun Bullet(text: String, dot: Color, ink: Color, weight: FontWeight) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 2.dp)) {
        Box(Modifier.size(6.dp).background(dot, CircleShape))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, color = ink, fontSize = 13.5.sp, fontWeight = weight)
    }
}

@Composable
fun BackupWordsScreen(
    session: OnboardingSession,
    onVerifiedReady: () -> Unit,
    onSkip: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val guard = rememberCaptureGuard()
    // Penpot 1.4 and 1.4.1: a passphrase for the key just created, offered in first setup only.
    val offerPassphrase = session.inFirstSetup && !session.imported
    var sheetOpen by remember { mutableStateOf(false) }
    MebitScreen(
        eyebrow = "BACKUP · ขั้น 2 / 3",
        title = "จด 24 คำนี้ลงกระดาษ",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        subtitle = "เรียงตามลำดับเลข ใครได้คำเหล่านี้ไปจะใช้เงินในกระเป๋าได้ทันที ห้ามถ่ายภาพหน้าจอหรือเก็บในแชต",
        tag = "screen_onboarding/backup-words",
        bottom = {
            BottomActions("จดครบแล้ว ไปยืนยัน", "cta_to_verify", onVerifiedReady, "ทำทีหลัง", "cta_skip_backup", onSkip, enabled = session.words.size >= 12)
        },
    ) {
        Spacer(modifier = Modifier.height(19.dp))
        val passphraseRow: (@Composable () -> Unit)? = if (offerPassphrase) {
            {
                PassphraseOption(
                    set = session.passphrase.isNotEmpty(),
                    onOpen = { sheetOpen = true },
                    onClear = { session.changeNewKeyPassphrase("") },
                )
            }
        } else {
            null
        }
        WordGrid(session.words, Modifier.hideWhen(guard), passphraseSet = session.passphrase.isNotEmpty(), passphrase = passphraseRow)
        session.lastError?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error"))
        }
        Spacer(modifier = Modifier.height(14.dp))
        if (guard.screenshotTaken) {
            IssueBanner("ถ่ายภาพหน้าจอ seed แล้ว", "สร้าง seed ใหม่ อย่าใช้ seed นี้", "screenshot_taken")
        } else {
            CaptureBanner(guard.claim)
        }
        // Not on board 1.4. A user who reads the words here is the one who must know the words are the only recovery.
        DeleteWarning(session, "backup_delete_warning")
    }
    if (sheetOpen) {
        PassphraseSheet(
            initial = session.passphrase,
            copy = CreatePassphraseCopy,
            guard = guard,
            onUse = {
                session.changeNewKeyPassphrase(it)
                sheetOpen = false
            },
            onNone = {
                session.changeNewKeyPassphrase("")
                sheetOpen = false
            },
            onDismiss = { sheetOpen = false },
        )
    }
}

/** Penpot 1.4.2: the user sets a passphrase for a new wallet, so the text differs from Import's. */
private val CreatePassphraseCopy = PassphraseCopy(
    body = "passphrase คือคำที่ 25 ที่คุณตั้งเอง ใช้คู่กับ 24 คำ ตอนกู้กระเป๋าต้องใส่ให้ตรงทุกตัวอักษร ไม่ตั้งก็ได้",
    warning = "ลืม passphrase จะกู้กระเป๋านี้ไม่ได้ · จดไว้คู่กับ 24 คำ",
    none = "ไม่ตั้ง passphrase",
)

/** Penpot 1.5.4: a new wallet has nothing to compare with, so the user writes the fingerprint down. */
internal val CreateFingerprintCopy = FingerprintCopy(
    title = "จด fingerprint นี้ไว้",
    body = "ค่านี้มาจาก 24 คำกับ passphrase ของคุณ จดไว้คู่กับ 24 คำ ตอนกู้กระเป๋า ถ้าได้ค่าไม่ตรง แปลว่า passphrase หรือ 24 คำพิมพ์ผิด",
    ok = "จดแล้ว · ไปต่อ",
    reject = "แก้ passphrase",
)

/**
 * Penpot 1.4: the phrase in three columns of eight, numbered down each column like the Import grid. With [passphrase] the
 * row sits 15 under the grid and the card grows to 357, and to 374 once set (1.4.1), as the Import card does.
 */
@Composable
private fun WordGrid(words: List<String>, modifier: Modifier = Modifier, passphraseSet: Boolean = false, passphrase: (@Composable () -> Unit)? = null) {
    val card = RoundedCornerShape(20.dp)
    val height = when {
        passphrase == null -> 320
        passphraseSet -> 374
        else -> 357
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(card)
            .background(MbWhite)
            .border(1.dp, MbBorder, card)
            .padding(16.dp),
    ) {
        WordColumns(words)
        if (passphrase != null) {
            Spacer(modifier = Modifier.height(15.dp))
            passphrase()
        }
    }
}

@Composable
private fun WordColumns(words: List<String>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { column ->
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(8) { row ->
                    val index = column * 8 + row
                    val slot = RoundedCornerShape(10.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth().height(30.dp).clip(slot).background(MbPage).padding(start = 10.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text((index + 1).toString().padStart(2, '0'), color = MbSlate, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(words.getOrElse(index) { "" }, color = MbNavyInk, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.testTag("word_$index"))
                    }
                }
            }
        }
    }
}

/** Penpot 1.4: the banner under the grid. */
@Composable
private fun CaptureBanner(claim: CaptureClaim) {
    val text = when (claim) {
        CaptureClaim.BlocksScreenshots -> "แอปจะบล็อกการถ่ายภาพหน้าจอระหว่างแสดงคำ"
        CaptureClaim.HidesWhileRecording -> "ถ้า seed ถูกถ่ายแล้ว โปรดสร้าง seed ใหม่"
        CaptureClaim.None -> return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MbIconWash)
            .padding(horizontal = 16.dp, vertical = 13.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("capture_banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(Res.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, color = MbEyebrow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun VerifyWordsScreen(
    session: OnboardingSession,
    onVerified: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val guard = rememberCaptureGuard()
    val quiz = remember(session.words) { verifyQuestions(session.words) }
    val picks = remember(quiz) { mutableStateListOf<String?>().apply { repeat(quiz.size) { add(null) } } }
    var wrong by remember { mutableStateOf(false) }
    MebitScreen(
        eyebrow = "BACKUP · ขั้น 2 / 3",
        title = "ยืนยันว่าจดถูก",
        subtitle = "ดูจากกระดาษที่จดไว้ แล้วเลือกคำให้ตรงกับเลขลำดับ",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_onboarding/verify-words",
        bottom = {
            if (quiz.isEmpty()) {
                PrimaryCta("ต่อไป", "cta_skip_verify", onVerified)
            } else {
                BottomActions(
                    primaryLabel = "ยืนยัน",
                    primaryTag = "cta_verify",
                    onPrimary = {
                        if (quiz.indices.all { picks[it] == quiz[it].answer }) {
                            session.markBackupConfirmed()
                            onVerified()
                        } else {
                            wrong = true
                        }
                    },
                    linkLabel = "กลับไปดูคำอีกครั้ง",
                    linkTag = "cta_back_to_words",
                    onLink = onBack,
                    enabled = picks.none { it == null },
                )
            }
        },
    ) {
        if (quiz.isEmpty()) {
            Text("ไม่มีคำให้ยืนยัน", color = MbBody, fontSize = 13.sp)
            return@MebitScreen
        }
        Spacer(modifier = Modifier.height(39.dp))
        Column(modifier = Modifier.hideWhen(guard)) {
            quiz.forEachIndexed { i, question ->
                if (i > 0) Spacer(modifier = Modifier.height(12.dp))
                QuestionCard(question, picks[i]) { word ->
                    picks[i] = word
                    wrong = false
                }
            }
        }
        if (wrong) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("ไม่ถูก · ลองใหม่", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("verify_error"))
        }
    }
}

@Composable
private fun QuestionCard(question: VerifyQuestion, picked: String?, onPick: (String) -> Unit) {
    val card = RoundedCornerShape(20.dp)
    val option = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier.fillMaxWidth().clip(card).background(MbWhite).border(1.dp, MbBorder, card).padding(16.dp),
    ) {
        Text("คำที่ " + (question.position + 1).toString().padStart(2, '0'), color = MbSlate, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            question.options.forEach { word ->
                val on = word == picked
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(option)
                        .background(if (on) MbNavy else MbWhite)
                        .then(if (on) Modifier else Modifier.border(1.dp, MbBorder, option))
                        .clickable(role = Role.Button) { onPick(word) }
                        .testTag("chip_$word"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(word, color = if (on) MbWhite else MbNavyInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Penpot 1.2.1. It follows Confirm Key, so [device] is the hardware key just saved. The debug catalog can
 * open it with none saved, and the card is then left out.
 */
@Composable
fun HwBackupConfirmScreen(
    device: PairedDevice?,
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    var noted by remember { mutableStateOf(false) }
    var kept by remember { mutableStateOf(false) }
    val vendor = device?.vendor ?: "อุปกรณ์"
    MebitScreen(
        eyebrow = "HARDWARE WALLET · ขั้น 2 / 3",
        title = "สำรอง seed แล้วหรือยัง",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        subtitle = "seed ของอุปกรณ์อยู่บนตัวเครื่องเท่านั้น mebit มองไม่เห็นและกู้ให้ไม่ได้ ถ้าอุปกรณ์หายหรือพัง ต้องใช้ seed ที่จดไว้",
        tag = "screen_onboarding/hw-backup-confirm",
        // The board's link points at a how-to the app does not have, so it stays plain text.
        bottom = {
            BottomActions("ยืนยัน ไปต่อ", "cta_hw_backup_done", onDone, "ยังไม่ได้สำรอง · ดูวิธีตั้งค่าในคู่มือของ $vendor", "hw_backup_howto", null, enabled = noted && kept, linkColor = MbBody)
        },
    ) {
        Spacer(modifier = Modifier.height(19.dp))
        if (device != null) {
            KeyCard(
                monogram = device.monogram,
                monogramInk = MbEyebrow,
                title = device.name,
                meta = device.meta,
                metaTag = "hw_device_meta",
                badge = device.status,
                badgeInk = if (device.isMock) MbEyebrow else MbSuccess,
                badgeBackground = if (device.isMock) MbIconWash else MbGreenWash,
            )
            Spacer(modifier = Modifier.height(20.dp))
        }
        val card = RoundedCornerShape(20.dp)
        Column(
            modifier = Modifier.fillMaxWidth().clip(card).background(MbWhite).border(1.dp, MbBorder, card).padding(horizontal = 16.dp, vertical = 17.dp),
        ) {
            CheckRow("จด seed ตอนตั้งค่าอุปกรณ์ครบทุกคำแล้ว", noted, "check_seed_noted") { noted = it }
            Spacer(modifier = Modifier.height(17.dp))
            Box(Modifier.padding(start = 38.dp).fillMaxWidth().height(1.dp).background(MbBorder))
            Spacer(modifier = Modifier.height(16.dp))
            CheckRow("เก็บกระดาษไว้คนละที่กับตัวอุปกรณ์", kept, "check_paper_apart") { kept = it }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "กระเป๋านี้รับเงินได้ทันที ส่งเงินต้องเชื่อม $vendor มาเซ็นทุกครั้ง มือถือไม่ได้ถือกุญแจ",
            color = MbMuted,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MbChip).padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val box = RoundedCornerShape(7.dp)
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(box)
                .background(if (checked) MbNavy else MbWhite)
                .then(if (checked) Modifier else Modifier.border(1.5.dp, MbPick, box)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Image(painterResource(Res.drawable.ic_tick_white), contentDescription = null, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(label, color = MbNavyInk, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
    }
}
