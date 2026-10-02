package dev.x4flasher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume

// ---------------- Look & feel ----------------

private val Amber = Color(0xFFFFB300)
private val Teal = Color(0xFF26C6DA)
private val Violet = Color(0xFF8B5CF6)
private val Mint = Color(0xFF34D399)
private val Rose = Color(0xFFF87171)
private val InkBg = Color(0xFF0E0F1A)
private val InkSurface = Color(0xFF171926)
private val InkSurface2 = Color(0xFF1F2233)

private val X4ColorScheme = darkColorScheme(
    primary = Violet,
    secondary = Teal,
    tertiary = Amber,
    background = InkBg,
    surface = InkSurface,
    surfaceVariant = InkSurface2,
    error = Rose,
    onPrimary = Color.White,
    onBackground = Color(0xFFE6E6F0),
    onSurface = Color(0xFFE6E6F0),
)

@Composable
private fun X4Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = X4ColorScheme, shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp),
    ), content = content)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { X4Theme { Surface(Modifier.fillMaxSize(), color = InkBg) { FlasherScreen() } } }
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

/** The bulk-IN endpoint the serial driver reads from (asked of the driver, else found by scanning). */
private fun readEndpointOf(port: UsbSerialPort, device: UsbDevice): UsbEndpoint? {
    val viaLib = runCatching { port.javaClass.getMethod("getReadEndpoint").invoke(port) as? UsbEndpoint }.getOrNull()
    if (viaLib != null) return viaLib
    for (i in 0 until device.interfaceCount) {
        val itf = device.getInterface(i)
        for (j in 0 until itf.endpointCount) {
            val e = itf.getEndpoint(j)
            if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK && e.direction == UsbConstants.USB_DIR_IN) return e
        }
    }
    return null
}

/** Opens the X4's USB port and runs [block] with a loader on it. */
private suspend fun <T> withLoader(ctx: Context, log: (String) -> Unit, block: (EspRomLoader) -> T): T =
    withContext(Dispatchers.IO) {
        val manager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = prober.findAllDrivers(manager).firstOrNull()
            ?: throw EspError("No X4 found. Wake it, use a data-capable USB-C cable, then retry.")
        if (!ensurePermission(ctx, manager, driver.device)) throw EspError("USB permission denied.")
        val conn = manager.openDevice(driver.device) ?: throw EspError("Could not open the USB device.")
        val port = driver.ports[0]
        port.open(conn)
        try {
            port.setParameters(921600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            val ep = if (UsbOptions.continuousReader) readEndpointOf(port, driver.device) else null
            val loader = EspRomLoader(port, log, ep?.let { conn to it })
            try { block(loader) } finally { loader.close() }
        } finally {
            runCatching { port.close() }
        }
    }

private fun stubJson(ctx: Context): String = try {
    ctx.assets.open("esp32c3.json").bufferedReader().use { it.readText() }
} catch (e: IOException) {
    throw EspError("Flasher stub missing from this APK. Check the 'Fetch ESP32-C3 stub' step of the build.")
}

/** Flash `image` into app0 and blank otadata so the bootloader boots it. */
private suspend fun runFlash(
    ctx: Context, image: ByteArray, preflight: Boolean, inactive: Boolean,
    log: (String) -> Unit, onProgress: (Float) -> Unit
) {
    ImageCheck.validate(image)?.let { throw EspError(it) }
    if (inactive) {
        val stub = stubJson(ctx)
        withLoader(ctx, log) { loader -> DeviceOps.flashInactive(loader, stub, image, log, onProgress) }
        return
    }
    val stub = if (preflight) stubJson(ctx) else null
    withLoader(ctx, log) { loader ->
        if (stub != null) {
            val st = DeviceOps.inspect(loader, stub, log)
            DeviceOps.requireStandardLayout(st, image.size)
            log("Layout OK. Returning to the ROM loader…")
        }
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
    }
}

private fun displayName(ctx: Context, uri: Uri): String =
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    } ?: uri.lastPathSegment ?: "firmware.bin"

