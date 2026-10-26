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
import com.mebit.app.data.OnboardingSession
import com.mebit.app.feature.onboarding.AppLockScreen
import com.mebit.app.feature.onboarding.BackupWordsScreen
import com.mebit.app.feature.onboarding.HomeFirstRunScreen
import com.mebit.app.feature.onboarding.HwBackupConfirmScreen
import com.mebit.app.feature.onboarding.ImportSeedScreen
import com.mebit.app.feature.onboarding.KeyCreatedScreen
import com.mebit.app.feature.onboarding.KeySourceScreen
import com.mebit.app.feature.onboarding.VerifyWordsScreen
import com.mebit.app.feature.onboarding.WelcomeScreen
import com.mebit.app.ui.components.ScreenStub

private val OnboardingRoutes = setOf(
    MovieRoute.Welcome,
    MovieRoute.KeySource,
    MovieRoute.KeyCreated,
    MovieRoute.BackupWords,
    MovieRoute.VerifyWords,
    MovieRoute.AppLock,
    MovieRoute.HomeFirstRun,
    MovieRoute.HwBackupConfirm,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MebitNavHost() {
    val backStack = remember { mutableStateListOf(MovieRoute.start) }
    var catalogOpen by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val session = remember { OnboardingSession() }
    val current = backStack.last()

    fun navigate(route: MovieRoute) {
        importing = false
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
                            if (route in OnboardingRoutes) " ✓" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                catalogOpen = false
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
