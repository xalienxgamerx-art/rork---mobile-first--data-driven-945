package app.rork.p7buzvrzcw3frddi8y50ou.render.glb

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/**
 * The glTF 2.0 document, as data: only what this game's pipeline consumes, every field
 * defaulted so a lean asset loads as easily as a rich one. All of it is pure Kotlin —
 * nothing here knows Android — so the whole pipeline is covered by JVM tests.
 */
@Serializable
data class GltfDocument(
    val asset: GltfAssetInfo = GltfAssetInfo(),
    val scene: Int = 0,
    val scenes: List<GltfScene> = emptyList(),
    val nodes: List<GltfNode> = emptyList(),
    val meshes: List<GltfMesh> = emptyList(),
    val materials: List<GltfMaterial> = emptyList(),
    val textures: List<GltfTexture> = emptyList(),
    val images: List<GltfImage> = emptyList(),
    val samplers: List<GltfSampler> = emptyList(),
    val accessors: List<GltfAccessor> = emptyList(),
    val bufferViews: List<GltfBufferView> = emptyList(),
    val buffers: List<GltfBuffer> = emptyList(),
    val skins: List<GltfSkin> = emptyList(),
    val animations: List<GltfAnimation> = emptyList(),
)

@Serializable
data class GltfAssetInfo(val version: String = "2.0", val generator: String = "")

@Serializable
data class GltfScene(val nodes: List<Int> = emptyList(), val name: String = "")

@Serializable
data class GltfNode(
    val name: String = "",
    val children: List<Int> = emptyList(),
    val mesh: Int = -1,
    val skin: Int = -1,
    val translation: List<Float> = emptyList(),
    val rotation: List<Float> = emptyList(), // xyzw quaternion
    val scale: List<Float> = emptyList(),
    /** Column-major local transform; takes precedence over TRS when present. */
    val matrix: List<Float> = emptyList(),
    /** Weights applied to the mesh's morph targets on this node. */
    val weights: List<Float> = emptyList(),
)

@Serializable
data class GltfMesh(
    val name: String = "",
    val primitives: List<GltfPrimitive> = emptyList(),
    val weights: List<Float> = emptyList(),
    val targets: List<Map<String, List<Float>>> = emptyList(),
)

@Serializable
data class GltfPrimitive(
    val attributes: Map<String, Int> = emptyMap(),
    val indices: Int = -1,
    val material: Int = -1,
    val mode: Int = 4, // 4 = TRIANGLES
    /** Per-primitive morph target attribute deltas (POSITION/NORMAL accessor indices). */
    val targets: List<Map<String, Int>> = emptyList(),
)

@Serializable
data class GltfMaterial(
    val name: String = "",
    val pbrMetallicRoughness: GltfPbr = GltfPbr(),
    val emissiveFactor: List<Float> = emptyList(),
    val alphaMode: String = "OPAQUE",
    val doubleSided: Boolean = false,
)

@Serializable
data class GltfPbr(
    val baseColorFactor: List<Float> = emptyList(),
    val baseColorTexture: GltfTextureInfo? = null,
    val metallicFactor: Float = 1f,
    val roughnessFactor: Float = 1f,
)

@Serializable
data class GltfTextureInfo(val index: Int = 0, val texCoord: Int = 0)

@Serializable
data class GltfTexture(val sampler: Int = 0, val source: Int = -1, val name: String = "")

/** An image embedded in the binary chunk (bufferView) or referenced by uri. */
@Serializable
data class GltfImage(val uri: String = "", val mimeType: String = "", val bufferView: Int = -1, val name: String = "")

@Serializable
data class GltfSampler(val magFilter: Int = 0, val minFilter: Int = 0, val wrapS: Int = 0, val wrapT: Int = 0)

@Serializable
data class GltfAccessor(
    val bufferView: Int = -1,
    val byteOffset: Int = 0,
    val componentType: Int = 0,
    val count: Int = 0,
    val type: String = "",
    val normalized: Boolean = false,
    val min: List<Float> = emptyList(),
    val max: List<Float> = emptyList(),
)

