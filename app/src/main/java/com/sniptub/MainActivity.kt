package com.sniptub

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    var tick by mutableIntStateOf(0)
    var shared by mutableStateOf<String?>(null)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        shared = intent?.getStringExtra(Intent.EXTRA_TEXT)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFFFB300), onPrimary = Color.Black)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { Root(this) }
            }
        }
    }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); shared = i.getStringExtra(Intent.EXTRA_TEXT) }
    override fun onResume() { super.onResume(); tick++ }
}

fun startJob(ctx: Context, m: Item, h: Int) {
    Jobs.add(ctx, m.url, m.title.ifBlank { "video" }, m.thumb, h)
    Toast.makeText(ctx, "بدأ التحميل", Toast.LENGTH_SHORT).show()
}

@Composable fun Root(a: MainActivity) {
    var nav by remember { mutableIntStateOf(0) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS) }
    Scaffold(bottomBar = {
        NavigationBar {
            listOf("تنزيل" to Icons.Default.Search, "تشغيل" to Icons.Default.PlayArrow, "الإعدادات" to Icons.Default.Settings)
                .forEachIndexed { i, (t, ic) -> NavigationBarItem(nav == i, { nav = i }, { Icon(ic, t) }, label = { Text(t) }) }
        }
    }) { p ->
        Column(Modifier.padding(p).fillMaxSize()) {
            if (!Jobs.ready) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(Jobs.initError?.let { "خطأ تهيئة: $it" } ?: "جاري تجهيز المحرك (أول تشغيل فقط)…", Modifier.padding(8.dp), fontSize = 12.sp)
            }
            when (nav) { 0 -> Home(a); 1 -> Queue(); else -> SettingsScreen(a) }
        }
    }
}

@Composable fun Home(a: MainActivity) {
    var tab by remember { mutableIntStateOf(0) }
    Column {
        TabRow(selectedTabIndex = tab) {
            listOf("بحث", "YouTube", "الموسيقى").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        when (tab) { 0 -> LinkTab(a); 1 -> Results("trending now", false); else -> Results("top hits 2026 official music video", true) }
    }
}

@Composable fun LinkTab(a: MainActivity) {
    var text by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<Item?>(null) }
    var heights by remember { mutableStateOf(listOf<Int>()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    fun load(u: String) {
        if (u.isBlank()) return
        text = u; busy = true; msg = ""; info = null
        scope.launch {
            try { val r = withContext(Dispatchers.IO) { fetchInfo(u) }; info = r.first; heights = r.second }
            catch (e: Exception) { msg = "تعذر قراءة الرابط" }
            busy = false
        }
    }
    LaunchedEffect(a.tick) {
        delay(400)
        val c = (ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text ?: "").toString().trim()
        if (c.startsWith("http") && c != text) load(c)
    }
    LaunchedEffect(a.shared) { a.shared?.let { s -> Regex("https?://\\S+").find(s)?.value?.let { load(it) } } }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sniptub", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB300), modifier = Modifier.align(Alignment.CenterHorizontally))
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text("الصق رابط الفيديو هنا") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { load(text.trim()) }),
            trailingIcon = { TextButton({ load(text.trim()) }) { Text("جلب") } })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg, color = Color.Red)
        info?.let { m ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    AsyncImage(m.thumb, null, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    Text(m.title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp), maxLines = 2)
                    (heights + 0).forEach { h ->
                        Button({ startJob(ctx, m, h) }, Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text(if (h == 0) "صوت MP3" else "فيديو ${h}p") }
                    }
                }
            }
        }
    }
}

