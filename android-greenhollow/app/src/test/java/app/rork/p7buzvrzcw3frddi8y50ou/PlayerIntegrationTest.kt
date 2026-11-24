package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.NpcDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ProfessionDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.PropertyDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.SettlementDef
import app.rork.p7buzvrzcw3frddi8y50ou.sim.FinanceEvent
import app.rork.p7buzvrzcw3frddi8y50ou.sim.InputState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PLAYER_ID
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PropRels
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveBinding
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveGame
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player as a participant in the world's own paper: the same properties, the same
 * economy, the same settlement books — never a second set of systems, never counted among
 * the villagers. Every test runs the production pathways: content, binding, the day's books.
 */
class PlayerIntegrationTest {

    /** Greenhollow with a room to let at the inn and a cottage of Oskar's. */
    private fun playerPack(): ContentPack = TestPacks.base(id = "core").copy(
        professions = listOf(
            ProfessionDef(id = "innkeeper", name = "Innkeeper", workplace = "inn", wage = 2, trader = true),
            ProfessionDef(id = "merchant", name = "Merchant", workplace = "shop", wage = 2, trader = true),
            ProfessionDef(id = "smith", name = "Smith", workplace = "smithy", wage = 3, trader = true),
        ),
        buildings = TestPacks.base().buildings + listOf(
            BuildingDef(id = "inn", name = "Inn", type = "inn", classification = "hospitality", x = 2f, z = 2f),
            BuildingDef(id = "shop", name = "Shop", type = "shop", classification = "mixed_use", x = -3f, z = 4f),
            BuildingDef(id = "smithy", name = "Smithy", type = "smithy", classification = "industrial", x = 4f, z = -3f),
            BuildingDef(id = "cottage2", name = "Oskar's house", type = "house", classification = "residential", x = 0f, z = 9f),
            BuildingDef(id = "well", name = "Well", type = "well", classification = "civic", x = 0f, z = 0f),
            BuildingDef(id = "farhouse", name = "Far house", type = "house", classification = "residential", x = 200f, z = 200f),
        ),
        npcs = listOf(
            NpcDef(id = "mira", name = "Mira", profession = "innkeeper", age = 40, home = "inn", gold = 50),
            NpcDef(id = "tomas", name = "Tomas", profession = "merchant", age = 30, home = "shop", gold = 30),
            NpcDef(id = "oskar", name = "Oskar", profession = "smith", age = 50, home = "cottage2", gold = 40),
        ),
        properties = listOf(
            PropertyDef(id = "prop_inn", building = "inn", name = "The inn", owner = "mira", business = "mira", rent = 1),
            PropertyDef(id = "prop_shop", building = "shop", name = "The shop", owner = "tomas", business = "tomas", rent = 1),
            PropertyDef(id = "prop_smithy", building = "smithy", name = "The smithy", owner = "oskar", business = "oskar", rent = 1),
            PropertyDef(id = "prop_cottage2", building = "cottage2", name = "Oskar's house", owner = "oskar", rent = 2),
        ),
        settlements = listOf(
            SettlementDef(id = "greenhollow", name = "Greenhollow", x = 0f, z = 0f, radius = 30f),
            SettlementDef(id = "millbrook", name = "Millbrook", kind = "hamlet", x = 200f, z = 200f, radius = 10f),
        ),
    )