@Serializable
data class GltfBufferView(
    val buffer: Int = 0,
    val byteOffset: Int = 0,
    val byteLength: Int = 0,
    val byteStride: Int = 0,
    val target: Int = 0,
)

@Serializable
data class GltfBuffer(val uri: String = "", val byteLength: Int = 0)

@Serializable
data class GltfSkin(
    val name: String = "",
    /** Joint node indices, in the order skins and animations reference them. */
    val joints: List<Int> = emptyList(),
    /** Optional root node of the joint hierarchy. */
    val skeleton: Int = -1,
    /** One inverse bind matrix (column-major MAT4) per joint. */
    val inverseBindMatrices: Int = -1,
)

@Serializable
data class GltfAnimation(
    val name: String = "",
    val channels: List<GltfChannel> = emptyList(),
    val samplers: List<GltfAnimationSampler> = emptyList(),
)

@Serializable
data class GltfAnimationSampler(val input: Int = 0, val output: Int = 0, val interpolation: String = "LINEAR")

/** One channel drives one property (translation/rotation/scale/weights) of one node. */
@Serializable
data class GltfChannel(val sampler: Int = 0, val target: GltfChannelTarget = GltfChannelTarget())

@Serializable
data class GltfChannelTarget(val node: Int = -1, val path: String = "")

/** Raised with a plain-language reason whenever a GLB cannot be used. */
class GlbFormatException(reason: String) : Exception(reason)

/** Component type constants, as glTF numbers them. */
object ComponentType {
    const val BYTE = 5120
    const val UNSIGNED_BYTE = 5121
    const val SHORT = 5122
    const val UNSIGNED_SHORT = 5123
    const val UNSIGNED_INT = 5125
    const val FLOAT = 5126
}

/**
 * Reads glTF accessors out of the binary chunk into plain float arrays, honouring component
 * type, normalization and buffer-view stride — the fiddly half of the format, kept in one
 * place and covered by tests.
 */
class AccessorReader(private val doc: GltfDocument, private val bin: ByteArray) {

    /** The accessor's element width in floats (MAT4 = 16, VEC4 = 4, SCALAR = 1). */
    fun floatsPerElement(type: String): Int = when (type) {
        "SCALAR" -> 1
        "VEC2" -> 2
        "VEC3" -> 3
        "VEC4" -> 4
        "MAT2" -> 4
        "MAT3" -> 9
        "MAT4" -> 16
        else -> throw GlbFormatException("The accessor type '$type' is not one the game knows.")
    }

    private fun componentSize(componentType: Int): Int = when (componentType) {
        ComponentType.BYTE, ComponentType.UNSIGNED_BYTE -> 1
        ComponentType.SHORT, ComponentType.UNSIGNED_SHORT -> 2
        ComponentType.UNSIGNED_INT, ComponentType.FLOAT -> 4
        else -> throw GlbFormatException("The accessor component type $componentType is not one the game knows.")
    }

    /** Reads every element of an accessor as floats, normalizing where the accessor says to. */
    fun read(index: Int): FloatArray {
        if (index < 0 || index >= doc.accessors.size) {
            throw GlbFormatException("The asset points at accessor $index, which does not exist.")
        }
        val acc = doc.accessors[index]
        val n = floatsPerElement(acc.type)
        val out = FloatArray(acc.count * n)
        if (acc.bufferView < 0) return out // a sparse-less empty accessor
        if (acc.bufferView >= doc.bufferViews.size) {
            throw GlbFormatException("The accessor $index points at buffer view ${acc.bufferView}, which does not exist.")
        }
        val view = doc.bufferViews[acc.bufferView]
        if (view.buffer != 0) {
            throw GlbFormatException("The asset reads from buffer ${view.buffer}, but a glTF binary carries only one.")
        }
        val compSize = componentSize(acc.componentType)
        val elemSize = compSize * n
        val stride = if (view.byteStride > 0) view.byteStride else elemSize
        if (stride < elemSize) {
            throw GlbFormatException("The buffer view behind accessor $index packs its data tighter than the accessor type allows.")
        }
        val base = view.byteOffset + acc.byteOffset
        val end = base + stride.toLong() * (acc.count - 1) + elemSize
        if (end > bin.size) {
            throw GlbFormatException("The accessor $index reads past the end of the asset's data.")
        }
        for (e in 0 until acc.count) {
            var p = base + e * stride
            for (c in 0 until n) {
                out[e * n + c] = readComponent(p, acc.componentType, acc.normalized)
                p += compSize
            }
        }
        return out
    }

