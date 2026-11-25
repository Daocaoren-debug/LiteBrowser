package com.litebrowser.app.m3u8

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.litebrowser.app.R
import java.io.File
import java.util.concurrent.Executors

/**
 * 本地视频库：扫描 m3u8 / mp4，播放、筛选、批量转换、单删/批删。
 * 转换走前台服务，切后台也不会中断。
 */
class M3u8BrowserActivity : Activity() {

    private enum class Filter { ALL, M3U8, MP4 }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var countText: TextView
    private lateinit var adapter: FileAdapter

    private val executor = Executors.newSingleThreadExecutor()

    private var cachedFiles: List<MediaFile> = emptyList()
    private var lastScan: Long = 0L
    private var savedTreeUri: String? = null
    private var filter = Filter.ALL
    private var lastStatus = ""
    private var convertedKeys: Set<String> = emptySet()
    private lateinit var deleteSourceCheck: CheckBox
    private lateinit var deleteSegmentsCheck: CheckBox

    private val filterButtons: Array<Button> by lazy {
        arrayOf(
            findViewById<Button>(R.id.filterAll),
            findViewById<Button>(R.id.filterM3u8),
            findViewById<Button>(R.id.filterMp4)
        )
    }
    private val modeButton: Button by lazy { findViewById(R.id.modeButton) }