// ---------------- Small UI building blocks ----------------

@Composable
private fun SectionCard(title: String, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = InkSurface),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(accent))
                Text(title, style = MaterialTheme.typography.labelLarge, color = accent)
            }
            content()
        }
    }
}

@Composable
private fun GlowButton(label: String, emoji: String, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black, disabledContainerColor = InkSurface2),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) { Text("$emoji  $label", style = MaterialTheme.typography.titleSmall) }
}

@Composable
private fun GhostButton(label: String, emoji: String, accent: Color, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) accent else Color(0xFF3A3D4F)),
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) { Text("$emoji $label", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
}

/** Colors each log line by what it reports, so errors and successes pop out of the scrollback. */
private fun colorize(lines: List<String>): AnnotatedString = buildAnnotatedString {
    lines.forEachIndexed { i, line ->
        val color = when {
            line.startsWith("ERROR") || line.contains("glitch", ignoreCase = true) -> Rose
            line.startsWith("DIAG") -> Color(0xFF8A8DA0)
            line.startsWith("Retrying") -> Amber
            line.contains("Done.") || line.contains("complete") || line.contains("Verified") || line.contains("confirmed") -> Mint
            line.contains("Switching") || line.contains("Loading") || line.contains("Resetting") -> Teal
            else -> Color(0xFFD6D7E6)
        }
        withStyle(SpanStyle(color = color)) { append(line) }
        if (i != lines.lastIndex) append("\n")
    }
}

@Composable
fun FlasherScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val logLines = remember { mutableStateListOf<String>() }
    var progress by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var image by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf("") }
    var preflight by remember { mutableStateOf(true) }
    var inactive by remember { mutableStateOf(false) }
    var continuous by remember { mutableStateOf(UsbOptions.continuousReader) }
    var confirmAction by remember { mutableStateOf<String?>(null) } // "flash" | "swap"
    var copied by remember { mutableStateOf(false) }

    fun log(s: String) { scope.launch(Dispatchers.Main) { logLines.add(s) } }
    fun setProgress(p: Float) { scope.launch(Dispatchers.Main) { progress = p } }

    fun task(block: suspend () -> Unit) {
        busy = true; progress = 0f
        scope.launch {
            try { block() } catch (e: Exception) { log("ERROR: ${e.message}") } finally { busy = false }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            fileName = displayName(ctx, uri)
            val err = if (bytes == null) "Could not read file." else ImageCheck.validate(bytes)
            if (err != null || bytes == null) { image = null; log("Rejected $fileName: $err") }
            else { image = bytes; log("Loaded $fileName (${bytes.size / 1024} KB).") }
        }
    }

    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) task {
            val stub = stubJson(ctx)
            withLoader(ctx, { s -> log(s) }) { loader ->
                ctx.contentResolver.openOutputStream(uri, "w")!!.use { out ->
                    DeviceOps.backup(loader, stub, out, { s -> log(s) }) { p -> setProgress(p) }
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(InkBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- Header ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Violet, Teal))),
                contentAlignment = Alignment.Center
            ) { Text("⚡", fontSize = 20.sp) }
            Column {
                Text("X4 Flasher", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(
                    "ESP32-C3 · USB-C OTG",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9A9CB5)
                )
            }
            Spacer(Modifier.weight(1f))
            if (busy) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Teal)
        }
        Text(
            "Keep the X4 awake while connected. Locked units: use the SD-card install instead.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9A9CB5)
        )

        // ---- Firmware & flashing ----
        SectionCard("FIRMWARE", Violet) {
            GhostButton("Choose firmware .bin", "📂", Violet, !busy, Modifier.fillMaxWidth()) {
                picker.launch(arrayOf("*/*"))
            }
            if (fileName.isNotEmpty()) {
                Surface(color = InkSurface2, shape = MaterialTheme.shapes.extraSmall) {
                    Text(
                        fileName, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFCFCFE6)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = preflight || inactive, onCheckedChange = { preflight = it },
                    enabled = !busy && !inactive,
                    colors = CheckboxDefaults.colors(checkedColor = Violet)
                )
                Text("Check partition table before flashing", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = inactive, onCheckedChange = { inactive = it }, enabled = !busy,
                    colors = CheckboxDefaults.colors(checkedColor = Violet)
                )
                Text("Flash into inactive slot (keep current as fallback)", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = continuous,
                    onCheckedChange = { continuous = it; UsbOptions.continuousReader = it },
                    enabled = !busy,
                    colors = CheckboxDefaults.colors(checkedColor = Teal)
                )
                Text("Continuous USB reader (fixes read glitches — keep on)", style = MaterialTheme.typography.bodySmall)
            }
            GlowButton("Flash to X4", "⚡", Violet, !busy && image != null) { confirmAction = "flash" }
        }

        // ---- Device tools ----
        SectionCard("DEVICE TOOLS", Teal) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                GhostButton("Inspect", "🔍", Teal, !busy, Modifier.weight(1f)) {
                    task {
                        val stub = stubJson(ctx)
                        withLoader(ctx, { s -> log(s) }) { loader ->
                            DeviceOps.inspect(loader, stub) { s -> log(s) }
                            loader.hardReset()
                        }
                    }
                }
                GhostButton("Backup", "💾", Mint, !busy, Modifier.weight(1f)) {
                    backupPicker.launch("x4-full-flash-backup.bin")
                }
                GhostButton("Swap", "🔁", Amber, !busy, Modifier.weight(1f)) { confirmAction = "swap" }
            }
        }

        // ---- Progress ----
        if (busy || progress > 0f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = Teal, trackColor = InkSurface2,
            )
        }

        // ---- Log ----
        SectionCard("LOG", Amber) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(logLines.joinToString("\n")))
                    copied = true
                    scope.launch { kotlinx.coroutines.delay(1500); copied = false }
                }, enabled = logLines.isNotEmpty()) {
                    Text(if (copied) "✓ Copied" else "📋 Copy logs", color = if (copied) Mint else Teal, style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { logLines.clear() }, enabled = logLines.isNotEmpty() && !busy) {
                    Text("🗑 Clear", color = Color(0xFF9A9CB5), style = MaterialTheme.typography.labelMedium)
                }
            }
            Surface(
                color = Color(0xFF0A0B14), shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp)
            ) {
                Box(Modifier.verticalScroll(rememberScrollState()).padding(10.dp)) {
                    Text(
                        colorize(logLines),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }

    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            containerColor = InkSurface,
            title = { Text(if (action == "flash") "Flash to X4?" else "Switch boot slot?", color = Color.White) },
            text = {
                Text(
                    if (action == "flash" && inactive) "Writes the firmware into the slot that is NOT running, then boots it. The current firmware stays in the other slot. Don't unplug until it says Done."
                    else if (action == "flash") "Overwrites the app0 slot and resets the boot selector. Don't unplug until it says Done."
                    else "Points the bootloader at the other firmware slot, if it holds a valid app. Don't unplug until it says Done.",
                    color = Color(0xFFCFCFE6)
                )
            },
            dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    confirmAction = null
                    task {
                        if (action == "flash") {
                            runFlash(ctx, image!!, preflight, inactive, { s -> log(s) }) { p -> setProgress(p) }
                        } else {
                            val stub = stubJson(ctx)
                            withLoader(ctx, { s -> log(s) }) { loader ->
                                DeviceOps.swapSlot(loader, stub) { s -> log(s) }
                            }
                        }
                    }
                }) { Text(if (action == "flash") "Flash" else "Switch", color = Violet) }
            }
        )
    }
}