    private fun readComponent(p: Int, componentType: Int, normalized: Boolean): Float = when (componentType) {
        ComponentType.FLOAT -> readFloat(p)
        ComponentType.BYTE -> {
            val v = bin[p].toInt()
            if (normalized) maxOf(v / 127f, -1f) else v.toFloat()
        }
        ComponentType.UNSIGNED_BYTE -> {
            val v = bin[p].toInt() and 0xFF
            if (normalized) v / 255f else v.toFloat()
        }
        ComponentType.SHORT -> {
            val v = readShort(p)
            if (normalized) maxOf(v / 32767f, -1f) else v.toFloat()
        }
        ComponentType.UNSIGNED_SHORT -> {
            val v = readShort(p) and 0xFFFF
            if (normalized) v / 65535f else v.toFloat()
        }
        ComponentType.UNSIGNED_INT -> (readInt(p).toLong() and 0xFFFFFFFFL).toFloat()
        else -> 0f
    }

    /** Reads an index accessor as ints — indices arrive as any unsigned component type. */
    fun readIndices(index: Int): IntArray {
        val acc = doc.accessors[index]
        val out = IntArray(acc.count)
        val view = doc.bufferViews[acc.bufferView]
        val compSize = componentSize(acc.componentType)
        var p = view.byteOffset + acc.byteOffset
        for (i in 0 until acc.count) {
            out[i] = when (acc.componentType) {
                ComponentType.UNSIGNED_BYTE -> bin[p].toInt() and 0xFF
                ComponentType.UNSIGNED_SHORT -> readShort(p) and 0xFFFF
                ComponentType.UNSIGNED_INT -> readInt(p)
                else -> throw GlbFormatException("Indices must be unsigned bytes, shorts or ints; this asset uses something else.")
            }
            p += compSize
        }
        return out
    }

    private fun readFloat(p: Int): Float = Float.fromBits(readInt(p))
    private fun readShort(p: Int): Int = (bin[p].toInt() and 0xFF) or ((bin[p + 1].toInt() and 0xFF) shl 8)
    private fun readInt(p: Int): Int =
        (bin[p].toInt() and 0xFF) or ((bin[p + 1].toInt() and 0xFF) shl 8) or
            ((bin[p + 2].toInt() and 0xFF) shl 16) or ((bin[p + 3].toInt() and 0xFF) shl 24)
}

/**
 * Small column-major 4x4 matrix helpers over FloatArray(16), in the same convention
 * Android's Matrix uses — the renderer's own matrices hand these straight over.
 */
object Mat4 {
    fun identity(out: FloatArray = FloatArray(16)): FloatArray {
        java.util.Arrays.fill(out, 0f)
        out[0] = 1f; out[5] = 1f; out[10] = 1f; out[15] = 1f
        return out
    }

    /** out = a * b (column-major). */
    fun mul(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (col in 0..3) {
            for (row in 0..3) {
                tmp[col * 4 + row] =
                    a[row] * b[col * 4] + a[4 + row] * b[col * 4 + 1] +
                        a[8 + row] * b[col * 4 + 2] + a[12 + row] * b[col * 4 + 3]
            }
        }
        System.arraycopy(tmp, 0, out, 0, 16)
    }

