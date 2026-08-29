package de.rdoe.weeklydjshows

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class MaintenanceKind { APP_UPDATE, NEWPIPE }

data class MaintenanceNotice(
    val id: String,
    val kind: MaintenanceKind,
    val title: String,
    val message: String,
    val primaryLabel: String,
    val primaryUrl: String,
)

/** Small, optional checks. They never collect usage data and do not run when disabled. */
internal object MaintenanceChecks {
    const val EMBEDDED_EXTRACTOR_VERSION = "0.26.4"
    private const val NEWPIPE_APP_PACKAGE = "org.schabi.newpipe"
    private const val NEWPIPE_DEBUG_PACKAGE = "org.schabi.newpipe.debug"
    private const val NEWPIPE_RELEASE_API = "https://api.github.com/repos/TeamNewPipe/NewPipe/releases/latest"
    private const val EXTRACTOR_RELEASE_API = "https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/releases/latest"
    private const val NEWPIPE_INSTALL_URL = "https://newpipe.net/FAQ/tutorials/install-add-fdroid-repo/"
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()

    suspend fun appUpdate(context: Context, includeDismissed: Boolean = false): MaintenanceNotice? = withContext(Dispatchers.IO) {
        val manifestUrl = BuildConfig.UPDATE_MANIFEST_URL.trim()
        if (!manifestUrl.startsWith("https://")) return@withContext null
        val root = getJson(manifestUrl)
        val versionCode = root.getLong("versionCode")
        if (versionCode <= BuildConfig.VERSION_CODE) return@withContext null
        val versionName = root.optString("versionName", versionCode.toString())
        val apkUrl = root.getString("apkUrl").takeIf { it.startsWith("https://") }
            ?: error("Update-Datei muss HTTPS verwenden")
        val id = "app-$versionCode"
        if (!includeDismissed && isDismissed(context, id)) return@withContext null
        val notes = root.optString("releaseNotes").trim().take(900)
        MaintenanceNotice(
            id = id,
            kind = MaintenanceKind.APP_UPDATE,
            title = "Update $versionName verfügbar",
            message = buildString {
                append("Die neue Version kann im Hintergrund geladen werden. Android fragt vor der Installation immer noch einmal nach.")
                if (notes.isNotEmpty()) append("\n\n$notes")
            },
            primaryLabel = "Laden & installieren",
            primaryUrl = apkUrl,
        )
    }

    suspend fun newPipe(context: Context, includeDismissed: Boolean = false): MaintenanceNotice? = withContext(Dispatchers.IO) {
        val extractorTag = runCatching { getJson(EXTRACTOR_RELEASE_API).optString("tag_name") }.getOrNull()
        val latestExtractor = extractorTag?.normalizedVersion()
        if (latestExtractor != null && compareVersions(latestExtractor, EMBEDDED_EXTRACTOR_VERSION) > 0) {
            val id = "extractor-$latestExtractor"
            if (!includeDismissed && isDismissed(context, id)) return@withContext null
            return@withContext MaintenanceNotice(
                id = id,
                kind = MaintenanceKind.NEWPIPE,
                title = "Interne Wiedergabe sollte aktualisiert werden",
                message = "YouTube oder SoundCloud haben sich verändert. Die App versucht weiterhin direkt abzuspielen, könnte dabei aber häufiger scheitern. Ein Weekly-DJ-Shows-Update mit der angepassten Wiedergabekomponente steht gegebenenfalls noch aus.",
                primaryLabel = "Problem melden",
                primaryUrl = "feedback://weekly-dj-shows",
            )
        }

        val installed = installedNewPipeVersion(context)
        if (installed == null) {
            val id = "newpipe-not-installed"
            if (!includeDismissed && isDismissed(context, id)) return@withContext null
            return@withContext MaintenanceNotice(
                id = id,
                kind = MaintenanceKind.NEWPIPE,
                title = "NewPipe als Ausweichmöglichkeit",
                message = "Die direkte Wiedergabe funktioniert ohne NewPipe. Optional kannst du NewPipe installieren, um problematische YouTube- oder SoundCloud-Folgen dort zu öffnen.",
                primaryLabel = "NewPipe installieren",
                primaryUrl = NEWPIPE_INSTALL_URL,
            )
        }

        val release = runCatching { getJson(NEWPIPE_RELEASE_API) }.getOrNull() ?: return@withContext null
        val latest = release.optString("tag_name").normalizedVersion()
        if (latest.isBlank() || compareVersions(latest, installed) <= 0) return@withContext null
        val id = "newpipe-app-$latest"
        if (!includeDismissed && isDismissed(context, id)) return@withContext null
        MaintenanceNotice(
            id = id,
            kind = MaintenanceKind.NEWPIPE,
            title = "NewPipe $latest verfügbar",
            message = "Auf dem Gerät ist NewPipe $installed installiert. Aktualisiere es am besten über dieselbe Quelle wie bei der Installation, damit Android die Signatur akzeptiert.",
            primaryLabel = "Installationshinweise",
            primaryUrl = NEWPIPE_INSTALL_URL,
        )
    }

