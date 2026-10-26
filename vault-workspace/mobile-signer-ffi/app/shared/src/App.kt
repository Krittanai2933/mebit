package com.mebit.app

import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.mebit.app.navigation.MebitNavHost
import com.mebit.app.ui.theme.MebitTheme
import org.jetbrains.compose.reload.DevelopmentEntryPoint

@Composable
@Preview
@DevelopmentEntryPoint
fun App() {
    MebitTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            MebitNavHost()
        }
    }
}
