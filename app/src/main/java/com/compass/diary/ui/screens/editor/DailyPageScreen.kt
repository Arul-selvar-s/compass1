package com.compass.diary.ui.screens.editor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.compass.diary.ui.components.DrawingCanvas
import com.compass.diary.ui.theme.CompassColors
import com.compass.diary.util.SaveState
import com.compass.diary.viewmodel.DiaryViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Fmt { BOLD, ITALIC, UNDERLINE, STRIKE }
private data class RSpan(val s: Int, val e: Int, val f: Fmt)

private fun Fmt.toSpanStyle() = when (this) {
    Fmt.BOLD      -> SpanStyle(fontWeight = FontWeight.Bold)
    Fmt.ITALIC    -> SpanStyle(fontStyle  = FontStyle.Italic)
    Fmt.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
    Fmt.STRIKE    -> SpanStyle(textDecoration = TextDecoration.LineThrough)
}

private fun adjustSpans(spans: List<RSpan>, oldLen: Int, newLen: Int, chAt: Int, delEnd: Int): List<RSpan> {
    val d = newLen - oldLen; if (d == 0) return spans
    return spans.mapNotNull { sp ->
        var s = when { sp.s < chAt -> sp.s; sp.s < delEnd -> chAt; else -> sp.s + d }
        var e = when { sp.e <= chAt -> sp.e; sp.e < delEnd -> chAt; else -> sp.e + d }
        if (s < 0) s = 0; if (e > newLen) e = newLen
        if (s >= e) null else sp.copy(s = s, e = e)
    }
}

private const val M_LOCK  = "\u2060L\u2060"
private const val M_DRAFT = "\u2060D\u2060"
private const val M_SPANS = "\u2060S\u2060"

private fun packContent(locked: String, draftText: String, spans: List<RSpan>): String {
    val sp = spans.joinToString(";") { "${it.s},${it.e},${it.f.name}" }
    return "$M_LOCK$locked$M_DRAFT$draftText$M_SPANS$sp"
}

