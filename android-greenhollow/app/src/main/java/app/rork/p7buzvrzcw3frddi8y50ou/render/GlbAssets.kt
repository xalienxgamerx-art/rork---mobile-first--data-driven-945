package app.rork.p7buzvrzcw3frddi8y50ou.render

import android.content.Context
import app.rork.p7buzvrzcw3frddi8y50ou.Diagnostics
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentRegistry
import app.rork.p7buzvrzcw3frddi8y50ou.render.glb.GlbRegistry

/**
 * Joins the content world to the GLB registry: declares every pack's models, points the
 * registry at the app's assets, and prefetches the models the world actually references on
 * a background thread, so the render thread only ever reads what is already in memory.
 */
object GlbAssets {
    private var installedFor: ContentRegistry? = null

    fun install(context: Context, content: ContentRegistry) {
        if (installedFor === content) return
        installedFor = content
        GlbRegistry.attach(object : GlbRegistry.SourceProvider {
            override fun read(file: String): ByteArray =
                context.assets.open(file).use { it.readBytes() }
        })
        GlbRegistry.reset()
        for (def in content.models.values) {
            for (problem in GlbRegistry.declare("content", def.id, def.category, def.file, def.version)) {
                Diagnostics.record("glb", problem)
            }
        }
        val referenced = buildList {
            content.npcs.values.forEach { if (it.model.isNotBlank()) add(it.model) }
            content.creatures.values.forEach { if (it.model.isNotBlank()) add(it.model) }
            content.buildings.values.forEach { if (it.model.isNotBlank()) add(it.model) }
        }.distinct()
        if (referenced.isNotEmpty()) GlbRegistry.prefetch(referenced)
    }
}