    /** The running world, bound in the engine's order, settlements included. */
    private fun playerWorld(
        save: SaveGame = SaveGame(),
        pack: ContentPack = playerPack(),
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
    private fun min(w: World): Double = w.save.minutes + 5.0

    // ------------------------------------------------------------ identity

    @Test
    fun `the paper recognises the player - ownership residence and a derived status`() {
        val w = playerWorld()
        val m = min(w)
        assertNotNull(w.properties.transferOwnership("smithy", PLAYER_ID, m))
        assertEquals("player", w.properties.ownerOf("smithy"))
        assertEquals(1, w.properties.ownershipsOf(PLAYER_ID).size)

        // Oskar still keeps his trade under the player's new roof, so the smithy reads
        // occupied until his business ends; a deed alone says nothing about living there.
        assertEquals("occupied", w.properties.statusOf("smithy"))
        assertTrue(w.properties.endBusiness("smithy", "oskar", m))
        assertEquals("owned", w.properties.statusOf("smithy"))
        assertNotNull(w.properties.addResident("smithy", PLAYER_ID, m))
        assertEquals("owner_occupied", w.properties.statusOf("smithy"))

        // And the same paper ends the same way for the player as for anyone.
        assertTrue(w.properties.removeResident("smithy", PLAYER_ID, m))
        assertEquals("owned", w.properties.statusOf("smithy"))
    }

    @Test
    fun `employment paper accepts the player as worker and as employer`() {
        val w = playerWorld()
        val m = min(w)
        val asWorker = w.properties.hire(PLAYER_ID, "smithy", "oskar", m)
        assertNotNull(asWorker)
        assertEquals(PLAYER_ID, asWorker!!.partyId)
        assertEquals("oskar", asWorker.principalId)

        val asEmployer = w.properties.hire("mira", "shop", PLAYER_ID, m)
        assertNotNull(asEmployer)
        assertEquals("mira", asEmployer!!.partyId)
        assertEquals(PLAYER_ID, asEmployer.principalId)
        assertTrue(w.properties.employeesOf("shop").contains("mira"))
    }

    @Test
    fun `a pack villager may not take the player's name`() {
        val impostorPack = playerPack().copy(
            npcs = playerPack().npcs + NpcDef(id = PLAYER_ID, name = "Impostor", profession = "smith", age = 50, home = "cottage2"),
        )
        val w = playerWorld(pack = impostorPack)
        assertNull(w.npcById[PLAYER_ID])
        assertTrue(w.npcs.none { it.def.id == PLAYER_ID })
        // The world's three real villagers are untouched by the refused definition.
        assertEquals(3, w.npcs.size)
    }

    // ------------------------------------------------------------ rents

    @Test
    fun `a player tenancy pays its landlord from the player's purse on the books' day`() {
        val w = playerWorld()
        val m = min(w)
        assertNotNull(w.properties.startTenancy("inn", PLAYER_ID, m))
        assertEquals(20, w.p.gold)
        // Mira staked her inn's till at bind, so her purse carries the rest.
        assertEquals(25, w.npcById["mira"]!!.s.gold)

        w.economy.tick(10, 9f)

        assertEquals(19, w.p.gold)
        assertEquals(26, w.npcById["mira"]!!.s.gold)
        assertTrue(w.economy.rentCollected >= 1)
        val rentLine = w.p.finHistory.lastOrNull { it.kind == "rent" }
        assertNotNull(rentLine)
        assertEquals(-1, rentLine!!.amount)
        assertEquals("mira", rentLine.other)
    }

    @Test
    fun `rent the player cannot pay becomes debt never coin from nowhere`() {
        val w = playerWorld()
        assertNotNull(w.properties.startTenancy("inn", PLAYER_ID, min(w)))
        w.p.gold = 0
        val arrearsBefore = w.economy.rentArrears

        w.economy.tick(10, 9f)

        assertEquals(0, w.p.gold)
        assertEquals(1, w.p.debt)
        assertEquals(arrearsBefore + 1, w.economy.rentArrears)
        // The landlord was paid nothing, and no rent line was written for nothing.
        assertEquals(25, w.npcById["mira"]!!.s.gold)
        assertTrue(w.p.finHistory.none { it.kind == "rent" })
    }

    @Test
    fun `the player's roof is charged once a day however often the books are asked`() {
        val w = playerWorld()
        assertNotNull(w.properties.startTenancy("inn", PLAYER_ID, min(w)))

        w.economy.tick(10, 9f)
        w.economy.tick(10, 15f)
        assertEquals(19, w.p.gold)

        w.economy.tick(11, 9f)
        assertEquals(18, w.p.gold)
    }

    // ------------------------------------------------------------ the coin's honesty

    @Test
    fun `a purchase by transfer moves every coin and is written in both books`() {
        val w = playerWorld()
        fun World.coin() = w.p.gold + w.npcs.sumOf { it.s.gold }
        val before = w.coin()
        val tomasBefore = w.npcById["tomas"]!!.s.gold

        assertTrue(w.economy.transfer(PLAYER_ID, "tomas", 7, "a basket of nails"))

        assertEquals(before, w.coin())
        assertEquals(13, w.p.gold)
        assertEquals(tomasBefore + 7, w.npcById["tomas"]!!.s.gold)
        val out = w.p.finHistory.lastOrNull { it.kind == "transfer" }
        assertNotNull(out)
        assertEquals(-7, out!!.amount)
        assertEquals("tomas", out.other)
        val into = w.npcById["tomas"]!!.s.finHistory.lastOrNull { it.kind == "transfer" }
        assertEquals(7, into!!.amount)
        assertEquals(PLAYER_ID, into.other)
    }

    @Test
    fun `a fresh trade is staked from the player's purse and a taken-over trade keeps its till`() {
        val w = playerWorld()
        val m = min(w)
        // The player opens a trade where none was kept: the float comes from their purse.
        assertNotNull(w.properties.associateBusiness("cottage2", PLAYER_ID, m))
        w.economy.bind()
        val fresh = w.economy.ledgerForBuilding("cottage2")
        assertNotNull(fresh)
        assertEquals(PLAYER_ID, fresh!!.operatorId)
        assertEquals(10, fresh.reserves) // half of 20, under the cap
        assertEquals(10, w.p.gold)
        assertEquals(-10, w.p.finHistory.last().amount)

        // A second bind must not stake the same purse twice.
        w.economy.bind()
        assertEquals(10, w.economy.ledgerForBuilding("cottage2")!!.reserves)
        assertEquals(10, w.p.gold)

        // A trade taken over keeps its till, float and all, and follows the paper's keeper.
        val coinBefore = w.p.gold + w.npcs.sumOf { it.s.gold }
        assertNotNull(w.properties.associateBusiness("smithy", PLAYER_ID, m))
        w.economy.bind()
        val taken = w.economy.ledgerForBuilding("smithy")
        assertEquals(PLAYER_ID, taken!!.operatorId)
        assertEquals(20, taken.reserves) // Oskar's float stays with the trade
        assertEquals(coinBefore, w.p.gold + w.npcs.sumOf { it.s.gold })
    }

    @Test
    fun `villagers shopping at the player's counter fill the player's till`() {
        val w = playerWorld()
        val m = min(w)
        assertNotNull(w.properties.associateBusiness("shop", PLAYER_ID, m))
        w.economy.bind()
        val till = w.economy.ledgerForBuilding("shop")!!
        val staked = till.reserves
        val mira = w.npcById["mira"]!!

        assertTrue(w.economy.villagerShopping(mira))

        assertEquals(staked + EconomySystem.SHOP_PRICE, till.reserves)
        assertEquals(25 - EconomySystem.SHOP_PRICE, mira.s.gold)
        // The take sits in the till, not the player's pocket — the books say where it is.
        assertEquals(20, w.p.gold)
        assertTrue(w.economy.salesTaken >= 1)
    }

    // ------------------------------------------------------------ the readings

    @Test
    fun `the player's means read purse savings till and debt`() {
        val w = playerWorld()
        assertEquals(20, w.economy.meansOfPlayer())
        assertTrue(w.economy.canAffordPlayer(15))
        assertFalse(w.economy.canAffordPlayer(200))

        w.p.savings = 5
        w.p.debt = 3
        assertEquals(22, w.economy.meansOfPlayer())

        // A till they run counts towards their means — staking moves coin from the purse
        // into a till that is still theirs, so the reading holds; debt never counts as wealth.
        assertNotNull(w.properties.associateBusiness("cottage2", PLAYER_ID, min(w)))
        w.economy.bind()
        assertEquals(22, w.economy.meansOfPlayer())
        assertFalse(w.economy.canAffordRentPlayer(2))
    }

    @Test
    fun `the player's book of accounts is trimmed from the front like anyone's`() {
        val w = playerWorld()
        repeat(15) { assertTrue(w.economy.transfer(PLAYER_ID, "oskar", 1, "a coin")) }
        assertEquals(EconomySystem.MAX_FIN_HISTORY, w.p.finHistory.size)
        // The oldest lines were paid away: the first kept entry is the fourth coin.
        assertEquals(-1, w.p.finHistory.first().amount)
        assertEquals(-1, w.p.finHistory.last().amount)
        assertEquals(12, w.p.finHistory.sumOf { -it.amount })
    }

    @Test
    fun `the player's money survives the save`() {
        val save = SaveGame()
        save.player.savings = 13
        save.player.debt = 4
        save.player.lastPaidDay = 9
        save.player.finHistory += FinanceEvent(save.minutes, "rent", -2, "mira", "the roof over you")

        val again = SaveStore.decode(SaveStore.encode(save)).player
        assertEquals(13, again.savings)
        assertEquals(4, again.debt)
        assertEquals(9, again.lastPaidDay)
        assertEquals(1, again.finHistory.size)
        assertEquals("rent", again.finHistory.first().kind)
        assertEquals(-2, again.finHistory.first().amount)
    }

    // ------------------------------------------------------------ the settlement's books

    @Test
    fun `the player keeps a roof but is never counted among the souls`() {
        val w = playerWorld()
        val statsBefore = w.settlements.statsOf(green(w))

        assertNotNull(w.properties.startTenancy("inn", PLAYER_ID, min(w)))
        assertEquals("Inn", w.settlements.homeOfPlayer()?.name)
        assertEquals(green(w), w.settlements.settlementOfPlayer())

        val statsAfter = w.settlements.statsOf(green(w))
        assertEquals(statsBefore.population, statsAfter.population)
        assertEquals(statsBefore.adults, statsAfter.adults)
        assertEquals(statsBefore.households, statsAfter.households)
        assertFalse(saveHasPlayerInHousehold(w))
    }

    @Test
    fun `the diagnostics know what the player keeps`() {
        val w = playerWorld()
        val m = min(w)
        assertNotNull(w.properties.transferOwnership("smithy", PLAYER_ID, m))
        assertNotNull(w.properties.startTenancy("inn", PLAYER_ID, m))
        w.economy.tick(10, 9f)

        w.npcSim.pushDiagnostics()
        val sim = Diagnostics.state.value.sim
        assertEquals("Inn", sim.playerResidence)
        assertEquals("Greenhollow", sim.playerSettlement)
        assertEquals(1, sim.playerOwned)
        assertEquals(0, sim.playerBusinesses)
        assertEquals(20 - 1, sim.playerMeans)
        assertEquals(0, sim.playerDebt)
    }

    private fun saveHasPlayerInHousehold(w: World): Boolean =
        w.save.households.any { hh -> hh.members.any { it == PLAYER_ID } }
}
