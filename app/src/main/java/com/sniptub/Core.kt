package com.sniptub

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

enum class St { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELED }

class Dl(val id: Int, val url: String, val title: String, val thumb: String, val height: Int) {
    var state by mutableStateOf(St.QUEUED)
    var percent by mutableStateOf(0)
    var eta by mutableStateOf(0L)
    var uri by mutableStateOf<Uri?>(null)
    var err by mutableStateOf("")
}

data class Item(val url: String, val title: String, val thumb: String, val dur: String)

object Jobs {
    val list = mutableStateListOf<Dl>()
    var ready by mutableStateOf(false)
    var initError by mutableStateOf<String?>(null)
    var status by mutableStateOf("")
    fun add(ctx: Context, url: String, title: String, thumb: String, height: Int) {
        val j = Dl((System.currentTimeMillis() % 1000000000).toInt(), url, title, thumb, height)
        list.add(0, j)
        act(ctx, "start", j.id)
    }
    fun act(ctx: Context, a: String, id: Int) {
        ctx.startForegroundService(Intent(ctx, DlService::class.java).setAction(a).putExtra("id", id))
    }
    fun active() = list.any { it.state == St.RUNNING || it.state == St.QUEUED }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("dl", "التحميلات", NotificationManager.IMPORTANCE_LOW))
        Thread {
            try {
                YoutubeDL.getInstance().init(this@App)
                FFmpeg.getInstance().init(this@App)
                val p = this@App.getSharedPreferences("s", Context.MODE_PRIVATE)
                if (System.currentTimeMillis() - p.getLong("upd", 0L) > 86400000L) {
                    Jobs.status = "جاري تحديث محرك التحميل…"
                    val t = Thread {
                        val r = updateEngine(this@App)
                        if (r.startsWith("تم")) p.edit().putLong("upd", System.currentTimeMillis()).apply()
                        Jobs.status = r
                    }
                    t.start(); t.join(25000)
                }
                Jobs.ready = true
            } catch (e: Exception) { Jobs.initError = e.message }
        }.start()
    }
}

fun exec(r: YoutubeDLRequest, ms: Long = 90000L): String {
    var w = 0
    while (!Jobs.ready && w++ < 300) Thread.sleep(200)
    val tag = "q" + System.nanoTime()
    r.addOption("--socket-timeout", "20")
    val t = Thread { try { Thread.sleep(ms); YoutubeDL.getInstance().destroyProcessById(tag) } catch (e: InterruptedException) {} }
    t.start()
    try { return YoutubeDL.getInstance().execute(r, tag) { _, _, _ -> }.out } finally { t.interrupt() }
}

fun errText(e: Exception): String =
    (e.message ?: "").lines().filter { it.isNotBlank() }.takeLast(3).joinToString("\n").take(300).ifBlank { "خطأ غير معروف" }

fun dur(s: Int) = if (s <= 0) "" else "%d:%02d".format(s / 60, s % 60)

fun fetchInfo(u: String): Pair<Item, List<Int>> {
    val r = YoutubeDLRequest(u)
    r.addOption("--dump-single-json"); r.addOption("--no-playlist")
    val o = JSONObject(exec(r))
    val fs = o.optJSONArray("formats")
    var mx = 0
    if (fs != null) for (i in 0 until fs.length()) mx = maxOf(mx, fs.getJSONObject(i).optInt("height", 0))
    val hs = listOf(1080, 720, 480, 360).filter { it <= mx }.ifEmpty { listOf(360) }
    return Item(u, o.optString("title"), o.optString("thumbnail"), dur(o.optInt("duration"))) to hs
}

fun search(q: String): List<Item> {
    val r = YoutubeDLRequest("ytsearch15:$q")
    r.addOption("--flat-playlist"); r.addOption("--dump-json")
    return exec(r).lines().filter { it.startsWith("{") }.map {
        val o = JSONObject(it); val id = o.optString("id")
        Item("https://www.youtube.com/watch?v=$id", o.optString("title"), "https://i.ytimg.com/vi/$id/mqdefault.jpg", dur(o.optInt("duration")))
    }
}

fun updateEngine(ctx: Context): String = try {
    "تم التحديث: " + YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
} catch (e: Exception) { "فشل التحديث: ${e.message}" }

fun openFile(ctx: Context, u: Uri) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, ctx.contentResolver.getType(u))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { Toast.makeText(ctx, "لا يوجد تطبيق لفتح الملف", Toast.LENGTH_SHORT).show() }
}

