package app.rork.p7buzvrzcw3frddi8y50ou.render.glb

import kotlin.math.acos
import kotlin.math.sin

/**
 * One named animation clip, resolved from the glTF animation: per node, TRS tracks of
 * (times, values), ready to sample. Clip data is read once at load; sampling allocates
 * nothing.
 */
class Clip(
    val name: String,
    val duration: Float,
    /** Per animated node: its index in the document, and its translation/rotation/scale tracks. */
    val tracks: List<Track>,
    /** Weight tracks for morph targets, per animated mesh node. */
    val morphTracks: List<MorphTrack> = emptyList(),
) {
    class Track(
        val node: Int,
        val times: FloatArray,
        /** Translation (3), rotation quaternion xyzw (4) or scale (3) values. */
        val values: FloatArray,
        val stride: Int,
        val path: String,
    )

    class MorphTrack(val node: Int, val times: FloatArray, val values: FloatArray, val widths: Int)

    /**
     * Samples every track at [seconds], writing local TRS into [out] (per node, 10 floats:
     * tx ty tz qx qy qz qw sx sy sz). One-shot clips hold their last frame; looping ones wrap.
     */
    fun sample(seconds: Float, out: FloatArray, loop: Boolean = true) {
        val t = if (loop) {
            if (duration <= 0f) 0f else seconds % duration
        } else {
            seconds.coerceIn(0f, duration)
 }
        for (track in tracks) {
            val base = track.node * 10
            when (track.path) {
                "translation" -> sampleVec(track, t, out, base, 3)
                "scale" -> sampleVec(track, t, out, base + 7, 3)
                "rotation" -> sampleQuat(track, t, out, base + 3)
            }
        }
    }

    private fun sampleVec(track: Track, t: Float, out: FloatArray, at: Int, n: Int) {
        val v = sampleValues(track, t, n)
        for (i in 0 until n) out[at + i] = v[i]
    }

    private fun sampleQuat(track: Track, t: Float, out: FloatArray, at: Int) {
        // Component-wise lerp then normalize (nlerp) — visually smooth and allocation-free.
        val v = sampleValues(track, t, 4)
        out[at] = v[0]; out[at + 1] = v[1]; out[at + 2] = v[2]; out[at + 3] = v[3]
        val len = kotlin.math.sqrt(
            out[at] * out[at] + out[at + 1] * out[at + 1] + out[at + 2] * out[at + 2] + out[at + 3] * out[at + 3],
        )
        if (len > 1e-9f) {
            out[at] /= len; out[at + 1] /= len; out[at + 2] /= len; out[at + 3] /= len
        }
    }

    /** Linear interpolation between the track's neighbouring keyframes. */
    private fun sampleValues(track: Track, t: Float, n: Int): FloatArray {
        val times = track.times
        val result = FloatArray(n)
        if (times.isEmpty()) return result
        var i = 0
        while (i < times.size - 1 && times[i + 1] < t) i++
        if (i >= times.size - 1 || t <= times[0]) {
            val idx = if (t <= times[0]) 0 else times.size - 1
            for (c in 0 until n) result[c] = track.values.getOrElse(idx * track.stride + c) { 0f }
            return result
        }
        val t0 = times[i]; val t1 = times[i + 1]
        val a = if (t1 > t0) (t - t0) / (t1 - t0) else 0f
        for (c in 0 until n) {
            val v0 = track.values.getOrElse(i * track.stride + c) { 0f }
            val v1 = track.values.getOrElse((i + 1) * track.stride + c) { 0f }
            result[c] = v0 + (v1 - v0) * a
        }
        return result
    }

    companion object {
        /** Resolves a document animation into a clip; missing accessors drop their channel. */
        fun from(a: GltfAnimation, doc: GltfDocument, reader: AccessorReader, skin: GlbModel.Skin?): Clip {
            val tracks = ArrayList<Track>()
            val morphs = ArrayList<MorphTrack>()
            var duration = 0f
            for (channel in a.channels) {
                val sampler = a.samplers.getOrNull(channel.sampler) ?: continue
                val times = try {
                    reader.read(sampler.input)
                } catch (e: GlbFormatException) {
                    continue
                }
                for (t in times) if (t > duration) duration = t
                val node = channel.target.node
                if (node < 0) continue
                when (channel.target.path) {
                    "translation", "rotation", "scale" -> {
                        val values = try {
                            reader.read(sampler.output)
                        } catch (e: GlbFormatException) {
                            continue
                        }
                        val stride = when (channel.target.path) {
                            "rotation" -> 4
                            else -> 3
                        }
                        tracks.add(Track(node, times, values, stride, channel.target.path))
                    }
                    "weights" -> {
                        val values = try {
                            reader.read(sampler.output)
                        } catch (e: GlbFormatException) {
                            continue
                        }
                        val nodeDef = doc.nodes.getOrNull(node)
                        val widths = nodeDef?.mesh?.let { doc.meshes[it].weights.size } ?: 0
                        if (widths > 0) morphs.add(MorphTrack(node, times, values, widths))
                    }
                }
            }
            return Clip(a.name.ifEmpty { "clip" }, duration, tracks, morphs)
        }

        /** Quaternion slerp between a and b, into out — used by blending. */
        fun slerp(ax: Float, ay: Float, az: Float, aw: Float, bx: Float, by: Float, bz: Float, bw: Float, weight: Float, out: FloatArray, at: Int) {
            var dot = ax * bx + ay * by + az * bz + aw * bw
            var ex = bx; var ey = by; var ez = bz; var ew = bw
            if (dot < 0f) {
                dot = -dot; ex = -ex; ey = -ey; ez = -ez; ew = -ew
            }
            if (dot > 0.9995f) {
                out[at] = ax + (ex - ax) * weight
                out[at + 1] = ay + (ey - ay) * weight
                out[at + 2] = az + (ez - az) * weight
                out[at + 3] = aw + (ew - aw) * weight
            } else {
                val theta = acos(dot.coerceIn(-1f, 1f))
                val s = sin(theta)
                val wa = sin((1f - weight) * theta) / s
                val wb = sin(weight * theta) / s
                out[at] = ax * wa + ex * wb
                out[at + 1] = ay * wa + ey * wb
                out[at + 2] = az * wa + ez * wb
                out[at + 3] = aw * wa + ew * wb
            }
        }
    }
}

