package app.rork.p7buzvrzcw3frddi8y50ou.render.glb

import java.util.concurrent.atomic.AtomicInteger

/**
 * The game's one registry of GLB assets. Content packs declare models (id, category,
 * file, version); characters and buildings reference them by id; nothing in gameplay ever
 * knows a file path. The registry loads lazily and caches — one parsed [GlbModel] per id,
 * never twice — records every failure with a plain-language reason, and can prefetch on a
 * background thread so the render thread only ever reads what is already in memory.
 *
 * Reads are safe from the render thread: [modelAt] returns only what is already loaded
 * and never blocks; an actor whose asset is still arriving draws procedurally until the
 * asset lands.
 */
object GlbRegistry {

    /** Asset categories, a closed vocabulary validated at declaration. */
    val CATEGORIES = listOf(
        "characters", "npcs", "enemies", "creatures", "equipment",
        "weapons", "armor", "clothing", "environment", "props",
    )

    /** A declared asset: the id gameplay references, and where its file lives. */
    class Entry(
        val id: String,
        val category: String,
        val file: String,
        val version: Int,
    ) {
        /** Filled when the asset loads. */
        var vertexCount = 0
            internal set
        var triangleCount = 0
            internal set
        var clipNames: List<String> = emptyList()
            internal set
        var attachmentNames: List<String> = emptyList()
            internal set
        var jointCount = 0
            internal set
        var skinned = false
            internal set
        var materials: List<String> = emptyList()
            internal set
        var generator = ""
            internal set
    }

    /** Where asset bytes come from: the app's assets on device, memory in tests. */
    interface SourceProvider {
        fun read(file: String): ByteArray
    }

    @Volatile private var provider: SourceProvider? = null
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private val loaded = HashMap<String, GlbModel>()
    private val failures = LinkedHashMap<String, String>()
    private val handles = HashMap<String, Int>()
    private val nextHandle = AtomicInteger(0)

    /** Points the registry at its byte source (the app's assets). Called once at startup. */
    fun attach(source: SourceProvider) {
        provider = source
    }

    /** Forgets every declaration, cache entry and failure — used when content reloads and by tests. */
    fun reset() {
        synchronized(lock) {
            entries.clear()
            loaded.clear()
            failures.clear()
            handles.clear()
            nextHandle.set(0)
        }
    }

    /** Declares a pack's models; unknown categories and blank ids are reported and left out. */
    fun declare(packId: String, id: String, category: String, file: String, version: Int): List<String> {
        val problems = ArrayList<String>()
        if (id.isBlank()) {
            problems.add("A model in the pack '$packId' has no id and was left out.")
            return problems
        }
        if (file.isBlank()) {
            problems.add("The model '$id' in the pack '$packId' names no file and was left out.")
            return problems
        }
        if (category !in CATEGORIES) {
            problems.add("The model '$id' in the pack '$packId' declares the unknown category '$category' and was left out.")
            return problems
        }
        synchronized(lock) {
            if (entries.containsKey(id) || handles.containsKey(id)) {
                problems.add("The model id '$id' in the pack '$packId' is already taken and was left out.")
                return problems
            }
            entries[id] = Entry(id, category, file, version)
            handles[id] = nextHandle.getAndIncrement()
            failures.remove(id)
        }
        return problems
    }

    /** A test seam: hands the registry bytes for [id] as if loaded from the source. */
    fun offer(id: String, bytes: ByteArray): Boolean {
        val entry = synchronized(lock) { entries[id] } ?: return false
        return load(entry, bytes)
    }

    private fun load(entry: Entry, bytes: ByteArray): Boolean {
        try {
            val model = GlbModel.from(bytes)
            synchronized(lock) {
                loaded[entry.id] = model
                entry.vertexCount = model.meshes.sumOf { it.vertexCount }
                entry.triangleCount = model.triangleCount
                entry.clipNames = model.clips.map { it.name }
                entry.attachmentNames = model.attachments.keys.toList()
                entry.jointCount = model.skin?.joints?.size ?: 0
                entry.skinned = model.isSkinned
                entry.materials = model.meshes.map { it.material.name }
                entry.generator = model.generator
                failures.remove(entry.id)
            }
            return true
        } catch (e: GlbFormatException) {
            synchronized(lock) {
                loaded.remove(entry.id)
                failures[entry.id] = e.message ?: "unreadable"
            }
            return false
        } catch (e: Exception) {
            synchronized(lock) {
                loaded.remove(entry.id)
                failures[entry.id] = "The asset could not be read: ${e.message}"
            }
            return false
        }
    }

    /** The numeric handle a def's `model` string resolves to (-1 when unset or unknown). */
    fun handle(id: String): Int = if (id.isBlank()) -1 else synchronized(lock) { handles[id] ?: -1 }

    fun entry(handle: Int): Entry? = synchronized(lock) {
        entries.values.firstOrNull { handles[it.id] == handle }
    }

    fun entryById(id: String): Entry? = synchronized(lock) { entries[id] }

    /** Loads (or fetches from cache) the model for [id], reading from the source. Null on failure. */
    fun get(id: String): GlbModel? {
        val cached = synchronized(lock) { loaded[id] }
        if (cached != null) return cached
        val entry = synchronized(lock) { entries[id] } ?: return null
        val bytes = try {
            provider?.read(entry.file) ?: return null
        } catch (e: Exception) {
            synchronized(lock) { failures[entry.id] = "The file '${entry.file}' could not be opened: ${e.message}" }
            return null
        }
        return if (load(entry, bytes)) synchronized(lock) { loaded[entry.id] } else null
    }

    /** Non-blocking, render-thread-safe lookup by handle: only the cache, never a load. */
    fun modelAt(handle: Int): GlbModel? = synchronized(lock) {
        entries.values.firstOrNull { handles[it.id] == handle }?.let { loaded[it.id] }
    }

    /** Prefetches the named ids on a background thread; the render thread never waits. */
    fun prefetch(ids: List<String>) {
        val wanted = ids.filter { it.isNotBlank() }
        if (wanted.isEmpty()) return
        Thread({
            for (id in wanted) get(id)
        }, "glb-prefetch").apply { isDaemon = true }.start()
    }

    /** Lines for the diagnostics report: what is declared, loaded and failed. */
    fun report(): List<String> = synchronized(lock) {
        val lines = ArrayList<String>()
        if (entries.isEmpty() && failures.isEmpty()) return lines
        val byCategory = entries.values.groupBy { it.category }
        for ((category, group) in byCategory) {
            lines.add("$category: ${group.size}")
            for (e in group) {
                val state = if (loaded.containsKey(e.id)) {
                    val clips = if (e.clipNames.isEmpty()) "" else ", clips ${e.clipNames.joinToString("/")}"
                    val joints = if (e.skinned) ", ${e.jointCount} joints" else ""
                    "loaded — ${e.vertexCount} vertices, ${e.triangleCount} triangles$clips$joints"
                } else {
                    failures[e.id]?.let { "failed — $it" } ?: "declared, not yet loaded"
                }
                lines.add("  ${e.id} v${e.version}: $state")
            }
        }
        lines
    }

    /** Drops every cached model (registry entries stay — declarations are content). */
    fun releaseAll() {
        synchronized(lock) {
            loaded.clear()
        }
    }
}
