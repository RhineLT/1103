package com.example.ffmpegbatch

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

private val android.content.Context.dataStore by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {

    private val pickMultipleVideos = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris != null && uris.isNotEmpty()) {
            enqueueBatch(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                AppScreen(onPick = { pickVideos() })
            }
        }
    }

    private fun pickVideos() {
        pickMultipleVideos.launch(arrayOf("video/*"))
    }

    private fun enqueueBatch(uris: List<Uri>) {
        val workData = Data.Builder()
            .putStringArray(FfmpegWorker.KEY_INPUT_URIS, uris.map { it.toString() }.toTypedArray())
            .build()

        val request = OneTimeWorkRequestBuilder<FfmpegWorker>()
            .setInputData(workData)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(FfmpegWorker.TAG)
            .build()
        WorkManager.getInstance(this).enqueue(request)
    }
}

@Composable
fun AppScreen(onPick: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("队列", "设置")

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("FFmpeg 批量压缩") })
        },
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(onClick = onPick) { Text("选择") }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, text ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(text) })
                }
            }
            when (tab) {
                0 -> QueueView()
                1 -> SettingsView()
            }
        }
    }
}

@Composable
fun QueueView() {
    val ctx = LocalContext.current
    val wm = remember { WorkManager.getInstance(ctx) }
    val works by wm.getWorkInfosByTagLiveData(FfmpegWorker.TAG).observeAsState(initial = emptyList())

    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        items(works) { info ->
            val state = info.state
            Text("任务: ${info.id} - ${state}")
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun SettingsView() {
    val context = LocalContext.current as ComponentActivity
    val scope = rememberCoroutineScope()

    val keyCodec = stringPreferencesKey("vcodec")
    val keyCrf = intPreferencesKey("crf")
    val keyPreset = stringPreferencesKey("preset")

    val prefsFlow = context.dataStore.data
    val codec by prefsFlow.map { it[keyCodec] ?: "libx265" }.collectAsState(initial = "libx265")
    val crf by prefsFlow.map { it[keyCrf] ?: 28 }.collectAsState(initial = 28)
    val preset by prefsFlow.map { it[keyPreset] ?: "medium" }.collectAsState(initial = "medium")

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("编码器")
        OutlinedTextField(value = codec, onValueChange = { v ->
            scope.launch { context.dataStore.edit { it[keyCodec] = v } }
        })
        Text("CRF")
        OutlinedTextField(value = crf.toString(), onValueChange = { v ->
            v.toIntOrNull()?.let { iv -> scope.launch { context.dataStore.edit { it[keyCrf] = iv } } }
        })
        Text("Preset")
        OutlinedTextField(value = preset, onValueChange = { v ->
            scope.launch { context.dataStore.edit { it[keyPreset] = v } }
        })

        Divider()
        Text("预览等效指令（示例 input.mp4 -> output.mp4）")
        val cmd = remember(codec, crf, preset) {
            buildCommandPreview(codec, crf, preset)
        }
        Text(cmd, style = MaterialTheme.typography.bodySmall)
    }
}

fun buildCommandPreview(codec: String, crf: Int, preset: String): String {
    // 默认分辨率/帧率与原始文件相同 -> 不额外设置 -r/-vf
    return listOf(
        "ffmpeg",
        "-y",
        "-i", "input.mp4",
        "-c:v", codec,
        "-crf", crf.toString(),
        "-preset", preset,
        "-c:a", "copy",
        "output.mp4"
    ).joinToString(" ")
}

class FfmpegWorker(appContext: android.content.Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val KEY_INPUT_URIS = "input_uris"
        const val TAG = "ffmpeg-batch"
    }

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo("FFmpeg 处理中…"))
        val uris = inputData.getStringArray(KEY_INPUT_URIS)?.map { Uri.parse(it) } ?: return Result.failure()

    val prefs = applicationContext.dataStore.data.first()
        val codec = prefs[stringPreferencesKey("vcodec")] ?: "libx265"
        val crf = prefs[intPreferencesKey("crf")] ?: 28
        val preset = prefs[stringPreferencesKey("preset")] ?: "medium"

        ensureBundledFfmpeg()
        val ffmpeg = File(applicationContext.filesDir, currentAbi() + "/ffmpeg").absolutePath

        for (uri in uris) {
            val inputFile = copyUriToCache(uri)
            val outFile = createOutputFile(contentResolver = applicationContext.contentResolver, suggestedName = inputFile.nameWithoutExtension + "_h265.mp4")

            val args = listOf(
                ffmpeg, "-y", "-i", inputFile.absolutePath,
                "-c:v", codec,
                "-crf", crf.toString(),
                "-preset", preset,
                "-c:a", "copy",
                outFile.absolutePath
            )

            val proc = ProcessBuilder(args).redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    // TODO: 解析进度并 setForeground 进度
                }
            }
            val code = proc.waitFor()
            if (code != 0) return Result.retry()
        }
        return Result.success()
    }

    private fun copyUriToCache(uri: Uri): File {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "input.mp4"
        val out = File(applicationContext.cacheDir, name)
        applicationContext.contentResolver.openInputStream(uri).use { src ->
            FileOutputStream(out).use { dst -> src?.copyTo(dst) }
        }
        return out
    }

    private fun createOutputFile(contentResolver: ContentResolver, suggestedName: String): File {
        val dir = File(applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "FFmpegBatch")
        dir.mkdirs()
        return File(dir, suggestedName)
    }

    private fun ensureBundledFfmpeg() {
        val abi = currentAbi()
        val targetDir = File(applicationContext.filesDir, abi)
        if (!targetDir.exists()) targetDir.mkdirs()
        val target = File(targetDir, "ffmpeg")
        if (target.exists()) return
        // 从 assets 提取 ffmpeg/<abi>/ffmpeg
        val path = "ffmpeg/$abi/ffmpeg"
        applicationContext.assets.open(path).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        target.setExecutable(true)
    }

    private fun currentAbi(): String {
        return if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "arm64-v8a"
    }

    private fun createForegroundInfo(title: String): ForegroundInfo {
        val notificationId = 1001
        val channelId = "ffmpeg_channel"
        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOngoing(true)
            .build()
        return ForegroundInfo(notificationId, notification)
    }
}