@Composable fun Results(preset: String, music: Boolean) {
    var q by remember { mutableStateOf(if (music) preset else "") }
    var list by remember { mutableStateOf(listOf<Item>()) }
    var busy by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<Item?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    fun go() {
        busy = true
        scope.launch {
            list = try { withContext(Dispatchers.IO) { search(q.ifBlank { preset }) } } catch (e: Exception) { emptyList() }
            busy = false
        }
    }
    LaunchedEffect(Jobs.ready) { if (Jobs.ready) go() }
    Column(Modifier.fillMaxSize()) {
        if (!music) OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(12.dp), placeholder = { Text("ابحث في يوتيوب") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { go() }))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn {
            items(list) { m ->
                Row(Modifier.fillMaxWidth().clickable { if (music) startJob(ctx, m, 0) else pick = m }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(m.thumb, null, Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                    Column(Modifier.padding(horizontal = 10.dp).weight(1f)) {
                        Text(m.title, maxLines = 2, fontSize = 14.sp); Text(m.dur, fontSize = 12.sp, color = Color.Gray)
                    }
                    TextButton({ if (music) startJob(ctx, m, 0) else pick = m }) { Text("تحميل") }
                }
            }
        }
    }
    pick?.let { m ->
        AlertDialog(onDismissRequest = { pick = null }, confirmButton = {}, title = { Text(m.title, maxLines = 2, fontSize = 16.sp) }, text = {
            Column { listOf(1080, 720, 480, 360, 0).forEach { h ->
                TextButton({ startJob(ctx, m, h); pick = null }) { Text(if (h == 0) "صوت MP3" else "فيديو ${h}p") } } }
        })
    }
}

@Composable fun Queue() {
    val ctx = LocalContext.current
    if (Jobs.list.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("لا توجد تحميلات بعد") }; return }
    LazyColumn {
        items(Jobs.list, key = { it.id }) { j ->
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(j.thumb, null, Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                Column(Modifier.padding(horizontal = 10.dp).weight(1f)) {
                    Text(j.title, maxLines = 1, fontSize = 14.sp)
                    Text(when (j.state) {
                        St.QUEUED -> "في الانتظار"; St.RUNNING -> "${j.percent}%"; St.PAUSED -> "متوقف مؤقتاً ${j.percent}%"
                        St.DONE -> "اكتمل ✓"; St.FAILED -> "فشل: ${j.err}"; else -> ""
                    }, fontSize = 12.sp, color = Color.Gray)
                    if (j.state != St.DONE) LinearProgressIndicator(progress = { j.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                when (j.state) {
                    St.QUEUED, St.RUNNING -> TextButton({ Jobs.act(ctx, "pause", j.id) }) { Text("إيقاف") }
                    St.PAUSED, St.FAILED -> TextButton({ Jobs.act(ctx, "resume", j.id) }) { Text("استئناف") }
                    St.DONE -> TextButton({ j.uri?.let { openFile(ctx, it) } }) { Text("تشغيل") }
                    else -> {}
                }
                if (j.state != St.DONE) TextButton({ Jobs.act(ctx, "cancel", j.id) }) { Text("إلغاء") }
            }
        }
    }
}

@Composable fun SettingsScreen(a: MainActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }
    var upd by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("الإعدادات", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Button({ upd = "جاري التحديث…"; scope.launch { upd = withContext(Dispatchers.IO) { updateEngine(ctx) } } }, Modifier.fillMaxWidth()) { Text("تحديث محرك التحميل") }
        if (upd.isNotEmpty()) Text(upd, fontSize = 12.sp)
        Button({ if (Jobs.active()) ask = true else a.finishAffinity() }, Modifier.fillMaxWidth()) { Text("خروج من التطبيق") }
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("تصميم طلال قاسم", fontWeight = FontWeight.Bold)
            Text("0599347848", color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:0599347848"))) }.padding(8.dp))
        }
    }
    if (ask) AlertDialog(onDismissRequest = { ask = false }, title = { Text("هناك تحميلات جارية") },
        confirmButton = {
            Column {
                TextButton({ Jobs.list.filter { it.state == St.RUNNING || it.state == St.QUEUED }.forEach { Jobs.act(ctx, "pause", it.id) }; a.finishAffinity() }) { Text("إيقاف مؤقت وخروج") }
                TextButton({ a.finish() }) { Text("إكمال بالخلفية وإغلاق الشاشة") }
                TextButton({ ask = false }) { Text("إلغاء") }
            }
        })
}
