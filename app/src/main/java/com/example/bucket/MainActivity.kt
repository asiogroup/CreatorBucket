package com.example.bucket

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.bucket.theme.BucketTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Date
import java.util.UUID

private data class CreatorColors(val primary: Color, val mist: Color, val ink: Color, val screen: Color, val card: Color, val muted: Color)
private val CleanColors = CreatorColors(Color(0xFF1F7A51), Color(0xFFE5F4EA), Color(0xFF18231D), Color(0xFFF9FAF8), Color.White, Color(0xFF64736B))
private val CreativeColors = CreatorColors(Color(0xFF116C4B), Color(0xFFE5F9DD), Color(0xFF10281F), Color(0xFFF0FBF0), Color(0xF7FFFFFF), Color(0xFF41685A))
private val DarkColors = CreatorColors(Color(0xFF87E0B2), Color(0xFF173C2E), Color(0xFFF1FAF4), Color(0xFF0F1713), Color(0xFF18251E), Color(0xFFA8BDB0))
private val LocalColors = staticCompositionLocalOf { CleanColors }
private val LocalAppTheme = staticCompositionLocalOf { AppTheme.CLEAN }
private val colors: CreatorColors @Composable get() = LocalColors.current

class MainActivity : ComponentActivity() {
  private lateinit var store: BucketStore
  private var buckets by mutableStateOf<List<Bucket>>(emptyList())
  private var incoming by mutableStateOf<List<Uri>>(emptyList())
  private var selectedBucketId by mutableStateOf<String?>(null)
  private var directBucketId: String? = null
  private var isWorking by mutableStateOf(false)
  private var message by mutableStateOf<String?>(null)
  private var importBehavior by mutableStateOf(ImportBehavior.COPY)
  private var appTheme by mutableStateOf(AppTheme.CLEAN)
  private var homeLayout by mutableStateOf(HomeLayout.TIMELINE)
  private var pendingMoveCount = 0

