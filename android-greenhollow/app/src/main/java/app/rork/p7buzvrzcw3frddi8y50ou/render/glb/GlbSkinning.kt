package app.rork.p7buzvrzcw3frddi8y50ou.render.glb

/**
 * Skeleton posing and linear-blend skinning for GLB actors: a clip's per-node local TRS
 * (from [GlbAnimator]) becomes joint world matrices, then skinning matrices, then skinned
 * engine-layout triangles the renderer uploads into a dynamic VBO. Pure Kotlin, testable
 * on the JVM.
 */
object GlbSkeleton {

    /**
     * Composes joint world matrices from sampled local TRS. Joints not driven by the clip
     * keep their rest pose. Returns one FloatArray(16) per joint, in the skin's joint order.
     */
    fun jointWorlds(model: GlbModel, locals: FloatArray): List<FloatArray> {
        val skin = model.skin ?: return emptyList()
        val worlds = ArrayList<FloatArray>(skin.joints.size)
        for ((j, nodeIndex) in skin.joints.withIndex()) {
            val local = FloatArray(16)
            localFrom(locals, nodeIndex, skin, j, local)
            val parentJoint = skin.parents[j]
            val world = FloatArray(16)
            if (parentJoint >= 0) {
                Mat4.mul(world, worlds[parentJoint], local)
            } else {
                System.arraycopy(local, 0, world, 0, 16)
            }
            worlds.add(world)
        }
        return worlds
    }

    /** A joint's local matrix: the clip's TRS when it drives the joint, else its rest pose. */
    private fun localFrom(locals: FloatArray, nodeIndex: Int, skin: GlbModel.Skin, joint: Int, out: FloatArray) {
        val base = nodeIndex * 10
        val driven = base + 9 < locals.size
        Mat4.identity(out)
        if (driven) {
            val tx = locals[base]; val ty = locals[base + 1]; val tz = locals[base + 2]
            val qx = locals[base + 3]; val qy = locals[base + 4]; val qz = locals[base + 5]; val qw = locals[base + 6]
            val sx = locals[base + 7]; val sy = locals[base + 8]; val sz = locals[base + 9]
            val rot = FloatArray(16)
            Mat4.quatToMatrix(floatArrayOf(qx, qy, qz, qw), rot)
            out[0] = rot[0] * sx; out[1] = rot[1] * sx; out[2] = rot[2] * sx
            out[4] = rot[4] * sy; out[5] = rot[5] * sy; out[6] = rot[6] * sy
            out[8] = rot[8] * sz; out[9] = rot[9] * sz; out[10] = rot[10] * sz
            out[12] = tx; out[13] = ty; out[14] = tz
        } else {
            val rest = skin.restLocal[joint]
            System.arraycopy(rest, 0, out, 0, 16)
        }
    }

    /** Skinning matrices: joint world × inverse bind. */
    fun skinningMatrices(model: GlbModel, jointWorlds: List<FloatArray>): List<FloatArray> {
        val skin = model.skin ?: return emptyList()
        return skin.joints.mapIndexed { j, _ ->
            val out = FloatArray(16)
            Mat4.mul(out, jointWorlds[j], skin.inverseBind[j])
            out
        }
    }

    /** The largest vertex count the renderer will skin per actor per frame; beyond it a GLB actor draws its bind pose. */
    const val SKIN_VERTEX_BUDGET = 20000

    /**
     * Skins one converted mesh with the given matrices into [out] at [offset], engine layout
     * (position 3, normal 3, colour 3). Returns the number of floats written. When the mesh
     * carries no joints, or exceeds the budget, it writes the bind pose unchanged.
     */
    fun skin(mesh: GlbModel.Mesh, matrices: List<FloatArray>, out: FloatArray, offset: Int): Int {
        val joints = mesh.joints
        val weights = mesh.weights
        if (joints == null || weights == null || matrices.isEmpty() || mesh.vertexCount > SKIN_VERTEX_BUDGET) {
            val floats = mesh.vertexCount * 9
            if (offset + floats > out.size) return 0
            System.arraycopy(mesh.vertices, 0, out, offset, floats)
            return floats
        }
        val point = FloatArray(3)
        val accP = FloatArray(3)
        val accN = FloatArray(3)
        val tmp = FloatArray(3)
        var s = offset
        var written = 0
        for (v in 0 until mesh.vertexCount) {
            val o = v * 9
            accP[0] = 0f; accP[1] = 0f; accP[2] = 0f
            accN[0] = 0f; accN[1] = 0f; accN[2] = 0f
            var unbound = true
            // Full linear-blend skinning over the vertex's four influences.
            for (k in 0 until 4) {
                val j = joints[v * 4 + k]
                val w = weights[v * 4 + k]
                if (w <= 0f || j !in matrices.indices) continue
                unbound = false
                val m = matrices[j]
                Mat4.transformPoint(m, mesh.vertices[o], mesh.vertices[o + 1], mesh.vertices[o + 2], point)
                accP[0] += point[0] * w; accP[1] += point[1] * w; accP[2] += point[2] * w
                Mat4.transformDirection(m, mesh.vertices[o + 3], mesh.vertices[o + 4], mesh.vertices[o + 5], tmp)
                accN[0] += tmp[0] * w; accN[1] += tmp[1] * w; accN[2] += tmp[2] * w
            }
            if (unbound) {
                accP[0] = mesh.vertices[o]; accP[1] = mesh.vertices[o + 1]; accP[2] = mesh.vertices[o + 2]
                accN[0] = mesh.vertices[o + 3]; accN[1] = mesh.vertices[o + 4]; accN[2] = mesh.vertices[o + 5]
            }
            if (offset + written + 9 <= out.size) {
                out[s] = accP[0]; out[s + 1] = accP[1]; out[s + 2] = accP[2]
                out[s + 3] = accN[0]; out[s + 4] = accN[1]; out[s + 5] = accN[2]
                out[s + 6] = mesh.vertices[o + 6]; out[s + 7] = mesh.vertices[o + 7]; out[s + 8] = mesh.vertices[o + 8]
                s += 9
                written += 9
            }
        }
        return written
    }
}
