package com.mebit.app

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

private class AndroidSharer(private val context: Context) : Sharer {
    override fun text(text: String) = share(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        },
    )

    override fun image(png: ByteArray, name: String) {
        val folder = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(folder, safeShareName(name) + ".png").apply { writeBytes(png) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        share(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                // Lets the share sheet itself read the file for its preview, not only the app the user picks.
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }

    private fun share(intent: Intent) {
        val chooser = Intent.createChooser(intent, null)
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}

@Composable
actual fun rememberSharer(): Sharer {
    val context = LocalContext.current
    return remember(context) { AndroidSharer(context) }
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
