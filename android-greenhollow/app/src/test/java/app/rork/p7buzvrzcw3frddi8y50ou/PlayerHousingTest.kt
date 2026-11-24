package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.NpcDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ProfessionDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.PropertyDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.SettlementDef
import app.rork.p7buzvrzcw3frddi8y50ou.sim.InputState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PLAYER_ID
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PropRels
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveBinding
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveGame
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveStore
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
 * The player keeping a home through the vale's own paper: buying freeholds, taking
 * tenancies, moving between roofs — the same properties, the same economy, the same
 * settlement coffers the villagers keep, and never a second set of systems. Every test
 * runs the production pathways: content, binding, the day's books.
 */
class PlayerHousingTest {

    /** Greenhollow with homes to buy, rooms to let, and one roof over in Millbrook. */
    private fun housingPack(): ContentPack = TestPacks.base(id = "core").copy(
        professions = listOf(
            ProfessionDef(id = "innkeeper", name = "Innkeeper", workplace = "inn", wage = 2, trader = true),
            ProfessionDef(id = "smith", name = "Smith", workplace = "smithy", wage = 3, trader = true),
        ),
        buildings = TestPacks.base().buildings + listOf(
            BuildingDef(id = "inn", name = "Inn", type = "inn", classification = "hospitality", x = 2f, z = 2f),
            BuildingDef(id = "smithy", name = "Smithy", type = "smithy", classification = "industrial", x = 4f, z = -3f),
            BuildingDef(id = "house_a", name = "Orchard House", type = "house", classification = "residential", x = 5f, z = 5f),
            BuildingDef(id = "cottage2", name = "Oskar's house", type = "house", classification = "residential", x = 0f, z = 9f),
            BuildingDef(id = "house_b", name = "Rose Cottage", type = "house", classification = "residential", x = -5f, z = 6f),
            BuildingDef(id = "roomhouse", name = "The Rooms", type = "house", classification = "residential", x = -4f, z = -5f),
            BuildingDef(id = "barn", name = "Barn", type = "barn", classification = "storage", x = 6f, z = 7f),
            BuildingDef(id = "well", name = "Well", type = "well", classification = "civic", x = 0f, z = 0f),
            BuildingDef(id = "farhouse", name = "Far House", type = "house", classification = "residential", x = 200f, z = 200f),
        ),
        npcs = listOf(
            NpcDef(id = "mira", name = "Mira", profession = "innkeeper", age = 40, home = "inn", gold = 50),
            NpcDef(id = "oskar", name = "Oskar", profession = "smith", age = 50, home = "cottage2", gold = 40),
        ),
        properties = listOf(
            PropertyDef(id = "prop_inn", building = "inn", name = "The inn", owner = "mira", business = "mira", rent = 1),
            PropertyDef(id = "prop_cottage2", building = "cottage2", name = "Oskar's house", owner = "oskar", rent = 2),
            PropertyDef(id = "prop_house_b", building = "house_b", name = "Rose Cottage", price = 75),
            PropertyDef(id = "prop_room", building = "roomhouse", name = "The Rooms", rent = 1, residents = listOf("mira")),
            PropertyDef(id = "prop_farhouse", building = "farhouse", name = "Far House", rent = 1),
        ),
        settlements = listOf(
            SettlementDef(id = "greenhollow", name = "Greenhollow", x = 0f, z = 0f, radius = 30f),
            SettlementDef(id = "millbrook", name = "Millbrook", kind = "hamlet", x = 200f, z = 200f, radius = 10f),
        ),
    )