/**
 * Plays a model's clips: one current clip, an optional outgoing one fading out, looping
 * or one-shot, at any speed, interruptible at any moment. Stateless with respect to the
 * game — the caller supplies the time, exactly as the procedural poses do.
 */
class GlbAnimator(private val model: GlbModel) {

    /** Named clips this model actually carries; lookups fall back down this ladder. */
    val clipNames: List<String> get() = model.clips.map { it.name }

    fun has(name: String): Boolean = model.clip(name) != null

    /** How the sampler fills: nodes not driven by a clip keep their rest pose. */
    private val localSize = model.nodes.size * 10

    /**
     * Samples [name] at [seconds], blending from the previous clip over [fadeSeconds] when
     * one is playing (a crossfade — the blend weight walks 0→1 and the outgoing pose fades
     * out under it). Falls back through the ladder when the clip is missing. Returns the
     * per-node local TRS array (10 floats per node). One-shot clips pass loop = false.
     */
    fun sample(name: String, seconds: Float, speed: Float = 1f, loop: Boolean = true, fadeSeconds: Float = 0.2f): FloatArray {
        val primary = resolve(name) ?: return restPose()
        val out = FloatArray(localSize)
        for (n in model.nodes.indices) restInto(out, n)
        primary.sample(seconds * speed, out, loop)
        lastTime = seconds
        // A fading previous pose, when interrupted recently, weights under the primary.
        val fade = fading
        if (fade != null && fadeSeconds > 0f) {
            val weight = ((seconds - fadeStart) / fadeSeconds).coerceIn(0f, 1f)
            if (weight < 1f) {
                val prev = FloatArray(localSize)
                for (n in model.nodes.indices) restInto(prev, n)
                fade.sample(fadeTime, prev)
                for (i in 0 until localSize) out[i] = out[i] * weight + prev[i] * (1f - weight)
            } else {
                fading = null
            }
        }
        current = primary
        return out
    }

    /** Interrupts whatever plays with [name] from [seconds], crossfading over [fadeSeconds]. */
    fun interrupt(name: String, seconds: Float, fadeSeconds: Float = 0.2f) {
        val next = resolve(name) ?: return
        fading = fading ?: current
        fadeStart = seconds
        fadeTime = lastTime
        current = next
        lastTime = seconds
    }

    private var current: Clip? = null
    private var fading: Clip? = null
    private var fadeStart = 0f
    private var fadeTime = 0f
    private var lastTime = 0f

    /** Falls through the clip ladder so a model missing "run" still walks. */
    private fun resolve(name: String): Clip? = model.clip(name) ?: ladder[name]?.let { ladderNames ->
        for (fallback in ladderNames) model.clip(fallback)?.let { return it }
        null
    }

    private fun restPose(): FloatArray {
        val out = FloatArray(localSize)
        for (n in model.nodes.indices) restInto(out, n)
        return out
    }

    private fun restInto(out: FloatArray, node: Int) {
        val local = model.nodes[node].local
        // Extract TRS from the matrix; the node's own rest — rotation included — is kept.
        out[node * 10] = local[12]; out[node * 10 + 1] = local[13]; out[node * 10 + 2] = local[14]
        Mat4.quatFromMatrix(local, out, node * 10 + 3)
        val sx = kotlin.math.sqrt(local[0] * local[0] + local[1] * local[1] + local[2] * local[2])
        val sy = kotlin.math.sqrt(local[4] * local[4] + local[5] * local[5] + local[6] * local[6])
        val sz = kotlin.math.sqrt(local[8] * local[8] + local[9] * local[9] + local[10] * local[10])
        out[node * 10 + 7] = sx; out[node * 10 + 8] = sy; out[node * 10 + 9] = sz
    }

    companion object {
        /** Fallback ladder: a requested clip resolves to the first the asset carries. */
        private val ladder = mapOf(
            "idle" to listOf("idle"),
            "walk" to listOf("walk", "run", "idle"),
            "run" to listOf("run", "walk", "idle"),
            "sit" to listOf("sit", "idle"),
            "interact" to listOf("interact", "attack", "idle"),
            "hold" to listOf("hold", "idle"),
            "work" to listOf("work", "interact", "idle"),
            "attack" to listOf("attack", "interact", "idle"),
            "block" to listOf("block", "idle"),
            "hit" to listOf("hit", "idle"),
            "death" to listOf("death", "hit", "idle"),
            "jump" to listOf("jump", "idle"),
            "fall" to listOf("fall", "jump", "idle"),
            "turn_left" to listOf("turn_left", "idle"),
            "turn_right" to listOf("turn_right", "idle"),
        )
        private val ladderNames = ladder.values.flatten().distinct()
    }
}
