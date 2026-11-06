package ly.kashif

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

val Green = Color(0xFF0D6B4F)
val Red = Color(0xFFC8352B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF3FBF8F)) else lightColorScheme(primary = Green)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize()) { ConsentGate { App() } }
                }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val dao = remember { AppDatabase.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    val entries by dao.all().collectAsState(emptyList())
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        if (dao.count() == 0) dao.insertAll(Seed.list)
        if (Build.VERSION.SDK_INT >= 33) perms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("المكالمات", "بحث", "المحظورة").forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Text(listOf("📞", "🔍", "⛔")[i]) }, label = { Text(t) })
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 14.dp)) {
            Text("كاشف ليبيا", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 14.dp))
            RoleBanner()
            when (tab) {
                0 -> Recents(entries)
                1 -> SearchTab(dao, scope)
                else -> BlockedTab(entries.filter { it.blocked }, dao, scope)
            }
        }
    }
}

@Composable
fun RoleBanner() {
    val ctx = LocalContext.current
    val rm = ctx.getSystemService(RoleManager::class.java)
    var held by remember { mutableStateOf(rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        held = rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }
    if (held) return
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("فعّل كشف المكالمات", fontWeight = FontWeight.Bold)
            Text("ليعرّفك التطبيق بالمتصل ويحظر المزعجين، اختر «كاشف ليبيا» كتطبيق فحص المكالمات.", fontSize = 13.sp)
            Button(onClick = { launcher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)) },
                modifier = Modifier.fillMaxWidth()) { Text("تفعيل") }
        }
    }
}

data class CallRow(val number: String, val date: Long)

fun readCalls(c: Context): List<CallRow> {
    val out = mutableListOf<CallRow>()
    c.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
        null, null, "${CallLog.Calls.DATE} DESC")?.use { cur ->
        while (cur.moveToNext() && out.size < 50) {
            out += CallRow(NumberUtil.normalize(cur.getString(0).orEmpty()), cur.getLong(1))
        }
    }
    return out
}

@Composable
fun Recents(entries: List<NumberEntry>) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        Text("اسمح بقراءة سجل المكالمات لعرض الأسماء بجانب الأرقام.", Modifier.padding(8.dp))
        Button({ req.launch(Manifest.permission.READ_CALL_LOG) }, Modifier.fillMaxWidth()) { Text("السماح") }
        return
    }
    val calls = remember(granted) { readCalls(ctx) }
    val map = entries.associateBy { it.number }
    val remote = remember { mutableStateMapOf<String, NumberEntry>() }
    LaunchedEffect(calls, entries) {
        withContext(Dispatchers.IO) {
            calls.map { it.number }.distinct().filter { it !in map && it !in remote }.take(30)
                .forEach { n -> Cloud.lookup(n)?.let { remote[n] = it } }
        }
    }
    val fmt = remember { SimpleDateFormat("d/M HH:mm", Locale("ar")) }
    if (calls.isEmpty()) Text("لا توجد مكالمات بعد.", Modifier.padding(8.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(calls) { r -> EntryRow(r.number, map[r.number] ?: remote[r.number], "\u2066" + fmt.format(Date(r.date)) + "\u2069") }
    }
}

@Composable
fun EntryRow(number: String, e: NumberEntry?, sub: String, action: @Composable (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = androidx.compose.foundation.shape.CircleShape,
                color = if (e?.spam == true) Red.copy(alpha = .15f) else MaterialTheme.colorScheme.primary.copy(alpha = .15f),
                modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(e?.name?.firstOrNull()?.toString() ?: "؟", fontWeight = FontWeight.Bold) }
            }
            Column(Modifier.weight(1f)) {
                Text(e?.name?.ifBlank { null } ?: "رقم غير معروف", fontWeight = FontWeight.Medium)
                Text("${NumberUtil.pretty(number)} · $sub", fontSize = 12.sp)
            }
            if (e?.spam == true) Text("مزعج", color = Red, fontSize = 12.sp)
            action?.invoke()
        }
    }
}

@Composable
fun SearchTab(dao: NumberDao, scope: CoroutineScope) {
    var q by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<NumberEntry?>(null) }
    fun refresh(n: String) = scope.launch { result = dao.find(n) ?: withContext(Dispatchers.IO) { Cloud.lookup(n) }; searched = n }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("+218 91 000 0000") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        Button({ val n = NumberUtil.normalize(q); if (n.length >= 9) refresh(n) },
            Modifier.fillMaxWidth().height(48.dp)) { Text("بحث") }
        val n = searched
        if (n == null) {
            Text("اكتب رقماً لتعرف من يتصل بك قبل أن تردّ.", Modifier.padding(16.dp))
        } else {
            val e = result
            EntryRow(n, e, e?.city?.ifBlank { null } ?: "ليبيا")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    scope.launch {
                        val cur = e ?: NumberEntry(n)
                        dao.upsert(cur.copy(blocked = !cur.blocked, updatedAt = System.currentTimeMillis())); refresh(n)
                    }
                }, Modifier.weight(1f)) { Text(if (e?.blocked == true) "إلغاء الحظر" else "حظر") }
                OutlinedButton({
                    scope.launch {
                        val cur = e ?: NumberEntry(n)
                        dao.upsert(cur.copy(spam = true, reports = cur.reports + 1, updatedAt = System.currentTimeMillis())); refresh(n)
                    }
                }, Modifier.weight(1f)) { Text("بلّغ كمزعج") }
            }
        }
    }
}

@Composable
fun BlockedTab(list: List<NumberEntry>, dao: NumberDao, scope: CoroutineScope) {
    if (list.isEmpty()) { Text("لا توجد أرقام محظورة.", Modifier.padding(8.dp)); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list) { e ->
            EntryRow(e.number, e, "محظور") {
                TextButton({ scope.launch { dao.upsert(e.copy(blocked = false)) } }) { Text("إلغاء") }
            }
        }
    }
}