    private val convertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ConvertService.ACTION_PROGRESS -> {
                    val current = intent.getIntExtra(ConvertService.EXTRA_CURRENT, 0)
                    val total = intent.getIntExtra(ConvertService.EXTRA_TOTAL, 0)
                    val name = intent.getStringExtra(ConvertService.EXTRA_NAME).orEmpty()
                    countText.text = getString(R.string.convert_progress, current, total, name)
                }
                ConvertService.ACTION_REMOVED -> {
                    // 源 m3u8 已被转换服务删掉，列表里立刻移除，不等下次扫描
                    val path = intent.getStringExtra(ConvertService.EXTRA_PATH).orEmpty()
                    if (path.isNotEmpty()) {
                        val removed = cachedFiles.filter { it.path == path }
                        cachedFiles = cachedFiles.filterNot { it.path == path }
                        removed.forEach { ConvertStore.removeSource(applicationContext, it.path) }
                        ScanStore.save(this@M3u8BrowserActivity, ScanStore.Snapshot(cachedFiles, lastScan, savedTreeUri))
                        refreshUi()
                    }
                }
                ConvertService.ACTION_DONE -> {
                    val summary = intent.getStringExtra(ConvertService.EXTRA_SUMMARY).orEmpty()
                    val hasError = File(filesDir, ConvertService.ERROR_FILE).exists()
                    val builder = AlertDialog.Builder(this@M3u8BrowserActivity)
                        .setTitle("转换完成")
                        .setMessage(summary)
                        .setPositiveButton(android.R.string.ok, null)
                    if (hasError) {
                        builder.setNeutralButton("查看日志") { _, _ -> showConvertError() }
                    }
                    builder.show()
                    refreshUi()
                }
            }
        }
    }

    companion object {
        private const val REQ_STORAGE = 1001
        private const val REQ_MANAGE = 1002
        private const val REQ_TREE = 1003
        private const val PREFS = "litebrowser_settings"
        private const val KEY_DELETE_SOURCE = "delete_source_after_convert"
        private const val KEY_DELETE_SEGMENTS = "delete_segments_after_convert"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_m3u8_browser)

        recyclerView = findViewById(R.id.recyclerView)
        emptyText = findViewById(R.id.emptyText)
        progress = findViewById(R.id.progress)
        countText = findViewById(R.id.countText)
        deleteSourceCheck = findViewById(R.id.deleteSourceCheck)
        deleteSourceCheck.isChecked = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_DELETE_SOURCE, true)
        deleteSourceCheck.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_DELETE_SOURCE, checked).apply()
        }
        deleteSegmentsCheck = findViewById(R.id.deleteSegmentsCheck)
        deleteSegmentsCheck.isChecked = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_DELETE_SEGMENTS, false)
        deleteSegmentsCheck.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_DELETE_SEGMENTS, checked).apply()
        }

        adapter = FileAdapter(
            onClick = { openPlayer(it) },
            onConvert = { convertFiles(listOf(it)) },
            onDelete = { askDelete(listOf(it)) },
            onPath = { showPath(it) },
            onSelectionChanged = { updateCountText() }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<Button>(R.id.scanButton).setOnClickListener { startScan(incremental = true) }
        findViewById<Button>(R.id.rescanButton).setOnClickListener { startScan(incremental = false) }
        findViewById<Button>(R.id.folderButton).setOnClickListener { openFolderPicker() }

        filterButtons[0].setOnClickListener { setFilter(Filter.ALL) }
        filterButtons[1].setOnClickListener { setFilter(Filter.M3U8) }
        filterButtons[2].setOnClickListener { setFilter(Filter.MP4) }

        modeButton.setOnClickListener {
            adapter.selectionMode = !adapter.selectionMode
            if (!adapter.selectionMode) adapter.clearSelection()
            modeButton.setText(
                if (adapter.selectionMode) R.string.batch_done else R.string.batch_mode
            )
            adapter.notifyDataSetChanged()
            updateCountText()
        }
        findViewById<Button>(R.id.selectAllButton).setOnClickListener {
            if (!adapter.selectionMode) {
                Toast.makeText(this, getString(R.string.batch_mode), Toast.LENGTH_SHORT).show()
            } else {
                adapter.selectAll()
            }
        }
        findViewById<Button>(R.id.convertSelectedButton).setOnClickListener {
            val picked = selectedFiles()
            if (picked.isEmpty()) {
                Toast.makeText(this, getString(R.string.batch_mode), Toast.LENGTH_SHORT).show()
            } else {
                convertFiles(picked)
            }
        }
        findViewById<Button>(R.id.deleteSelectedButton).setOnClickListener {
            val picked = selectedFiles()
            if (picked.isEmpty()) {
                Toast.makeText(this, getString(R.string.batch_mode), Toast.LENGTH_SHORT).show()
            } else {
                askDelete(picked)
            }
        }

        updateFilterButtons()
        modeButton.setText(R.string.batch_mode)

        val snapshot = ScanStore.load(this)
        cachedFiles = snapshot.files
        lastScan = snapshot.lastScan
        savedTreeUri = snapshot.treeUri
        if (cachedFiles.isNotEmpty()) {
            lastStatus = getString(R.string.m3u8_loaded_cache, cachedFiles.size)
        }
        refreshUi()

        if (hasStoragePermission() || !savedTreeUri.isNullOrEmpty()) {
            startScan(incremental = true)
        } else {
            requestStoragePermission()
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(convertReceiver, IntentFilter(ConvertService.ACTION_PROGRESS).apply {
            addAction(ConvertService.ACTION_DONE)
            addAction(ConvertService.ACTION_REMOVED)
        })
        if (ConvertService.running) {
            countText.text = "视频转换进行中（${ConvertService.current}/${ConvertService.total}）"
        }
    }

    override fun onStop() {
        try {
            unregisterReceiver(convertReceiver)
        } catch (e: Exception) {
            // 未注册成功时忽略
        }
        super.onStop()
    }

    private fun selectedFiles(): List<MediaFile> =
        adapter.currentItems().filter { it.uri.toString() in adapter.selected }

    private fun setFilter(value: Filter) {
        filter = value
        adapter.selectionMode = false
        adapter.clearSelection()
        modeButton.setText(R.string.batch_mode)
        updateFilterButtons()
        refreshUi()
    }

    private fun updateFilterButtons() {
        val active = when (filter) {
            Filter.ALL -> 0
            Filter.M3U8 -> 1
            Filter.MP4 -> 2
        }
        filterButtons.forEachIndexed { index, button ->
            button.setBackgroundColor(
                if (index == active) Color.parseColor("#90CAF9") else Color.parseColor("#E0E0E0")
            )
        }
    }

    private fun refreshUi() {
        val visible = when (filter) {
            Filter.ALL -> cachedFiles
            Filter.M3U8 -> cachedFiles.filter { it.kind == MediaFile.Kind.M3U8 }
            Filter.MP4 -> cachedFiles.filter { it.kind == MediaFile.Kind.MP4 }
        }
        convertedKeys = ConvertStore.convertedKeys(applicationContext, visible)
        adapter.submitList(visible, convertedKeys)
        progress.visibility = View.GONE
        emptyText.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        updateCountText()
    }

    private fun updateCountText() {
        val base = lastStatus.ifBlank { getString(R.string.m3u8_hint_scan) }
        val lines = ArrayList<String>()
        lines += base
        if (filter != Filter.ALL) {
            lines += getString(
                R.string.count_filtered,
                adapter.itemCount,
                cachedFiles.size
            )
        }
        if (convertedKeys.isNotEmpty()) {
            lines += "已转换 ${convertedKeys.size} 个（不会再重复转换）"
        }
        if (adapter.selectionMode) {
            lines += getString(R.string.selection_hint, adapter.selectedCount())
        }
        countText.text = lines.joinToString("\n")
    }

    private fun showConvertError() {
        val text = try {
            File(filesDir, ConvertService.ERROR_FILE).readText(Charsets.UTF_8)
        } catch (e: Exception) {
            "日志读取失败：${e.message}"
        }
        AlertDialog.Builder(this)
            .setTitle("转换错误日志")
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showPath(file: MediaFile) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.path_dialog_title))
            .setMessage(file.path)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun convertFiles(files: List<MediaFile>) {
        if (ConvertService.running) {
            Toast.makeText(this, getString(R.string.convert_running), Toast.LENGTH_SHORT).show()
            return
        }

        // 双重去重：已转换记录 + 同名 mp4 已存在，都不再转第二次
        val pending = files.filter {
            it.convertible && File(it.path).exists() &&
                it.uri.toString() !in convertedKeys &&
                !File(ConvertService.outputPathOf(it.path)).exists()
        }
        if (pending.isEmpty()) {
            Toast.makeText(this, getString(R.string.convert_no_candidates), Toast.LENGTH_LONG).show()
            return
        }
        // FFmpegKit 只提供 64 位 so，32 位设备明确告知，避免只看到一句“失败”
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" || it == "x86_64" }) {
            Toast.makeText(
                this,
                "当前设备是 32 位，FFmpeg 转换不可用（仍可正常播放）",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (pending.size < files.size) {
            Toast.makeText(this, "已自动跳过转换过的文件", Toast.LENGTH_SHORT).show()
        }

        val deleteSource = deleteSourceCheck.isChecked
        val deleteSegments = deleteSource && deleteSegmentsCheck.isChecked

        fun showConfirm(message: String) {
            AlertDialog.Builder(this@M3u8BrowserActivity)
                .setTitle(getString(R.string.batch_convert))
                .setMessage(message)
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.batch_convert)) { _, _ ->
                    startConvert(pending, deleteSource, deleteSegments)
                }
                .show()
        }

        if (deleteSegments) {
            estimateRelease(pending) { bytes ->
                showConfirm(
                    getString(
                        R.string.convert_confirm_delete_with_size,
                        pending.size,
                        SegmentResolver.formatSize(bytes)
                    )
                )
            }
        } else {
            showConfirm(
                getString(
                    if (deleteSource) R.string.convert_confirm_delete else R.string.convert_confirm_keep,
                    pending.size
                )
            )
        }
    }

    /** 后台估算"删文件 + 清理其独占分片"能腾出多少空间 */
    private fun estimateRelease(files: List<MediaFile>, callback: (Long) -> Unit) {
        executor.execute {
            var bytes = files.sumOf { it.size }
            for (file in files) {
                bytes += SegmentResolver.sizeOf(
                    SegmentResolver.releasableSegments(applicationContext, file)
                )
            }
            runOnUiThread { callback(bytes) }
        }
    }

    private fun startConvert(
        pending: List<MediaFile>,
        deleteSource: Boolean,
        deleteSegments: Boolean
    ) {
        try {
            File(filesDir, ConvertService.ERROR_FILE).delete()
        } catch (e: Exception) {
            // 清不掉旧日志不影响转换
        }
        val intent = Intent(this, ConvertService::class.java)
            .putStringArrayListExtra(
                ConvertService.EXTRA_INPUTS,
                ArrayList(pending.map { it.path })
            )
            .putExtra(ConvertService.EXTRA_DELETE_SOURCE, deleteSource)
            .putExtra(ConvertService.EXTRA_DELETE_SEGMENTS, deleteSegments)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(
            this,
            getString(R.string.convert_started, pending.size),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun askDelete(files: List<MediaFile>) {
        // 删除的目的就是腾空间：无论开关如何，都连带清理独占分片
        estimateRelease(files) { bytes ->
            AlertDialog.Builder(this@M3u8BrowserActivity)
                .setTitle(getString(R.string.delete))
                .setMessage(
                    getString(
                        R.string.delete_confirm_with_size,
                        files.size,
                        SegmentResolver.formatSize(bytes)
                    )
                )
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.delete)) { _, _ -> deleteFiles(files) }
                .show()
        }
    }

    private fun deleteFiles(files: List<MediaFile>) {
        executor.execute {
            var deleted = 0
            var freed = 0L
            for (file in files) {
                try {
                    // 先算出这份列表独占的分片，再删列表本身
                    val segments = SegmentResolver.releasableSegments(applicationContext, file)
                    val segmentBytes = SegmentResolver.sizeOf(segments)
                    val ok = if (file.uri.scheme == "file") {
                        File(file.path).delete()
                    } else {
                        DocumentsContract.deleteDocument(contentResolver, file.uri)
                    }
                    if (ok) {
                        deleted++
                        freed += file.size + segmentBytes
                        SegmentResolver.delete(segments)
                        // 删掉输出 mp4 要让源 m3u8 恢复可转换；删源则清掉对应记录
                        if (file.kind == MediaFile.Kind.MP4) {
                            ConvertStore.removeOutput(applicationContext, file.path)
                        } else {
                            ConvertStore.removeSource(applicationContext, file.path)
                        }
                    }
                } catch (e: Exception) {
                    // 单个失败不影响其余文件
                }
            }

            runOnUiThread {
                if (deleted > 0) {
                    val removed = files.map { it.uri.toString() }.toHashSet()
                    cachedFiles = cachedFiles.filterNot { it.uri.toString() in removed }
                    ScanStore.save(this, ScanStore.Snapshot(cachedFiles, lastScan, savedTreeUri))
                    lastStatus = getString(
                        R.string.delete_with_freed,
                        SegmentResolver.formatSize(freed)
                    )
                    adapter.clearSelection()
                    refreshUi()
                } else {
                    Toast.makeText(this, getString(R.string.delete_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, REQ_MANAGE)
            } catch (e: Exception) {
                try {
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        REQ_MANAGE
                    )
                } catch (ignored: Exception) {
                    Toast.makeText(this, "无法打开权限设置，请用“选择文件夹”方式", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            requestPermissions(
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
                REQ_STORAGE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startScan(incremental = true)
            } else {
                Toast.makeText(this, "未获得存储权限，可用“选择文件夹”方式", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_MANAGE -> {
                if (hasStoragePermission()) {
                    startScan(incremental = true)
                } else {
                    Toast.makeText(this, "未开启“所有文件访问权限”，可改用“选择文件夹”", Toast.LENGTH_LONG).show()
                }
            }
            REQ_TREE -> {
                if (resultCode == Activity.RESULT_OK) {
                    val treeUri = data?.data ?: return
                    try {
                        contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        // 部分机型不支持持久化授权，仍可在本次会话中使用
                    }
                    savedTreeUri = treeUri.toString()
                    startScan(incremental = false)
                }
            }
        }
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        try {
            startActivityForResult(intent, REQ_TREE)
        } catch (e: Exception) {
            Toast.makeText(this, "此设备不支持文件夹选择", Toast.LENGTH_SHORT).show()
        }
    }

    /** @param incremental true 只找新增，false 全量重扫 */
    private fun startScan(incremental: Boolean) {
        val tree = savedTreeUri
        if (tree.isNullOrEmpty() && !hasStoragePermission()) {
            requestStoragePermission()
            return
        }

        val since = if (incremental) lastScan else 0L
        val knownPaths = if (incremental) {
            cachedFiles.filter { it.uri.scheme == "file" }.map { it.path }.toHashSet()
        } else HashSet()
        val knownUris = if (incremental) {
            cachedFiles.map { it.uri.toString() }.toHashSet()
        } else HashSet()

        progress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        countText.text =
            if (incremental) getString(R.string.m3u8_scanning_new) else getString(R.string.m3u8_scanning_all)

        executor.execute {
            val found = ArrayList<MediaFile>()
            try {
                if (!tree.isNullOrEmpty()) {
                    walkTree(DocumentFile.fromTreeUri(this, Uri.parse(tree)), found, 0, knownUris, since)
                } else {
                    found.addAll(Scanner.scanStorage(knownPaths, since))
                }
            } catch (e: Exception) {
                // 遍历异常不影响已找到的结果
            }

            val now = System.currentTimeMillis()
            val merged = ArrayList<MediaFile>()
            if (incremental) merged.addAll(cachedFiles)
            merged.addAll(found)

            val alive = ArrayList<MediaFile>(merged.size)
            for (f in merged) if (exists(f)) alive.add(f)
            val sorted = alive.distinctBy { it.uri.toString() }.sortedBy { it.name.lowercase() }

            ScanStore.save(this, ScanStore.Snapshot(sorted, now, tree))

            runOnUiThread {
                cachedFiles = sorted
                lastScan = now
                lastStatus = when {
                    sorted.isEmpty() -> getString(R.string.m3u8_empty_short)
                    incremental && found.isEmpty() -> getString(R.string.m3u8_count_no_new, sorted.size)
                    incremental -> getString(R.string.m3u8_count_with_new, sorted.size, found.size)
                    else -> getString(R.string.m3u8_count_rescanned, sorted.size)
                }
                refreshUi()
            }
        }
    }

    private fun exists(file: MediaFile): Boolean {
        if (file.uri.scheme != "file") return true
        return try {
            File(file.path).exists()
        } catch (e: Exception) {
            true
        }
    }

    private fun walkTree(
        dir: DocumentFile?,
        out: MutableList<MediaFile>,
        depth: Int,
        knownUris: Set<String>,
        since: Long
    ) {
        if (dir == null || depth > 12) return

        if (since > 0L) {
            val modified = try {
                dir.lastModified()
            } catch (e: Exception) {
                0L
            }
            if (modified in 1 until since) return
        }

        for (child in dir.listFiles()) {
            try {
                if (child.isDirectory) {
                    if (child.name?.startsWith(".") == true) continue
                    walkTree(child, out, depth + 1, knownUris, since)
                } else {
                    val name = child.name ?: continue
                    if (!Scanner.isMediaName(name.lowercase())) continue
                    if (child.uri.toString() in knownUris) continue
                    out.add(
                        MediaFile(
                            name = name,
                            path = pathFromDocumentUri(child.uri) ?: name,
                            size = child.length(),
                            uri = child.uri,
                            lastModified = child.lastModified()
                        )
                    )
                }
            } catch (e: Exception) {
                // 跳过无法访问的条目
            }
        }
    }

    /** 把外部存储的 content:// 文档 Uri 转回可读的真实路径（仅用于展示） */
    private fun pathFromDocumentUri(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            if (parts.size == 2 && parts[0].equals("primary", ignoreCase = true)) {
                "${Environment.getExternalStorageDirectory().absolutePath}/${parts[1]}"
            } else {
                docId
            }
        } catch (e: Exception) {
            uri.toString()
        }
    }

    private fun openPlayer(file: MediaFile) {
        val intent = Intent(this, M3u8PlayerActivity::class.java)
        intent.putExtra(M3u8PlayerActivity.EXTRA_URI, file.uri.toString())
        intent.putExtra(M3u8PlayerActivity.EXTRA_NAME, file.name)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(intent)
    }

    override fun onBackPressed() {
        if (adapter.selectionMode) {
            adapter.selectionMode = false
            adapter.clearSelection()
            modeButton.setText(R.string.batch_mode)
            adapter.notifyDataSetChanged()
            updateCountText()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}