class DlService : Service() {
    private val pool = Executors.newFixedThreadPool(3)
    private lateinit var nm: NotificationManager
    override fun onBind(i: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); nm = getSystemService(NotificationManager::class.java) }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        startForeground(1, NotificationCompat.Builder(this, "dl").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Sniptub").setContentText("التحميل جارٍ في الخلفية").setOngoing(true).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val j = Jobs.list.firstOrNull { it.id == i?.getIntExtra("id", 0) }
        when (i?.action) {
            "start", "resume" -> j?.let { it.state = St.QUEUED; pool.execute { run(it) } }
            "pause" -> j?.let { pause(it) }
            "cancel" -> j?.let {
                it.state = St.CANCELED
                YoutubeDL.getInstance().destroyProcessById(it.id.toString())
                Jobs.list.remove(it); nm.cancel(it.id); tmp(it).deleteRecursively()
            }
        }
        stopIfIdle()
        return START_NOT_STICKY
    }

    private fun pause(j: Dl) {
        if (j.state == St.RUNNING || j.state == St.QUEUED) {
            j.state = St.PAUSED
            YoutubeDL.getInstance().destroyProcessById(j.id.toString())
            notify(j)
        }
    }

    private fun stopIfIdle() {
        if (!Jobs.active()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    private fun tmp(j: Dl) = File(getExternalFilesDir(null) ?: filesDir, "dl/${j.id}")

    private fun run(j: Dl) {
        if (j.state != St.QUEUED) return
        j.state = St.RUNNING; notify(j)
        val dir = tmp(j); dir.mkdirs()
        try {
            var w = 0
            while (!Jobs.ready && w++ < 300) Thread.sleep(200)
            if (!Jobs.ready) throw Exception("المحرك غير جاهز: ${Jobs.initError}")
            val r = YoutubeDLRequest(j.url)
            r.addOption("--no-playlist"); r.addOption("--no-mtime")
            r.addOption("-o", "${dir.absolutePath}/f.%(ext)s")
            if (j.height == 0) { r.addOption("-x"); r.addOption("--audio-format", "mp3") }
            else {
                r.addOption("-f", "bv*[height<=${j.height}]+ba/b[height<=${j.height}]/b")
                r.addOption("--merge-output-format", "mp4")
            }
            YoutubeDL.getInstance().execute(r, j.id.toString()) { p, eta, _ ->
                val n = p.toInt().coerceIn(0, 100)
                if (n != j.percent) { j.percent = n; j.eta = eta; notify(j) }
            }
            val f = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() } ?: throw Exception("لم يتم العثور على الملف")
            j.uri = save(f, j)
            j.percent = 100; j.state = St.DONE
            dir.deleteRecursively()
        } catch (e: Exception) {
            if (j.state == St.RUNNING) { j.state = St.FAILED; j.err = errText(e) }
        } finally {
            notify(j); stopIfIdle()
        }
    }

    private fun save(f: File, j: Dl): Uri {
        val ext = f.extension
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, j.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) + "." + ext)
            put(MediaStore.Downloads.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/sniptub")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
        contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        v.clear(); v.put(MediaStore.Downloads.IS_PENDING, 0)
        contentResolver.update(u, v, null, null)
        return u
    }

    private fun pi(a: String, id: Int) = PendingIntent.getForegroundService(this, id,
        Intent(this, DlService::class.java).setAction(a).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notify(j: Dl) {
        if (j.state == St.CANCELED) { nm.cancel(j.id); return }
        val b = NotificationCompat.Builder(this, "dl").setContentTitle(j.title).setOnlyAlertOnce(true)
        when (j.state) {
            St.RUNNING, St.QUEUED -> b.setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true)
                .setProgress(100, j.percent, j.state == St.QUEUED)
                .setContentText("${j.percent}%" + if (j.eta > 0) " • ${j.eta} ث" else "")
                .addAction(android.R.drawable.ic_media_pause, "إيقاف مؤقت", pi("pause", j.id))
                .addAction(android.R.drawable.ic_delete, "إلغاء", pi("cancel", j.id))
            St.PAUSED -> b.setSmallIcon(android.R.drawable.ic_media_pause).setProgress(100, j.percent, false)
                .setContentText("متوقف مؤقتاً ${j.percent}%")
                .addAction(android.R.drawable.ic_media_play, "استئناف", pi("resume", j.id))
                .addAction(android.R.drawable.ic_delete, "إلغاء", pi("cancel", j.id))
            St.DONE -> {
                b.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentText("اكتمل التحميل").setAutoCancel(true)
                j.uri?.let {
                    b.setContentIntent(PendingIntent.getActivity(this, j.id,
                        Intent(Intent.ACTION_VIEW).setDataAndType(it, contentResolver.getType(it))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
            }
            else -> b.setSmallIcon(android.R.drawable.stat_notify_error).setContentText("فشل التحميل")
        }
        nm.notify(j.id, b.build())
    }
}
