package com.mebit.app.navigation

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mebit.app.data.KeyringStore
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.SendDraft
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.data.VaultDraft
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
import com.mebit.app.ui.components.ScreenStub

private val ImplementedRoutes = setOf(
    MovieRoute.Welcome,
    MovieRoute.KeySource,
    MovieRoute.KeyCreated,
    MovieRoute.BackupWords,
    MovieRoute.VerifyWords,
    MovieRoute.AppLock,
    MovieRoute.HomeFirstRun,
    MovieRoute.HwBackupConfirm,
    MovieRoute.AddKey,
    MovieRoute.PairMebitOther,
    MovieRoute.HardwareWallet,
    MovieRoute.VerifyKey,
    MovieRoute.Keyring,
    MovieRoute.KeyringFirstVault,
    MovieRoute.Policy,
    MovieRoute.ChooseKeys,
    MovieRoute.Review,
    MovieRoute.VaultCreated,
    MovieRoute.VaultList,
    MovieRoute.VaultDetail,
    MovieRoute.T13UnsupportedModel,
    MovieRoute.T14VerifyMismatch,
    MovieRoute.T15KeyringEmpty,
    MovieRoute.T15UnverifiedFirst,
    MovieRoute.T15DeleteBlocked,
    MovieRoute.T16PolicyDecaying,
    MovieRoute.T16InvalidMn,
    MovieRoute.T17CountMismatch,
    MovieRoute.T18BackendError,
    MovieRoute.T20VaultActions,
    MovieRoute.S1HomeSingleSig,
    MovieRoute.S2ReceiveBackupGate,
    MovieRoute.S3Receive,
    MovieRoute.S4Send,
    MovieRoute.S5ReviewSign,
    MovieRoute.S6Sent,
    MovieRoute.S6SentSlip,
    MovieRoute.S6SentBlock,
    MovieRoute.S6SentPoster,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MebitNavHost() {
    val backStack = remember { mutableStateListOf(MovieRoute.start) }
    var catalogOpen by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val session = remember { OnboardingSession() }
    val keyring = remember { KeyringStore() }
    val vaultDraft = remember { VaultDraft() }
    val singleSig = remember { SingleSigWallet() }
    val sendDraft = remember { SendDraft() }
    val current = backStack.last()

    fun navigate(route: MovieRoute) {
        importing = false
        if (route == MovieRoute.Keyring || route == MovieRoute.KeyringFirstVault) {
            keyring.importVerifiedFromOnboarding(session)
        }
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    fun goBack() {
        if (importing) {
            importing = false
            return
        }
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    val onBack: (() -> Unit)? = if (backStack.size > 1 || importing) ({ goBack() }) else null
    val openCatalog = { catalogOpen = true }

    when {
        importing -> ImportSeedScreen(
            session = session,
            onRestored = { navigate(MovieRoute.KeyCreated) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.Welcome -> WelcomeScreen(
            onCreate = { navigate(MovieRoute.KeySource) },
            onImport = { importing = true },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.KeySource -> KeySourceScreen(
            session = session,
            onPhone = { navigate(MovieRoute.KeyCreated) },
            onHardware = { navigate(MovieRoute.HwBackupConfirm) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.KeyCreated -> KeyCreatedScreen(
            session = session,
            onBackup = { navigate(MovieRoute.BackupWords) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.BackupWords -> BackupWordsScreen(
            session = session,
            onVerifiedReady = { navigate(MovieRoute.VerifyWords) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.VerifyWords -> VerifyWordsScreen(
            session = session,
            onVerified = { navigate(MovieRoute.AppLock) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.AppLock -> AppLockScreen(
            session = session,
            onContinue = { navigate(MovieRoute.HomeFirstRun) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.HomeFirstRun -> HomeFirstRunScreen(
            session = session,
            onSingleSig = { navigate(MovieRoute.S1HomeSingleSig) },
            onVault = { navigate(MovieRoute.KeyringFirstVault) },
            onLater = { navigate(MovieRoute.VaultList) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.HwBackupConfirm -> HwBackupConfirmScreen(
            onDone = { navigate(MovieRoute.HardwareWallet) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.AddKey -> AddKeyScreen(
            store = keyring,
            onThisDevice = { navigate(MovieRoute.VerifyKey) },
            onMebitOther = { navigate(MovieRoute.PairMebitOther) },
            onHardware = { navigate(MovieRoute.HardwareWallet) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.PairMebitOther -> PairMebitOtherScreen(
            store = keyring,
            onPaired = { navigate(MovieRoute.VerifyKey) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.HardwareWallet -> HardwareWalletScreen(
            store = keyring,
            onEnrolled = { navigate(MovieRoute.VerifyKey) },
            onUnsupported = { navigate(MovieRoute.T13UnsupportedModel) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.VerifyKey -> VerifyKeyScreen(
            store = keyring,
            onMatched = { navigate(MovieRoute.Keyring) },
            onMismatch = { navigate(MovieRoute.T14VerifyMismatch) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.Keyring -> KeyringScreen(
            store = keyring,
            onAddKey = { navigate(MovieRoute.AddKey) },
            onFirstVault = { navigate(MovieRoute.KeyringFirstVault) },
            onDeleteBlocked = { navigate(MovieRoute.T15DeleteBlocked) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.KeyringFirstVault -> KeyringFirstVaultScreen(
            store = keyring,
            onContinuePolicy = { navigate(MovieRoute.Policy) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.Policy -> PolicyScreen(
            draft = vaultDraft,
            onContinue = { navigate(MovieRoute.ChooseKeys) },
            onDecaying = { navigate(MovieRoute.T16PolicyDecaying) },
            onInvalidMn = { navigate(MovieRoute.T16InvalidMn) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.ChooseKeys -> ChooseKeysScreen(
            draft = vaultDraft,
            store = keyring,
            onContinue = { navigate(MovieRoute.Review) },
            onCountMismatch = { navigate(MovieRoute.T17CountMismatch) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.Review -> ReviewScreen(
            draft = vaultDraft,
            store = keyring,
            onCreated = { navigate(MovieRoute.VaultCreated) },
            onBackendError = { navigate(MovieRoute.T18BackendError) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.VaultCreated -> VaultCreatedScreen(
            draft = vaultDraft,
            onOpenVaults = { navigate(MovieRoute.VaultList) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.VaultList -> VaultListScreen(
            draft = vaultDraft,
            onOpenDetail = { navigate(MovieRoute.VaultDetail) },
            onCreate = { navigate(MovieRoute.KeyringFirstVault) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.VaultDetail -> VaultDetailScreen(
            draft = vaultDraft,
            onReceive = { /* address on this screen is the receive path */ },
            onSend = { navigate(MovieRoute.M1SendVault) },
            onActions = { navigate(MovieRoute.T20VaultActions) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.T13UnsupportedModel -> T13UnsupportedModelScreen(
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.T14VerifyMismatch -> T14VerifyMismatchScreen(
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.T15KeyringEmpty -> T15KeyringEmptyScreen(
            onAddKey = { navigate(MovieRoute.AddKey) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.T15UnverifiedFirst -> T15UnverifiedFirstScreen(
            onVerify = { navigate(MovieRoute.VerifyKey) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.T15DeleteBlocked -> T15DeleteBlockedScreen(
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.T16PolicyDecaying -> T16PolicyDecayingScreen(
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.T16InvalidMn -> T16InvalidMnScreen(
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.T17CountMismatch -> T17CountMismatchScreen(
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.T18BackendError -> T18BackendErrorScreen(
            onRetry = { navigate(MovieRoute.Review) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.T20VaultActions -> T20VaultActionsScreen(
            draft = vaultDraft,
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.S1HomeSingleSig -> S1HomeSingleSigScreen(
            wallet = singleSig,
            onReceive = { navigate(MovieRoute.S2ReceiveBackupGate) },
            onSend = { navigate(MovieRoute.S4Send) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.S2ReceiveBackupGate -> S2ReceiveBackupGateScreen(
            session = session,
            onBackedUp = { navigate(MovieRoute.S3Receive) },
            onGoBackup = { navigate(MovieRoute.BackupWords) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.S3Receive -> S3ReceiveScreen(
            wallet = singleSig,
            onDone = { navigate(MovieRoute.S1HomeSingleSig) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.S4Send -> S4SendScreen(
            wallet = singleSig,
            draft = sendDraft,
            onContinue = { navigate(MovieRoute.S5ReviewSign) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.S5ReviewSign -> S5ReviewSignScreen(
            wallet = singleSig,
            draft = sendDraft,
            onSigned = { navigate(MovieRoute.S6Sent) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.S6Sent -> S6SentScreen(
            wallet = singleSig,
            draft = sendDraft,
            variant = "default",
            onHome = { navigate(MovieRoute.S1HomeSingleSig) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.S6SentSlip -> S6SentScreen(
            wallet = singleSig,
            draft = sendDraft,
            variant = "slip",
            onHome = { navigate(MovieRoute.S1HomeSingleSig) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.S6SentBlock -> S6SentScreen(
            wallet = singleSig,
            draft = sendDraft,
            variant = "block",
            onHome = { navigate(MovieRoute.S1HomeSingleSig) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        current == MovieRoute.S6SentPoster -> S6SentScreen(
            wallet = singleSig,
            draft = sendDraft,
            variant = "poster",
            onHome = { navigate(MovieRoute.S1HomeSingleSig) },
            onBack = onBack,
            onOpenCatalog = openCatalog,
        )
        else -> ScreenStub(
            route = current,
            onBack = onBack,
            onNext = { navigate(current.nextInDemo()) },
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
}
