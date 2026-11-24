package app.rork.p7buzvrzcw3frddi8y50ou.render.glb

/**
 * A GLB asset turned into the shapes this game's renderer consumes. The engine draws
 * vertex-coloured triangles (position, normal, colour — the [app.rork.p7buzvrzcw3frddi8y50ou.render.Meshes]
 * stride of 9 floats), so conversion bakes each material's resolved base colour into the
 * vertices and keeps everything else — nodes, skins, clips, morph targets, embedded
 * images — as structured data the renderer and registry read.
 *
 * Nothing here reaches into Android: a [GlbModel] is built from parsed bytes anywhere,
 * and the JVM tests exercise every path.
 */
class GlbModel private constructor(
    /** The asset's declared generator, for the registry's report. */
    val generator: String,
    /** Meshes after conversion, in scene order. */
    val meshes: List<Mesh>,
    /** Scene-graph nodes with their local transforms resolved. */
    val nodes: List<Node>,
    /** Each node's parent's index, or -1 for a root — attachment walks and skins read it. */
    val parents: IntArray,
    /** Root node indices of the default scene. */
    val roots: List<Int>,
    /** The skin, when the asset carries one (this engine renders one skin per asset). */
    val skin: Skin?,
    /** Named animation clips, ready to sample. */
    val clips: List<Clip>,
    /** Embedded images, kept for the texture hook (the engine shades vertex colours). */
    val images: List<ImageData>,
    /** Attachment points: named, mesh-less nodes a piece of equipment may hang from. */
    val attachments: Map<String, Int>,
    /** Total triangle count across all meshes. */
    val triangleCount: Int,
) {
    /** One converted mesh: a triangle strip in engine layout, one material per mesh. */
    class Mesh(
        val name: String,
        /** position(3) normal(3) colour(3) per vertex. */
        val vertices: FloatArray,
        val vertexCount: Int,
        val material: Material,
        /** Morph target deltas in engine layout, aligned to [vertices] (empty when none). */
        val morphTargets: List<FloatArray>,
        /** Per-vertex joint indices (into the skin's joint list) and weights, four of each per vertex, when skinned. */
        val joints: IntArray?,
        val weights: FloatArray?,
    )

    /** A material resolved the way this engine shades: one base colour per surface. */
    class Material(
        val name: String,
        val r: Float, val g: Float, val b: Float, val a: Float,
        val metallic: Float,
        val roughness: Float,
        /** Index into [images] when the material textures its base colour, else -1. */
        val baseColorImage: Int,
    )

    class Node(
        val index: Int,
        val name: String,
        val children: List<Int>,
        val mesh: Int,
        val skin: Int,
        /** Local transform, column-major. */
        val local: FloatArray,
    )

    /** A joint hierarchy: the nodes that bend, their parents, and the mesh's rest pose. */
    class Skin(
        val name: String,
        /** Node indices of the joints, in the order animations address them. */
        val joints: List<Int>,
        /** For each joint, its parent's index into [joints] or -1 for a root. */
        val parents: List<Int>,
        /** Inverse bind matrices, one per joint, column-major. */
        val inverseBind: List<FloatArray>,
        /** Each joint's rest-pose local transform (used when a clip does not drive it). */
        val restLocal: List<FloatArray>,
    )

    class ImageData(val name: String, val mimeType: String, val bytes: ByteArray)

    // ------------------------------------------------------------ static conversion

    /**
     * Bakes the whole default scene into engine-layout triangles: node transforms applied,
     * materials baked into vertex colours, morph weights applied. This is the shape the
     * world batch and the actor VBOs consume.
     */
    fun bakeStatic(morphWeights: List<Float> = emptyList()): FloatArray {
        val sink = FloatSink(triangleCount * 3 * 9)
        for (root in roots) bakeNode(root, Mat4.identity(), morphWeights, sink)
        return sink.trimmed()
    }

    private fun bakeNode(nodeIndex: Int, parent: FloatArray, morphWeights: List<Float>, sink: FloatSink) {
        val node = nodes[nodeIndex]
        val world = FloatArray(16)
        Mat4.mul(world, parent, node.local)
        node.mesh.takeIf { it >= 0 }?.let { m -> meshes.getOrNull(m)?.let { sink.add(it, world, morphWeights) } }
        for (child in node.children) bakeNode(child, world, morphWeights, sink)
    }

    /**
     * The world transform of a named attachment point, or null when no node bears the
     * name: the matrix a piece of equipment hung on that node should carry.
     */
    fun attachmentTransform(name: String): FloatArray? {
        val idx = attachments[name] ?: return null
        var m = Mat4.identity()
        val path = mutableListOf<Int>()
        var cur = idx
        while (cur >= 0) {
            path.add(0, cur)
            cur = parents.getOrNull(cur) ?: -1
        }
        for (step in path) {
            val next = FloatArray(16)
            Mat4.mul(next, m, nodes[step].local)
            m = next
        }
        return m
    }

    /** Whether the asset carries a skin and can therefore be posed. */
    val isSkinned: Boolean get() = skin != null

    /** The named clip, or null — callers fall back to whatever the asset does carry. */
    fun clip(name: String): Clip? = clips.firstOrNull { it.name.equals(name, ignoreCase = true) }

    // ------------------------------------------------------------ build internals

    /** Accumulates engine-layout vertices; grows once, copied out once. */
    class FloatSink(capacity: Int) {
        var data = FloatArray(maxOf(capacity, 64))
        var size = 0
            private set

        fun add(mesh: Mesh, world: FloatArray, morphWeights: List<Float>) {
            val needed = size + mesh.vertexCount * 9
            if (needed > data.size) data = data.copyOf(maxOf(needed, data.size * 2))
            val point = FloatArray(3)
            val normal = FloatArray(3)
            var s = size
            for (v in 0 until mesh.vertexCount) {
                val o = v * 9
                var px = mesh.vertices[o]; var py = mesh.vertices[o + 1]; var pz = mesh.vertices[o + 2]
                var nx = mesh.vertices[o + 3]; var ny = mesh.vertices[o + 4]; var nz = mesh.vertices[o + 5]
                for ((t, weight) in morphWeights.withIndex()) {
                    if (weight == 0f || t >= mesh.morphTargets.size) continue
                    val target = mesh.morphTargets[t]
                    px += target[o] * weight; py += target[o + 1] * weight; pz += target[o + 2] * weight
                    nx += target[o + 3] * weight; ny += target[o + 4] * weight; nz += target[o + 5] * weight
                }
                Mat4.transformPoint(world, px, py, pz, point)
                Mat4.transformDirection(world, nx, ny, nz, normal)
                data[s++] = point[0]; data[s++] = point[1]; data[s++] = point[2]
                data[s++] = normal[0]; data[s++] = normal[1]; data[s++] = normal[2]
                data[s++] = mesh.vertices[o + 6]; data[s++] = mesh.vertices[o + 7]; data[s++] = mesh.vertices[o + 8]
            }
            size = s
        }

        fun trimmed(): FloatArray = data.copyOf(size)
    }

    private class Builder(private val doc: GltfDocument, private val bin: ByteArray) {
        val reader = AccessorReader(doc, bin)

        fun build(): GlbModel {
            val materials = doc.materials.map { m ->
                val c = m.pbrMetallicRoughness.baseColorFactor
                Material(
                    name = m.name,
                    r = c.getOrElse(0) { 1f },
                    g = c.getOrElse(1) { 1f },
                    b = c.getOrElse(2) { 1f },
                    a = c.getOrElse(3) { 1f },
                    metallic = m.pbrMetallicRoughness.metallicFactor,
                    roughness = m.pbrMetallicRoughness.roughnessFactor,
                    baseColorImage = m.pbrMetallicRoughness.baseColorTexture
                        ?.let { doc.textures.getOrNull(it.index)?.source } ?: -1,
                )
            }
            fun materialAt(index: Int): Material =
                materials.getOrNull(index) ?: Material("", 0.8f, 0.8f, 0.8f, 1f, 0f, 1f, -1)

            val meshes = doc.meshes.map { convertMesh(it, ::materialAt) }

            val nodes = doc.nodes.mapIndexed { i, n ->
                Node(
                    index = i,
                    name = n.name,
                    children = n.children,
                    mesh = n.mesh,
                    skin = n.skin,
                    local = Mat4.trs(n, FloatArray(16)).copyOf(),
                )
            }
            // A node's parent, by node index (-1 for roots) — attachment walks use it.
            val parents = IntArray(doc.nodes.size) { -1 }
            nodes.forEach { n -> n.children.forEach { c -> if (c in parents.indices) parents[c] = n.index } }

            val scene = doc.scenes.getOrNull(doc.scene) ?: doc.scenes.firstOrNull() ?: GltfScene()
            val roots = scene.nodes

            val attachments = HashMap<String, Int>()
            nodes.forEach { n -> if (n.mesh < 0 && n.name.isNotEmpty()) attachments[n.name] = n.index }

            val skin = doc.skins.firstOrNull()?.let { s ->
                val joints = s.joints
                val jointParents = joints.map { j -> parents.getOrElse(j) { -1 }.let { p -> joints.indexOf(p).takeIf { it >= 0 } ?: -1 } }
                val inverseBind = if (s.inverseBindMatrices >= 0) {
                    val raw = reader.read(s.inverseBindMatrices)
                    joints.map { j ->
                        FloatArray(16).also { out ->
                            for (i in 0 until 16) out[i] = raw.getOrElse(j * 16 + i) { if (i % 5 == 0) 1f else 0f }
                        }
                    }
                } else {
                    joints.map { Mat4.identity() }
                }
                Skin(s.name, joints, jointParents, inverseBind, joints.map { nodes[it].local.copyOf() })
            }

            val clips = doc.animations.map { Clip.from(it, doc, reader, skin) }

            val images = doc.images.map { img ->
                val bytes = if (img.bufferView >= 0 && img.bufferView < doc.bufferViews.size) {
                    val view = doc.bufferViews[img.bufferView]
                    bin.copyOfRange(view.byteOffset, view.byteOffset + view.byteLength)
                } else {
                    ByteArray(0)
                }
                ImageData(img.name, img.mimeType, bytes)
            }

            val triangleCount = meshes.sumOf { it.vertexCount / 3 }
            return GlbModel(
                generator = doc.asset.generator,
                meshes = meshes,
                nodes = nodes,
                parents = parents,
                roots = roots,
                skin = skin,
                clips = clips,
                images = images,
                attachments = attachments,
                triangleCount = triangleCount,
            )
        }

        private fun convertMesh(mesh: GltfMesh, materialAt: (Int) -> Material): Mesh {
            var vertexCount = 0
            val converted = ArrayList<FloatArray>()
            val expandedJoints = ArrayList<Int>()
            val expandedWeights = ArrayList<Float>()
            var usedMaterial = -1
            var carryJoints = false
            for (prim in mesh.primitives) {
                if (prim.mode != 4) continue // this engine draws triangles only
                val posIndex = prim.attributes["POSITION"] ?: throw GlbFormatException(
                    "The mesh '${mesh.name}' has a triangle set with no POSITION attribute — there is nothing to draw."
                )
                val positions = reader.read(posIndex)
                val normals = prim.attributes["NORMAL"]?.let { reader.read(it) }
                val jointsRaw = prim.attributes["JOINTS_0"]?.let { reader.read(it) }
                val weightsRaw = prim.attributes["WEIGHTS_0"]?.let { reader.read(it) }
                if (jointsRaw != null && weightsRaw != null) carryJoints = true
                val indices = if (prim.indices >= 0) reader.readIndices(prim.indices) else null
                val count = indices?.size ?: (positions.size / 3)
                val mat = materialAt(prim.material)
                if (usedMaterial < 0) usedMaterial = prim.material
                val primVertices = FloatArray(count * 9)
                for (i in 0 until count) {
                    val src = (indices?.get(i) ?: i) * 3
                    primVertices[i * 9] = positions.getOrElse(src) { 0f }
                    primVertices[i * 9 + 1] = positions.getOrElse(src + 1) { 0f }
                    primVertices[i * 9 + 2] = positions.getOrElse(src + 2) { 0f }
                    if (normals != null && src + 2 < normals.size) {
                        primVertices[i * 9 + 3] = normals[src]
                        primVertices[i * 9 + 4] = normals[src + 1]
                        primVertices[i * 9 + 5] = normals[src + 2]
                    } else {
                        primVertices[i * 9 + 3] = 0f; primVertices[i * 9 + 4] = 1f; primVertices[i * 9 + 5] = 0f
                    }
                    primVertices[i * 9 + 6] = mat.r
                    primVertices[i * 9 + 7] = mat.g
                    primVertices[i * 9 + 8] = mat.b
                    if (jointsRaw != null && weightsRaw != null) {
                        val v = src / 3
                        for (k in 0 until 4) {
                            expandedJoints.add(jointsRaw.getOrElse(v * 4 + k) { 0f }.toInt())
                            expandedWeights.add(weightsRaw.getOrElse(v * 4 + k) { 0f })
                        }
                    }
                }
                converted.add(primVertices)
                vertexCount += count
            }
            val all = FloatArray(vertexCount * 9)
            var o = 0
            for (c in converted) {
                System.arraycopy(c, 0, all, o, c.size)
                o += c.size
            }
            val morphs = mesh.primitives.firstOrNull()?.targets?.map { target ->
                val posDelta = target["POSITION"]?.let { reader.read(it) } ?: FloatArray(vertexCount * 3)
                val normDelta = target["NORMAL"]?.let { reader.read(it) } ?: FloatArray(vertexCount * 3)
                FloatArray(vertexCount * 9).also { out ->
                    for (v in 0 until vertexCount) {
                        for (k in 0 until 3) {
                            out[v * 9 + k] = posDelta.getOrElse(v * 3 + k) { 0f }
                            out[v * 9 + 3 + k] = normDelta.getOrElse(v * 3 + k) { 0f }
                        }
                    }
                }
            } ?: emptyList()
            // Joints and weights ride the expanded triangle order, taken from the original
            // vertex each emitted corner came from.
            val joints = if (carryJoints) expandedJoints.toIntArray() else null
            val weights = if (carryJoints) expandedWeights.toFloatArray() else null
            return Mesh(mesh.name, all, vertexCount, materialAt(usedMaterial), morphs, joints, weights)
        }
    }

    companion object {
        /** Builds a model from GLB bytes; raises [GlbFormatException] with the reason. */
        fun from(bytes: ByteArray): GlbModel {
            val parsed = GlbSource.parse(bytes)
            return Builder(parsed.doc, parsed.bin).build()
        }
    }
}
