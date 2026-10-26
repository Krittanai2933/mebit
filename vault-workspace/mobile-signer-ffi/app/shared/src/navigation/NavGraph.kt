package com.mebit.app.navigation

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mebit.app.ui.components.ScreenStub

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MebitNavHost() {
    val backStack = remember { mutableStateListOf(MovieRoute.start) }
    var catalogOpen by remember { mutableStateOf(false) }
    val current = backStack.last()

    fun navigate(route: MovieRoute) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    fun goBack() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    ScreenStub(
        route = current,
        onBack = if (backStack.size > 1) ({ goBack() }) else null,
        onNext = { navigate(current.nextInDemo()) },
        onOpenCatalog = { catalogOpen = true },
    )

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
                        text = route.eyebrow + " · " + route.titleTh,
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