  private val importPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { receiveUris(it) }
  private val exportPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri -> selectedBucketId?.let(::bucketById)?.let { bucket -> if (treeUri != null) exportBucket(bucket, treeUri) } }
  private val moveConfirmation = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
    message = if (result.resultCode == Activity.RESULT_OK) "Moved $pendingMoveCount source video${if (pendingMoveCount == 1) "" else "s"}." else "Videos were copied; the originals were kept because deletion was not confirmed."
    pendingMoveCount = 0
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    store = BucketStore(this)
    buckets = store.load(); importBehavior = store.importBehavior(); appTheme = store.appTheme(); homeLayout = store.homeLayout()
    selectedBucketId = savedInstanceState?.getString("selectedBucketId")
    directBucketId = savedInstanceState?.getString("directBucketId")
    pendingMoveCount = savedInstanceState?.getInt("pendingMoveCount", 0) ?: 0
    incoming = savedInstanceState?.getStringArrayList("incomingUris")?.map(Uri::parse).orEmpty()
    handleIncoming(intent)
    migratePublicBucketCopies()
    migrateLegacyBucketFolders()
    refreshMissingDurations()
    scanMissingProjectFiles()
    setContent {
      BucketTheme(appTheme) {
        val palette = when (appTheme) { AppTheme.CLEAN -> CleanColors; AppTheme.CREATIVE -> CreativeColors; AppTheme.DARK -> DarkColors }
        CompositionLocalProvider(LocalColors provides palette, LocalAppTheme provides appTheme) {
          BucketApp(
            buckets = buckets, incoming = incoming, selected = selectedBucketId?.let(::bucketById), working = isWorking, message = message,
            behavior = importBehavior, theme = appTheme, homeLayout = homeLayout, onDeleteClips = ::deleteClips, onFinishBucket = ::finishBucket,
            onPreviewClip = ::previewClip, onCreate = ::createBucket, onSelect = { selectedBucketId = it.id }, onAdd = ::addFootage,
            onImport = ::importInto, onDismiss = { incoming = emptyList() }, onExport = { exportPicker.launch(null) }, onShare = ::shareBucket,
            onSaveNotes = ::saveNotes, onBehavior = { importBehavior = it; store.setImportBehavior(it) },
            onTheme = { appTheme = it; store.setAppTheme(it) }, onHomeLayout = { homeLayout = it; store.setHomeLayout(it) }, onClear = { message = null }
          )
        }
      }
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putString("selectedBucketId", selectedBucketId)
    outState.putString("directBucketId", directBucketId)
    outState.putInt("pendingMoveCount", pendingMoveCount)
    outState.putStringArrayList("incomingUris", ArrayList(incoming.map(Uri::toString)))
    super.onSaveInstanceState(outState)
  }

  override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIncoming(intent) }
  override fun onResume() { super.onResume(); if (::store.isInitialized) scanMissingProjectFiles() }
  private fun handleIncoming(intent: Intent?) {
    if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return
    val uris = when (intent.action) {
      Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
      Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
      else -> emptyList()
    }.filter { contentResolver.getType(it)?.startsWith("video/") != false }.distinct()
    if (uris.isNotEmpty()) incoming = uris
  }
  private fun receiveUris(uris: List<Uri>) { if (uris.isEmpty()) return; val target = directBucketId?.let(::bucketById); directBucketId = null; if (target == null) incoming = uris else importInto(target, uris) }
  private fun addFootage(bucket: Bucket?) { directBucketId = bucket?.id; importPicker.launch(arrayOf("video/*")) }
  private fun createBucket(name: String) { val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "-").take(70); if (clean.isBlank()) return; val bucket = Bucket(UUID.randomUUID().toString(), clean, System.currentTimeMillis(), emptyList()); buckets = listOf(bucket) + buckets; store.save(buckets); selectedBucketId = bucket.id }

  private fun importInto(bucket: Bucket, sources: List<Uri> = incoming) {
    if (sources.isEmpty()) return
    isWorking = true
    lifecycleScope.launch {
      val results = withContext(Dispatchers.IO) { sources.map { uri -> uri to runCatching { copyIntoBucket(uri, bucket) } } }
      val copies = results.mapNotNull { it.second.getOrNull() }
      val moveCandidates = results.mapNotNull { (source, result) ->
        if (result.isSuccess) mediaStoreUri(source) else null
      }
      val failures = results.count { it.second.isFailure }
      if (copies.isNotEmpty()) { buckets = buckets.map { if (it.id == bucket.id) it.copy(clips = copies + it.clips) else it }; store.save(buckets); selectedBucketId = bucket.id }
      incoming = emptyList(); isWorking = false
      message = if (failures == 0) "Imported ${copies.size} original video${if (copies.size == 1) "" else "s"} into ${bucket.name}." else "Imported ${copies.size}; $failures could not be copied."
      if (importBehavior == ImportBehavior.MOVE && moveCandidates.isNotEmpty()) requestMove(moveCandidates)
      else if (importBehavior == ImportBehavior.MOVE && copies.isNotEmpty()) message = "Copied ${copies.size} clip${if (copies.size == 1) "" else "s"}. Those source apps do not allow Bucket to remove their originals."
    }
  }
  private fun mediaStoreUri(uri: Uri): Uri? = when (uri.authority) {
    "media" -> uri
    "com.android.providers.media.documents" -> DocumentsContract.getDocumentId(uri)
      .substringAfterLast(':').toLongOrNull()
      ?.let { ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, it) }
    else -> null
  }
  private fun requestMove(uris: List<Uri>) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { message = "Copied clips. Moving camera originals requires Android 11 or later."; return }
    runCatching { pendingMoveCount = uris.size; val request = MediaStore.createDeleteRequest(contentResolver, uris); moveConfirmation.launch(IntentSenderRequest.Builder(request.intentSender).build()) }.onFailure { message = "Copied clips, but Android could not request removal of the originals." }
  }
  private fun copyIntoBucket(source: Uri, bucket: Bucket): Clip {
    val (rawName, size) = sourceDetails(source); val name = uniqueName(rawName); val mime = contentResolver.getType(source) ?: "video/mp4"
    val destination = uniqueBucketFile(bucket, name)
    contentResolver.openInputStream(source).use { input -> checkNotNull(input) { "Cannot read selected video" }; FileOutputStream(destination).use { input.copyTo(it, 128 * 1024) } }
    return Clip(destination.absolutePath, destination.name, mime, size, sourceDurationMs(source), System.currentTimeMillis())
  }
  private fun exportBucket(bucket: Bucket, treeUri: Uri) { isWorking = true; lifecycleScope.launch { val result = withContext(Dispatchers.IO) { contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); val root = DocumentFile.fromTreeUri(this@MainActivity, treeUri) ?: error("Could not open destination"); val dir = root.findFile(bucket.safeFolderName) ?: root.createDirectory(bucket.safeFolderName) ?: error("Could not create project folder"); bucket.clips.map { runCatching { copyToDocument(it, dir) } } }; isWorking = false; val copied = result.count { it.isSuccess }; val failed = result.size - copied; message = if (failed == 0) "Copied $copied clips to ${bucket.name}." else "Copied $copied clips; $failed could not be exported." } }
  private fun copyToDocument(clip: Clip, directory: DocumentFile) { val destination = checkNotNull(directory.createFile(clip.mime, uniqueDocumentName(directory, clip.name))); openClipInput(clip).use { input -> checkNotNull(input) { "Clip is missing" }; contentResolver.openOutputStream(destination.uri).use { output -> checkNotNull(output).apply { input.copyTo(this, 128 * 1024) } } } }
  private fun shareBucket(bucket: Bucket) { val uris = ArrayList(bucket.clips.map(::shareUri)); if (uris.isEmpty()) { message = "This bucket has no footage yet."; return }; startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "video/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share CreatorBucket footage")) }
  private fun saveNotes(bucket: Bucket, notes: String) { buckets = buckets.map { if (it.id == bucket.id) it.copy(notes = notes) else it }; store.save(buckets) }
  private fun previewClip(clip: Clip) = runCatching {
    startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(shareUri(clip), clip.mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
  }.onFailure { message = "No video player is available for this clip." }
  private fun deleteClips(bucket: Bucket, clipUris: Set<String>) {
    val target = bucket.clips.filter { it.uri in clipUris }
    val deleted = target.filter { clip -> runCatching { if (clip.uri.startsWith("content:")) contentResolver.delete(Uri.parse(clip.uri), null, null) > 0 else File(clip.uri).delete() }.getOrDefault(false) }
    buckets = buckets.map { if (it.id == bucket.id) it.copy(clips = it.clips.filterNot { clip -> clip in deleted }) else it }
    store.save(buckets)
    val failed = target.size - deleted.size
    message = if (failed == 0) "Deleted ${deleted.size} project clip${if (deleted.size == 1) "" else "s"}." else "Deleted ${deleted.size}; $failed files could not be removed."
  }
  private fun finishBucket(bucket: Bucket) {
    val remaining = bucket.clips.filter { clip -> !runCatching { if (clip.uri.startsWith("content:")) contentResolver.delete(Uri.parse(clip.uri), null, null) > 0 else File(clip.uri).delete() }.getOrDefault(false) }
    if (remaining.isEmpty()) { bucketFolder(bucket).deleteRecursively(); legacyBucketFolder(bucket).deleteRecursively(); buckets = buckets.filterNot { it.id == bucket.id }; selectedBucketId = null; message = "Finished ${bucket.name} and deleted its project copies." } else { buckets = buckets.map { if (it.id == bucket.id) it.copy(clips = remaining) else it }; message = "Some project files could not be deleted. ${bucket.name} was kept." }
    store.save(buckets)
  }
  private fun scanMissingProjectFiles() {
    val original = buckets
    val repaired = original.mapNotNull { bucket ->
      val remaining = bucket.clips.filter { clip -> clip.uri.startsWith("content:") || File(clip.uri).exists() }
      when {
        remaining.size == bucket.clips.size -> bucket
        bucket.clips.isNotEmpty() && remaining.isEmpty() -> { bucketFolder(bucket).deleteRecursively(); legacyBucketFolder(bucket).deleteRecursively(); null }
        else -> bucket.copy(clips = remaining)
      }
    }
    if (repaired != original) { val removedClips = original.sumOf { it.clips.size } - repaired.sumOf { it.clips.size }; val removedBuckets = original.size - repaired.size; buckets = repaired; store.save(repaired); message = if (removedBuckets > 0) "Removed $removedClips missing clips and $removedBuckets empty project${if (removedBuckets == 1) "" else "s"}." else "Removed $removedClips missing project clip${if (removedClips == 1) "" else "s"}."; if (selectedBucketId != null && repaired.none { it.id == selectedBucketId }) selectedBucketId = null }
  }
  private fun sourceDetails(uri: Uri): Pair<String, Long> = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) (c.getString(0)?.ifBlank { null } ?: "video-${System.currentTimeMillis()}.mp4") to c.getLong(1).coerceAtLeast(0) else null } ?: ("video-${System.currentTimeMillis()}.mp4" to 0L)
  private fun sourceDurationMs(uri: Uri): Long = runCatching { MediaMetadataRetriever().use { retriever -> retriever.setDataSource(this, uri); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } }.getOrDefault(0L)
  private fun clipDurationMs(clip: Clip): Long = runCatching { MediaMetadataRetriever().use { retriever -> if (clip.uri.startsWith("content:")) retriever.setDataSource(this, Uri.parse(clip.uri)) else retriever.setDataSource(clip.uri); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } }.getOrDefault(0L)
  private fun uniqueName(name: String) = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
  private fun bucketById(id: String) = buckets.firstOrNull { it.id == id }

  private fun uniqueBucketFile(bucket: Bucket, requestedName: String): File {
    val folder = bucketFolder(bucket).apply { mkdirs() }
    val stem = requestedName.substringBeforeLast('.', requestedName)
    val extension = requestedName.substringAfterLast('.', "")
    var candidate = File(folder, requestedName)
    var suffix = 2
    while (candidate.exists()) { candidate = File(folder, "$stem ($suffix)${if (extension.isBlank()) "" else ".$extension"}"); suffix++ }
    return candidate
  }
  private fun bucketFolder(bucket: Bucket) = File(checkNotNull(getExternalFilesDir(null)), "CreatorBucket/${bucket.id}")
  private fun legacyBucketFolder(bucket: Bucket) = File(checkNotNull(getExternalFilesDir(null)), "CreatorBucket/${bucket.safeFolderName}")
  private fun uniqueDocumentName(directory: DocumentFile, requestedName: String): String { val stem = requestedName.substringBeforeLast('.', requestedName); val extension = requestedName.substringAfterLast('.', ""); var candidate = requestedName; var suffix = 2; while (directory.findFile(candidate) != null) { candidate = "$stem ($suffix)${if (extension.isBlank()) "" else ".$extension"}"; suffix++ }; return candidate }

  private fun openClipInput(clip: Clip) = if (clip.uri.startsWith("content:")) contentResolver.openInputStream(Uri.parse(clip.uri)) else FileInputStream(File(clip.uri))
  private fun shareUri(clip: Clip): Uri = if (clip.uri.startsWith("content:")) Uri.parse(clip.uri) else FileProvider.getUriForFile(this, "$packageName.files", File(clip.uri))

  private fun migratePublicBucketCopies() {
    if (buckets.none { bucket -> bucket.clips.any { it.uri.startsWith("content:") } }) return
    lifecycleScope.launch {
      val migrated = withContext(Dispatchers.IO) {
        buckets.map { bucket ->
          bucket.copy(clips = bucket.clips.map { clip ->
            if (!clip.uri.startsWith("content:")) clip else runCatching {
              val target = uniqueBucketFile(bucket, clip.name)
              contentResolver.openInputStream(Uri.parse(clip.uri)).use { input -> checkNotNull(input); FileOutputStream(target).use { input.copyTo(it, 128 * 1024) } }
              contentResolver.delete(Uri.parse(clip.uri), null, null)
              clip.copy(uri = target.absolutePath, name = target.name)
            }.getOrDefault(clip)
          })
        }
      }
      buckets = migrated; store.save(buckets)
      message = "CreatorBucket moved earlier project copies out of Gallery."
    }
  }
  private fun refreshMissingDurations() {
    if (buckets.none { bucket -> bucket.clips.any { it.durationMs == 0L } }) return
    lifecycleScope.launch {
      val updated = withContext(Dispatchers.IO) { buckets.map { bucket -> bucket.copy(clips = bucket.clips.map { clip -> if (clip.durationMs > 0) clip else clip.copy(durationMs = clipDurationMs(clip)) }) } }
      if (updated != buckets) { buckets = updated; store.save(buckets) }
    }
  }
  private fun migrateLegacyBucketFolders() {
    if (buckets.none { bucket -> bucket.clips.any { clip -> !clip.uri.startsWith("content:") && File(clip.uri).parentFile == legacyBucketFolder(bucket) } }) return
    lifecycleScope.launch {
      val migrated = withContext(Dispatchers.IO) {
        buckets.map { bucket ->
          bucket.copy(clips = bucket.clips.map { clip ->
            if (clip.uri.startsWith("content:") || File(clip.uri).parentFile != legacyBucketFolder(bucket)) clip else runCatching {
              val source = File(clip.uri); val destination = uniqueBucketFile(bucket, source.name)
              if (!source.renameTo(destination)) error("Could not move ${source.name}")
              clip.copy(uri = destination.absolutePath, name = destination.name)
            }.getOrDefault(clip)
          })
        }
      }
      if (migrated != buckets) { buckets = migrated; store.save(buckets) }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BucketApp(buckets: List<Bucket>, incoming: List<Uri>, selected: Bucket?, working: Boolean, message: String?, behavior: ImportBehavior, theme: AppTheme, homeLayout: HomeLayout, onDeleteClips: (Bucket, Set<String>) -> Unit, onFinishBucket: (Bucket) -> Unit, onPreviewClip: (Clip) -> Unit, onCreate: (String) -> Unit, onSelect: (Bucket) -> Unit, onAdd: (Bucket?) -> Unit, onImport: (Bucket) -> Unit, onDismiss: () -> Unit, onExport: () -> Unit, onShare: (Bucket) -> Unit, onSaveNotes: (Bucket, String) -> Unit, onBehavior: (ImportBehavior) -> Unit, onTheme: (AppTheme) -> Unit, onHomeLayout: (HomeLayout) -> Unit, onClear: () -> Unit) {
  var showCreate by remember { mutableStateOf(false) }; var showSettings by remember { mutableStateOf(false) }; val snackbar = remember { SnackbarHostState() }
  BackHandler(enabled = showSettings || selected != null) { if (showSettings) showSettings = false else onSelect(Bucket("", "", 0, emptyList())) }
  if (message != null) LaunchedEffect(message) { snackbar.showSnackbar(message); onClear() }
  val background = if (LocalAppTheme.current == AppTheme.CREATIVE) Brush.verticalGradient(listOf(Color(0xFFE7FAE8), Color(0xFFF6FFF1), Color(0xFFDDF6EB))) else Brush.verticalGradient(listOf(colors.screen, colors.screen))
  Box(Modifier.fillMaxSize().background(background)) { Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snackbar) }, topBar = { TopAppBar(title = { Image(painter = painterResource(R.drawable.creator_bucket_icon), contentDescription = "CreatorBucket", modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))) }, actions = { Text("⚙", color = colors.primary, fontSize = 30.sp, modifier = Modifier.clickable { showSettings = true }.padding(16.dp)) }) }) { padding -> when { showSettings -> SettingsScreen(Modifier.padding(padding), behavior, theme, homeLayout, onBehavior, onTheme, onHomeLayout) { showSettings = false }; selected == null -> BucketList(buckets, homeLayout, Modifier.padding(padding), onSelect, onFinishBucket) { showCreate = true }; else -> BucketDetail(selected, Modifier.padding(padding), { onSelect(Bucket("", "", 0, emptyList())) }, { onAdd(selected) }, onExport, onShare, onSaveNotes, onDeleteClips, onFinishBucket, onPreviewClip) } } }
  if (showCreate) CreateBucketDialog({ showCreate = false }) { onCreate(it); showCreate = false }
  if (incoming.isNotEmpty()) ImportDialog(incoming.size, buckets, working, behavior, onImport, onDismiss) { showCreate = true }
}

