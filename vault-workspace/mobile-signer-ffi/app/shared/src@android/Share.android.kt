package com.mebit.app

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

private class AndroidSharer(private val context: Context) : Sharer {
    override fun text(text: String) = startChooser(
        context,
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        },
    )

    override fun image(png: ByteArray, name: String) = shareFile(context, safeShareName(name) + ".png", png, "image/png")
}

private fun startChooser(context: Context, intent: Intent) {
    val chooser = Intent.createChooser(intent, null)
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

/** Writes [bytes] as `cacheDir/share/[fileName]`, the one folder the FileProvider serves, and opens the share sheet with that file. */
private fun shareFile(context: Context, fileName: String, bytes: ByteArray, mime: String) {
    val folder = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(folder, fileName).apply { writeBytes(bytes) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    startChooser(
        context,
        Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            // Lets the share sheet itself read the file for its preview, not only the app the user picks.
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
    )
}

@Composable
actual fun rememberSharer(): Sharer {
    val context = LocalContext.current
    return remember(context) { AndroidSharer(context) }
}

// shortcut: the picker may rename a file saved as this type. Octet-stream keeps ".bsms" where text/plain may add ".txt". Switch if a provider renames it.
private const val BSMS_SAVE_MIME = "application/octet-stream"

@Composable
actual fun rememberTextFileExport(onResult: (ExportOutcome) -> Unit): TextFileExport {
    val context = LocalContext.current
    val current by rememberUpdatedState(onResult)
    // The dialog answers with a URI only, so the text it writes waits here. Memory only.
    var waiting by remember { mutableStateOf<String?>(null) }
    val dialog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BSMS_SAVE_MIME)) { uri ->
        val text = waiting
        waiting = null
        val error = when {
            uri == null -> null
            text == null -> "no text"
            else -> writeText(context, uri, text)
        }
        current(exportOutcome(chose = uri != null, error = error))
    }
    return remember(context, dialog) {
        object : TextFileExport {
            override fun save(name: String, text: String) {
                waiting = text
                try {
                    dialog.launch(name)
                } catch (e: Exception) {
                    waiting = null
                    current(ExportOutcome.Failed(e.message ?: "no dialog"))
                }
            }

            override fun share(name: String, text: String) {
                // Android's chooser never says whether the user picked a target, so it reports Done once it opens.
                current(
                    try {
                        shareFile(context, name, text.encodeToByteArray(), "text/plain")
                        ExportOutcome.Done
                    } catch (e: Exception) {
                        ExportOutcome.Failed(e.message ?: "share")
                    },
                )
            }
        }
    }
}

/** Null when [text] was written to [uri], else the reason it was not. */
private fun writeText(context: Context, uri: Uri, text: String): String? = try {
    checkNotNull(context.contentResolver.openOutputStream(uri)) { "no stream" }.use { it.write(text.encodeToByteArray()) }
    null
} catch (e: Exception) {
    e.message ?: "write"
}

actual fun ImageBitmap.encodePng(): ByteArray {
    val bitmap = asAndroidBitmap()
    // A captured graphics layer can be a hardware bitmap, which compress cannot read.
    val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE
    val readable = if (hardware) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    val out = ByteArrayOutputStream()
    check(readable.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Android could not encode the bitmap as PNG" }
    return out.toByteArray()
}
