package com.mebit.app.navigation

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mebit.app.data.AppLockState
import com.mebit.app.data.KeyringStore
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.OwnerCheck
import com.mebit.app.data.MultisigSendDraft
import com.mebit.app.data.Replacement
import com.mebit.app.data.SendDraft
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.data.StartNotice
import com.mebit.app.data.VaultDraft
import com.mebit.app.data.liveQuoteCache
import com.mebit.app.data.pairedDevice
import com.mebit.app.feature.keys.AddKeyScreen
import com.mebit.app.feature.keys.HardwareWalletScreen
import com.mebit.app.feature.keys.KeyringFirstVaultScreen
import com.mebit.app.feature.keys.KeyringScreen
import com.mebit.app.feature.keys.PairMebitOtherScreen
import com.mebit.app.feature.keys.T13UnsupportedModelScreen
import com.mebit.app.feature.keys.T14VerifyMismatchScreen
import com.mebit.app.feature.keys.T15DeleteBlockedScreen
import com.mebit.app.feature.keys.T15KeyringEmptyScreen
import com.mebit.app.feature.keys.T15UnverifiedFirstScreen
import com.mebit.app.feature.keys.VerifyKeyScreen
import com.mebit.app.feature.onboarding.AppLockScreen
import com.mebit.app.feature.onboarding.BackupWordsScreen
import com.mebit.app.feature.onboarding.HomeFirstRunScreen
import com.mebit.app.feature.onboarding.HwBackupConfirmScreen
import com.mebit.app.feature.onboarding.ImportSeedScreen
import com.mebit.app.feature.onboarding.KeyCreatedScreen
import com.mebit.app.feature.onboarding.KeySourceScreen
import com.mebit.app.feature.onboarding.LockMode
import com.mebit.app.feature.onboarding.PinSheet
import com.mebit.app.feature.onboarding.SpendingPinScreen
import com.mebit.app.feature.onboarding.VerifyWordsScreen
import com.mebit.app.feature.onboarding.WelcomeScreen
import com.mebit.app.feature.vault.ChooseKeysScreen
import com.mebit.app.feature.vault.PolicyScreen
import com.mebit.app.feature.vault.ReviewScreen
import com.mebit.app.feature.vault.T16InvalidMnScreen
import com.mebit.app.feature.vault.T16PolicyDecayingScreen
import com.mebit.app.feature.vault.T17CountMismatchScreen
import com.mebit.app.feature.vault.T18BackendErrorScreen
import com.mebit.app.feature.vault.T20VaultActionsScreen
import com.mebit.app.feature.vault.VaultCreatedScreen
import com.mebit.app.feature.vault.VaultDetailScreen
import com.mebit.app.feature.vault.VaultListScreen
import com.mebit.app.feature.send.S1HomeSingleSigScreen
import com.mebit.app.feature.send.S2ReceiveBackupGateScreen
import com.mebit.app.feature.send.S3ReceiveScreen
import com.mebit.app.feature.send.S4SendScreen
import com.mebit.app.feature.send.S5ReviewSignScreen
import com.mebit.app.feature.send.S6SentScreen
import com.mebit.app.feature.send.S7HistoryScreen
import com.mebit.app.feature.send.S8TxDetailScreen
import com.mebit.app.feature.send.DEMO_TX
import com.mebit.app.feature.send.M1SendVaultScreen
import com.mebit.app.feature.send.M2CollectSignaturesScreen
import com.mebit.app.feature.send.M3SignOnJadeScreen
import com.mebit.app.feature.send.M4ReadyToBroadcastScreen
import com.mebit.app.rememberBiometrics
import com.mebit.app.rememberIsDebugBuild
import com.mebit.app.ui.components.coversScreen
import com.mebit.app.ui.theme.MbPage
import com.mebit.ffi.signer.HotTx
import kotlinx.coroutines.launch

private val ImplementedRoutes = MovieRoute.entries.toSet()

