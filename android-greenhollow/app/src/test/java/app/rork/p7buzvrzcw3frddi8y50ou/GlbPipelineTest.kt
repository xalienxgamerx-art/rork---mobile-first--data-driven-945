package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.GlbModelDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.NpcDef
import app.rork.p7buzvrzcw3frddi8y50ou.render.BuildingModels
import app.rork.p7buzvrzcw3frddi8y50ou.render.glb.AccessorReader
import app.rork.p7buzvrzcw3frddi8y50ou.render.glb.GlbModel
import app.rork.p7buzvrzcw3frddi8y50ou.render.glb.GlbRegistry
import app.rork.p7buzvrzcw3frddi8y50ou.render.glb.GlbSkeleton
import app.rork.p7buzvrzcw3frddi8y50ou.render.glb.GlbAnimator
import app.rork.p7buzvrzcw3frddi8y50ou.render.glb.GlbFormatException
import app.rork.p7buzvrzcw3frddi8y50ou.render.glb.GlbSource
import app.rork.p7buzvrzcw3frddi8y50ou.render.glb.Mat4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The GLB pipeline, end to end, on real constructed .glb binaries: container strictness,
 * accessor decoding, static and skinned conversion, animation sampling and blending, the
 * registry's caching and failure bookkeeping, the content validation pathway, and the
 * building bake that consumes a declared model — with its fallback.
 */
class GlbPipelineTest {

    @Before fun freshRegistry() = GlbRegistry.reset()

    // ---------------------------------------------------------------- container

    @Test fun tooShortIsRejected() {
        val e = assertFails<GlbFormatException> { GlbSource.parse(ByteArray(5)) }
        assertTrue(e.message!!.contains("too short"))
    }

    @Test fun badMagicIsRejected() {
        val bytes = TestGlb.glb("""{"asset":{"version":"2.0"}}""")
        bytes[0] = 'X'.code.toByte()
        val e = assertFails<GlbFormatException> { GlbSource.parse(bytes) }
        assertTrue(e.message!!.contains("not a .glb"))
    }