    /** The running world, bound in the engine's order, settlements included. */
    private fun housingWorld(
        save: SaveGame = SaveGame(),
        pack: ContentPack = housingPack(),
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

    private fun green(w: World) = w.settlements.byId("greenhollow")!!
    private fun mill(w: World) = w.settlements.byId("millbrook")!!
    private fun min(w: World): Double = w.save.minutes + 5.0

    /** Every coin there is: pockets, savings, tills and coffers — the sum that must not move. */
    private fun coinInWorld(w: World): Int {
        val tills = w.save.businesses.values.sumOf { it.reserves }
        val coffers = w.save.settlements.sumOf { it.coffer }
        return w.p.gold + w.p.savings + w.npcs.sumOf { it.s.gold + it.s.savings } + tills + coffers
    }

    // ------------------------------------------------------------ what a home costs

    @Test
    fun `an unclaimed home is for sale at the derived price`() {
        val w = housingWorld()
        assertEquals(EconomySystem.HOUSE_PRICE, w.economy.askingPrice("house_a"))
    }

    @Test
    fun `a declared price is honoured and seeded onto the paper`() {
        val w = housingWorld()
        assertEquals(75, w.economy.askingPrice("house_b"))
        assertEquals(75, w.properties.propertyOf("house_b")?.price)
    }

    @Test
    fun `a month of rent is the price when the paper knows only rent`() {
        val w = housingWorld()
        assertEquals(60, w.economy.askingPrice("cottage2"))
        assertEquals(30, w.economy.askingPrice("roomhouse"))
    }

    @Test
    fun `no price at all is asked for what is no home`() {
        val w = housingWorld()
        assertNull(w.economy.askingPrice("barn"))
        assertNull(w.economy.askingPrice("well"))
        assertNull(w.economy.askingPrice("smithy"))
    }

    // ------------------------------------------------------------ buying

    @Test
    fun `buying the freehold takes the coin and writes the deed`() {
        val w = housingWorld()
        w.p.gold = 150
        val msg = w.economy.buyHome("house_a")
        assertTrue(msg, msg.contains("freehold"))
        assertEquals("player", w.properties.ownerOf("house_a"))
        assertEquals(50, w.p.gold)
        assertEquals("owned", w.properties.statusOf("house_a"))
    }

    @Test
    fun `the purse pays first and the savings make up the rest`() {
        val w = housingWorld()
        w.p.gold = 40
        w.p.savings = 60
        assertTrue(w.economy.buyHome("house_a").contains("freehold"))
        assertEquals(0, w.p.gold)
        assertEquals(0, w.p.savings)
    }

    @Test
    fun `an unclaimed home's price goes to the settlement coffer`() {
        val w = housingWorld()
        w.p.gold = 150
        w.economy.buyHome("house_a")
        assertEquals(EconomySystem.HOUSE_PRICE, green(w).coffer)
    }

    @Test
    fun `the housing trade mints nothing and destroys nothing`() {
        val w = housingWorld()
        w.p.gold = 120
        w.p.savings = 30
        val before = coinInWorld(w)
        w.economy.buyHome("house_a")
        w.economy.rentHome("roomhouse")
        w.economy.leaveHome("roomhouse")
        assertEquals(before, coinInWorld(w))
    }

    @Test
    fun `a home someone holds is not for sale`() {
        val w = housingWorld()
        w.p.gold = 500
        assertTrue(w.economy.buyHome("inn").contains("not selling"))
        assertEquals("mira", w.properties.ownerOf("inn"))
        assertEquals(500, w.p.gold)
        assertEquals(0, green(w).coffer)
    }

    @Test
    fun `a home the player already holds is simply theirs`() {
        val w = housingWorld()
        w.p.gold = 150
        w.economy.buyHome("house_a")
        val gold = w.p.gold
        assertTrue(w.economy.buyHome("house_a").contains("already"))
        assertEquals(gold, w.p.gold)
        assertEquals(1, w.properties.ownershipsOf(PLAYER_ID).size)
    }

    @Test
    fun `what is no home is not sold, and no paper is written for it`() {
        val w = housingWorld()
        assertFalse(w.economy.buyHome("barn").isEmpty())
        assertFalse(w.economy.buyHome("well").isEmpty())
        assertFalse(w.economy.buyHome("no-such-hall").isEmpty())
        assertNull(w.properties.propertyOf("barn"))
        assertNull(w.properties.propertyOf("well"))
    }

    @Test
    fun `a freehold the player cannot raise is refused without spending`() {
        val w = housingWorld()
        w.p.gold = 50
        w.p.savings = 10
        val msg = w.economy.buyHome("house_a")
        assertTrue(msg, msg.contains("cannot raise"))
        assertEquals(50, w.p.gold)
        assertEquals(10, w.p.savings)
        assertNull(w.properties.ownerOf("house_a"))
    }

    @Test
    fun `buying the room you rent ends the tenancy and returns the deposit`() {
        val w = housingWorld()
        w.p.gold = 200
        assertTrue(w.economy.rentHome("roomhouse").contains("tenancy"))
        val afterRent = w.p.gold
        assertEquals(6, green(w).coffer) // the deposit, in the common purse
        assertTrue(w.economy.buyHome("roomhouse").contains("freehold"))
        assertEquals("player", w.properties.ownerOf("roomhouse"))
        // The tenancy is over, the deposit back from the coffer: 200 - 6 - 30 + 6.
        assertEquals(afterRent - 30 + 6, w.p.gold)
        assertEquals(30, green(w).coffer)
        assertTrue(w.properties.homesOf(PLAYER_ID).isEmpty())
    }

    @Test
    fun `buying a roof does not move anyone in`() {
        val w = housingWorld()
        w.p.gold = 150
        w.economy.buyHome("house_a")
        assertTrue(w.properties.residencesOf(PLAYER_ID).isEmpty())
        assertNull(w.settlements.homeOfPlayer())
    }

    // ------------------------------------------------------------ renting

    @Test
    fun `renting a room registers the tenancy and takes the deposit`() {
        val w = housingWorld()
        w.p.gold = 40
        val msg = w.economy.rentHome("roomhouse")
        assertTrue(msg, msg.contains("tenancy"))
        assertEquals(listOf(PLAYER_ID), w.properties.tenantsOf("roomhouse"))
        assertEquals(34, w.p.gold)
        assertEquals(6, green(w).coffer)
    }

    @Test
    fun `taking the same tenancy twice changes nothing`() {
        val w = housingWorld()
        w.p.gold = 40
        w.economy.rentHome("roomhouse")
        val gold = w.p.gold
        val coffer = green(w).coffer
        assertTrue(w.economy.rentHome("roomhouse").contains("already"))
        assertEquals(gold, w.p.gold)
        assertEquals(coffer, green(w).coffer)
    }

    @Test
    fun `renting a roof the player owns is refused`() {
        val w = housingWorld()
        w.p.gold = 150
        w.economy.buyHome("house_a")
        assertTrue(w.economy.rentHome("house_a").contains("yours already"))
    }

    @Test
    fun `a building with no rooms to let is not rented`() {
        val w = housingWorld()
        assertTrue(w.economy.rentHome("house_a").contains("letting rooms"))
        assertTrue(w.properties.homesOf(PLAYER_ID).isEmpty())
    }

    @Test
    fun `a deposit the player cannot raise is refused`() {
        val w = housingWorld()
        w.p.gold = 3
        val msg = w.economy.rentHome("roomhouse")
        assertTrue(msg, msg.contains("cannot raise"))
        assertTrue(w.properties.homesOf(PLAYER_ID).isEmpty())
        assertEquals(3, w.p.gold)
    }

    @Test
    fun `renting at an inn pays the innkeeper the deposit`() {
        val w = housingWorld()
        w.p.gold = 40
        val miraBefore = w.npcById["mira"]!!.s.gold
        assertTrue(w.economy.rentHome("inn").contains("tenancy"))
        assertEquals(miraBefore + 6, w.npcById["mira"]!!.s.gold)
        assertEquals(0, green(w).coffer)
    }

    // ------------------------------------------------------------ residence

    @Test
    fun `moving in writes the residence behind the paper`() {
        val w = housingWorld()
        w.p.gold = 40
        w.economy.rentHome("roomhouse")
        assertTrue(w.properties.moveResidence("roomhouse", PLAYER_ID, min(w)))
        assertEquals("roomhouse", w.settlements.homeOfPlayer()?.id)
        assertEquals("Greenhollow", w.settlements.settlementOfPlayer()?.name)
    }

    @Test
    fun `moving in without a deed or tenancy is refused`() {
        val w = housingWorld()
        assertFalse(w.properties.moveResidence("house_a", PLAYER_ID, min(w)))
        assertNull(w.settlements.homeOfPlayer())
    }

    @Test
    fun `moving residence ends the roof kept elsewhere`() {
        val w = housingWorld()
        w.p.gold = 200
        w.economy.buyHome("house_a")
        w.properties.moveResidence("house_a", PLAYER_ID, min(w))
        w.economy.rentHome("roomhouse")
        assertTrue(w.properties.moveResidence("roomhouse", PLAYER_ID, min(w)))
        assertEquals(listOf("roomhouse"), w.properties.residencesOf(PLAYER_ID).map { it.buildingId })
        assertTrue(w.properties.residentsOf("house_a", includeHousehold = false).isEmpty())
        assertEquals("player", w.properties.ownerOf("house_a")) // the deed stays theirs
    }

    @Test
    fun `a roof over in another settlement is where the books say the player lives`() {
        val w = housingWorld()
        w.p.gold = 40
        w.economy.rentHome("farhouse")
        w.properties.moveResidence("farhouse", PLAYER_ID, min(w))
        assertEquals("Millbrook", w.settlements.settlementOfPlayer()?.name)
    }

    @Test
    fun `giving a roof up ends the residence and tenancy and returns the deposit`() {
        val w = housingWorld()
        w.p.gold = 40
        w.economy.rentHome("roomhouse")
        w.properties.moveResidence("roomhouse", PLAYER_ID, min(w))
        val msg = w.economy.leaveHome("roomhouse")
        assertTrue(msg, msg.contains("give up"))
        assertTrue(w.properties.homesOf(PLAYER_ID).isEmpty())
        assertTrue(w.properties.residencesOf(PLAYER_ID).isEmpty())
        assertNull(w.settlements.homeOfPlayer())
        assertEquals(40, w.p.gold) // the deposit came back whole
        assertEquals(0, green(w).coffer)
    }

    @Test
    fun `giving up a roof leaves the other occupants be`() {
        val w = housingWorld()
        w.p.gold = 40
        w.economy.rentHome("roomhouse")
        w.properties.moveResidence("roomhouse", PLAYER_ID, min(w))
        assertTrue(w.properties.residentsOf("roomhouse").contains("mira"))
        w.economy.leaveHome("roomhouse")
        assertTrue(w.properties.residentsOf("roomhouse").contains("mira"))
    }

    @Test
    fun `leaving a roof the player keeps no paper for is refused`() {
        val w = housingWorld()
        assertTrue(w.economy.leaveHome("house_a").contains("no home"))
    }

    // ------------------------------------------------------------ the books

    @Test
    fun `rent on the player's roof reaches the right landlord`() {
        val w = housingWorld()
        w.p.gold = 40
        w.economy.rentHome("inn")
        val miraBefore = w.npcById["mira"]!!.s.gold
        w.economy.tick(10, 9f)
        assertEquals(miraBefore + 1, w.npcById["mira"]!!.s.gold)
        assertEquals(33, w.p.gold) // the deposit went at the door; the day's rent with the books
    }

    @Test
    fun `rent the player cannot pay becomes debt`() {
        val w = housingWorld()
        w.p.gold = 0
        w.economy.rentHome("inn") // the deposit can't be raised — use paper directly instead
        assertTrue(w.properties.homesOf(PLAYER_ID).isEmpty())
        // The paper, written by the world's own hand as any tenant's would be:
        assertNotNull(w.properties.startTenancy("inn", PLAYER_ID, min(w), "the world's own paper"))
        w.economy.tick(10, 9f)
        assertEquals(1, w.p.debt)
        assertEquals(0, w.p.gold)
    }

    @Test
    fun `the day's rent is asked once, however often the books are opened`() {
        val w = housingWorld()
        w.p.gold = 40
        w.economy.rentHome("inn")
        w.economy.tick(10, 9f)
        assertEquals(33, w.p.gold)
        w.economy.tick(10, 20f)
        assertEquals(33, w.p.gold)
        w.economy.tick(11, 9f)
        assertEquals(32, w.p.gold)
    }

    @Test
    fun `rent on a landlordless roof goes to the common coffer`() {
        val w = housingWorld()
        w.p.gold = 40
        w.economy.rentHome("roomhouse")
        w.economy.tick(10, 9f)
        // The deposit (6) sat in the coffer; the day's rent joins it, and no coin is destroyed.
        assertEquals(7, green(w).coffer)
        assertEquals(33, w.p.gold)
    }

    // ------------------------------------------------------------ the paper and the neighbours

    @Test
    fun `ownership and residence remain separate papers`() {
        val w = housingWorld()
        w.p.gold = 150
        w.economy.buyHome("house_a")
        assertFalse(w.properties.residencesOf(PLAYER_ID).any { it.buildingId == "house_a" })
        w.properties.moveResidence("house_a", PLAYER_ID, min(w))
        w.economy.leaveHome("house_a")
        assertEquals("player", w.properties.ownerOf("house_a"))
        assertTrue(w.properties.residencesOf(PLAYER_ID).isEmpty())
    }

    @Test
    fun `the neighbours' paper is untouched by the player's housekeeping`() {
        val w = housingWorld()
        w.p.gold = 500
        w.economy.buyHome("house_a")
        w.economy.rentHome("roomhouse")
        w.properties.moveResidence("roomhouse", PLAYER_ID, min(w))
        w.economy.leaveHome("roomhouse")
        assertEquals("mira", w.properties.ownerOf("inn"))
        assertEquals("mira", w.properties.businessOf("inn"))
        assertTrue(w.properties.homesOf("mira").isEmpty())
        // The room Mira keeps at The Rooms is her own paper, never the player's.
        assertTrue(w.properties.residentsOf("roomhouse").contains("mira"))
        assertTrue(w.properties.homesOf("oskar").isEmpty())
    }

    @Test
    fun `the save keeps the price the coffer and the player's paper`() {
        val w = housingWorld()
        w.p.gold = 150
        w.economy.buyHome("house_a")
        w.economy.rentHome("roomhouse")
        w.properties.moveResidence("roomhouse", PLAYER_ID, min(w))
        val again = SaveStore.decode(SaveStore.encode(w.save))
        assertEquals(75, again.properties.first { it.buildingId == "house_b" }.price)
        // The coffer holds the sale's price and the room's deposit together.
        assertEquals(EconomySystem.HOUSE_PRICE + 6, again.settlements.first { it.id == "greenhollow" }.coffer)
        assertEquals("house_a", again.propertyRels
            .filter { it.kind == PropRels.OWNERSHIP && it.isActive() && it.partyId == PLAYER_ID }
            .mapNotNull { r -> again.properties.firstOrNull { it.id == r.propertyId }?.buildingId }
            .single())
    }

    @Test
    fun `the diagnostics see the player's tenancies`() {
        val w = housingWorld()
        w.p.gold = 40
        w.economy.rentHome("roomhouse")
        w.npcSim.pushDiagnostics()
        assertEquals(1, app.rork.p7buzvrzcw3frddi8y50ou.Diagnostics.state.value.sim.playerTenancies)
    }
}
