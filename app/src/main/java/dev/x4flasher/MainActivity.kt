package dev.x4flasher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { FlasherScreen() } } }
    }
}

private const val ACTION_USB_PERMISSION = "dev.x4flasher.USB_PERMISSION"

private val prober = UsbSerialProber(ProbeTable().apply {
    addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java) // ESP32-C3 USB Serial/JTAG
})

private suspend fun ensurePermission(ctx: Context, manager: UsbManager, device: UsbDevice): Boolean {
    if (manager.hasPermission(device)) return true
    return suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching { ctx.unregisterReceiver(this) }
                if (cont.isActive) cont.resume(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName), PendingIntent.FLAG_MUTABLE
        )
        manager.requestPermission(device, pi)
        cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }
    }
}

/** Flash `image` into app0 and blank otadata so the bootloader boots it. */
private suspend fun runFlash(
    ctx: Context, image: ByteArray, log: (String) -> Unit, onProgress: (Float) -> Unit
) = withContext(Dispatchers.IO) {
    ImageCheck.validate(image)?.let { throw EspError(it) }

    val manager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    val driver = prober.findAllDrivers(manager).firstOrNull()
        ?: throw EspError("No X4 found. Wake it, use a data-capable USB-C cable, then retry.")
    if (!ensurePermission(ctx, manager, driver.device)) throw EspError("USB permission denied.")
    val conn = manager.openDevice(driver.device) ?: throw EspError("Could not open the USB device.")

    val port = driver.ports[0]
    port.open(conn)
    try {
        port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        val loader = EspRomLoader(port, log)
        loader.enterBootloader()
        loader.sync()
        loader.identify()
        loader.configureFlash()

        log("Writing ${image.size / 1024} KB to app0 (0x10000)…")
        loader.writeFlash(ImageCheck.APP0_OFFSET, image, onProgress)

        log("Verifying MD5…")
        if (!loader.verifyMd5(ImageCheck.APP0_OFFSET, image))
            throw EspError("MD5 mismatch after write. NOT switching boot slot. Retry the flash.")

        log("Verified. Blanking otadata so app0 boots…")
        loader.blankOtadata()
        loader.finish()
        loader.hardReset()
        log("Done. If the screen stays blank: press reset, then hold Power 3–5 s.")
    } finally {
        runCatching { port.close() }
    }
}

private fun displayName(ctx: Context, uri: Uri): String =
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    } ?: uri.lastPathSegment ?: "firmware.bin"

@Composable
fun FlasherScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val logLines = remember { mutableStateListOf<String>() }
    var progress by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var image by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }

    fun log(s: String) { scope.launch(Dispatchers.Main) { logLines.add(s) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            fileName = displayName(ctx, uri)
            val err = bytes?.let { ImageCheck.validate(it) } ?: "Could not read file."
            if (err != null) { image = null; log("Rejected $fileName: $err") }
            else { image = bytes; log("Loaded $fileName (${bytes!!.size / 1024} KB).") }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("X4 Flasher", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Xteink X4 only (ESP32-C3). Flashes an app image into app0 over USB-C OTG. " +
                "Keep the X4 awake. Locked units: use the SD-card install instead.",
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Choose firmware .bin") }
        if (fileName.isNotEmpty()) Text(fileName, style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { confirm = true }, enabled = !busy && image != null) { Text("Flash to X4") }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(logLines.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Flash to X4?") },
        text = { Text("Overwrites the app0 slot and resets the boot selector. Don't unplug until it says Done.") },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = {
                confirm = false; busy = true; progress = 0f
                scope.launch {
                    try {
                        runFlash(ctx, image!!, { s -> log(s) }) { p -> scope.launch(Dispatchers.Main) { progress = p } }
                    } catch (e: Exception) {
                        log("ERROR: ${e.message}")
                    } finally { busy = false }
                }
            }) { Text("Flash") }
        }
    )
}
