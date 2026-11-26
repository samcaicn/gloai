package com.tuptup.speech.ui

import android.Manifest
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuptup.speech.data.Product
import com.tuptup.speech.data.Settings
import com.tuptup.speech.tts.AnchorVoiceEngine
import com.tuptup.speech.tts.ZipVoiceEngine
import com.tuptup.speech.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MainScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val ui by vm.ui.collectAsState()
    val products by vm.products.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        vm.tts.init { }
        micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Card, tonalElevation = 2.dp,
                modifier = Modifier.borderTop(Line)) {
                val items = listOf(
                    "首页" to Icons.Default.Home, "直播" to Icons.Default.PlayArrow,
                    "评论" to Icons.Default.Comment, "数据" to Icons.Default.BarChart,
                    "设置" to Icons.Default.Settings
                )
                items.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent, selectedTextColor = Accent,
                            unselectedIconColor = Muted, unselectedTextColor = Muted,
                            indicatorColor = AccentSoft)
                    )
                }
            }
        }
    ) { pad ->
        when (tab) {
            0 -> HomePage(vm, Modifier.padding(pad)) { i -> tab = i }
            1 -> ScriptPage(vm, ui, Modifier.padding(pad))
            2 -> CommentPage(vm, Modifier.padding(pad))
            3 -> DataPage(Modifier.padding(pad), ui, settings, products) { i -> tab = i }
            4 -> SettingsPage(vm, settings, ui, products, Modifier.padding(pad))
        }
    }
}

private fun Modifier.borderTop(color: Color) = this.border(1.dp, color)

@Composable
fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp).fillMaxWidth(),
        tonalElevation = 1.dp) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun Hint(text: String) { Text(text, color = Muted, fontSize = 12.sp) }