@Composable private fun BucketList(buckets: List<Bucket>, layout: HomeLayout, modifier: Modifier, onSelect: (Bucket) -> Unit, onDelete: (Bucket) -> Unit, onCreate: () -> Unit) { var confirmBucket by remember { mutableStateOf<Bucket?>(null) }; Column(modifier.fillMaxSize().padding(20.dp)) { Text("Your projects", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = colors.ink); Text("Every shoot, ready to pick up later.", color = colors.muted, modifier = Modifier.padding(top = 5.dp, bottom = 16.dp)); when (layout) { HomeLayout.TIMELINE -> FootageTimeline(buckets); HomeLayout.DASHBOARD -> DashboardSummary(buckets); HomeLayout.GALLERY -> GallerySummary(buckets) }; Spacer(Modifier.height(16.dp)); Button(onClick = onCreate, colors = ButtonDefaults.buttonColors(containerColor = colors.primary), modifier = Modifier.fillMaxWidth()) { Text("+  New bucket") }; Spacer(Modifier.height(18.dp)); if (buckets.isEmpty()) EmptyState() else if (layout == HomeLayout.GALLERY) LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(buckets.chunked(2)) { row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) { row.forEach { bucket -> SwipeDeleteBox(onDeleteRequested = { confirmBucket = bucket }) { GalleryCard(bucket, Modifier.weight(1f)) { onSelect(bucket) } } }; if (row.size == 1) Spacer(Modifier.weight(1f)) } } } else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(buckets, key = { it.id }) { bucket -> SwipeDeleteBox(onDeleteRequested = { confirmBucket = bucket }) { BucketCard(bucket) { onSelect(bucket) } } } } }; confirmBucket?.let { bucket -> AlertDialog(onDismissRequest = { confirmBucket = null }, title = { Text("Delete ${bucket.name}?") }, text = { Text("This deletes all project copies in this bucket. Original camera videos are not changed.") }, confirmButton = { Text("Delete", color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { onDelete(bucket); confirmBucket = null }.padding(10.dp)) }, dismissButton = { Text("Cancel", modifier = Modifier.clickable { confirmBucket = null }.padding(10.dp)) }) } }
@Composable private fun FootageTimeline(buckets: List<Bucket>) { val clips = buckets.sumOf { it.clips.size }; val duration = buckets.sumOf { bucket -> bucket.clips.sumOf { it.durationMs } }; Card(colors = CardDefaults.cardColors(containerColor = colors.mist), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(17.dp)) { Text("${formatDuration(duration)} of footage", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = colors.ink); Spacer(Modifier.height(12.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { buckets.take(6).forEachIndexed { index, _ -> Box(Modifier.height(8.dp).weight(1f).clip(RoundedCornerShape(4.dp)).background(listOf(Color(0xFF66C999), Color(0xFFB9A2E6), Color(0xFFF5C967), Color(0xFF9BD688))[index % 4])) }; if (buckets.isEmpty()) Box(Modifier.height(8.dp).fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Color.White)) }; Spacer(Modifier.height(10.dp)); Row(Modifier.fillMaxWidth()) { Text("${buckets.size} projects", color = colors.muted, modifier = Modifier.weight(1f)); Text("$clips clips", color = colors.muted) } } } }
@Composable private fun GallerySummary(buckets: List<Bucket>) { val clips = buckets.sumOf { it.clips.size }; val duration = buckets.sumOf { bucket -> bucket.clips.sumOf { it.durationMs } }; Text("${buckets.size} projects · $clips clips · ${formatDuration(duration)} filmed", color = colors.muted) }
@Composable private fun DashboardSummary(buckets: List<Bucket>) { val clips = buckets.sumOf { it.clips.size }; val duration = buckets.sumOf { bucket -> bucket.clips.sumOf { it.durationMs } }; Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) { SummaryCard("PROJECTS", buckets.size.toString(), Color(0xFFE5F4EA), Modifier.weight(1f)); SummaryCard("CLIPS", clips.toString(), Color(0xFFE5EFFA), Modifier.weight(1f)); SummaryCard("FOOTAGE", formatDuration(duration), Color(0xFFF8EFE0), Modifier.weight(1f)) } }
@Composable private fun SummaryCard(label: String, value: String, tint: Color, modifier: Modifier) = Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = tint)) { Column(Modifier.padding(12.dp)) { Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = colors.muted); Text(value, fontSize = 20.sp, fontWeight = FontWeight.Black, color = colors.ink, maxLines = 1) } }
@Composable private fun BucketCard(bucket: Bucket, onClick: () -> Unit) = Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = colors.card), elevation = CardDefaults.cardElevation(1.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(colors.mist), contentAlignment = Alignment.Center) { Text("▶", color = colors.primary, fontSize = 19.sp) }; Spacer(Modifier.width(13.dp)); Column(Modifier.weight(1f)) { Text(bucket.name, fontWeight = FontWeight.Bold, color = colors.ink); Text("${bucket.clips.size} clips · ${formatDuration(bucket.clips.sumOf { it.durationMs })} · ${formatBytes(bucket.clips.sumOf { it.size })}", color = colors.muted, fontSize = 13.sp) }; Text("›", color = colors.primary, fontSize = 28.sp) } }
@Composable private fun GalleryCard(bucket: Bucket, modifier: Modifier, onClick: () -> Unit) = Card(modifier = modifier.clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = colors.mist)) { Column(Modifier.padding(15.dp)) { Box(Modifier.size(38.dp).clip(RoundedCornerShape(19.dp)).background(colors.primary), contentAlignment = Alignment.Center) { Text("▶", color = Color.White) }; Spacer(Modifier.height(24.dp)); Text(bucket.name, fontWeight = FontWeight.Bold, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${formatDuration(bucket.clips.sumOf { it.durationMs })} · ${bucket.clips.size} clips", color = colors.muted, fontSize = 12.sp) } }
@Composable private fun EmptyState() = Card(colors = CardDefaults.cardColors(containerColor = colors.mist), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(22.dp)) { Text("Start with a project", fontWeight = FontWeight.Bold, color = colors.ink); Spacer(Modifier.height(6.dp)); Text("Record with your usual camera. Select clips in Gallery, tap Share, then choose CreatorBucket.", color = colors.muted) } }
@Composable private fun BucketDetail(bucket: Bucket, modifier: Modifier, onBack: () -> Unit, onAdd: () -> Unit, onExport: () -> Unit, onShare: (Bucket) -> Unit, onSaveNotes: (Bucket, String) -> Unit, onDeleteClips: (Bucket, Set<String>) -> Unit, onFinishBucket: (Bucket) -> Unit, onPreviewClip: (Clip) -> Unit) { var selecting by remember(bucket.id) { mutableStateOf(false) }; var selectedUris by remember(bucket.id) { mutableStateOf(setOf<String>()) }; var confirmDelete by remember { mutableStateOf(false) }; var confirmFinish by remember { mutableStateOf(false) }; var confirmClip by remember { mutableStateOf<Clip?>(null) }; LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { item { OutlinedButton(onClick = onBack) { Text("‹  All buckets") } }; item { Text(bucket.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = colors.ink) }; item { Text("${bucket.clips.size} clips · ${formatDuration(bucket.clips.sumOf { it.durationMs })} · ${formatBytes(bucket.clips.sumOf { it.size })} · Hidden from Gallery", color = colors.muted, fontSize = 14.sp) }; item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = colors.primary), modifier = Modifier.weight(1f)) { Text("+  Add footage") }; if (bucket.clips.isNotEmpty()) OutlinedButton(onClick = { selecting = !selecting; selectedUris = emptySet() }) { Text(if (selecting) "Done" else "Select") } } }; if (selecting) item { Row(verticalAlignment = Alignment.CenterVertically) { Text("${selectedUris.size} selected", color = colors.muted, modifier = Modifier.weight(1f)); Button(enabled = selectedUris.isNotEmpty(), onClick = { confirmDelete = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") } } }; if (bucket.clips.isEmpty()) item { EmptyState() }; items(bucket.clips, key = { it.uri }) { clip -> ClipRow(clip, selecting, clip.uri in selectedUris, onPreviewClip, { confirmClip = clip }) { uri -> selectedUris = if (uri in selectedUris) selectedUris - uri else selectedUris + uri } }; item { NotesCard(bucket, onSaveNotes) }; if (bucket.clips.isNotEmpty() && !selecting) { item { Button(onClick = { onShare(bucket) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = colors.primary)) { Text("Share clips to editor") } }; item { OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Copy project to SSD or folder") } }; item { Text("Done with this project", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { confirmFinish = true }.padding(10.dp)) } } }; if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete selected footage?") }, text = { Text("This removes the ${selectedUris.size} selected project clip${if (selectedUris.size == 1) "" else "s"} from CreatorBucket. It does not restore or remove the original camera videos.") }, confirmButton = { Text("Delete", color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { onDeleteClips(bucket, selectedUris); selectedUris = emptySet(); selecting = false; confirmDelete = false }.padding(10.dp)) }, dismissButton = { Text("Cancel", modifier = Modifier.clickable { confirmDelete = false }.padding(10.dp)) }); confirmClip?.let { clip -> AlertDialog(onDismissRequest = { confirmClip = null }, title = { Text("Delete ${clip.name}?") }, text = { Text("This removes this project copy from CreatorBucket. The original camera video is not changed.") }, confirmButton = { Text("Delete", color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { onDeleteClips(bucket, setOf(clip.uri)); confirmClip = null }.padding(10.dp)) }, dismissButton = { Text("Cancel", modifier = Modifier.clickable { confirmClip = null }.padding(10.dp)) }) }; if (confirmFinish) AlertDialog(onDismissRequest = { confirmFinish = false }, title = { Text("Finish and delete project?") }, text = { Text("This permanently deletes the ${bucket.clips.size} project copies in ${bucket.name}. Export or share the footage first if you still need it. Original camera videos are not changed.") }, confirmButton = { Text("Finish & delete", color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { onFinishBucket(bucket); confirmFinish = false }.padding(10.dp)) }, dismissButton = { Text("Cancel", modifier = Modifier.clickable { confirmFinish = false }.padding(10.dp)) }) }
@Composable private fun ClipRow(clip: Clip, selecting: Boolean, selected: Boolean, onPreview: (Clip) -> Unit, onSwipeDelete: () -> Unit, onSelect: (String) -> Unit) = if (selecting) Card(colors = CardDefaults.cardColors(containerColor = if (selected) colors.mist else colors.card), modifier = Modifier.fillMaxWidth().clickable { onSelect(clip.uri) }) { ClipContent(clip, true, selected, onSelect) } else SwipeDeleteBox(onDeleteRequested = onSwipeDelete) { Card(colors = CardDefaults.cardColors(containerColor = colors.card), modifier = Modifier.fillMaxWidth().clickable { onPreview(clip) }) { ClipContent(clip, false, false, onSelect) } }
@Composable private fun ClipContent(clip: Clip, selecting: Boolean, selected: Boolean, onSelect: (String) -> Unit) = Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { if (selecting) { Checkbox(checked = selected, onCheckedChange = { onSelect(clip.uri) }); Spacer(Modifier.width(8.dp)) }; Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(colors.mist), contentAlignment = Alignment.Center) { Text("▶", color = colors.primary) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(clip.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = colors.ink); Text("${formatDuration(clip.durationMs)} · ${formatBytes(clip.size)} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(clip.importedAt))}", fontSize = 12.sp, color = colors.muted) } }
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SwipeDeleteBox(onDeleteRequested: () -> Unit, content: @Composable RowScope.() -> Unit) { val state = rememberSwipeToDismissBoxState(); LaunchedEffect(state.currentValue) { if (state.currentValue == SwipeToDismissBoxValue.EndToStart) { onDeleteRequested(); state.reset() } }; SwipeToDismissBox(state = state, enableDismissFromStartToEnd = false, backgroundContent = { Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(end = 22.dp), contentAlignment = Alignment.CenterEnd) { Text("Delete", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold) } }, content = content) }
@Composable private fun NotesCard(bucket: Bucket, onSave: (Bucket, String) -> Unit) { var editing by remember(bucket.id) { mutableStateOf(false) }; var notes by remember(bucket.id, bucket.notes) { mutableStateOf(bucket.notes) }; Card(colors = CardDefaults.cardColors(containerColor = colors.card), modifier = Modifier.fillMaxWidth().clickable { editing = true }) { Column(Modifier.padding(16.dp)) { Text("Project notes", fontWeight = FontWeight.Bold, color = colors.ink); Spacer(Modifier.height(6.dp)); if (editing) { OutlinedTextField(value = notes, onValueChange = { notes = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Add a shot list, edit notes, or an idea…") }, minLines = 3); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Text("Done", color = colors.primary, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onSave(bucket, notes); editing = false }.padding(top = 12.dp)) } } else Text(notes.ifBlank { "Tap to add a shot list, edit notes, or an idea." }, color = colors.muted) } } }
@Composable private fun SettingsScreen(modifier: Modifier, behavior: ImportBehavior, theme: AppTheme, homeLayout: HomeLayout, onBehavior: (ImportBehavior) -> Unit, onTheme: (AppTheme) -> Unit, onHomeLayout: (HomeLayout) -> Unit, onBack: () -> Unit) = Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) { OutlinedButton(onClick = onBack) { Text("‹  Back") }; Spacer(Modifier.height(18.dp)); Text("Preferences", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = colors.ink); Spacer(Modifier.height(22.dp)); Text("HOME VIEW", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.muted); PreferenceOption("Footage timeline", "Shows total filming time and project proportions.", homeLayout == HomeLayout.TIMELINE) { onHomeLayout(HomeLayout.TIMELINE) }; PreferenceOption("Dashboard tiles", "Shows project, clip, and footage totals.", homeLayout == HomeLayout.DASHBOARD) { onHomeLayout(HomeLayout.DASHBOARD) }; PreferenceOption("Project gallery", "Shows projects as color-coded cards.", homeLayout == HomeLayout.GALLERY) { onHomeLayout(HomeLayout.GALLERY) }; Spacer(Modifier.height(18.dp)); Text("IMPORT BEHAVIOR", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.muted); PreferenceOption("Copy originals", "Keep videos in Gallery. CreatorBucket makes a full-quality project copy.", behavior == ImportBehavior.COPY) { onBehavior(ImportBehavior.COPY) }; PreferenceOption("Move after copy", "After each import, Android asks before removing the camera originals.", behavior == ImportBehavior.MOVE) { onBehavior(ImportBehavior.MOVE) }; Card(colors = CardDefaults.cardColors(containerColor = colors.mist), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Column(Modifier.padding(15.dp)) { Text("Move permission", fontWeight = FontWeight.Bold, color = colors.ink); Text("Use your phone's local Gallery or Files app, select camera videos, then Share → CreatorBucket. After importing, approve Android's delete prompt. Google Photos, Drive, and other cloud apps do not allow CreatorBucket to remove their originals.", color = colors.muted, fontSize = 13.sp) } }; Spacer(Modifier.height(18.dp)); Text("APPEARANCE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.muted); PreferenceOption("Clean", "Quiet, focused, default theme.", theme == AppTheme.CLEAN) { onTheme(AppTheme.CLEAN) }; PreferenceOption("Creative", "Soft green gradient palette with a brighter, more expressive feel.", theme == AppTheme.CREATIVE) { onTheme(AppTheme.CREATIVE) }; PreferenceOption("Dark", "Low-light dark green theme.", theme == AppTheme.DARK) { onTheme(AppTheme.DARK) }; Spacer(Modifier.height(28.dp)) }
@Composable private fun PreferenceOption(title: String, description: String, selected: Boolean, onClick: () -> Unit) = Card(modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = if (selected) colors.mist else colors.card)) { Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(if (selected) colors.primary else Color.Transparent), contentAlignment = Alignment.Center) { if (selected) Text("✓", color = Color.White, fontSize = 13.sp) }; Spacer(Modifier.width(12.dp)); Column { Text(title, fontWeight = FontWeight.Bold, color = colors.ink); Text(description, color = colors.muted, fontSize = 13.sp) } } }
@Composable private fun ImportDialog(count: Int, buckets: List<Bucket>, working: Boolean, behavior: ImportBehavior, onPick: (Bucket) -> Unit, onDismiss: () -> Unit, onNew: () -> Unit) = AlertDialog(onDismissRequest = { if (!working) onDismiss() }, title = { Text("Add $count video${if (count == 1) "" else "s"} to a bucket") }, text = { if (working) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp); Spacer(Modifier.width(12.dp)); Text("Importing full-quality files…") } else Column { Text(if (behavior == ImportBehavior.COPY) "Originals will stay in Gallery." else "After copying, Android will ask before removing the camera originals."); Spacer(Modifier.height(14.dp)); if (buckets.isEmpty()) Text("Create a bucket first.") else buckets.forEach { bucket -> Row(Modifier.fillMaxWidth().clickable { onPick(bucket) }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(colors.mist), contentAlignment = Alignment.Center) { Text("▶", color = colors.primary) }; Spacer(Modifier.width(10.dp)); Text(bucket.name, fontWeight = FontWeight.SemiBold) } } } }, confirmButton = { if (!working) Text("+ New bucket", color = colors.primary, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onNew).padding(10.dp)) }, dismissButton = { if (!working) Text("Cancel", modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp)) })
@Composable private fun CreateBucketDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) { var name by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = onDismiss, title = { Text("New bucket") }, text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Project name") }, singleLine = true) }, confirmButton = { Text("Create", color = colors.primary, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(enabled = name.isNotBlank()) { onSave(name) }.padding(10.dp)) }, dismissButton = { Text("Cancel", modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp)) }) }
private fun formatBytes(bytes: Long): String = when { bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0); bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0); bytes > 0 -> "%.0f KB".format(bytes / 1024.0); else -> "Unknown size" }
private fun formatDuration(durationMs: Long): String { val seconds = durationMs / 1000; return when { seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"; seconds >= 60 -> "${seconds / 60} min"; seconds > 0 -> "${seconds}s"; else -> "—" } }
