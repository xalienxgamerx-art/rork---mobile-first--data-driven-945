package app.rork.p7buzvrzcw3frddi8y50ou.render.glb

import kotlinx.serialization.json.Json

/**
 * The glTF 2.0 binary container: a twelve-byte header, then a JSON chunk holding the
 * document and an optional binary chunk holding the buffers. This is the format's whole
 * grammar, checked strictly — a broken asset fails here with a plain-language reason,
 * never later as a mystery.
 */
object GlbSource {
    private const val MAGIC = 0x46546C67 // 'glTF'
    private const val VERSION = 2
    private const val CHUNK_JSON = 0x4E4F534A // 'JSON'
    private const val CHUNK_BIN = 0x004E4942 // 'BIN'

    private val json = Json { ignoreUnknownKeys = true }

    /** A parsed container: the document and the binary chunk's bytes. */
    class Parsed(val doc: GltfDocument, val bin: ByteArray)

    /** Parses GLB bytes; raises [GlbFormatException] with the reason when anything is off. */
    fun parse(bytes: ByteArray): Parsed {
        if (bytes.size < 12) {
            throw GlbFormatException("The file is ${bytes.size} bytes — too short to be a glTF binary at all.")
        }
        val magic = u32(bytes, 0)
        if (magic != MAGIC) {
            throw GlbFormatException("The file does not begin with the glTF binary signature — it is not a .glb file.")
        }
        val version = u32(bytes, 4)
        if (version != VERSION) {
            throw GlbFormatException("The asset declares glTF version $version; the game reads version 2 only.")
        }
        val totalLength = u32(bytes, 8)
        if (totalLength.toLong() != bytes.size.toLong()) {
            if (totalLength > bytes.size) {
                throw GlbFormatException("The asset declares $totalLength bytes but only ${bytes.size} arrived — the file is truncated.")
            }
            // Trailing padding after the declared length is tolerated; the chunks inside
            // the declared length are what the format vouches for.
        }
        var p = 12
        var doc: GltfDocument? = null
        var bin: ByteArray? = null
        while (p + 8 <= totalLength) {
            val chunkLength = u32(bytes, p)
            val chunkType = u32(bytes, p + 4)
            p += 8
            if (p + chunkLength > totalLength) {
                throw GlbFormatException("A chunk of $chunkLength bytes runs past the end of the asset.")
            }
            when (chunkType) {
                CHUNK_JSON -> {
                    val text = bytes.decodeToString(p, p + chunkLength)
                    doc = try {
                        json.decodeFromString(GltfDocument.serializer(), text)
                    } catch (e: Exception) {
                        throw GlbFormatException("The asset's scene description could not be read: ${e.message}")
                    }
                }
                CHUNK_BIN -> bin = bytes.copyOfRange(p, p + chunkLength)
                else -> {} // Unknown chunks are legal and skipped, as the spec allows.
            }
            p += chunkLength
        }
        val document = doc ?: throw GlbFormatException("The asset carries no JSON chunk — there is no scene description in it.")
        if (document.buffers.size > 1) {
            throw GlbFormatException("The asset declares ${document.buffers.size} buffers; a glTF binary carries at most one.")
        }
        if (document.buffers.isNotEmpty() && bin == null) {
            throw GlbFormatException("The asset declares a buffer of ${document.buffers[0].byteLength} bytes but carries no binary chunk.")
        }
        return Parsed(document, bin ?: ByteArray(0))
    }

    private fun u32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
}