@Composable
fun ScriptPage(vm: MainViewModel, ui: UiState, mod: Modifier) {
    val s = vm.settings()
    var style by remember { mutableStateOf(s.speakStyle) }
    var autoSpeak by remember { mutableStateOf(s.commentSpeakEnabled) }
    Column(mod.fillMaxWidth().verticalScroll(rememberScrollState()).background(Bg)) {
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("直播讲解", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("有评论先回复，没评论自动讲解", color = Muted, fontSize = 11.sp)
            }
            StatusDot(ui)
        }
        Spacer(Modifier.height(14.dp))
        CardBox {
            Text("播讲设置", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextMain)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("热情", "专业", "亲切", "幽默").forEach { st ->
                    FilterChip(selected = style == st, onClick = {
                        style = st; s.speakStyle = st
                    }, label = { Text(st, fontSize = 12.sp) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自动播讲", color = TextMain, fontSize = 13.sp)
                    Text("评论回复 + 自动讲解，讲完一条再切下一条", color = Muted, fontSize = 10.sp)
                }
                Switch(autoSpeak, {
                    autoSpeak = it; s.commentSpeakEnabled = it
                    if (it) vm.resumePlayback() else vm.stopSpeak()
                })
            }
        }
        ui.context?.let { c ->
            CardBox {
                Text("实时上下文", fontWeight = FontWeight.Bold, color = TextMain)
                Spacer(Modifier.height(6.dp))
                Chip("时间", c.time); Chip("天气", c.weather); Chip("热点", c.hotspots)
            }
        }
        ui.decision?.let { d ->
            CardBox {
                Text("推荐话术", fontWeight = FontWeight.Bold, color = TextMain)
                val s = vm.jevEngine().strategyById(d.selectedId)
                Hint("${s.name}（匹配度 ${(d.confidence * 100).toInt()}%）")
            }
        }
        if (ui.lines.isNotEmpty()) {
            CardBox {
                Text("最近讲解", fontWeight = FontWeight.Bold, color = TextMain)
                ui.lines.forEachIndexed { i, line ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = Accent, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                        Text(line, Modifier.weight(1f), fontSize = 14.sp,
                            color = if (ui.playingIndex == i) Accent else TextMain)
                        IconButton(onClick = { vm.speak(i) }) {
                            Icon(Icons.Default.PlayArrow, "播放", tint = Accent)
                        }
                    }
                }
                TextButton(onClick = { vm.stopSpeak() }, modifier = Modifier.fillMaxWidth()) { Text("停止播讲") }
            }
        }
        if (ui.note.isNotBlank()) CardBox { Hint(ui.note) }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
fun Chip(label: String, value: String) {
    Surface(color = Card2, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(end = 6.dp, bottom = 6.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text("$label ", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(value.take(40), color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun StatusDot(ui: UiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = if (ui.jevReady) Success else Color(0xFFD69A3C), shape = RoundedCornerShape(4.dp),
            modifier = Modifier.size(8.dp)) {}
        Spacer(Modifier.width(6.dp))
        Text(ui.status, color = Muted, fontSize = 11.sp)
    }
}

@Composable
fun ShopPage(s: Settings, mod: Modifier) {
    var name by remember { mutableStateOf(s.shopName) }
    var loc by remember { mutableStateOf(s.shopLocation) }
    var feat by remember { mutableStateOf(s.shopFeatures) }
    var city by remember { mutableStateOf(s.city) }
    val ctx = LocalContext.current
    Column(mod.fillMaxWidth().verticalScroll(rememberScrollState()).background(Bg)) {
        Spacer(Modifier.height(16.dp))
        CardBox {
            Text("店铺设置", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextMain)
            Hint("保存在本机，作为话术背景")
            listOf("店铺名称" to name, "店铺地点" to loc, "特色亮点" to feat, "天气城市" to city).forEach { (l, v) ->
                Spacer(Modifier.height(8.dp))
                Text(l, color = Muted, fontSize = 12.sp)
                OutlinedTextField(v, { nv ->
                    when (l) {
                        "店铺名称" -> { name = nv; s.shopName = nv }
                        "店铺地点" -> { loc = nv; s.shopLocation = nv }
                        "特色亮点" -> { feat = nv; s.shopFeatures = nv }
                        "天气城市" -> { city = nv; s.city = nv }
                    }
                }, modifier = Modifier.fillMaxWidth(), singleLine = l != "特色亮点")
            }
            Button(onClick = { android.widget.Toast.makeText(ctx, "已保存到本机", android.widget.Toast.LENGTH_SHORT).show() },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("保存店铺信息", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ProductPage(vm: MainViewModel, list: List<Product>, mod: Modifier) {
    var name by remember { mutableStateOf("") }
    var points by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    Column(mod.fillMaxWidth().verticalScroll(rememberScrollState()).background(Bg)) {
        Spacer(Modifier.height(16.dp))
        CardBox {
            Text("新增产品", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextMain)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(name, { name = it }, label = { Text("产品名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(points, { points = it }, label = { Text("介绍要点（每行一条）") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Button(onClick = {
                if (name.isNotBlank()) { vm.addProduct(name, points); name = ""; points = "" }
                else android.widget.Toast.makeText(ctx, "请填产品名称", android.widget.Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("添加产品", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        CardBox {
            Text("产品列表（共 ${list.size} 个）", fontWeight = FontWeight.Bold, color = TextMain)
            if (list.isEmpty()) Hint("还没有产品，先添加一个吧")
            list.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.Bold, color = TextMain)
                        Text(p.points.replace("\n", " · "), color = Muted, fontSize = 12.sp)
                    }
                    IconButton(onClick = { vm.deleteProduct(p.id) }) {
                        Icon(Icons.Default.Delete, "删除", tint = Danger)
                    }
                }
            }
        }
    }
}

@Composable
fun VoicePage(vm: MainViewModel, s: Settings, mod: Modifier) {
    val ctx = LocalContext.current
    val local = vm.tts.local
    val anchor = vm.tts.anchor
    val scope = rememberCoroutineScope()
    var refText by remember { mutableStateOf(s.localRefText) }
    var modelReady by remember { mutableStateOf(local.isDownloaded) }
    var refReady by remember { mutableStateOf(local.hasReference()) }
    var preparing by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("all") }
    var mode by remember { mutableStateOf(s.voiceMode) }
    var packReady by remember { mutableStateOf(anchor.isDownloaded) }
    var packBusy by remember { mutableStateOf(false) }
    var packProgress by remember { mutableIntStateOf(0) }
    var anchorSid by remember { mutableIntStateOf(s.anchorVoiceSid) }
    val main = Handler(Looper.getMainLooper())
    fun toast(m: String) = android.widget.Toast.makeText(ctx, m, android.widget.Toast.LENGTH_SHORT).show()

    Column(mod.fillMaxWidth().verticalScroll(rememberScrollState()).background(Bg)) {
        Spacer(Modifier.height(16.dp))

        // 音色切换
        CardBox {
            Text("播讲音色", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextMain)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == "default", onClick = {
                    mode = "default"; s.voiceMode = "default"; toast("已切换默认音色")
                }, label = { Text("默认", fontSize = 12.sp) })
                FilterChip(selected = mode == "mine", onClick = {
                    mode = "mine"; s.voiceMode = "mine"; toast("已切换我的声音")
                }, label = { Text("我的声音", fontSize = 12.sp) })
            }
        }

        // 我的声音（本机复刻）
        CardBox {
            Text("我的声音", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextMain)
            Hint("录 3 秒，用你自己的声音播讲评论")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (preparing) return@OutlinedButton
                    preparing = true; progress = 0
                    scope.launch {
                        val ok = local.downloadAll { done, total, _ -> progress = done * 100 / total }
                        withContext(Dispatchers.Main) {
                            preparing = false; modelReady = ok
                            toast(if (ok) "准备好了，可以录音了" else "准备失败，请检查网络")
                        }
                    }
                }, modifier = Modifier.weight(1f), enabled = !preparing) {
                    Text(if (preparing) "准备中 $progress%" else (if (modelReady) "重新准备" else "准备声音"), fontSize = 12.sp)
                }
                Button(onClick = {
                    if (!modelReady) { toast("请先准备声音"); return@Button }
                    if (recording) return@Button
                    recording = true
                    Thread {
                        try {
                            val sr = 16000
                            val minBuf = android.media.AudioRecord.getMinBufferSize(sr,
                                android.media.AudioFormat.CHANNEL_IN_MONO,
                                android.media.AudioFormat.ENCODING_PCM_16BIT)
                            val rec = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, sr,
                                android.media.AudioFormat.CHANNEL_IN_MONO,
                                android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 4096))
                            val max = sr * 3
                            val buf = ShortArray(max)
                            val chunk = ShortArray(1600)
                            rec.startRecording()
                            var total = 0
                            while (total < max) {
                                val n = rec.read(chunk, 0, chunk.size)
                                if (n > 0) { System.arraycopy(chunk, 0, buf, total, minOf(n, max - total)); total += n }
                            }
                            rec.stop(); rec.release()
                            if (total > sr * 2) {
                                ZipVoiceEngine.writeWav(buf.copyOf(total), sr, local.referenceFile())
                                local.invalidateRef()
                                main.post { refReady = true; toast("录好了，填一下刚才读的内容") }
                            } else main.post { toast("录音太短，请重录") }
                        } catch (e: Exception) {
                            main.post { toast("录音失败".take(20)) }
                        } finally { main.post { recording = false } }
                    }.start()
                }, modifier = Modifier.weight(1f), enabled = !recording,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text(if (recording) "录音中…" else "录 3 秒", color = Color.White, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(refText, { refText = it; s.localRefText = it },
                label = { Text("刚才读的内容") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                if (!modelReady || !refReady) { toast("请先准备声音并录音"); return@OutlinedButton }
                if (refText.isBlank()) { toast("请填刚才读的内容"); return@OutlinedButton }
                if (busy) return@OutlinedButton
                busy = true
                scope.launch {
                    val f = File(ctx.cacheDir, "voice_preview.wav")
                    val err = local.synthesize("大家好，欢迎来到直播间。", refText, f)
                    withContext(Dispatchers.Main) {
                        busy = false
                        if (err == null) playPreview(vm, f, ctx) else toast("试听失败，请重试")
                    }
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                Text(if (busy) "准备中…" else "试听我的声音")
            }
        }

        // 主播音色（含克隆，按性别筛选；点击即切换）
        CardBox {
            Text("音色列表", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextMain)
            Hint("点击即切换播讲音色")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部" to "all", "女声" to "女声", "男声" to "男声").forEach { (label, key) ->
                    FilterChip(selected = filter == key, onClick = { filter = key },
                        label = { Text(label, fontSize = 11.sp) })
                }
            }
            Spacer(Modifier.height(4.dp))
            if (refReady) {
                Row(Modifier.fillMaxWidth().clickable {
                    mode = "mine"; s.voiceMode = "mine"; toast("已切换：我的声音")
                }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.RecordVoiceOver, null, tint = if (mode == "mine") Accent else Muted)
                    Spacer(Modifier.width(10.dp))
                    Text("我的声音", fontSize = 14.sp, color = if (mode == "mine") Accent else TextMain)
                    Spacer(Modifier.width(8.dp))
                    VoiceTag("克隆", Accent)
                    Spacer(Modifier.weight(1f))
                    if (mode == "mine") Text("使用中", color = Accent, fontSize = 11.sp)
                }
                HorizontalDivider(color = Line)
            }
            if (!packReady) {
                // 主播音色包一键下载（约180MB，一次下载离线可用）
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("专业主播音色包", fontSize = 14.sp, color = TextMain)
                            Hint("男女多款可选，下载后离线可用")
                        }
                        Button(onClick = {
                            if (packBusy) return@Button
                            packBusy = true; packProgress = 0
                            scope.launch {
                                val ok = anchor.downloadAll { done, total, _ -> packProgress = done * 100 / total }
                                withContext(Dispatchers.Main) {
                                    packBusy = false; packReady = ok
                                    toast(if (ok) "音色包准备好了" else "下载失败，请检查网络后重试")
                                    if (ok) vm.resumePlayback()
                                }
                            }
                        }, enabled = !packBusy, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                            Text(if (packBusy) "$packProgress%" else "下载", color = Color.White, fontSize = 12.sp)
                        }
                    }
                    if (packBusy) LinearProgressIndicator(packProgress / 100f, Modifier.fillMaxWidth())
                }
            } else {
                val shown = AnchorVoiceEngine.VOICES.filter { filter == "all" || it.gender == filter }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(shown) { v ->
                        val active = mode != "mine" && anchorSid == v.sid
                        Row(Modifier.fillMaxWidth().clickable {
                            anchorSid = v.sid; s.anchorVoiceSid = v.sid
                            mode = "default"; s.voiceMode = "default"
                            toast("已切换：${v.name}")
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.RecordVoiceOver, null, tint = if (active) Accent else Muted)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(v.name, fontSize = 14.sp, color = if (active) Accent else TextMain)
                                Spacer(Modifier.height(2.dp))
                                VoiceTag(v.gender, if (v.gender == "女声") Accent else Muted)
                            }
                            if (active) Text("使用中", color = Accent, fontSize = 11.sp)
                        }
                    }
                }
                if (mode == "mine" && refReady) {
                    Hint("当前使用我的声音，点上面列表切回主播音色")
                }
                OutlinedButton(onClick = { vm.speakText("大家好，欢迎来到直播间。") }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (mode == "mine" && refReady) "试听我的声音" else "试听")
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun VoiceTag(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color.copy(alpha = 0.1f), shape = RoundedCornerShape(4.dp)) {
        Text(text, color = color, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

private fun playPreview(vm: MainViewModel, f: File, ctx: android.content.Context) {
    try {
        val mp = android.media.MediaPlayer()
        mp.setDataSource(f.absolutePath)
        mp.setOnCompletionListener { it.release() }
        mp.prepare(); mp.start()
        android.widget.Toast.makeText(ctx, "正在播放复刻试听", android.widget.Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "播放失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun SettingsPage(vm: MainViewModel, s: Settings, ui: UiState, products: List<Product>, mod: Modifier) {
    var base by remember { mutableStateOf(s.apiBase) }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(s.apiModel) }
    var speed by remember { mutableFloatStateOf(s.ttsSpeed) }
    var jevOn by remember { mutableStateOf(s.jevEnabled) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sub by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    if (sub.isNotBlank()) {
        Box(mod.fillMaxSize().background(Bg)) {
            when (sub) {
                "店铺" -> ShopPage(s, Modifier)
                "产品" -> ProductPage(vm, products, Modifier)
                "音色" -> VoicePage(vm, s, Modifier)
            }
            Button(onClick = { sub = "" }, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("返回", fontSize = 12.sp, color = Color.White)
            }
        }
        return
    }
    Column(mod.fillMaxWidth().verticalScroll(rememberScrollState()).background(Bg)) {
        Spacer(Modifier.height(18.dp))
        Text("设置", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(14.dp))
        CardBox {
            Text("功能", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextMain)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("店铺", "产品", "音色").forEach { n ->
                    OutlinedButton(onClick = { sub = n }, modifier = Modifier.weight(1f)) { Text(n, fontSize = 12.sp) }
                }
            }
        }
        CardBox {
            Text("播讲语速", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextMain)
            Slider(speed, { speed = it; s.ttsSpeed = it; vm.tts.speed = it }, valueRange = 0.5f..2f)
            Text("${String.format("%.1f", speed)}x", color = Muted, fontSize = 12.sp)
        }
        CardBox {
            Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }, verticalAlignment = Alignment.CenterVertically) {
                Text("高级设置", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextMain, modifier = Modifier.weight(1f))
                Text(if (advanced) "收起" else "展开", color = Accent, fontSize = 12.sp)
            }
        }
        if (advanced) {
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(jevOn, { jevOn = it; s.jevEnabled = it })
                Text("智能话术", fontSize = 13.sp, color = TextMain)
            }
        }
        CardBox {
            Text("自定义话术接口", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextMain)
            Hint("填写接口后话术由它生成，仅保存在本机")
            OutlinedTextField(base, { base = it; s.apiBase = it }, label = { Text("base_url") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(key, { key = it }, label = { Text(if (s.apiKey.isNotBlank()) "API Key（留空不改）" else "API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(model, { model = it; s.apiModel = it }, label = { Text("模型名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(10.dp))
            Row {
                Button(onClick = {
                    if (key.isNotBlank()) s.apiKey = key
                    android.widget.Toast.makeText(ctx, "API 设置已保存", android.widget.Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("保存", color = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { scope.launch {
                    android.widget.Toast.makeText(ctx, vm.testApi(), android.widget.Toast.LENGTH_SHORT).show()
                } }, modifier = Modifier.weight(1f)) { Text("测试连接") }
            }
        }
        } // advanced
        Spacer(Modifier.height(30.dp))
    }
}
