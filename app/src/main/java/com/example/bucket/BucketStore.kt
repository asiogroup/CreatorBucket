package com.example.bucket

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

data class Clip(val uri: String, val name: String, val mime: String, val size: Long, val durationMs: Long, val importedAt: Long)
enum class ImportBehavior { COPY, MOVE }
enum class AppTheme { CLEAN, CREATIVE, DARK }
enum class HomeLayout { TIMELINE, DASHBOARD, GALLERY }

data class Bucket(val id: String, val name: String, val createdAt: Long, val clips: List<Clip>, val notes: String = "") {
  val safeFolderName: String get() = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "Untitled" }
}

class BucketStore(context: Context) {
  private val preferences = context.getSharedPreferences("bucket-store", Context.MODE_PRIVATE)
  private val database = BucketDatabase(context)

  init { migrateLegacyProjectsIfNeeded() }

  fun load(): List<Bucket> = database.readableDatabase.use { db ->
    db.query("buckets", arrayOf("id", "name", "created_at", "notes"), null, null, null, null, "created_at DESC").use { cursor ->
      buildList { while (cursor.moveToNext()) add(Bucket(cursor.getString(0), cursor.getString(1), cursor.getLong(2), loadClips(db, cursor.getString(0)), cursor.getString(3))) }
    }
  }

  fun save(buckets: List<Bucket>) = database.writableDatabase.use { db ->
    db.beginTransaction()
    try {
      db.delete("clips", null, null); db.delete("buckets", null, null)
      buckets.forEach { bucket ->
        db.insertOrThrow("buckets", null, ContentValues().apply { put("id", bucket.id); put("name", bucket.name); put("created_at", bucket.createdAt); put("notes", bucket.notes) })
        bucket.clips.forEach { clip -> db.insertOrThrow("clips", null, ContentValues().apply { put("uri", clip.uri); put("bucket_id", bucket.id); put("name", clip.name); put("mime", clip.mime); put("size", clip.size); put("duration_ms", clip.durationMs); put("imported_at", clip.importedAt) }) }
      }
      db.setTransactionSuccessful()
    } finally { db.endTransaction() }
  }

  fun importBehavior(): ImportBehavior = runCatching { ImportBehavior.valueOf(preferences.getString("importBehavior", ImportBehavior.COPY.name)!!) }.getOrDefault(ImportBehavior.COPY)
  fun setImportBehavior(value: ImportBehavior) = preferences.edit().putString("importBehavior", value.name).apply()
  fun appTheme(): AppTheme = runCatching { AppTheme.valueOf(preferences.getString("appTheme", AppTheme.CLEAN.name)!!) }.getOrDefault(AppTheme.CLEAN)
  fun setAppTheme(value: AppTheme) = preferences.edit().putString("appTheme", value.name).apply()
  fun homeLayout(): HomeLayout = runCatching { HomeLayout.valueOf(preferences.getString("homeLayout", HomeLayout.TIMELINE.name)!!) }.getOrDefault(HomeLayout.TIMELINE)
  fun setHomeLayout(value: HomeLayout) = preferences.edit().putString("homeLayout", value.name).apply()

  private fun loadClips(db: SQLiteDatabase, bucketId: String): List<Clip> = db.query("clips", arrayOf("uri", "name", "mime", "size", "duration_ms", "imported_at"), "bucket_id = ?", arrayOf(bucketId), null, null, "imported_at DESC").use { cursor -> buildList { while (cursor.moveToNext()) add(Clip(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getLong(4), cursor.getLong(5))) } }

  private fun migrateLegacyProjectsIfNeeded() {
    if (database.readableDatabase.rawQuery("SELECT COUNT(*) FROM buckets", null).use { it.moveToFirst(); it.getInt(0) > 0 }) return
    val legacy = preferences.getString("buckets", null) ?: return
    val buckets = runCatching { JSONArray(legacy).let { array -> buildList { for (index in 0 until array.length()) { val item = array.getJSONObject(index); val clips = item.getJSONArray("clips"); add(Bucket(item.getString("id"), item.getString("name"), item.getLong("createdAt"), buildList { for (clipIndex in 0 until clips.length()) { val clip = clips.getJSONObject(clipIndex); add(Clip(clip.getString("uri"), clip.getString("name"), clip.getString("mime"), clip.getLong("size"), clip.optLong("durationMs", 0L), clip.getLong("importedAt"))) } }, item.optString("notes", ""))) } } } }.getOrDefault(emptyList())
    if (buckets.isNotEmpty()) save(buckets)
    preferences.edit().remove("buckets").apply()
  }

  private class BucketDatabase(context: Context) : SQLiteOpenHelper(context, "creatorbucket.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
      db.execSQL("CREATE TABLE buckets (id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL, notes TEXT NOT NULL)")
      db.execSQL("CREATE TABLE clips (uri TEXT PRIMARY KEY, bucket_id TEXT NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, duration_ms INTEGER NOT NULL, imported_at INTEGER NOT NULL)")
      db.execSQL("CREATE INDEX clips_bucket_index ON clips(bucket_id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
  }
}