private fun unpackContent(raw: String): Triple<String, String, List<RSpan>> {
    if (!raw.contains(M_LOCK)) return Triple("", raw, emptyList())
    return try {
        val locked    = raw.substringAfter(M_LOCK).substringBefore(M_DRAFT)
        val rest      = raw.substringAfter(M_DRAFT, "")
        val draftText = rest.substringBefore(M_SPANS)
        val spanStr   = rest.substringAfter(M_SPANS, "")
        val spans     = if (spanStr.isBlank()) emptyList()
        else spanStr.split(";").mapNotNull { p ->
            val t = p.split(",")
            if (t.size == 3) runCatching { RSpan(t[0].toInt(), t[1].toInt(), Fmt.valueOf(t[2])) }.getOrNull()
            else null
        }
        Triple(locked, draftText, spans)
    } catch (e: Exception) { Triple("", raw, emptyList()) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyPageScreen(
    dateKey: String,
    onBack: () -> Unit,
    onAI: () -> Unit,
    viewModel: DiaryViewModel = hiltViewModel()
) {
    val entry     by viewModel.currentEntry.collectAsState()
    val saveState by viewModel.saveState.collectAsState()

    var lockedField by remember(dateKey) { mutableStateOf(TextFieldValue("")) }
    var draft       by remember { mutableStateOf(TextFieldValue()) }
    var spans       by remember { mutableStateOf(listOf<RSpan>()) }
    var loaded      by remember(dateKey) { mutableStateOf(false) }
    var showDrawing by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var starredDay  by remember(dateKey) { mutableStateOf(false) }

    LaunchedEffect(entry) {
        if (!loaded && entry != null && entry!!.dateKey == dateKey) {
            val (locked, draftText, draftSpans) = unpackContent(entry!!.contentJson)
            lockedField = TextFieldValue(locked)
            if (draftText.isNotEmpty()) draft = TextFieldValue(draftText)
            spans = draftSpans
            loaded = true
        }
    }
    LaunchedEffect(dateKey) { viewModel.selectEntry(dateKey) }

    val scope  = rememberCoroutineScope()
    var saveJob by remember { mutableStateOf<Job?>(null) }
    fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(800)
            viewModel.onContentChanged(dateKey, packContent(lockedField.text, draft.text, spans))
        }
    }
    fun saveImmediately() {
        saveJob?.cancel()
        viewModel.forceSave(dateKey, packContent(lockedField.text, draft.text, spans))
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, dateKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                saveImmediately()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val annotated = remember(draft.text, spans) {
        buildAnnotatedString {
            append(draft.text)
            spans.forEach { sp ->
                if (sp.s >= 0 && sp.e <= draft.text.length && sp.s < sp.e)
                    addStyle(sp.f.toSpanStyle(), sp.s, sp.e)
            }
        }
    }

    fun applyFmt(f: Fmt) {
        val sel = draft.selection
        if (!sel.collapsed) { spans = spans + RSpan(sel.min, sel.max, f) }
    }

    if (showDrawing) {
        DrawingCanvas(
            onSave  = { paths -> viewModel.saveDrawing(dateKey, paths); showDrawing = false },
            onClose = { showDrawing = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { saveImmediately(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = {
                    Column {
                        Text(entry?.title ?: dateKey,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text(
                            when (saveState) {
                                SaveState.SAVING -> "Saving…"
                                SaveState.SAVED  -> "Saved ✓"
                                SaveState.ERROR  -> "Save error"
                                else -> "${entry?.wordCount ?: 0} words"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when (saveState) {
                                SaveState.SAVED -> CompassColors.Success
                                SaveState.ERROR -> CompassColors.Error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.starWholeDay(dateKey)
                        starredDay = true
                    }) {
                        Icon(
                            if (starredDay) Icons.Default.Star else Icons.Default.StarBorder,
                            "Star this day",
                            tint = CompassColors.Star
                        )
                    }
                    if (draft.text.isNotBlank()) {
                        IconButton(onClick = { showConfirm = true }) {
                            Icon(Icons.Default.Save, "Save & Lock", tint = CompassColors.Gold400)
                        }
                    }
                    IconButton(onClick = onAI) { Icon(Icons.Default.AutoAwesome, "AI") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FmtBtn("B", FontWeight.Bold)              { applyFmt(Fmt.BOLD) }
                FmtBtn("I", style = FontStyle.Italic)     { applyFmt(Fmt.ITALIC) }
                FmtBtn("U", deco = TextDecoration.Underline) { applyFmt(Fmt.UNDERLINE) }
                FmtBtn("S̶", deco = TextDecoration.LineThrough) { applyFmt(Fmt.STRIKE) }
                VerticalDivider(Modifier.height(28.dp).padding(horizontal = 4.dp))
                FmtIcon(Icons.Default.List)            { draft = draft.ins("\n• "); scheduleSave() }
                FmtIcon(Icons.Default.FormatListNumbered) { draft = draft.ins("\n${draft.text.count { it == '\n' } + 1}. "); scheduleSave() }
                FmtIcon(Icons.Default.CheckBox)        { draft = draft.ins("\n☐ "); scheduleSave() }
                VerticalDivider(Modifier.height(28.dp).padding(horizontal = 4.dp))
                FmtIcon(Icons.Default.Draw) { showDrawing = true }
                FmtIcon(Icons.Default.Star) {
                    val sel = draft.selection
                    if (!sel.collapsed) viewModel.starContent(dateKey, draft.text.substring(sel.min, sel.max))
                }
            }
            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .imePadding()
            ) {
                if (lockedField.text.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lock, null,
                                    Modifier.size(13.dp), tint = CompassColors.Success)
                                Spacer(Modifier.width(4.dp))
                                Text("Saved — read only • select text to star it",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CompassColors.Success)
                            }
                            Spacer(Modifier.height(8.dp))

                            BasicTextField(
                                value = lockedField,
                                onValueChange = { lockedField = it },
                                readOnly = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                ),
                                cursorBrush = SolidColor(Color.Transparent)
                            )

                            if (!lockedField.selection.collapsed) {
                                Spacer(Modifier.height(6.dp))
                                TextButton(onClick = {
                                    val sel = lockedField.selection
                                    viewModel.starContent(dateKey, lockedField.text.substring(sel.min, sel.max))
                                    lockedField = lockedField.copy(selection = TextRange(sel.max))
                                }) {
                                    Icon(Icons.Default.Star, null, tint = CompassColors.Star, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Star selected text", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 16.dp))
                    Text("Continue below ↓",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(12.dp))
                }

                BasicTextField(
                    value = draft.copy(annotatedString = annotated),
                    onValueChange = { nv ->
                        spans = adjustSpans(spans, draft.text.length, nv.text.length,
                            draft.selection.min, draft.selection.max)
                        draft = nv
                        scheduleSave()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 260.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color      = MaterialTheme.colorScheme.onBackground,
                        lineHeight = 26.sp
                    ),
                    cursorBrush = SolidColor(CompassColors.Blue400),
                    decorationBox = { inner ->
                        if (draft.text.isEmpty()) {
                            Text(
                                if (lockedField.text.isBlank()) "Write anything…" else "Continue your entry…",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            )
                        }
                        inner()
                    }
                )
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            icon  = { Icon(Icons.Default.Lock, null, tint = CompassColors.Gold400) },
            title = { Text("Save and lock?") },
            text  = {
                Text("The current text will be permanently saved and locked.\n\n" +
                     "You can keep writing below it, but the locked part cannot be edited — only selected and starred.\n\n" +
                     "Your diary will also be backed up to Google Drive.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val combined = if (lockedField.text.isBlank()) draft.text
                                       else "${lockedField.text}\n\n${draft.text}"
                        lockedField = TextFieldValue(combined)
                        draft       = TextFieldValue()
                        spans       = emptyList()
                        showConfirm = false
                        viewModel.saveAndLock(dateKey, packContent(combined, "", emptyList()))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CompassColors.Gold400)
                ) { Text("Save & Lock") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Not yet") }
            }
        )
    }
}

@Composable
private fun FmtBtn(
    label: String,
    weight: FontWeight = FontWeight.Normal,
    style: FontStyle = FontStyle.Normal,
    deco: TextDecoration? = null,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, shape = RoundedCornerShape(6.dp), color = Color.Transparent,
        modifier = Modifier.size(36.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontWeight = weight, fontStyle = style,
                style = MaterialTheme.typography.labelLarge.copy(textDecoration = deco),
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun FmtIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
    }
}

private fun TextFieldValue.ins(text: String): TextFieldValue {
    val p = selection.end
    return TextFieldValue(this.text.substring(0, p) + text + this.text.substring(p), TextRange(p + text.length))
}
