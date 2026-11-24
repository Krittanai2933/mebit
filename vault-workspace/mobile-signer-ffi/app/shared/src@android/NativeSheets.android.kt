package com.mebit.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.mebit.app.ui.components.LocalCovered

// Below 0x10000, where ComponentActivity's result registry starts numbering, so no launcher shares it.
private const val CHOOSER_REQUEST = 0x5A1E

/**
 * Opens the system share sheet with [intent]. The chooser is an activity of the system's, drawn above the app, so it runs for a result
 * under [CHOOSER_REQUEST] and [CloseNativeSheetsWhileCovered] ends it with Activity.finishActivity. CoverableWindowsRuleTest fails on a
 * chooser opened anywhere else.
 */
internal fun startChooser(context: Context, intent: Intent) {
    val chooser = Intent.createChooser(intent, null)
    val activity = context.activity()
    // Composition always has an activity, so the second branch, a chooser in a task of its own that nothing can close, is a fallback only.
    if (activity != null) activity.startActivityForResult(chooser, CHOOSER_REQUEST) else context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Ends the chooser and any share target it started under [CHOOSER_REQUEST]. Does nothing when neither is open. */
@Composable
actual fun CloseNativeSheetsWhileCovered() {
    val activity = LocalContext.current.activity()
    val covered = LocalCovered.current
    LaunchedEffect(covered, activity) { if (covered) activity?.finishActivity(CHOOSER_REQUEST) }
}

private fun Context.activity(): Activity? = generateSequence(this) { (it as? ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? Activity }
