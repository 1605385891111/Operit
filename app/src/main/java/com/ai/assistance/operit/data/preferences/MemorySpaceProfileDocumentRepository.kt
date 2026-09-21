package com.ai.assistance.operit.data.preferences

import android.content.Context
import android.util.AtomicFile
import com.ai.assistance.operit.data.model.LegacyUserProfile
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stores the Markdown profile owned by one memory space. */
class MemorySpaceProfileDocumentRepository private constructor(private val context: Context) {
    companion object {
        const val MAX_CONTENT_CHARS = 12_000
        const val USER_FILE_NAME = "user.md"
        private const val USER_VERSIONS_FILE_NAME = "user.versions.jsonl"
        private const val MAX_VERSIONS = 120

        private const val STORAGE_DIRECTORY = "memory-space-profiles"
        private const val PUBLISHED_GLOBAL_USER_FILE_NAME = "user.md"
        private const val PUBLISHED_LEGACY_ARCHIVE_FILE_NAME = "legacy-user-profiles.md"
        private const val MIGRATION_PREFERENCES = "memory_space_profile_documents"
        private const val SCHEMA_VERSION_KEY = "schema_version"
        private const val CURRENT_SCHEMA_VERSION = 2
        private const val TAG = "MemorySpaceProfileDocs"

        @Volatile
        private var instance: MemorySpaceProfileDocumentRepository? = null

        fun getInstance(context: Context): MemorySpaceProfileDocumentRepository {
            return instance ?: synchronized(this) {
                instance
                    ?: MemorySpaceProfileDocumentRepository(context.applicationContext ?: context).also {
                        instance = it
                    }
            }
        }
    }

    private val profileRoot = File(context.filesDir, STORAGE_DIRECTORY)
    private val publishedGlobalUserFile = File(context.filesDir, PUBLISHED_GLOBAL_USER_FILE_NAME)
    private val publishedLegacyArchiveFile =
        File(context.filesDir, PUBLISHED_LEGACY_ARCHIVE_FILE_NAME)
    private val migrationPreferences =
        context.getSharedPreferences(MIGRATION_PREFERENCES, Context.MODE_PRIVATE)
    private val initializationMutex = Mutex()
    private val writeMutex = Mutex()
    private var initialized = false

    suspend fun initialize() {
        initializationMutex.withLock {
            if (initialized) return

            val schemaVersion = migrationPreferences.getInt(SCHEMA_VERSION_KEY, 0)
            if (schemaVersion < CURRENT_SCHEMA_VERSION) {
                migrateReleasedData()
                check(
                    migrationPreferences.edit()
                        .putInt(SCHEMA_VERSION_KEY, CURRENT_SCHEMA_VERSION)
                        .commit()
                ) { "Failed to persist memory-space profile document migration version" }
            }

            profileRoot.mkdirs()
            initialized = true
        }
    }

    suspend fun load(memorySpaceId: String): String {
        initialize()
        val file = documentFile(memorySpaceId)
        if (!file.exists()) {
            writeMutex.withLock {
                if (!file.exists()) writeAtomically(file, "")
            }
        }
        return file.readText(StandardCharsets.UTF_8)
    }

    suspend fun save(memorySpaceId: String, markdown: String) {
        require(markdown.length <= MAX_CONTENT_CHARS) {
            "user.md exceeds the $MAX_CONTENT_CHARS character limit"
        }
        initialize()
        writeMutex.withLock {
            writeAtomically(documentFile(memorySpaceId), markdown)
            appendVersionLocked(memorySpaceId, markdown)
        }
    }

    suspend fun saveAutomatic(memorySpaceId: String, markdown: String): Boolean {
        initialize()
        val space = UserPreferencesManager.getInstance(context).getMemorySpaceFlow(memorySpaceId).first()
        if (!space.profileAutoUpdateEnabled || space.profileAutoUpdateLocked) return false
        save(memorySpaceId, markdown)
        return true
    }

    suspend fun reset(memorySpaceId: String) {
        save(memorySpaceId, "")
    }