/** [WalletGate] passes the session, the route a saved wallet opens at and Welcome's [startNotice]. A bare call is memory only and starts at Welcome. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MebitNavHost(
    session: OnboardingSession = remember { OnboardingSession() },
    start: MovieRoute = MovieRoute.start,
    startNotice: StartNotice? = null,
) {
    val backStack = remember { mutableStateListOf(start) }
    var catalogOpen by remember { mutableStateOf(false) }
    val keyring = remember { KeyringStore() }
    val vaultDraft = remember { VaultDraft() }
    val singleSig = remember { SingleSigWallet() }
    val quotes = remember { liveQuoteCache() }
    val sendDraft = remember { SendDraft() }
    val multisigSend = remember { MultisigSendDraft() }
    val phone = rememberBiometrics()
    // The gates' owner check, which is the phone's own or, on a phone without one, the Spending PIN (open item 79).
    val biometrics = remember(phone, session) { OwnerCheck(phone, session) }
    val appLock = remember { AppLockState() }
    val scope = rememberCoroutineScope()
    // Starts as Keyring so the debug catalog can open Verify Key without passing Key Source.
    var keyFlow by remember { mutableStateOf(KeyFlow.Keyring) }
    var verifyRetryRoute by remember { mutableStateOf(MovieRoute.HardwareWallet) }
    var unsupportedModel by remember { mutableStateOf<String?>(null) }
    // The row a tap opened, whole, so the detail draws at once. The catalog opens the demo row until a tap.
    var openedTx by remember { mutableStateOf(DEMO_TX) }
    val current = backStack.last()

    // More than a minute away locks the app again, once the user turned the lock on or where the Spending PIN guards it.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { appLock.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { appLock.onForeground(biometrics.locksWhenAway) }

    fun navigate(route: MovieRoute) {
        keyFlow = keyFlowAfter(route, keyFlow)
        if (route == MovieRoute.Keyring || route == MovieRoute.KeyringFirstVault) {
            keyring.importVerifiedFromOnboarding(session)
        }
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    fun returnToKeySource(sourceRoute: MovieRoute) {
        val retryStack = keyRetryBackStack(backStack, sourceRoute)
        backStack.clear()
        backStack.addAll(retryStack)
    }

    fun goBack() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    // After create or import comes App Lock on a phone with an owner check, else the Spending PIN, set before Home.
    fun lockStep() = navigate(if (biometrics.needsPin) MovieRoute.SpendingPin else MovieRoute.AppLock)

    // A backup started from the Home banner returns there. The create flow goes on to the lock step.
    fun afterBackup() = if (MovieRoute.HomeFirstRun in backStack) navigate(MovieRoute.HomeFirstRun) else lockStep()

    // Once onboarding is over the words open past the Spending PIN where it guards the session, and as before elsewhere.
    fun openWords() {
        scope.launch { if (biometrics.guardWords("ดู 24 คำสำรอง")) navigate(MovieRoute.BackupWords) }
    }

    val openTx: (HotTx) -> Unit = { tx ->
        openedTx = tx
        navigate(MovieRoute.S8TxDetail)
    }

    // Posting a guarded payment's bytes again asks the owner as signing does, so a stranger cannot push out a payment the owner gave up on.
    val confirmRepost: suspend () -> Boolean = { appLock.confirm(biometrics, session.appLockEnabled, "ยืนยันการส่งรายการเดิมอีกครั้ง") }

    // A fee bump or a cancel signs a new transaction, so it asks the owner as signing does, with its own reason.
    val confirmReplacement: suspend (Replacement) -> Boolean = { kind ->
        val reason = when (kind) {
            Replacement.FeeBump -> "ยืนยันการเพิ่มค่าธรรมเนียมแล้วส่งใหม่"
            Replacement.Cancel -> "ยืนยันการยกเลิกรายการ"
        }
        appLock.confirm(biometrics, session.appLockEnabled, reason)
    }

    val onBack: (() -> Unit)? = if (backStack.size > 1) ({ goBack() }) else null
    // The link is the only caller, and it is gone in a release build. This keeps the sheet shut even if another caller appears.
    val debugBuild = rememberIsDebugBuild()
    val openCatalog = { if (debugBuild) catalogOpen = true }

    when (current) {
        MovieRoute.Welcome -> WelcomeScreen(
            onCreate = { navigate(MovieRoute.KeySource) },
            onImport = { navigate(MovieRoute.ImportSeed) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
            notice = startNotice,
        )
        MovieRoute.KeySource -> KeySourceScreen(
            session = session,
            onPhone = { navigate(MovieRoute.KeyCreated) },
            onHardware = { navigate(MovieRoute.HardwareWallet) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.ImportSeed -> ImportSeedScreen(
            session = session,
            // An import skips backup: the user typed the words from their own record.
            onRestored = { lockStep() },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.KeyCreated -> KeyCreatedScreen(
            session = session,
            onBackup = { navigate(MovieRoute.BackupWords) },
            onSkip = { lockStep() },
            onOpenCatalog = openCatalog,
        )
        MovieRoute.BackupWords -> BackupWordsScreen(
            session = session,
            onVerifiedReady = { navigate(MovieRoute.VerifyWords) },
            onSkip = { afterBackup() },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.VerifyWords -> VerifyWordsScreen(
            session = session,
            onVerified = { afterBackup() },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.AppLock -> AppLockScreen(
            session = session,
            biometrics = biometrics,
            mode = LockMode.Enable,
            // A lock removed while this screen showed leaves the phone with no owner check, so its PIN comes before Home.
            onDone = { navigate(if (biometrics.needsPin) MovieRoute.SpendingPin else MovieRoute.HomeFirstRun) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.SpendingPin -> SpendingPinScreen(
            session = session,
            onDone = { navigate(MovieRoute.HomeFirstRun) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.HomeFirstRun -> HomeFirstRunScreen(
            session = session,
            wallet = singleSig,
            onSingleSig = { navigate(MovieRoute.S1HomeSingleSig) },
            onVault = { navigate(MovieRoute.KeyringFirstVault) },
            onBackup = { openWords() },
            onOpenCatalog = openCatalog,
        )
        MovieRoute.HwBackupConfirm -> HwBackupConfirmScreen(
            device = pairedDevice(keyring.keys),
            onDone = {
                session.markBackupConfirmed()
                navigate(MovieRoute.HomeFirstRun)
            },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.AddKey -> AddKeyScreen(
            store = keyring,
            onThisDevice = { navigate(MovieRoute.VerifyKey) },
            onMebitOther = { navigate(MovieRoute.PairMebitOther) },
            onHardware = { navigate(MovieRoute.HardwareWallet) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.PairMebitOther -> PairMebitOtherScreen(
            store = keyring,
            onPaired = { navigate(MovieRoute.VerifyKey) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.HardwareWallet -> HardwareWalletScreen(
            store = keyring,
            onEnrolled = { navigate(MovieRoute.VerifyKey) },
            onUnsupported = { model ->
                unsupportedModel = model
                navigate(MovieRoute.T13UnsupportedModel)
            },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.VerifyKey -> VerifyKeyScreen(
            store = keyring,
            onMatched = { navigate(routeAfterVerify(keyFlow)) },
            onMismatch = { source ->
                verifyRetryRoute = routeBeforeVerify(source, keyFlow)
                navigate(MovieRoute.T14VerifyMismatch)
            },
            onReconnect = { source -> returnToKeySource(routeBeforeVerify(source, keyFlow)) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.Keyring -> KeyringScreen(
            store = keyring,
            onAddKey = { navigate(MovieRoute.AddKey) },
            onFirstVault = { navigate(MovieRoute.KeyringFirstVault) },
            onDeleteBlocked = { navigate(MovieRoute.T15DeleteBlocked) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.KeyringFirstVault -> KeyringFirstVaultScreen(
            store = keyring,
            onContinuePolicy = { navigate(MovieRoute.Policy) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.Policy -> PolicyScreen(
            draft = vaultDraft,
            onContinue = { navigate(MovieRoute.ChooseKeys) },
            onDecaying = { navigate(MovieRoute.T16PolicyDecaying) },
            onInvalidMn = { navigate(MovieRoute.T16InvalidMn) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.ChooseKeys -> ChooseKeysScreen(
            draft = vaultDraft,
            store = keyring,
            onContinue = { navigate(MovieRoute.Review) },
            onCountMismatch = { navigate(MovieRoute.T17CountMismatch) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.Review -> ReviewScreen(
            draft = vaultDraft,
            store = keyring,
            onCreated = { navigate(MovieRoute.VaultCreated) },
            onBackendError = { navigate(MovieRoute.T18BackendError) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.VaultCreated -> VaultCreatedScreen(
            draft = vaultDraft,
            onOpenVaults = { navigate(MovieRoute.VaultList) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.VaultList -> VaultListScreen(
            draft = vaultDraft,
            onOpenDetail = { navigate(MovieRoute.VaultDetail) },
            onCreate = { navigate(MovieRoute.KeyringFirstVault) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.VaultDetail -> VaultDetailScreen(
            draft = vaultDraft,
            onReceive = { /* address on this screen is the receive path */ },
            onSend = { navigate(MovieRoute.M1SendVault) },
            onActions = { navigate(MovieRoute.T20VaultActions) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.T13UnsupportedModel -> T13UnsupportedModelScreen(
            model = unsupportedModel,
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.T14VerifyMismatch -> T14VerifyMismatchScreen(
            onBack = { returnToKeySource(verifyRetryRoute) },
            onOpenCatalog = openCatalog,
        )
        MovieRoute.T15KeyringEmpty -> T15KeyringEmptyScreen(
            onAddKey = { navigate(MovieRoute.AddKey) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.T15UnverifiedFirst -> T15UnverifiedFirstScreen(
            onVerify = { navigate(MovieRoute.VerifyKey) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.T15DeleteBlocked -> T15DeleteBlockedScreen(
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.T16PolicyDecaying -> T16PolicyDecayingScreen(
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.T16InvalidMn -> T16InvalidMnScreen(
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.T17CountMismatch -> T17CountMismatchScreen(
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.T18BackendError -> T18BackendErrorScreen(
            onRetry = { navigate(MovieRoute.Review) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.T20VaultActions -> T20VaultActionsScreen(
            draft = vaultDraft,
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.S1HomeSingleSig -> S1HomeSingleSigScreen(
            wallet = singleSig,
            session = session,
            quotes = quotes,
            onReceive = { navigate(if (session.backupConfirmed) MovieRoute.S3Receive else MovieRoute.S2ReceiveBackupGate) },
            onSend = {
                sendDraft.reset()
                navigate(MovieRoute.S4Send)
            },
            onVault = { navigate(MovieRoute.KeyringFirstVault) },
            onHistory = { navigate(MovieRoute.S7History) },
            onOpenTx = openTx,
            onOpenCatalog = openCatalog,
            onChangePin = if (session.pin.isSet) ({ scope.launch { biometrics.changePin("เปลี่ยน PIN") } }) else null,
        )
        MovieRoute.S7History -> S7HistoryScreen(
            wallet = singleSig,
            onOpenTx = openTx,
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.S8TxDetail -> S8TxDetailScreen(
            tx = openedTx,
            wallet = singleSig,
            session = session,
            quotes = quotes,
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.S2ReceiveBackupGate -> S2ReceiveBackupGateScreen(
            wallet = singleSig,
            session = session,
            quotes = quotes,
            onBackedUp = { navigate(MovieRoute.S3Receive) },
            onGoBackup = { openWords() },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.S3Receive -> S3ReceiveScreen(
            wallet = singleSig,
            session = session,
            onSend = {
                sendDraft.reset()
                navigate(MovieRoute.S4Send)
            },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.S4Send -> S4SendScreen(
            wallet = singleSig,
            session = session,
            draft = sendDraft,
            quotes = quotes,
            confirmRepost = confirmRepost,
            confirmReplacement = confirmReplacement,
            onContinue = { navigate(MovieRoute.S5ReviewSign) },
            onReceive = { navigate(if (session.backupConfirmed) MovieRoute.S3Receive else MovieRoute.S2ReceiveBackupGate) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.S5ReviewSign -> S5ReviewSignScreen(
            wallet = singleSig,
            session = session,
            draft = sendDraft,
            quotes = quotes,
            confirm = { appLock.confirm(biometrics, session.appLockEnabled, "ยืนยันการเซ็นส่งเงิน") },
            confirmRepost = confirmRepost,
            confirmReplacement = confirmReplacement,
            onSigned = { navigate(MovieRoute.S6SentSlip) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.S6Sent -> S6SentScreen(
            wallet = singleSig,
            draft = sendDraft,
            quotes = quotes,
            variant = "default",
            onHome = { navigate(MovieRoute.S1HomeSingleSig) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.S6SentSlip -> S6SentScreen(
            wallet = singleSig,
            draft = sendDraft,
            quotes = quotes,
            variant = "slip",
            onHome = { navigate(MovieRoute.S1HomeSingleSig) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.S6SentBlock -> S6SentScreen(
            wallet = singleSig,
            draft = sendDraft,
            quotes = quotes,
            variant = "block",
            onHome = { navigate(MovieRoute.S1HomeSingleSig) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.S6SentPoster -> S6SentScreen(
            wallet = singleSig,
            draft = sendDraft,
            quotes = quotes,
            variant = "poster",
            onHome = { navigate(MovieRoute.S1HomeSingleSig) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.M1SendVault -> M1SendVaultScreen(
            vault = vaultDraft.selectedVault(),
            draft = multisigSend,
            onContinue = { navigate(MovieRoute.M2CollectSignatures) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.M2CollectSignatures -> M2CollectSignaturesScreen(
            store = keyring,
            draft = multisigSend,
            onSignJade = { navigate(MovieRoute.M3SignOnJade) },
            onReady = { navigate(MovieRoute.M4ReadyToBroadcast) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.M3SignOnJade -> M3SignOnJadeScreen(
            draft = multisigSend,
            onSigned = { navigate(MovieRoute.M2CollectSignatures) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        MovieRoute.M4ReadyToBroadcast -> M4ReadyToBroadcastScreen(
            draft = multisigSend,
            onBroadcast = { navigate(MovieRoute.VaultDetail) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
    }

    if (catalogOpen) {
        ModalBottomSheet(
            onDismissRequest = { catalogOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            modifier = Modifier.testTag("route_catalog"),
        ) {
            Text(
                text = "MOVIE PAGE ROUTES",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.padding(bottom = 32.dp)) {
                items(MovieRoute.entries, key = { it.path }) { route ->
                    Text(
                        text = route.eyebrow + " · " + route.titleTh +
                            if (route in ImplementedRoutes) " ✓" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                catalogOpen = false
                                if (route == MovieRoute.T15DeleteBlocked && keyring.keys.isNotEmpty()) {
                                    keyring.markUsedInVault(keyring.keys.first().fingerprint)
                                }
                                navigate(route)
                            }
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                            .testTag("catalog_" + route.path),
                    )
                }
            }
        }
    }

    if (appLock.locked) {
        // A phone the Spending PIN guards unlocks only through the PIN sheet drawn over this cover, never through a check it lacks.
        val pinLock = biometrics.usesPin
        // Covers everything, swallows taps, and goes away only on Face ID, the device passcode or the PIN.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MbPage)
                .coversScreen(),
        ) {
            if (!pinLock) {
                AppLockScreen(
                    session = session,
                    biometrics = biometrics,
                    mode = LockMode.Unlock,
                    onDone = { appLock.unlock() },
                    onBack = null,
                    onOpenCatalog = {},
                )
            }
        }
        if (pinLock) LaunchedEffect(Unit) { if (biometrics.unlock("ปลดล็อก mebit")) appLock.unlock() }
    }

    biometrics.asking?.let { PinSheet(it, session.pin) }
}
