package com.leaf.hyperdragshare.codex

import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Shared dialog state enum (top-level so the top-level BigBangOverlayContent Composable can reference it)
private enum class DialogMode {
    NONE, EDIT, TAG_CLOUD, EXPORT
}

/**
 * DragShare's text-only BigBang surface. The selectable word-chip implementation is retained
 * from the reference project; Compose only supplies its floating overlay shell.
 */
class TextSegmentationActivity : ComponentActivity() {
    private var boomChipPage: BoomChipPage? = null
    private lateinit var legacyContentView: View
    private var launchTouchX by mutableIntStateOf(-1)
    private var launchTouchY by mutableIntStateOf(-1)
    private var currentText = ""
    private var currentSegment: IntArray? = null
    private var animatedDismissRequester: (() -> Unit)? = null
    private lateinit var exportLauncher: androidx.activity.result.ActivityResultLauncher<String>

    // ===== TTS (read selected words aloud) =====
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isSpeaking by mutableStateOf(false)

    // Unified Compose dialog state (keeps popups visually consistent with the segmentation panel)
    private var dialogMode by mutableStateOf(DialogMode.NONE)
    private var editDraft by mutableStateOf("")
    private var tagCloudItems by mutableStateOf<List<Map.Entry<String, Int>>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
            uri?.let { handleExportUri(it) }
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        updateLaunchPosition()

