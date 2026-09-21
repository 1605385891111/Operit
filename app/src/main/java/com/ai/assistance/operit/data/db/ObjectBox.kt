package com.ai.assistance.operit.data.db

import android.content.Context
import com.ai.assistance.operit.data.model.MyObjectBox
import io.objectbox.BoxStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ObjectBoxManager {
    private val stores = ConcurrentHashMap<String, BoxStore>()
    private val storeLock = Any()

    fun get(context: Context, profileId: String): BoxStore {
        synchronized(storeLock) {
            stores[profileId]?.let { return it }
            val store = buildStore(context, profileId)
            stores[profileId] = store
            return store
        }
    }

    private fun buildStore(context: Context, profileId: String): BoxStore {
        // 如果profileId是"default"，我们使用旧的数据库位置以实现向后兼容
        val dbName = if (profileId == "default") "objectbox" else "objectbox_$profileId"
        val dbDir = File(context.filesDir, dbName)

        return MyObjectBox.builder()
            .androidContext(context.applicationContext)
            .directory(dbDir)
            .build()
    }

/** 某个记忆空间对应的 ObjectBox目录 */
    private fun directoryFor(context: Context, profileId: String): File {
        val dbName = if (profileId == "default") "objectbox" else "objectbox_$profileId"
        return File(context.filesDir, dbName)
    }

/**
     * 把记忆空间的 ObjectBox库复制到新空间，并裁掉"分支点之后"才产生的记忆。
     *
     * 先关闭源/目标 store 再整目录复制以保证拷贝一致；复制完成后删除 createdAt 晚于
     * [maxCreatedAtMillis] 的记忆，以及引用它们的记忆链接与文档分块；目标空间的自动记忆
     * 候选队列会被清空（候选属于原对话，分支会自己重新产生）。
     *
     * @return true 表示完成了复制；false 表示源空间本来就没有结构化记忆
     */
    fun forkMemoryStore(
        context: Context,
        sourceProfileId: String,
        targetProfileId: String,
        maxCreatedAtMillis: Long
    ): Boolean {
        return synchronized(storeLock) {
            try {
                close(sourceProfileId)
                close(targetProfileId)
                val sourceDir = directoryFor(context, sourceProfileId)
                val targetDir = directoryFor(context, targetProfileId)
                if (targetDir.exists()) targetDir.deleteRecursively()
                if (!sourceDir.exists()) return@synchronized false
                sourceDir.copyRecursively(targetDir, overwrite = true)

                val store = get(context, targetProfileId)
                store.runInTx {
                    val memoryBox =
                        store.boxFor(com.ai.assistance.operit.data.model.Memory::class.java)
                    val staleMemories =
                        memoryBox.query().build().find().filter {
                            it.createdAt.time > maxCreatedAtMillis
                        }
                    if (staleMemories.isNotEmpty()) {
                        val staleIds = staleMemories.map { it.id }.toSet()
                        val linkBox =
                            store.boxFor(
                                com.ai.assistance.operit.data.model.MemoryLink::class.java
                            )
                        linkBox
                            .query()
                            .build()
                            .find()
                            .filter { it.source.targetId in staleIds || it.target.targetId in staleIds }
                            .forEach { linkBox.remove(it) }
                        val chunkBox =
                            store.boxFor(
                                com.ai.assistance.operit.data.model.DocumentChunk::class.java
                            )
                        chunkBox
                            .query()
                            .build()
                            .find()
                            .filter { it.memory.targetId in staleIds }
                            .forEach { chunkBox.remove(it) }
                        staleMemories.forEach { memoryBox.remove(it) }
                    }
                    store
                        .boxFor(
                            com.ai.assistance.operit.data.model.MemoryAutoSaveCandidate::class.java
                        )
                        .removeAll()
                }
                true
            } catch (e: Exception) {
                com.ai.assistance.operit.util.AppLogger.e(
                    "ObjectBoxManager",
                    "记忆图谱复制失败: $sourceProfileId -> $targetProfileId",
                    e
                )
                false
            }
        }
    }

    fun close(profileId: String) {
        synchronized(storeLock) {
            val store = stores.remove(profileId) ?: return
            store.close()
        }
    }

    /**
     * 物理删除指定profileId的数据库（包括关闭store和删除文件夹）。
     */
    fun delete(context: Context, profileId: String) {
        synchronized(storeLock) {
            stores.remove(profileId)?.close() // 先关闭
            val dbName = if (profileId == "default") "objectbox" else "objectbox_$profileId"
            val dbDir = File(context.filesDir, dbName)
            if (dbDir.exists()) {
                dbDir.deleteRecursively()
            }
        }
    }

    fun closeAll() {
        synchronized(storeLock) {
            stores.values.forEach { it.close() }
            stores.clear()
        }
    }
} 