    fun dismiss(context: Context, notice: MaintenanceNotice) {
        context.getSharedPreferences("weekly_dj_internal", Context.MODE_PRIVATE)
            .edit().putBoolean("dismissed_${notice.id}", true).apply()
    }

    fun updateChannelConfigured(): Boolean = BuildConfig.UPDATE_MANIFEST_URL.startsWith("https://")

    private fun isDismissed(context: Context, id: String): Boolean =
        context.getSharedPreferences("weekly_dj_internal", Context.MODE_PRIVATE)
            .getBoolean("dismissed_$id", false)

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "WeeklyDJShows/${BuildConfig.VERSION_NAME} (Android)")
            .build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Serverantwort ${response.code}" }
            JSONObject(response.body?.string() ?: error("Leere Serverantwort"))
        }
    }

    private fun installedNewPipeVersion(context: Context): String? =
        listOf(NEWPIPE_APP_PACKAGE, NEWPIPE_DEBUG_PACKAGE).firstNotNullOfOrNull { packageName ->
            runCatching { context.packageManager.getPackageInfo(packageName, 0).versionName?.normalizedVersion() }.getOrNull()
        }

    private fun String.normalizedVersion(): String = trim().removePrefix("v").substringBefore('-')

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.').map { it.toIntOrNull() ?: 0 }
        val b = right.split('.').map { it.toIntOrNull() ?: 0 }
        repeat(maxOf(a.size, b.size)) { index ->
            val difference = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }
}

internal object AppUpdateInstaller {
    private const val PREFERENCES = "weekly_dj_internal"
    private const val PENDING_DOWNLOAD = "pending_app_update_download"

    /** Returns false after opening Android's one-time permission screen. */
    fun enqueue(context: Context, notice: MaintenanceNotice): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = Uri.parse(notice.primaryUrl)
        require(uri.scheme == "https") { "Update-Datei muss HTTPS verwenden" }
        val manager = requireNotNull(context.getSystemService<DownloadManager>())
        val fileName = "WeeklyDJShows-${notice.id.removePrefix("app-")}.apk"
        val request = DownloadManager.Request(uri)
            .setTitle("Weekly DJ Shows – ${notice.title}")
            .setDescription("Update wird heruntergeladen")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val id = manager.enqueue(request)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putLong(PENDING_DOWNLOAD, id).apply()
        return true
    }

    fun installCompleted(context: Context, completedId: Long) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getLong(PENDING_DOWNLOAD, -1L) != completedId) return
        val manager = context.getSystemService<DownloadManager>() ?: return
        val cursor = manager.query(DownloadManager.Query().setFilterById(completedId)) ?: return
        cursor.use {
            if (!it.moveToFirst()) return
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) return
        }
        val uri = manager.getUriForDownloadedFile(completedId) ?: return
        preferences.edit().remove(PENDING_DOWNLOAD).apply()
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private const val APK_MIME = "application/vnd.android.package-archive"
}

class AppUpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        AppUpdateInstaller.installCompleted(
            context,
            intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L),
        )
    }
}
