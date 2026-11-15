package com.mebit.app.ble

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

private const val SCAN = "android.permission.BLUETOOTH_SCAN"
private const val CONNECT = "android.permission.BLUETOOTH_CONNECT"
private const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"

/**
 * The runtime permissions Bluetooth needs at this API level. 31 and up: scan (the manifest says
 * neverForLocation) and connect. 30 and below: fine location; BLUETOOTH and BLUETOOTH_ADMIN are
 * install-time there.
 */
fun blePermissions(sdk: Int): List<String> =
    if (sdk >= 31) listOf(SCAN, CONNECT) else listOf(FINE_LOCATION)

fun missingBlePermissions(context: Context): List<String> =
    blePermissions(Build.VERSION.SDK_INT)
        .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

/**
 * 30 and below drop every scan result while Location services are off, so the scan ends in
 * `NotFound`, not in an error. 31 and up scan without Location because the manifest says
 * neverForLocation.
 */
@Suppress("DEPRECATION")
fun locationOffBlocksScans(context: Context): Boolean =
    Build.VERSION.SDK_INT < 31 &&
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) ==
        Settings.Secure.LOCATION_MODE_OFF

/**
 * Must run once, first, on a Java thread: ART then runs the library's `JNI_OnLoad`, which starts
 * btleplug. JNA's own load never does, and every Bluetooth export then answers `Unsupported`.
 */
fun loadBleLibraryIfPackaged() {
    try {
        System.loadLibrary("mobile_signer_ffi")
    } catch (error: UnsatisfiedLinkError) {
        Log.w("mebit.ble", "mobile_signer_ffi did not load: ${error.message}")
    }
}