    suspend fun delete(memorySpaceId: String) {
        initialize()
        writeMutex.withLock {
            documentFile(memorySpaceId).delete()
            profileDirectory(memorySpaceId).deleteRecursively()
        }
    }

/**
     * 加载"截至某一时刻"的记忆文档快照。
     *
     * 版本日志保存了每次写入的历史版本，这里取时间戳不晚于 [timestampMillis] 的最后一个版本；
     * 若时间点早于日志起点，则取最早的一版（比"当前"更接近当时）；没有任何版本记录时回退到当前文档。
     */
    suspend fun loadAsOf(memorySpaceId: String, timestampMillis: Long): String {
        initialize()
        val versions = readVersions(memorySpaceId)
        if (versions.isEmpty()) return load(memorySpaceId)
        val hit = versions.lastOrNull { it.first <= timestampMillis }
        if (hit == null) {
            AppLogger.w(TAG, "分支点早于记忆版本日志起点，回退为最早版本（近似快照）: $memorySpaceId")
            return versions.first().second
        }
        return hit.second
    }

/** 版本日志的文件位置（与 user.md 同目录） */
    private fun versionFile(memorySpaceId: String): File {
        return File(profileDirectory(memorySpaceId), USER_VERSIONS_FILE_NAME)
    }

/** 读取版本日志；损坏的行直接跳过，不影响其它版本 */
    private suspend fun readVersions(memorySpaceId: String): List<Pair<Long, String>> {
        return writeMutex.withLock {
            val file = versionFile(memorySpaceId)
            if (!file.exists()) return@withLock emptyList()
            runCatching {
                    file.readLines(StandardCharsets.UTF_8).mapNotNull { line ->
                        if (line.isBlank()) return@mapNotNull null
                        runCatching {
                                val record = org.json.JSONObject(line)
                                record.getLong("t") to record.getString("md")
                            }
                            .getOrNull()
                    }
                }
                .getOrElse {
                    AppLogger.e(TAG, "读取记忆版本日志失败: $memorySpaceId", it)
                    emptyList()
                }
        }
    }

/** 追加一次记忆写入到版本日志（与上一版相同则跳过；超出上限时只保留最近 MAX_VERSIONS 版） */
    private fun appendVersionLocked(memorySpaceId: String, markdown: String) {
        runCatching {
                val file = versionFile(memorySpaceId)
                val existing =
                    if (file.exists()) {
                        file.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
                    } else {
                        emptyList()
                    }
                val lastMarkdown =
                    existing.lastOrNull()?.let { line ->
                        runCatching { org.json.JSONObject(line).getString("md") }.getOrNull()
                    }
                if (lastMarkdown == markdown) return
                val record =
                    org.json.JSONObject()
                        .put("t", System.currentTimeMillis())
                        .put("md", markdown)
                        .toString()
                val kept = (existing + record).takeLast(MAX_VERSIONS)
                writeAtomically(file, kept.joinToString("\n", postfix = "\n"))
            }
            .onFailure { AppLogger.e(TAG, "写入记忆版本日志失败: $memorySpaceId", it) }
    }

    private suspend fun migrateReleasedData() {
        val manager = UserPreferencesManager.getInstance(context)
        // profile_list is the complete +4 source of truth. It can coexist with a default
        // memory-space record after raw snapshot recovery, so metadata alone cannot identify +5.
        if (manager.hasLegacyUserProfileMetadata()) {
            migrateLegacyStructuredProfiles(manager)
            return
        }
        if (manager.hasMemorySpaceMetadata()) {
            migratePublishedGlobalDocuments(manager)
            manager.memorySpaceListFlow.first().forEach { ensureDocument(it) }
            return
        }

        migrateLegacyStructuredProfiles(manager)
    }

    private suspend fun migrateLegacyStructuredProfiles(manager: UserPreferencesManager) {
        val snapshot = manager.readLegacyUserProfiles()
        snapshot.profiles.forEach { profile ->
            writeAtomically(
                documentFile(profile.id),
                profile.toUserMarkdown()
            )
        }
        manager.migrateLegacyProfilesToMemorySpaces(snapshot)
    }

    /**
     * Release +5 retained the former multi-profile payload as one active user.md plus an archive
     * of the other profiles. The root file has no owner id, and active_memory_space_id may have
     * changed after +5's migration. The archive preserves +4's profile-list order, so the root
     * owner is the unique space whose removal leaves an archive-name subsequence.
     */
    private suspend fun migratePublishedGlobalDocuments(manager: UserPreferencesManager) {
        val memorySpaceIds = manager.memorySpaceListFlow.first()
        val spaces = memorySpaceIds.map { id -> manager.getMemorySpaceFlow(id).first() }
        val activeDocument =
            publishedGlobalUserFile
                .takeIf(File::isFile)
                ?.readText(StandardCharsets.UTF_8)

        val archiveContent =
            publishedLegacyArchiveFile
                .takeIf(File::isFile)
                ?.readText(StandardCharsets.UTF_8)
        val archiveDocuments = archiveContent?.let(::parsePublishedArchive).orEmpty()
        val archiveNames = archiveDocuments.map(ArchivedProfileDocument::name)
        val rootDocumentCandidates =
            if (activeDocument.isNullOrBlank()) {
                emptyList()
            } else {
                spaces.filterIndexed { rootIndex, _ ->
                    archiveNames.isSubsequenceOf(
                        spaces.filterIndexed { index, _ -> index != rootIndex }.map { it.name }
                    )
                }
            }
        if (rootDocumentCandidates.size > 1) {
            AppLogger.w(
                TAG,
                "Cannot uniquely determine the owner of the published root user.md; " +
                    "retaining source files"
            )
            return
        }

        val rootDocumentSpace = rootDocumentCandidates.singleOrNull()
        val remainingSpaces = spaces.filter { it.id != rootDocumentSpace?.id }.toMutableList()
        archiveDocuments.forEach { document ->
            val targetIndex = remainingSpaces.indexOfFirst { it.name == document.name }
            if (targetIndex == -1) {
                AppLogger.w(TAG, "No memory space found for archived profile: ${document.name}")
            } else {
                val targetSpace = remainingSpaces.removeAt(targetIndex)
                writeIfDocumentEmpty(targetSpace.id, document.toUserMarkdown())
            }
        }
        if (rootDocumentSpace != null) {
            writeIfDocumentEmpty(rootDocumentSpace.id, requireNotNull(activeDocument))
        }
    }

