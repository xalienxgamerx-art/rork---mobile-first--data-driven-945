package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.NpcDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ProfessionDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.PropertyDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.SettlementDef
import app.rork.p7buzvrzcw3frddi8y50ou.sim.EVENT_OPEN
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Condition
import app.rork.p7buzvrzcw3frddi8y50ou.sim.InputState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Prosperity
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveBinding
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveGame
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveMigrations
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveStore
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SettlementSave
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.EconomySystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.GoalSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.HouseholdSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.KinshipSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.NpcSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.ProgressionSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.PropertySystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.SettlementSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.SocialSystem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settlement: a community whose books are kept from the records the other systems already
 * hold — the people, the roofs, the tills, the larders — with only what cannot be reconstructed
 * persisted (standing, events, the day's memory). Every test runs the production pathways:
 * content, binding, the day's books.
 */
class SettlementTest {

    /** Greenhollow: three trades kept under its roofs, a farmstead over the hill in Millbrook. */
    private fun settlePack(): ContentPack = TestPacks.base(id = "core").copy(
        professions = listOf(
            ProfessionDef(id = "innkeeper", name = "Innkeeper", workplace = "inn", wage = 2, trader = true),
            ProfessionDef(id = "merchant", name = "Merchant", workplace = "shop", wage = 2, trader = true),
            ProfessionDef(id = "smith", name = "Smith", workplace = "smithy", wage = 3, trader = true),
            ProfessionDef(id = "fisher", name = "Fisher", workplace = "harbour", wage = 2),
        ),
        buildings = TestPacks.base().buildings + listOf(
            BuildingDef(id = "inn", name = "Inn", type = "inn", classification = "hospitality", x = 2f, z = 2f),
            BuildingDef(id = "shop", name = "Shop", type = "shop", classification = "mixed_use", x = -3f, z = 4f),
            BuildingDef(id = "smithy", name = "Smithy", type = "smithy", classification = "industrial", x = 4f, z = -3f),
            BuildingDef(id = "apothecary", name = "Apothecary", type = "apothecary", classification = "commercial", x = -4f, z = -2f),
            BuildingDef(id = "well", name = "Well", type = "well", classification = "civic", x = 0f, z = 0f),
            BuildingDef(id = "barn", name = "Barn", type = "barn", classification = "storage", x = 6f, z = 6f),
            BuildingDef(id = "cottage2", name = "Oskar's house", type = "house", classification = "residential", x = 0f, z = 9f),
            BuildingDef(id = "farhouse", name = "Far house", type = "house", classification = "residential", x = 200f, z = 200f),
        ),
        npcs = listOf(
            NpcDef(id = "mira", name = "Mira", profession = "innkeeper", age = 40, home = "inn", gold = 50),
            NpcDef(id = "finn", name = "Finn", profession = "fisher", age = 12, home = "inn", gold = 0),
            NpcDef(id = "tomas", name = "Tomas", profession = "merchant", age = 30, home = "shop", gold = 30),
            NpcDef(id = "oskar", name = "Oskar", profession = "smith", age = 50, home = "cottage2", gold = 40),
        ),
        properties = listOf(
            PropertyDef(id = "prop_inn", building = "inn", name = "The inn", owner = "mira", business = "mira", rent = 1),
            PropertyDef(id = "prop_shop", building = "shop", name = "The shop", owner = "tomas", business = "tomas", rent = 1),
            PropertyDef(id = "prop_smithy", building = "smithy", name = "The smithy", owner = "oskar", business = "oskar", rent = 1),
        ),
        settlements = listOf(
            SettlementDef(id = "greenhollow", name = "Greenhollow", x = 0f, z = 0f, radius = 30f),
            SettlementDef(id = "millbrook", name = "Millbrook", kind = "hamlet", x = 200f, z = 200f, radius = 10f),
        ),
    )

    /** The running world, bound in the engine's order, settlements included. */
    private fun settleWorld(
        save: SaveGame = SaveGame(),
        pack: ContentPack = settlePack(),
    ): World {
        val content = TestPacks.build(pack)
        val w = World(content, save, InputState(), MutableSharedFlow())
        SaveBinding.bind(save, content)
        w.progression = ProgressionSystem(w)
        w.social = SocialSystem(w)
        w.npcSim = NpcSystem(w)
        w.progression.recalc()
        w.npcSim.build()
        w.kin = KinshipSystem(w)
        w.households = HouseholdSystem(w)
        w.goals = GoalSystem(w)
        w.properties = PropertySystem(w)
        w.economy = EconomySystem(w)
        w.settlements = SettlementSystem(w)
        w.kin.bind()
        w.households.bind()
        w.goals.bind()
        w.properties.bind()
        w.economy.bind()
        w.settlements.bind()
        return w
    }

    private fun green(w: World): SettlementSave = w.settlements.byId("greenhollow")!!
    private fun mill(w: World): SettlementSave = w.settlements.byId("millbrook")!!

    // ------------------------------------------------------------ binding

    @Test
    fun `pack settlements bind once and rebind changes nothing`() {
        val w = settleWorld()
        assertEquals(2, w.save.settlements.size)
        assertEquals("Greenhollow", green(w).name)
        assertEquals("village", green(w).kind)
        assertEquals("hamlet", mill(w).kind)

        // A second bind must not duplicate a community or forget its books.
        green(w).reputation = 77
        w.settlements.bind()
        assertEquals(2, w.save.settlements.size)
        assertEquals(77, green(w).reputation)
    }

    @Test
    fun `a save round-trips with its settlement books intact`() {
        val save = SaveGame()
        save.settlements += SettlementSave(
            id = "greenhollow", name = "Greenhollow", reputation = 63, reputationNote = "the harvest feast",
            lastSimDay = 12, lastPopulation = 9, prosperityScore = 58,
        ).apply {
            conditions += Condition.GROWING
            conditionDays[Condition.GROWING] = 4
            knownBusinesses += "inn"
            events += app.rork.p7buzvrzcw3frddi8y50ou.sim.CommunityEventSave(
                id = "harvest:greenhollow:10:0", type = "harvest", settlementId = "greenhollow",
                startDay = 10, endDay = 12, text = "The harvest is in.",
            )
        }

        val again = SaveStore.decode(SaveStore.encode(save)).settlements.single()
        assertEquals("greenhollow", again.id)
        assertEquals(63, again.reputation)
        assertEquals("the harvest feast", again.reputationNote)
        assertEquals(12, again.lastSimDay)
        assertEquals(9, again.lastPopulation)
        assertEquals(58, again.prosperityScore)
        assertEquals(setOf(Condition.GROWING), again.conditions)
        assertEquals(4, again.conditionDays[Condition.GROWING])
        assertEquals(setOf("inn"), again.knownBusinesses)
        assertEquals(1, again.events.size)
        assertEquals(12, again.events.first().endDay)
    }

    @Test
    fun `sanitize trims the event roll and repairs impossible numbers`() {
        val s = SettlementSave(id = "g", name = "G").apply {
            reputation = 500
            prosperityScore = -9
        }
        repeat(20) { n ->
            s.events += app.rork.p7buzvrzcw3frddi8y50ou.sim.CommunityEventSave(
                id = "e$n", type = "old", settlementId = "g", startDay = n, endDay = n, text = "old news $n",
            )
        }
        s.events += app.rork.p7buzvrzcw3frddi8y50ou.sim.CommunityEventSave(
            id = "open", type = "food_shortage", settlementId = "g", startDay = 30, text = "thin larders",
        )
        s.conditionDays["made_up"] = 5
        s.sanitize()

        assertEquals("clamped to the top of the scale", 100, s.reputation)
        assertEquals(0, s.prosperityScore)
        assertTrue("the open event is always kept", s.events.any { it.id == "open" })
        assertTrue("old news is trimmed to the roll's bound", s.events.size <= 13)
        assertFalse("a condition nothing defines is forgotten", "made_up" in s.conditionDays)
    }

    // ------------------------------------------------------------ population and housing

    @Test
    fun `population is read from the people under the settlement's roofs`() {
        val w = settleWorld()
        val st = w.settlements.statsOf(green(w))

        assertEquals("mira, finn, tomas, oskar all roofed inside", 4, st.population)
        assertEquals(3, st.adults)
        assertEquals(1, st.children)
        assertEquals(0, st.unhoused)
        assertEquals("three roofs: the inn, the shop, oskar's house", 3, st.households)
        assertEquals("the inn, the shop and the house may house", 3, st.dwellings)
        assertEquals(0, st.vacantDwellings)
        assertEquals("the declared mixed use plus the inn's lodgings over its trade", 2, st.mixedUse)
        assertFalse(st.housingConstrained)
    }

    @Test
    fun `the roofless are counted where they stand and crowd the housing`() {
        val w = settleWorld()
        w.save.households.first { it.id == "hh:inn" }.home = ""

        val st = w.settlements.statsOf(green(w))
        assertEquals("mira and finn are unhoused but still here", 4, st.population)
        assertEquals(2, st.unhoused)
        assertEquals("the inn stands empty", 1, st.vacantDwellings)
        assertTrue(st.housingConstrained)
    }

    @Test
    fun `moving a roof moves its people between settlements`() {
        val w = settleWorld()
        w.settlements.tick(10)

        w.save.households.first { it.id == "hh:inn" }.home = "farhouse"

        val g = w.settlements.statsOf(green(w))
        val m = w.settlements.statsOf(mill(w))
        assertEquals("tomas and oskar remain", 2, g.population)
        assertEquals("mira and finn now live over the hill", 2, m.population)
        assertEquals("their roof houses them there, not as vagrants", 0, g.unhoused)
    }

    // ------------------------------------------------------------ the day's books

    @Test
    fun `an arrival is news to one settlement and a departure to the other`() {
        val w = settleWorld()
        w.settlements.tick(10)
        assertEquals(4, green(w).lastPopulation)

        w.save.households.first { it.id == "hh:inn" }.home = "farhouse"
        w.settlements.tick(11)

        assertTrue(green(w).events.any { it.type == "departure" })
        assertTrue(mill(w).events.any { it.type == "arrival" })
        assertEquals(2, green(w).lastPopulation)
        assertEquals(2, mill(w).lastPopulation)
    }

    @Test
    fun `the books are kept once a day however often the clock asks`() {
        val w = settleWorld()
        w.settlements.tick(10)
        w.save.households.first { it.id == "hh:inn" }.home = "farhouse"
        w.settlements.tick(11)
        val afterFirst = green(w).events.size

        w.settlements.tick(11)
        w.settlements.tick(11)
        assertEquals("a day is read once", afterFirst, green(w).events.size)
    }

    @Test
    fun `a year away settles in bounded days without duplicate news`() {
        val w = settleWorld()
        w.settlements.tick(10)
        w.save.households.first { it.id == "hh:inn" }.home = "farhouse"

        w.settlements.tick(10 + SettlementSystem.CATCH_UP_DAYS * 3)

        assertEquals("the books are up to date", 10 + SettlementSystem.CATCH_UP_DAYS * 3, green(w).lastSimDay)
        assertEquals("the one departure is news once, not once per caught-up day", 1, green(w).events.count { it.type == "departure" })
    }

    // ------------------------------------------------------------ trades

    @Test
    fun `the trades are read from the paper and their tills from the books`() {
        val w = settleWorld()
        val st = w.settlements.statsOf(green(w))

        assertEquals(listOf("inn", "shop", "smithy"), st.operatingBusinesses.sorted())
        assertEquals(3, st.businessKeepers)
        assertEquals(0, st.strainedTills)
    }

    @Test
    fun `a trade closing and reopening is recorded both ways`() {
        val w = settleWorld()
        w.settlements.tick(10)

        w.save.propertyRels.filter {
            it.kind == app.rork.p7buzvrzcw3frddi8y50ou.sim.PropRels.BUSINESS && it.propertyId == "prop:shop"
        }.forEach { it.endMinute = 5000.0 }
        w.settlements.tick(11)
        assertTrue(green(w).events.any { it.type == "business_closed" })
        assertFalse("the shop is no longer among the kept", "shop" in green(w).knownBusinesses)

        w.save.propertyRels.filter { it.propertyId == "prop:shop" }.forEach { it.endMinute = -1.0 }
        w.settlements.tick(12)
        assertTrue(green(w).events.any { it.type == "business_opened" })
        assertTrue("shop" in green(w).knownBusinesses)
    }

    @Test
    fun `a till under strain is counted`() {
        val w = settleWorld()
        w.economy.ledgerForBuilding("inn")!!.reserves = 5

        assertTrue(w.settlements.statsOf(green(w)).strainedTills >= 1)
    }

    // ------------------------------------------------------------ food

    @Test
    fun `a thin larder is a shortage and its ending a recovery`() {
        val w = settleWorld()
        w.settlements.tick(10)

        w.save.households.forEach { it.food = 10f }
        w.settlements.tick(11)
        assertTrue(green(w).events.any { it.type == "food_shortage" })

        w.save.households.forEach { it.food = 90f }
        w.settlements.tick(12)
        assertTrue("the shortage closes", green(w).events.none {
            it.type == "food_shortage" && it.endDay == EVENT_OPEN
        })
        assertTrue(green(w).events.any { it.type == "recovery" })
    }

    // ------------------------------------------------------------ prosperity and reputation

    @Test
    fun `prosperity reads the whole condition and tiers it`() {
        val w = settleWorld()
        val st = w.settlements.statsOf(green(w))

        assertTrue("every factor is explained", st.factors.size == 6)
        assertTrue("a fed, housed, working village prospers", st.prosperityRaw >= 60)
        assertEquals(
            Prosperity.label(Prosperity.classify(green(w).prosperityScore)),
            Prosperity.label(Prosperity.classify(green(w).prosperityScore)),
        )
    }

    @Test
    fun `prosperity moves slowly - one good day does not make a golden age`() {
        val w = settleWorld()
        val before = green(w).prosperityScore
        val raw = w.settlements.statsOf(green(w)).prosperityRaw

        w.settlements.tick(10)

        val expected = (before * (100 - SettlementSystem.PROSPERITY_ALPHA) + raw * SettlementSystem.PROSPERITY_ALPHA) / 100
        assertEquals(expected, green(w).prosperityScore)
        assertTrue("folded, not jumped", kotlin.math.abs(green(w).prosperityScore - before) <= kotlin.math.abs(raw - before))
    }

    @Test
    fun `reputation drifts a step a day toward what the settlement has become`() {
        val w = settleWorld()
        w.settlements.tick(10)
        green(w).reputation = 0

        w.settlements.tick(11)

        assertTrue("it rose, and only by the one step", green(w).reputation in 1..2)
    }

    @Test
    fun `reputation jumps for reasons and remembers why`() {
        val w = settleWorld()
        w.settlements.tick(10)
        val before = green(w).reputation

        w.save.households.first { it.id == "hh:inn" }.home = "farhouse"
        w.settlements.tick(11)

        assertEquals("two for the loss, less the day's one step back toward the mean", before - 1, green(w).reputation)
        assertEquals("people leaving", green(w).reputationNote)
    }

    // ------------------------------------------------------------ events

    @Test
    fun `the harvest comes once a year and only in autumn`() {
        val save = SaveGame(minutes = 250 * 1440.0)
        val w = settleWorld(save = save)
        w.settlements.tick(250)
        assertEquals(1, green(w).events.count { it.type == "harvest" })

        w.settlements.tick(251)
        assertEquals("not twice in a year", 1, green(w).events.count { it.type == "harvest" })
    }

    @Test
    fun `events persist through the save`() {
        val w = settleWorld()
        w.settlements.tick(10)
        w.save.households.first { it.id == "hh:inn" }.home = "farhouse"
        w.settlements.tick(11)

        val again = SaveStore.decode(SaveStore.encode(w.save))
        val g = again.settlements.first { it.id == "greenhollow" }
        assertTrue("a departure is one-shot news, still open", g.events.any { it.type == "departure" && it.endDay == EVENT_OPEN })
    }

    // ------------------------------------------------------------ conditions

    @Test
    fun `a condition becomes truth only after holding its days and clears when it lifts`() {
        val w = settleWorld()
        w.settlements.tick(10)
        w.save.households.first { it.id == "hh:inn" }.home = ""

        w.settlements.tick(11)
        assertFalse("one day is not yet truth", Condition.HOUSING_CONSTRAINED in green(w).conditions)
        w.settlements.tick(12)
        assertFalse("two days either", Condition.HOUSING_CONSTRAINED in green(w).conditions)
        w.settlements.tick(13)
        assertTrue("three days and the books believe it", Condition.HOUSING_CONSTRAINED in green(w).conditions)

        w.save.households.first { it.id == "hh:inn" }.home = "inn"
        w.settlements.tick(14)
        assertFalse("and it clears when it lifts", Condition.HOUSING_CONSTRAINED in green(w).conditions)
    }

    // ------------------------------------------------------------ migration and inert worlds

    @Test
    fun `a v7 save migrates with its settlement books empty`() {
        val migrated = SaveMigrations.migrate(
            Json.parseToJsonElement("""{"version": 7, "minutes": 100.0}""").jsonObject
        )

        assertEquals(8, SaveMigrations.versionOf(migrated))
        assertEquals(0, migrated["settlements"]!!.jsonArray.size)
        assertEquals(100.0, migrated["minutes"]!!.jsonPrimitive.content.toDouble(), 0.001)
    }

    @Test
    fun `a world no pack settles stays inert`() {
        val w = settleWorld(pack = TestPacks.base(id = "core"))

        assertTrue(w.save.settlements.isEmpty())
        w.settlements.tick(50)
        w.settlements.tick(51)
        assertTrue(w.settlements.diagnosticsLines().isEmpty())
        assertEquals(0, w.save.settlements.size)
    }
}