        legacyContentView = layoutInflater.inflate(R.layout.boom_activity_layout, null, false)
        legacyContentView.findViewById<View>(R.id.boom_page).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            BoomAnimator.makeFadeIn(this, BoomAnimator.BOOM_DURATION)
        }
        boomChipPage = BoomChipPage(this, legacyContentView, false).also { page ->
            page.restoreSelectedState(savedInstanceState?.getSerializable(SELECTED_STATE))
        }

        initTts()

        setContent {
            BigBangOverlayContent(
                contentView = legacyContentView,
                touchX = launchTouchX,
                touchY = launchTouchY,
                onDismissRequesterChanged = { animatedDismissRequester = it },
                onDismissRequest = { shouldDismissPage() },
                onDismissFinished = { finish() },
                onEditMode = { showEditDialog() },
                onSelectAll = { selectAll() },
                onShareAll = { shareAll() },
                onMore = { showTagCloud() },
                onExport = { exportContent() },
                onSpeak = { speakSelected() },
                isSpeaking = isSpeaking,
                dialogMode = dialogMode,
                editDraft = editDraft,
                tagCloudItems = tagCloudItems,
                onDialogEditConfirm = { newText ->
                    if (newText.isNotBlank()) {
                        currentText = ""
                        currentSegment = null
                        boomChipPage?.prepareForReinit()
                        segmentLocally(newText)
                    } else {
                        Toast.makeText(this, "文本不能为空", Toast.LENGTH_SHORT).show()
                    }
                    dialogMode = DialogMode.NONE
                },
                onDialogTagCloudCopy = {
                    val copyText = tagCloudItems.joinToString(", ") { it.key }
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("tags", copyText))
                    Toast.makeText(this, "已复制标签列表", Toast.LENGTH_SHORT).show()
                    dialogMode = DialogMode.NONE
                },
                onDialogExportPick = { which ->
                    when (which) {
                        0 -> exportLauncher.launch("分词笔记_${timestamp()}.txt")
                        1 -> exportLauncher.launch("分词笔记_${timestamp()}.pdf")
                        2 -> exportLauncher.launch("分词笔记_${timestamp()}.html")
                    }
                    dialogMode = DialogMode.NONE
                },
                onDialogDismiss = { dialogMode = DialogMode.NONE },
            )
        }

        val savedText = savedInstanceState?.getString(SAVED_TEXT)
        val savedSegment = savedInstanceState?.getIntArray(SAVED_SEGMENT)
        if (savedText != null && savedSegment != null) {
            handleSegmentResult(savedText, savedSegment, fromSavedState = true)
        } else {
            reinitializeFromIntent()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reinitializeFromIntent()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    fun requestAnimatedDismissFromLegacy() {
        animatedDismissRequester?.invoke() ?: finish()
    }

    /** Called by the imported selection toolbar. */
    fun openSearch(text: String, type: Int) {
        if (text.isBlank()) {
            return
        }
        val query = if (type == BoomActionHandler.SEARCH_DICTIONARY) "$text 词典" else text
        try {
            startActivity(
                Intent(Intent.ACTION_WEB_SEARCH)
                    .putExtra(SearchManager.QUERY, query),
            )
        } catch (_: Throwable) {
            try {
                val uri = Uri.Builder()
                    .scheme("https")
                    .authority("www.google.com")
                    .appendPath("search")
                    .appendQueryParameter("q", query)
                    .build()
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Throwable) {
                Toast.makeText(this, "无法打开搜索", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Translates the selected text and rebuilds the word chips with the result. */
    fun translateSelected(text: String) {
        if (text.isBlank()) {
            return
        }
        Toast.makeText(this, R.string.bigbang_translate_started, Toast.LENGTH_SHORT).show()
        Thread({
            try {
                val translated = TextTranslationEngine.translate(
                    this,
                    text,
                    TranslationSettings.readLocal(this),
                )
                runOnUiThread {
                    if (!isFinishing && translated.isNotBlank()) {
                        currentText = ""
                        currentSegment = null
                        boomChipPage?.prepareForReinit()
                        segmentLocally(translated)
                    }
                }
            } catch (error: Throwable) {
                DragShareLog.w(TAG, "translation failed", error)
                runOnUiThread {
                    if (!isFinishing) {
                        Toast.makeText(
                            this,
                            getString(R.string.bigbang_translate_failed, error.message ?: ""),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }, "drag-share-translate").start()
    }

    private fun updateLaunchPosition() {
        launchTouchX = intent.getIntExtra(EXTRA_TOUCH_X, -1)
        launchTouchY = intent.getIntExtra(EXTRA_TOUCH_Y, -1)
    }

    private fun reinitializeFromIntent() {
        val inputText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (inputText.isNullOrBlank()) {
            finish()
            return
        }
        updateLaunchPosition()
        currentText = ""
        currentSegment = null
        boomChipPage?.prepareForReinit()
        segmentLocally(inputText)
    }

    private fun segmentLocally(text: String) {
        Thread({
            try {
                val result = TextSegmenter.get(this).segment(text)
                runOnUiThread {
                    if (!isFinishing) {
                        handleSegmentResult(text, result)
                    }
                }
            } catch (error: Throwable) {
                DragShareLog.w(TAG, "local text segmentation failed", error)
                runOnUiThread {
                    if (!isFinishing) {
                        Toast.makeText(this, R.string.a_msg_no_words, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
        }, "drag-share-segmenter").start()
    }

    private fun handleSegmentResult(
        text: String,
        result: IntArray?,
        fromSavedState: Boolean = false,
    ) {
        if (result == null || result.isEmpty()) {
            Toast.makeText(this, R.string.a_msg_no_words, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (boomChipPage?.initWords(result, text, -1, launchTouchX, launchTouchY) != true) {
            Toast.makeText(this, R.string.a_msg_no_words, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentText = text
        currentSegment = result
        if (fromSavedState) {
            // The chip core restores the serialised selection during its first layout pass.
        }
    }

    private fun shouldDismissPage(): Boolean = boomChipPage?.handleClick() != true

    private fun selectAll() {
        boomChipPage?.selectAll()
    }

    private fun shareAll() {
        val shareText = boomChipPage?.originalText ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        startActivity(Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        })
    }

    // ===== New features =====

    private fun showEditDialog() {
        editDraft = currentText
        dialogMode = DialogMode.EDIT
    }

    // ===== TTS: read selected words (or whole text) aloud =====

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                    }
                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                    }
                })
            } else {
                Toast.makeText(this, "朗读引擎不可用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun speakSelected() {
        if (isSpeaking) {
            stopSpeaking()
            return
        }
        val page = boomChipPage
        val text = if (page != null && page.hasSelection()) {
            page.getSelectedText()
        } else {
            currentText
        }
        if (text.isBlank()) {
            Toast.makeText(this, "没有可朗读的内容", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ttsReady) {
            Toast.makeText(this, "朗读引擎未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "drag_share_tts")
    }

    private fun stopSpeaking() {
        tts?.stop()
        isSpeaking = false
    }

    private fun showTagCloud() {
        val text = currentText
        if (text.isBlank()) {
            Toast.makeText(this, "没有可分析的文本", Toast.LENGTH_SHORT).show()
            return
        }
        val words = text.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 }
            .map { it.lowercase(Locale.getDefault()) }
        if (words.isEmpty()) {
            Toast.makeText(this, "没有足够的关键词", Toast.LENGTH_SHORT).show()
            return
        }
        val freq = words.groupingBy { it }.eachCount()
        tagCloudItems = freq.entries.sortedByDescending { it.value }.take(30)
        dialogMode = DialogMode.TAG_CLOUD
    }

    private fun exportContent() {
        val text = currentText
        if (text.isBlank()) {
            Toast.makeText(this, "没有可导出的内容", Toast.LENGTH_SHORT).show()
            return
        }
        dialogMode = DialogMode.EXPORT
    }

    private fun handleExportUri(uri: Uri) {
        val text = currentText
        if (text.isBlank()) {
            Toast.makeText(this, "没有可导出的内容", Toast.LENGTH_SHORT).show()
            return
        }
        val type = contentResolver.getType(uri) ?: run {
            // fallback: determine from file extension
            val path = uri.path ?: return
            when {
                path.endsWith(".pdf") -> "application/pdf"
                path.endsWith(".html") -> "text/html"
                else -> "text/plain"
            }
        }
        Toast.makeText(this, "正在生成...", Toast.LENGTH_SHORT).show()
        Thread({
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    when {
                        type == "application/pdf" -> writePdf(output, text)
                        type == "text/html" -> writeHtml(output, text)
                        else -> writePlain(output, text)
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                DragShareLog.w(TAG, "export failed", e)
                runOnUiThread {
                    Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }, "drag-share-export").start()
    }

    private fun writePlain(output: java.io.OutputStream, text: String) {
        output.write(text.toByteArray(Charsets.UTF_8))
    }

    private fun writeHtml(output: java.io.OutputStream, text: String) {
        val html = """
            <html><head><meta charset="UTF-8"><title>分词笔记</title></head>
            <body><pre>$text</pre></body></html>
        """.trimIndent()
        output.write(html.toByteArray(Charsets.UTF_8))
    }

    private fun writePdf(output: java.io.OutputStream, text: String) {
        val pdf = android.graphics.pdf.PdfDocument()
        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(612, 792, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas
        val paint = android.graphics.Paint().apply {
            color = AndroidColor.BLACK
            textSize = 12f
        }
        val lines = text.split("\n")
        var y = 50f
        for (line in lines) {
            if (y > 750) break
            canvas.drawText(line, 50f, y, paint)
            y += 20f
        }
        pdf.finishPage(page)
        pdf.writeTo(output)
        pdf.close()
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }

    // ===== End of new features =====

    override fun onSaveInstanceState(outState: Bundle) {
        boomChipPage?.captureSelectedState()?.let { outState.putSerializable(SELECTED_STATE, it) }
        currentSegment?.let {
            outState.putString(SAVED_TEXT, currentText)
            outState.putIntArray(SAVED_SEGMENT, it)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    companion object {
        @JvmField
        val DBG = true

        private const val TAG = "DragShare/BigBang"
        private const val EXTRA_TOUCH_X = "dragshare_boom_touch_x"
        private const val EXTRA_TOUCH_Y = "dragshare_boom_touch_y"
        private const val SELECTED_STATE = "selected_state"
        private const val SAVED_TEXT = "saved_text"
        private const val SAVED_SEGMENT = "saved_segment"

        @JvmStatic
        fun createIntent(text: String): Intent = createIntent(text, -1, -1)

        @JvmStatic
        fun createIntent(text: String, touchX: Int, touchY: Int): Intent {
            return Intent(Intent.ACTION_SEND).apply {
                component = ComponentName(
                    BuildConfig.APPLICATION_ID,
                    TextSegmentationActivity::class.java.name,
                )
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(EXTRA_TOUCH_X, touchX)
                putExtra(EXTRA_TOUCH_Y, touchY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
        }
    }
}

@Composable
private fun BigBangOverlayContent(
    contentView: View,
    touchX: Int,
    touchY: Int,
    onDismissRequesterChanged: ((() -> Unit)?) -> Unit,
    onDismissRequest: () -> Boolean,
    onDismissFinished: () -> Unit,
    onEditMode: () -> Unit,
    onSelectAll: () -> Unit,
    onShareAll: () -> Unit,
    onMore: () -> Unit,
    onExport: () -> Unit,
    onSpeak: () -> Unit,
    isSpeaking: Boolean,
    dialogMode: DialogMode,
    editDraft: String,
    tagCloudItems: List<Map.Entry<String, Int>>,
    onDialogEditConfirm: (String) -> Unit,
    onDialogTagCloudCopy: () -> Unit,
    onDialogExportPick: (Int) -> Unit,
    onDialogDismiss: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val panelMetrics = rememberOverlayPanelMetrics()
    val panelBackground = if (dark) Color(0xFF171B20) else Color(0xFFF3F3F4)
    val panelBorder = if (dark) Color(0xFF2E353E) else Color(0xFFD7D7DA)
    val topBarColor = if (dark) Color(0xFF1D2126) else Color.White
    val bottomBarColor = if (dark) Color(0xFF1D2126) else Color.White
    val scrimColor = if (dark) Color.Black.copy(alpha = 0.62f) else Color.Black.copy(alpha = 0.48f)
    val shadowColor = Color.Black.copy(alpha = 0.5f)
    val panelShape = androidx.compose.foundation.shape.RoundedCornerShape(panelMetrics.cornerRadius)
    var panelVisible by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    var panelBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val enterProgress by animateFloatAsState(
        targetValue = if (panelVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "bigbang_panel_enter",
        finishedListener = {
            if (dismissing && it == 0f) {
                onDismissFinished()
            }
        },
    )
    val scrimProgress by animateFloatAsState(
        targetValue = if (panelVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "bigbang_scrim_enter",
    )
    val transformOrigin = remember(panelBounds, touchX, touchY) {
        val bounds = panelBounds
        if (bounds == null || touchX < 0 || touchY < 0) {
            TransformOrigin.Center
        } else {
            TransformOrigin(
                pivotFractionX = ((touchX - bounds.left) / bounds.width).coerceIn(0f, 1f),
                pivotFractionY = ((touchY - bounds.top) / bounds.height).coerceIn(0f, 1f),
            )
        }
    }
    val panelScale = 0.84f + (0.16f * enterProgress)
    val requestDismiss = {
        if (!dismissing && onDismissRequest()) {
            dismissing = true
            panelVisible = false
        }
    }

    DisposableEffect(requestDismiss) {
        onDismissRequesterChanged(requestDismiss)
        onDispose { onDismissRequesterChanged(null) }
    }
    LaunchedEffect(Unit) { panelVisible = true }

    BackHandler(onBack = requestDismiss)
    ApplyOverlaySystemBars(
        statusBarColor = if (panelMetrics.fullScreen) topBarColor else Color.Transparent,
        navigationBarColor = if (panelMetrics.fullScreen) bottomBarColor else Color.Transparent,
        darkIcons = !dark,
    )
    OverlayScene(
        scrimColor = scrimColor.copy(alpha = scrimColor.alpha * scrimProgress),
        onDismiss = requestDismiss,
    ) {
        FloatingPanel(
            width = panelMetrics.width,
            height = panelMetrics.height,
            fillMax = panelMetrics.fullScreen,
            modifier = overlayPanelPlacement(panelMetrics)
                .onGloballyPositioned { coordinates -> panelBounds = coordinates.boundsInWindow() }
                .graphicsLayer {
                    alpha = enterProgress
                    scaleX = panelScale
                    scaleY = panelScale
                    this.transformOrigin = transformOrigin
                },
            shape = panelShape,
            backgroundColor = panelBackground,
            borderColor = panelBorder,
            shadowColor = if (panelMetrics.multiWindow) null else shadowColor,
        ) {
            OverlayPanelScaffold(
                topBar = {
                    OverlayHeaderBar(
                        backgroundColor = topBarColor,
                        topInset = panelMetrics.topSystemInset,
                        leftInset = panelMetrics.leftSystemInset,
                        rightInset = panelMetrics.rightSystemInset,
                        leading = {
                            OverlayIconAction(
                                imageVector = Icons.Outlined.Edit,
                                tint = if (dark) Color(0xFFD7DEE7) else Color(0xFF6F6962),
                                onClick = onEditMode,
                                contentDescription = stringResource(R.string.bigbang_action_edit),
                            )
                            OverlayIconAction(
                                imageVector = Icons.Outlined.SelectAll,
                                tint = if (dark) Color(0xFFF2F5F8) else Color(0xFF6C6760),
                                onClick = onSelectAll,
                                contentDescription = stringResource(R.string.bigbang_action_select_all),
                            )
                        },
                        center = {
                            androidx.compose.material3.Text(
                                text = stringResource(R.string.bigbang_overlay_title),
                                color = if (dark) Color(0xFFF2F5F8) else Color(0xFFD1CCC6),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        trailing = {
                            OverlayIconAction(
                                imageVector = Icons.Outlined.Share,
                                tint = if (dark) Color(0xFFF2F5F8) else Color(0xFF6C6760),
                                onClick = onShareAll,
                                contentDescription = stringResource(R.string.bigbang_action_share_all),
                            )
                            OverlayIconAction(
                                imageVector = Icons.Outlined.MoreHoriz,
                                tint = if (dark) Color(0xFFD7DEE7) else Color(0xFF6F6962),
                                onClick = onMore,
                                contentDescription = stringResource(R.string.bigbang_action_more),
                            )
                        },
                    )
                },
                bottomBar = {
                    OverlayBottomBar(
                        backgroundColor = bottomBarColor,
                        bottomInset = panelMetrics.bottomSystemInset,
                        leftInset = panelMetrics.leftSystemInset,
                        rightInset = panelMetrics.rightSystemInset,
                        leading = {
                            OverlayIconAction(
                                imageVector = Icons.Outlined.DocumentScanner,
                                tint = if (dark) Color(0xFFF2F5F8) else Color(0xFF6C6760),
                                enabled = true,
                                onClick = onExport,
                                contentDescription = stringResource(R.string.bigbang_action_ocr),
                            )
                        },
                        center = {
                            OverlayIconAction(
                                iconRes = R.drawable.boom_cancel,
                                tint = if (dark) Color(0xFFF2F5F8) else Color(0xFF8D8983),
                                onClick = requestDismiss,
                                contentDescription = stringResource(R.string.search_overlay_close),
                            )
                        },
                        trailing = {
                            OverlayIconAction(
                                imageVector = Icons.Outlined.VolumeUp,
                                tint = if (isSpeaking) Color(0xFFFF5252) else if (dark) Color(0xFFF2F5F8) else Color(0xFF6C6760),
                                enabled = true,
                                onClick = onSpeak,
                                contentDescription = stringResource(R.string.bigbang_action_speak),
                            )
                        },
                    )
                },
) { bodyModifier ->
                Column(modifier = bodyModifier.fillMaxSize()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(bottom = 4.dp),
                        factory = { contentView },
                    )
                }
            }
        }
    }

    if (dialogMode != DialogMode.NONE) {
        val dialogBg = if (dark) Color(0xFF1D2126) else Color.White
        val dialogText = if (dark) Color(0xFFF2F5F8) else Color(0xFF6C6760)
        val dialogTitle = if (dark) Color(0xFFF2F5F8) else Color(0xFF6C6760)
        val dialogAccent = if (dark) Color(0xFFF2F5F8) else Color(0xFF6C6760)
        var editInput by remember { mutableStateOf(editDraft) }
        AlertDialog(
            onDismissRequest = onDialogDismiss,
            containerColor = dialogBg,
            tonalElevation = 0.dp,
            title = {
                Text(
                    text = when (dialogMode) {
                        DialogMode.EDIT -> "编辑原文"
                        DialogMode.TAG_CLOUD -> "高频词标签"
                        DialogMode.EXPORT -> "导出为"
                        else -> ""
                    },
                    color = dialogTitle,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                when (dialogMode) {
                    DialogMode.EDIT -> {
                        OutlinedTextField(
                            value = editInput,
                            onValueChange = { editInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(color = dialogText, fontSize = 15.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = dialogAccent,
                                unfocusedBorderColor = dialogText.copy(alpha = 0.5f),
                                cursorColor = dialogAccent,
                            ),
                        )
                    }
                    DialogMode.TAG_CLOUD -> {
                        val items = tagCloudItems
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                            items(items.size) { idx ->
                                val e = items[idx]
                                Text(
                                    text = "${e.key}  (${e.value})",
                                    color = dialogText,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                        }
                    }
                    DialogMode.EXPORT -> {
                        val formats = listOf("纯文本 (.txt)", "PDF (.pdf)", "Word (.html)")
                        Column(modifier = Modifier.fillMaxWidth()) {
                            formats.forEachIndexed { index, label ->
                                Text(
                                    text = label,
                                    color = dialogText,
                                    fontSize = 15.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onDialogExportPick(index) }
                                        .padding(vertical = 12.dp),
                                )
                            }
                        }
                    }
                    else -> {}
                }
            },
            confirmButton = {
                when (dialogMode) {
                    DialogMode.EDIT -> {
                        TextButton(onClick = {
                            onDialogEditConfirm(editInput)
                        }) {
                            Text("更新", color = dialogAccent)
                        }
                    }
                    DialogMode.TAG_CLOUD -> {
                        TextButton(onClick = onDialogTagCloudCopy) {
                            Text("复制标签列表", color = dialogAccent)
                        }
                    }
                    else -> {}
                }
            },
            dismissButton = {
                TextButton(onClick = onDialogDismiss) {
                    Text("取消", color = dialogText)
                }
            },
        )
    }
}
