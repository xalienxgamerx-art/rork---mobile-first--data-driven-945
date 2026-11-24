package app.rork.p7buzvrzcw3frddi8y50ou

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds real glTF 2.0 Binary files in test code: a valid container (header, JSON chunk,
 * BIN chunk) around a document the test composes, with accessors and buffer views allocated
 * into the binary chunk as it goes. Tests describe scenes, not byte plumbing.
 */
object TestGlb {

    /** Wraps a glTF JSON document and its binary scratch into .glb bytes. */
    fun glb(json: String, bin: ByteArray = ByteArray(0)): ByteArray {
        // The spec pads the JSON chunk with spaces and the BIN chunk with zeros.
        val j = pad4(json.toByteArray(Charsets.UTF_8), ' '.code.toByte())
        val b = pad4(bin)
        val total = 12 + 8 + j.size + 8 + b.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67) // 'glTF'
        out.putInt(2)
        out.putInt(total)
        out.putInt(j.size)
        out.putInt(0x4E4F534A) // 'JSON'
        out.put(j)
        out.putInt(b.size)
        out.putInt(0x004E4942) // 'BIN'
        out.put(b)
        return out.array()
    }

    private fun pad4(b: ByteArray, fill: Byte = 0): ByteArray {
        val p = (4 - b.size % 4) % 4
        return b + ByteArray(p) { fill }
    }

    fun floats(vararg f: Float): ByteArray =
        ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN).let {
            f.forEach(it::putFloat); it.array()
        }

    fun shorts(vararg s: Int): ByteArray =
        ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN).let {
            s.forEach { v -> it.putShort(v.toShort()) }; it.array()
        }

    fun bytes(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

    /**
     * A scene under composition: buffer views and accessors are appended to the binary
     * chunk in order; the test supplies the rest of the document (meshes, nodes, …) as
     * JSON fragments and takes back finished .glb bytes.
     */
    class Builder {
        private val binBuf = ByteArrayOutputStream()
        private val viewsJson = ArrayList<String>()
        private val accessorsJson = ArrayList<String>()
        private var offset = 0

        /** Appends raw bytes as a buffer view (4-byte aligned, as the spec requires). */
        fun view(data: ByteArray, stride: Int = 0): Int {
            while (offset % 4 != 0) {
                binBuf.write(0); offset++
            }
            val at = offset
            binBuf.write(data, 0, data.size)
            offset += data.size
            viewsJson += buildString {
                append("""{"buffer":0,"byteOffset":""").append(at)
                append(""","byteLength":""").append(data.size)
                if (stride > 0) append(""","byteStride":""").append(stride)
                append("}")
            }
            return viewsJson.size - 1
        }

        fun accessor(
            view: Int,
            count: Int,
            type: String,
            componentType: Int,
            normalized: Boolean = false,
            byteOffset: Int = 0,
        ): Int {
            accessorsJson += buildString {
                append("""{"bufferView":""").append(view)
                append(""","byteOffset":""").append(byteOffset)
                append(""","componentType":""").append(componentType)
                append(""","count":""").append(count)
                append(""","type":""").append("\"$type\"")
                if (normalized) append(""","normalized":true""")
                append("}")
            }
            return accessorsJson.size - 1
        }

        /** Positions accessor (VEC3 float) over [xyz] triples. */
        fun positions(vararg xyz: Float): Int = accessor(view(floats(*xyz)), xyz.size / 3, "VEC3", 5126)

        /** Indices accessor (ushort). */
        fun indices(vararg idx: Int): Int = accessor(view(shorts(*idx)), idx.size, "SCALAR", 5123)

        /** JOINTS_0 accessor (ubyte vec4). */
        fun joints(vararg j: Int): Int = accessor(view(bytes(*j)), j.size / 4, "VEC4", 5121)

        /** WEIGHTS_0 accessor (float vec4). */
        fun weights(vararg w: Float): Int = accessor(view(floats(*w)), w.size / 4, "VEC4", 5126)

        /** Inverse bind matrices accessor (MAT4 float), column-major. */
        fun inverseBinds(vararg m: Float): Int = accessor(view(floats(*m)), m.size / 16, "MAT4", 5126)

        /** Finishes the document; [extras] carries meshes, nodes, skins, animations, materials. */
        fun build(
            meshes: String,
            nodes: String,
            extras: String = "",
            sceneNodes: String = "[0]",
            generator: String = "TestGlb",
        ): ByteArray {
            val json = buildString {
                append("""{"asset":{"version":"2.0","generator":"$generator"},"scene":0,"scenes":[{"nodes":""")
                append(sceneNodes)
                append("""}]""")
                append(",")
                if (nodes.isNotEmpty()) append(""""nodes":[""").append(nodes).append("],")
                if (meshes.isNotEmpty()) append(""""meshes":[""").append(meshes).append("],")
                if (accessorsJson.isNotEmpty()) {
                    append(""""bufferViews":[""").append(viewsJson.joinToString()).append("],")
                    append(""""accessors":[""").append(accessorsJson.joinToString()).append("],")
                }
                append(""""buffers":[{"byteLength":""").append(binBuf.size()).append("}]")
                if (extras.isNotEmpty()) {
                    append(",")
                    append(extras)
                }
                append("}")
            }
            return glb(json, binBuf.toByteArray())
        }
    }
}
