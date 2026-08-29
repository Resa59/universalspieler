package de.rdoe.weeklydjshows

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small privacy-conscious local ring log; it is shared only after an explicit user action. */
internal object AppDiagnostics {
    private const val FILE_NAME = "diagnostics.log"
    private const val MAX_BYTES = 192 * 1024L

    @Synchronized
    fun record(context: Context, area: String, message: String, error: Throwable? = null) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.length() > MAX_BYTES) {
                val tail = file.readText().takeLast((MAX_BYTES / 2).toInt())
                file.writeText(tail)
            }
            val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            val cause = error?.let { " | ${it.javaClass.simpleName}: ${it.message.orEmpty().take(300)}" }.orEmpty()
            file.appendText("$at | $area | ${message.replace('\n', ' ').take(500)}$cause\n")
        }
    }

    fun report(context: Context): String {
        val newPipeVersion = listOf("org.schabi.newpipe", "org.schabi.newpipe.debug")
            .firstNotNullOfOrNull { packageName ->
                runCatching {
                    val info = context.packageManager.getPackageInfo(packageName, 0)
                    "$packageName ${info.versionName} (${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()})"
                }.getOrNull()
            } ?: "nicht installiert"
        val log = runCatching { File(context.filesDir, FILE_NAME).readText().takeLast(32_000) }
            .getOrDefault("Noch kein Diagnoseprotokoll vorhanden.")
        return buildString {
            appendLine("Weekly DJ Shows – Fehler/Anregung")
            appendLine()
            appendLine("Beschreibung:")
            appendLine("[Bitte hier ergänzen]")
            appendLine()
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Gerät: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("NewPipe-App: $newPipeVersion")
            appendLine("Interne Wiedergabe: NewPipe Extractor ${MaintenanceChecks.EMBEDDED_EXTRACTOR_VERSION}")
            appendLine()
            appendLine("Diagnoseprotokoll:")
            append(log)
        }
    }
}