    private fun List<String>.isSubsequenceOf(values: List<String>): Boolean {
        var nextValueIndex = 0
        values.forEach { value ->
            if (nextValueIndex < size && this[nextValueIndex] == value) {
                nextValueIndex++
            }
        }
        return nextValueIndex == size
    }

    private suspend fun ensureDocument(memorySpaceId: String) {
        val file = documentFile(memorySpaceId)
        if (!file.exists()) {
            writeMutex.withLock {
                if (!file.exists()) writeAtomically(file, "")
            }
        }
    }

    private fun writeIfDocumentEmpty(memorySpaceId: String, markdown: String) {
        val file = documentFile(memorySpaceId)
        if (!file.exists() || file.readText(StandardCharsets.UTF_8).isBlank()) {
            writeAtomically(file, markdown)
        }
    }

    private fun profileDirectory(memorySpaceId: String): File {
        return File(profileRoot, memorySpaceId)
    }

    private fun documentFile(memorySpaceId: String): File {
        require(memorySpaceId.isNotBlank()) { "Memory space ID cannot be blank" }
        return File(profileDirectory(memorySpaceId), USER_FILE_NAME)
    }

    private data class ArchivedProfileDocument(val name: String, val content: String) {
        fun toUserMarkdown(): String {
            return "# About me\n\n${content.trim()}\n"
        }
    }

    private fun parsePublishedArchive(archiveContent: String): List<ArchivedProfileDocument> {
        val sectionNames =
            setOf("Basic information", "Personality", "Preferred assistant style")
        val headers =
            Regex("(?m)^## ([^\\r\\n]+)\\r?$")
                .findAll(archiveContent)
                .filter { it.groupValues[1] !in sectionNames }
                .toList()
        return headers.mapIndexedNotNull { index, header ->
            val contentStart = header.range.last + 1
            val contentEnd = headers.getOrNull(index + 1)?.range?.first ?: archiveContent.length
            val content = archiveContent.substring(contentStart, contentEnd).trim()
            content.takeIf(String::isNotBlank)?.let {
                ArchivedProfileDocument(name = header.groupValues[1], content = it)
            }
        }
    }

    private fun LegacyUserProfile.toUserMarkdown(): String {
        if (!hasStructuredUserContent()) return ""
        return buildString {
            appendLine("# About me")
            appendLine()
            appendProfileSections(this@toUserMarkdown)
        }.trimEnd() + "\n"
    }

    private fun LegacyUserProfile.hasStructuredUserContent(): Boolean {
        return birthDate > 0L || gender.isNotBlank() || personality.isNotBlank() ||
            identity.isNotBlank() || occupation.isNotBlank() || aiStyle.isNotBlank()
    }

    private fun StringBuilder.appendProfileSections(profile: LegacyUserProfile) {
        val basicItems = buildList {
            if (profile.gender.isNotBlank()) add("Gender: ${profile.gender}")
            if (profile.birthDate > 0L) {
                val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                add("Birth date: ${formatter.format(Date(profile.birthDate))}")
            }
            if (profile.identity.isNotBlank()) add("Identity: ${profile.identity}")
            if (profile.occupation.isNotBlank()) add("Occupation: ${profile.occupation}")
        }
        if (basicItems.isNotEmpty()) {
            appendLine("## Basic information")
            appendLine()
            basicItems.forEach { appendLine("- $it") }
        }
        if (profile.personality.isNotBlank()) {
            if (basicItems.isNotEmpty()) appendLine()
            appendLine("## Personality")
            appendLine()
            appendLine(profile.personality)
        }
        if (profile.aiStyle.isNotBlank()) {
            if (basicItems.isNotEmpty() || profile.personality.isNotBlank()) appendLine()
            appendLine("## Preferred assistant style")
            appendLine()
            appendLine(profile.aiStyle)
        }
    }

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val atomicFile = AtomicFile(target)
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(content.toByteArray(StandardCharsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            output?.let(atomicFile::failWrite)
            throw error
        }
    }
}