    @Test fun wrongVersionIsRejected() {
        val bytes = TestGlb.glb("""{"asset":{"version":"2.0"}}""")
        // Overwrite the version field with 3.
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4, 3)
        val e = assertFails<GlbFormatException> { GlbSource.parse(bytes) }
        assertTrue(e.message!!.contains("version 3"))
    }

    @Test fun truncatedAssetIsRejected() {
        val bytes = TestGlb.glb("""{"asset":{"version":"2.0"}}""")
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(8, bytes.size + 100)
        val e = assertFails<GlbFormatException> { GlbSource.parse(bytes) }
        assertTrue(e.message!!.contains("truncated"))
    }

    @Test fun missingJsonChunkIsRejected() {
        // A valid header followed only by a binary chunk.
        val bin = TestGlb.floats(0f, 0f, 0f)
        val out = java.nio.ByteBuffer.allocate(12 + 8 + bin.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67); out.putInt(2); out.putInt(12 + 8 + bin.size)
        out.putInt(bin.size); out.putInt(0x004E4942); out.put(bin)
        val e = assertFails<GlbFormatException> { GlbSource.parse(out.array()) }
        assertTrue(e.message!!.contains("no JSON chunk"))
    }

    // ---------------------------------------------------------------- conversion

    /** One red triangle at the origin, unindexed, with no normals. */
    private fun triangleGlb(
        nodes: String = """{"name":"body","mesh":0}""",
        extras: String = MATERIALS_RED,
    ): ByteArray = TestGlb.Builder().apply {
        positions(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
    }.build(
        meshes = """{"name":"tri","primitives":[{"attributes":{"POSITION":0},"material":0}]}""",
        nodes = nodes,
        extras = extras,
    )

    @Test fun minimalTriangleLoads() {
        val model = GlbModel.from(triangleGlb())
        assertEquals(1, model.meshes.size)
        assertEquals(3, model.meshes[0].vertexCount)
        assertEquals(1, model.triangleCount)
        val v = model.meshes[0].vertices
        assertEquals(0f, v[0], 1e-6f)
        // A mesh without normals draws anyway: they fall back to straight up.
        assertEquals(0f, v[3], 1e-6f)
        assertEquals(1f, v[4], 1e-6f)
        // The material's base colour is baked into every vertex.
        assertEquals(1f, v[6], 1e-6f)
        assertEquals(0f, v[7], 1e-6f)
        assertEquals(0f, v[8], 1e-6f)
        assertFalse(model.isSkinned)
    }

    @Test fun indexedTriangleExpandsInOrder() {
        val bytes = TestGlb.Builder().apply {
            positions(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
            indices(2, 1, 0)
        }.build(
            meshes = """{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}""",
            nodes = """{"mesh":0}""",
        )
        val model = GlbModel.from(bytes)
        assertEquals(3, model.meshes[0].vertexCount)
        val v = model.meshes[0].vertices
        // Corners come out in index order: first the (0,1,0) vertex.
        assertEquals(0f, v[0], 1e-6f)
        assertEquals(1f, v[1], 1e-6f)
    }

    @Test fun normalizedBytesDecode() {
        val bytes = TestGlb.Builder().apply {
            accessor(view(TestGlb.bytes(0, 128, 255)), 1, "VEC3", 5121, normalized = true)
        }.build(meshes = "", nodes = "")
        val parsed = GlbSource.parse(bytes)
        val read = AccessorReader(parsed.doc, parsed.bin).read(0)
        assertEquals(0f, read[0], 1e-6f)
        assertEquals(128f / 255f, read[1], 1e-4f)
        assertEquals(1f, read[2], 1e-6f)
    }

    @Test fun bufferViewStrideIsHonoured() {
        // Three positions interleaved with a junk float each (stride 16 bytes).
        val bytes = TestGlb.Builder().apply {
            val interleaved = TestGlb.floats(
                1f, 2f, 3f, 99f,
                4f, 5f, 6f, 99f,
                7f, 8f, 9f, 99f,
            )
            accessor(view(interleaved, stride = 16), 3, "VEC3", 5126)
        }.build(meshes = "", nodes = "")
        val parsed = GlbSource.parse(bytes)
        val read = AccessorReader(parsed.doc, parsed.bin).read(0)
        assertEquals(7f, read[6], 1e-6f)
        assertEquals(9f, read[8], 1e-6f)
    }

    @Test fun nodeTransformBakesIntoVertices() {
        // 90° about Z, scaled 2, moved to (2,3,4): vertex (1,0,0) lands on (2,5,4).
        val bytes = TestGlb.Builder().apply {
            positions(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }.build(
            meshes = """{"primitives":[{"attributes":{"POSITION":0}}]}""",
            nodes = """{"name":"t","mesh":0,"translation":[2,3,4],"rotation":[0,0,${S45},${C45}],"scale":[2,2,2]}""",
        )
        val model = GlbModel.from(bytes)
        val v = model.bakeStatic()
        assertEquals(2f, v[0], 1e-4f)
        assertEquals(5f, v[1], 1e-4f)
        assertEquals(4f, v[2], 1e-4f)
        // Vertex (0,1,0) -> scaled (0,2,0) -> rotated (-2,0,0) -> translated (0,3,4).
        assertEquals(0f, v[9], 1e-4f)
        assertEquals(3f, v[10], 1e-4f)
    }

    @Test fun multiPrimitiveMaterialsBake() {
        val bytes = TestGlb.Builder().apply {
            positions(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
            positions(2f, 0f, 0f, 3f, 0f, 0f, 2f, 1f, 0f)
        }.build(
            meshes = """{"primitives":[{"attributes":{"POSITION":0},"material":0},{"attributes":{"POSITION":1},"material":1}]}""",
            nodes = """{"mesh":0}""",
            extras = """"materials":[{"name":"red","pbrMetallicRoughness":{"baseColorFactor":[1,0,0,1]}},{"name":"blue","pbrMetallicRoughness":{"baseColorFactor":[0,0,1,1]}}]""",
        )
        val model = GlbModel.from(bytes)
        assertEquals(6, model.meshes[0].vertexCount)
        val v = model.meshes[0].vertices
        assertEquals(1f, v[6], 1e-6f)  // red corner: red channel
        assertEquals(0f, v[33], 1e-6f) // blue corner: red channel
        assertEquals(1f, v[35], 1e-6f) // blue corner: blue channel
    }

    @Test fun morphTargetBakes() {
        val bytes = TestGlb.Builder().apply {
            positions(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
            accessor(view(TestGlb.floats(0f, 0f, 2f, 0f, 0f, 2f, 0f, 0f, 2f)), 3, "VEC3", 5126)
        }.build(
            meshes = """{"primitives":[{"attributes":{"POSITION":0},"targets":[{"POSITION":1}]}]}""",
            nodes = """{"mesh":0}""",
        )
        val model = GlbModel.from(bytes)
        val raised = model.bakeStatic(listOf(1f))
        assertEquals(2f, raised[2], 1e-5f)
        assertEquals(2f, raised[2 + 9], 1e-5f)
        val still = model.bakeStatic(listOf(0f))
        assertEquals(0f, still[2], 1e-6f)
    }

    // ---------------------------------------------------------------- skins & animation

    /** A mesh bound to one joint at the origin, with a "swing" clip rotating it 90° over a second. */
    private fun skinnedGlb(): ByteArray = TestGlb.Builder().apply {
        positions(1f, 0f, 0f, 0f, 1f, 0f, -1f, 0f, 0f)
        joints(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        weights(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)
        inverseBinds(*Mat4.identity())
        val times = accessor(view(TestGlb.floats(0f, 1f)), 2, "SCALAR", 5126)
        val quats = accessor(view(TestGlb.floats(0f, 0f, 0f, 1f, 0f, 0f, S45, C45)), 2, "VEC4", 5126)
        animTimes = times
        animQuats = quats
    }.build(
        meshes = """{"name":"body","primitives":[{"attributes":{"POSITION":0,"JOINTS_0":1,"WEIGHTS_0":2},"material":0}]}""",
        nodes = """{"name":"body","mesh":0,"skin":0},{"name":"j0"}""",
        extras = MATERIALS_RED + "," +
            """"skins":[{"name":"s","joints":[1],"inverseBindMatrices":3}],""" +
            """"animations":[{"name":"swing","samplers":[{"input":$animTimes,"output":$animQuats}],"channels":[{"sampler":0,"target":{"node":1,"path":"rotation"}}]}]""",
    )

    private var animTimes = 0
    private var animQuats = 0

    @Test fun skinAtRestKeepsBindPose() {
        val model = GlbModel.from(skinnedGlb())
        assertTrue(model.isSkinned)
        val animator = GlbAnimator(model)
        val locals = animator.sample("swing", 0f) // the swing clip at its first frame
        val worlds = GlbSkeleton.jointWorlds(model, locals)
        val matrices = GlbSkeleton.skinningMatrices(model, worlds)
        val mesh = model.meshes[0]
        val out = FloatArray(mesh.vertexCount * 9)
        val written = GlbSkeleton.skin(mesh, matrices, out, 0)
        assertEquals(mesh.vertexCount * 9, written)
        // An identity rest pose and identity inverse binds reproduce the authored vertices.
        assertEquals(1f, out[0], 1e-5f)
        assertEquals(-1f, out[18], 1e-5f)
    }

    @Test fun rotatedJointMovesVertices() {
        val model = GlbModel.from(skinnedGlb())
        val animator = GlbAnimator(model)
        val locals = animator.sample("swing", 1f, loop = false) // clamped to the clip's end
        val matrices = GlbSkeleton.skinningMatrices(model, GlbSkeleton.jointWorlds(model, locals))
        val mesh = model.meshes[0]
        val out = FloatArray(mesh.vertexCount * 9)
        GlbSkeleton.skin(mesh, matrices, out, 0)
        // (1,0,0) rotated 90° about Z lands on (0,1,0).
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(1f, out[1], 1e-4f)
        // (−1,0,0) lands on (0,−1,0).
        assertEquals(0f, out[18], 1e-4f)
        assertEquals(-1f, out[19], 1e-4f)
        // Colour rides along untouched.
        assertEquals(1f, out[6], 1e-6f)
    }

    @Test fun clipLoopsAndOneShotClamps() {
        val model = GlbModel.from(skinnedGlb())
        val clip = model.clip("swing")!!
        assertEquals(1f, clip.duration, 1e-5f)
        val out = FloatArray(model.nodes.size * 10)
        clip.sample(0.5f, out)
        // The clip drives node 1; its rotation sits at 1*10+3.
        val q = 13
        // Halfway: a 45° rotation about Z moves (1,0,0) to (0.707, 0.707, 0).
        val world = FloatArray(16)
        Mat4.quatToMatrix(floatArrayOf(out[q], out[q + 1], out[q + 2], out[q + 3]), world)
        val p = FloatArray(3)
        Mat4.transformPoint(world, 1f, 0f, 0f, p)
        assertEquals(0.70710677f, p[0], 1e-3f)
        // A looping clip wraps: t = 2.5 samples the same pose as 0.5.
        val wrapped = FloatArray(model.nodes.size * 10)
        clip.sample(2.5f, wrapped)
        for (i in q..q + 3) assertEquals(out[i], wrapped[i], 1e-5f)
        // A one-shot clamps: t = 2.5 holds the end pose.
        clip.sample(2.5f, wrapped, loop = false)
        val endPose = floatArrayOf(0f, 0f, S45, C45)
        for (i in 0..3) assertEquals(endPose[i], wrapped[q + i], 1e-5f)
    }

    @Test fun animatorFallsDownLadder() {
        val model = GlbModel.from(skinnedGlb()) // carries only "swing"
        val animator = GlbAnimator(model)
        // "run" is missing and its ladder holds no clip the asset has -> rest pose.
        val rest = animator.sample("run", 0f)
        val locals = FloatArray(model.nodes.size * 10)
        for (n in model.nodes.indices) {
            locals[n * 10] = model.nodes[n].local[12]
            locals[n * 10 + 3] = 0f; locals[n * 10 + 4] = 0f; locals[n * 10 + 5] = 0f; locals[n * 10 + 6] = 1f
            locals[n * 10 + 7] = 1f; locals[n * 10 + 8] = 1f; locals[n * 10 + 9] = 1f
        }
        for (i in locals.indices) assertEquals(locals[i], rest[i], 1e-5f)
    }

    @Test fun animatorCrossfades() {
        // Two clips: "idle" lifts node 0 to y=5 over a second; "work" shoves it to x=3.
        val bytes = TestGlb.Builder().apply {
            positions(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
            val times = accessor(view(TestGlb.floats(0f, 2f)), 2, "SCALAR", 5126)
            val idleOut = accessor(view(TestGlb.floats(0f, 0f, 0f, 0f, 4f, 0f)), 2, "VEC3", 5126)
            val workOut = accessor(view(TestGlb.floats(3f, 0f, 0f, 3f, 0f, 0f)), 2, "VEC3", 5126)
            idleTrack = times; idleVals = idleOut; workVals = workOut
        }.build(
            meshes = """{"primitives":[{"attributes":{"POSITION":0},"material":0}]}""",
            nodes = """{"name":"body","mesh":0}""",
            extras = MATERIALS_RED + "," +
                """"animations":[""" +
                """{"name":"idle","samplers":[{"input":$idleTrack,"output":$idleVals}],"channels":[{"sampler":0,"target":{"node":0,"path":"translation"}}]},""" +
                """{"name":"work","samplers":[{"input":$idleTrack,"output":$workVals}],"channels":[{"sampler":0,"target":{"node":0,"path":"translation"}}]}]""",
        )
        val model = GlbModel.from(bytes)
        val animator = GlbAnimator(model)
        // Settle into idle (a 2s loop, halfway lifted), then interrupt with work at t=1.5
        // and blend over 0.2s. The clip loops, so times are kept strictly inside (0, 2).
        animator.sample("idle", 1.5f)
        animator.interrupt("work", 1.5f)
        val start = animator.sample("work", 1.5f)
        assertEquals(3f, start[1], 1e-4f) // the outgoing idle pose, mid-lift
        val end = animator.sample("work", 1.7f)
        assertEquals(3f, end[0], 1e-4f) // fully in the incoming work pose
        assertEquals(0f, end[1], 1e-4f)
    }

    private var idleTrack = 0
    private var idleVals = 0
    private var workVals = 0

    // ---------------------------------------------------------------- registry

    @Test fun registryCachesAndRecordsFailures() {
        val problems = GlbRegistry.declare("testpack", "smith", "npcs", "models/smith.glb", 1)
        assertTrue(problems.isEmpty())
        GlbRegistry.offer("smith", triangleGlb())
        val first = GlbRegistry.get("smith")
        assertNotNull(first)
        assertSame(first, GlbRegistry.get("smith")) // parsed once, cached
        val corrupt = triangleGlb(); corrupt[1] = 'X'.code.toByte()
        GlbRegistry.offer("smith", corrupt)
        assertNull(GlbRegistry.get("smith"))
        val lines = GlbRegistry.report()
        assertTrue(lines.any { it.contains("npcs: 1") })
        assertTrue(lines.any { it.contains("failed") && it.contains("not a .glb") })
    }

    @Test fun registryRejectsBadDeclarations() {
        assertTrue(GlbRegistry.declare("p", "a", "wizardry", "a.glb", 1).isNotEmpty())
        assertTrue(GlbRegistry.declare("p", "", "npcs", "a.glb", 1).isNotEmpty())
        assertTrue(GlbRegistry.declare("p", "b", "npcs", "", 1).isNotEmpty())
        assertTrue(GlbRegistry.entryById("a") == null)
    }

    @Test fun registryHandlesMoveWithContent() {
        val pack = TestPacks.base().copy(
            models = listOf(
                GlbModelDef(id = "m_a", category = "npcs", file = "a.glb"),
                GlbModelDef(id = "m_b", category = "props", file = "b.glb"),
            ),
        )
        val registry = TestPacks.build(pack)
        assertEquals(0, registry.modelHandle("base:m_a"))
        assertEquals(1, registry.modelHandle("base:m_b"))
        assertEquals("base:m_b", registry.modelIdAt(1))
        assertEquals(-1, registry.modelHandle("nope"))
        assertEquals(-1, registry.modelHandle(""))
    }

    // ---------------------------------------------------------------- content validation

    @Test fun unknownModelReferenceFallsBackToProcedural() {
        val broken = TestPacks.base().copy(
            models = listOf(GlbModelDef(id = "odd", category = "wizardry", file = "odd.glb")),
            npcs = listOf(NpcDef(id = "smith", name = "Smith", profession = "fisher", home = "cottage", model = "ghost")),
        )
        val registry = TestPacks.build(broken)
        val smith = registry.npcs["base:smith"]
        assertNotNull(smith)
        assertEquals("", smith!!.model)
        assertTrue(registry.models.isEmpty())
        assertTrue(registry.issues.any { it.entry == "base:smith" && it.message.contains("ghost") })
        assertTrue(registry.issues.any { it.message.contains("wizardry") })
    }

    @Test fun declaredModelReferenceSurvives() {
        val pack = TestPacks.base().copy(
            models = listOf(GlbModelDef(id = "smith_model", category = "npcs", file = "models/smith.glb")),
            npcs = listOf(NpcDef(id = "smith", name = "Smith", profession = "fisher", home = "cottage", model = "smith_model")),
        )
        val registry = TestPacks.build(pack)
        // The ref is canonicalised, so the renderer's handle lookup finds it.
        assertEquals("base:smith_model", registry.npcs["base:smith"]!!.model)
        assertEquals(0, registry.modelHandle(registry.npcs["base:smith"]!!.model))
        assertFalse(registry.issues.any { it.entry == "base:smith" })
    }

    // ---------------------------------------------------------------- building bake

    @Test fun buildingBakesGlbShellAndFallsBack() {
        GlbRegistry.declare("testpack", "hall_model", "environment", "models/hall.glb", 1)
        GlbRegistry.offer("hall_model", triangleGlb())
        val baked = BuildingModels.exterior(BuildingDef(id = "glbhall", name = "Hall", x = 0f, z = 0f, model = "hall_model"))
        assertNotNull(baked)
        val v = baked!!.mesh.data
        // The GLB's red triangle is the whole shell.
        assertEquals(3, baked.mesh.vertexCount)
        assertEquals(1f, v[6], 1e-6f)
        assertEquals(0f, v[7], 1e-6f)

        // A building whose asset never arrived keeps its authored shell, with the reason recorded.
        val fallback = BuildingModels.exterior(BuildingDef(id = "nofile", name = "No File", x = 0f, z = 0f, model = "missing_model"))
        assertNotNull(fallback)
        assertTrue(fallback!!.mesh.vertexCount > 3)
        app.rork.p7buzvrzcw3frddi8y50ou.Diagnostics.state.value.entries
            .any { it.area == "glb" && it.message.contains("missing_model") }
            .let { assertTrue(it) }
    }

    // ---------------------------------------------------------------- helpers

    private companion object {
        const val S45 = 0.70710677f
        const val C45 = 0.70710677f
        val MATERIALS_RED = """"materials":[{"name":"red","pbrMetallicRoughness":{"baseColorFactor":[1,0,0,1]}}]"""
    }

    private inline fun <reified T : Exception> assertFails(block: () -> Unit): T =
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName} was not thrown")
        } catch (e: Exception) {
            if (e is T) e
            else throw AssertionError("Expected ${T::class.java.simpleName} but got ${e.javaClass.simpleName}: ${e.message}")
        }
}