    /** Composes a local transform from an optional matrix, else translation/rotation/scale. */
    fun trs(node: GltfNode, out: FloatArray): FloatArray {
        if (node.matrix.size == 16) {
            System.arraycopy(node.matrix.toFloatArray(), 0, out, 0, 16)
            return out
        }
        identity(out)
        if (node.rotation.size == 4) {
            val q = floatArrayOf(node.rotation[0], node.rotation[1], node.rotation[2], node.rotation[3])
            val r = FloatArray(16)
            quatToMatrix(q, r)
            val saved = out.copyOf()
            mul(out, saved, r)
        }
        if (node.scale.size == 3) {
            out[0] *= node.scale[0]; out[1] *= node.scale[0]; out[2] *= node.scale[0]
            out[4] *= node.scale[1]; out[5] *= node.scale[1]; out[6] *= node.scale[1]
            out[8] *= node.scale[2]; out[9] *= node.scale[2]; out[10] *= node.scale[2]
        }
        if (node.translation.size == 3) {
            out[12] += node.translation[0]; out[13] += node.translation[1]; out[14] += node.translation[2]
        }
        return out
    }

    /** Quaternion (xyzw) to a column-major rotation matrix. */
    fun quatToMatrix(q: FloatArray, out: FloatArray) {
        val (x, y, z, w) = q
        val len = sqrt(x * x + y * y + z * z + w * w)
        if (len < 1e-9f) {
            identity(out)
            return
        }
        val nx = x / len; val ny = y / len; val nz = z / len; val nw = w / len
        val xx = nx * nx; val yy = ny * ny; val zz = nz * nz
        val xy = nx * ny; val xz = nx * nz; val yz = ny * nz
        val wx = nw * nx; val wy = nw * ny; val wz = nw * nz
        out[0] = 1f - 2f * (yy + zz); out[1] = 2f * (xy + wz); out[2] = 2f * (xz - wy); out[3] = 0f
        out[4] = 2f * (xy - wz); out[5] = 1f - 2f * (xx + zz); out[6] = 2f * (yz + wx); out[7] = 0f
        out[8] = 2f * (xz + wy); out[9] = 2f * (yz - wx); out[10] = 1f - 2f * (xx + yy); out[11] = 0f
        out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
    }

    /** Extracts a rotation quaternion (xyzw) from a column-major, shear-free matrix. */
    fun quatFromMatrix(m: FloatArray, out: FloatArray, at: Int) {
        val tr = m[0] + m[5] + m[10]
        if (tr > 0f) {
            val s = sqrt(tr + 1f) * 2f
            out[at + 3] = 0.25f * s
            out[at] = (m[6] - m[9]) / s
            out[at + 1] = (m[8] - m[2]) / s
            out[at + 2] = (m[4] - m[1]) / s
        } else if (m[0] > m[5] && m[0] > m[10]) {
            val s = sqrt(1f + m[0] - m[5] - m[10]) * 2f
            out[at + 3] = (m[6] - m[9]) / s
            out[at] = 0.25f * s
            out[at + 1] = (m[4] + m[1]) / s
            out[at + 2] = (m[8] + m[2]) / s
        } else if (m[5] > m[10]) {
            val s = sqrt(1f + m[5] - m[0] - m[10]) * 2f
            out[at + 3] = (m[8] - m[2]) / s
            out[at] = (m[4] + m[1]) / s
            out[at + 1] = 0.25f * s
            out[at + 2] = (m[9] + m[6]) / s
        } else {
            val s = sqrt(1f + m[10] - m[0] - m[5]) * 2f
            out[at + 3] = (m[4] - m[1]) / s
            out[at] = (m[8] + m[2]) / s
            out[at + 1] = (m[9] + m[6]) / s
            out[at + 2] = 0.25f * s
        }
    }

    /** Transforms a point (w=1), writing into out[0..2]. */
    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = m[0] * x + m[4] * y + m[8] * z + m[12]
        out[1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        out[2] = m[2] * x + m[6] * y + m[10] * z + m[14]
    }

    /** Transforms a direction (w=0) and normalizes; zero vectors pass through as (0,0,0). */
    fun transformDirection(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        val nx = m[0] * x + m[4] * y + m[8] * z
        val ny = m[1] * x + m[5] * y + m[9] * z
        val nz = m[2] * x + m[6] * y + m[10] * z
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-9f) {
            out[0] = 0f; out[1] = 0f; out[2] = 0f
        } else {
            out[0] = nx / len; out[1] = ny / len; out[2] = nz / len
        }
    }

    private val tmp = FloatArray(16)
}
