package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.NpcDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ProfessionDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.PropertyDef
import app.rork.p7buzvrzcw3frddi8y50ou.sim.InputState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveBinding
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveGame
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveMigrations
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveStore
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Wealth
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.EconomySystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.GoalSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.HouseholdSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.KinshipSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.NpcSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.ProgressionSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.PropertySystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.SocialSystem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vale's economy: wages paid by real payers, tills that fall into arrears rather than
 * minting coin, rents charged once a day, and a wealth reading derived from it all. Money
 * is never created or destroyed by a transfer — every test here can account for every coin.
 * Everything runs on the JVM through the production pathways: content, binding, the systems
 * themselves.
 */
class EconomyTest {

    /** A fisher's wage of 2 for nine hours; a smith's of 3; a trader's of 4. */
    private fun econPack(): ContentPack = TestPacks.base(id = "core").copy(
        professions = listOf(
            ProfessionDef(id = "fisher", name = "Fisher", workplace = "harbour", workStart = 8, workEnd = 17, wage = 2),
            ProfessionDef(id = "smith", name = "Smith", workplace = "forge", workStart = 8, workEnd = 17, wage = 3),
            ProfessionDef(id = "trader", name = "Trader", workplace = "store", workStart = 8, workEnd = 17, wage = 4, trader = true),
        ),
        buildings = TestPacks.base().buildings + listOf(
            BuildingDef(id = "forge", name = "Forge", type = "workshop", classification = "industrial", x = 5f, z = 10f),
            BuildingDef(id = "store", name = "Store", type = "shop", classification = "commercial", x = 2f, z = 8f),
        ),
        npcs = listOf(
            NpcDef(id = "hawk", name = "Hawk", profession = "fisher", age = 46, home = "cottage", gold = 40, personality = mapOf("generosity" to 0.8f)),
            NpcDef(id = "wren", name = "Wren", profession = "smith", age = 40, home = "cottage", gold = 60, workplace = "forge"),
            NpcDef(id = "finn", name = "Finn", profession = "smith", age = 20, home = "forge", gold = 5, workplace = "forge"),
            NpcDef(id = "kip", name = "Kip", profession = "fisher", age = 8, home = "cottage", gold = 8),
            NpcDef(id = "oskar", name = "Oskar", profession = "trader", age = 33, home = "store", gold = 80, workplace = "store"),
        ),
        properties = listOf(
            PropertyDef(id = "prop_forge", building = "forge", name = "The forge", owner = "hawk", business = "wren", employees = listOf("finn"), rent = 2),
            PropertyDef(id = "prop_cottage", building = "cottage", name = "The cottage", owner = "oskar", tenants = listOf("hawk"), rent = 3),
            PropertyDef(id = "prop_store", building = "store", name = "The store", owner = "oskar", business = "oskar", rent = 1),
        ),
    )

    /** The running world, bound in the engine's order, economy included. */
    private fun econWorld(save: SaveGame = SaveGame(), pack: ContentPack = econPack()): World {
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
        w.kin.bind()
        w.households.bind()
        w.goals.bind()
        w.properties.bind()
        w.economy.bind()
        return w
    }

    /** The whole vale's loose coin — pockets, tills, purses and the player's — for conservation checks. */
    private fun coinInPockets(w: World): Int =
        w.npcs.sumOf { it.s.gold } + w.p.gold + w.save.businesses.values.sumOf { it.reserves } +
            w.save.households.sumOf { it.purse }

    // ------------------------------------------------------------ staking and ledgers

    @Test
    fun `a business is staked from its operator's own purse, never minted`() {
        val w = econWorld()

        val forge = w.economy.ledgerForBuilding("forge")
        assertNotNull(forge)
        assertEquals("wren", forge!!.operatorId)
        assertEquals("half of Wren's 60, the float", 30, forge.reserves)
        assertEquals(30, w.npcById["wren"]!!.s.gold)

        val store = w.economy.ledgerForBuilding("store")
        assertNotNull(store)
        assertEquals(40, store!!.reserves)

        // The float moved; it did not appear. The pockets began at 20 + 40 + 60 + 5 + 8 + 80.
        assertEquals(213, coinInPockets(w))
    }

    @Test
    fun `a till is looked up the same from property, building or operator`() {
        val w = econWorld()

        assertSame(w.economy.ledgerForBuilding("forge"), w.economy.ledgerOf("prop:forge"))
        assertSame(w.economy.ledgerOperatedBy("wren"), w.economy.ledgerForBuilding("forge"))
        assertNull(w.economy.ledgerForBuilding("cottage"))
    }

    // ------------------------------------------------------------ wages

    @Test
    fun `a wage is paid from the employer's till and shared with the household`() {
        val w = econWorld()
        val finn = w.npcById["finn"]!!

        w.economy.payWage(finn, 3)

        // The till at the forge paid its employee; generosity (the default 0.5) sent a
        // share home to the purse of the roof he sleeps under.
        assertEquals(27, w.economy.ledgerForBuilding("forge")!!.reserves)
        assertEquals("5 + 3 wage - 1 provided", 7, finn.s.gold)
        assertEquals(1, w.households.of("finn")!!.purse)
        assertEquals("every coin accounted for", 213, coinInPockets(w))
    }

    @Test
    fun `a proprietor paying themselves moves coin only from till to pocket`() {
        val w = econWorld()
        val wren = w.npcById["wren"]!!

        w.economy.payWage(wren, 3)

        assertEquals(27, w.economy.ledgerForBuilding("forge")!!.reserves)
        assertEquals("33 wage less the share provided home", 32, wren.s.gold)
        assertEquals("her generosity sent a coin to the purse", 1, w.households.of("wren")!!.purse)
        assertEquals(1, w.economy.wagesPaid)
    }

    @Test
    fun `a short till falls into arrears and repays its payroll when it recovers`() {
        val w = econWorld()
        val finn = w.npcById["finn"]!!
        val forge = w.economy.ledgerForBuilding("forge")!!

        // A wage the till and its operator together cannot raise: what arrived arrived,
        // the rest is owed, and no coin was minted.
        w.economy.payWage(finn, 100)
        assertEquals(0, forge.reserves)
        assertEquals(40, forge.wagesOwed)
        assertEquals("till 30 + wren's pocket 30", 60, finn.s.gold + finn.s.savings + w.households.of("finn")!!.purse - 5)
        assertEquals(1, w.economy.wagesInArrears)

        // Trade recovers the till; the books then make the arrears good to the payroll.
        w.economy.saleTo(w.npcById["wren"]!!, 60)
        val before = finn.s.gold + finn.s.savings
        w.economy.tick(5, 9f)
        assertEquals(0, forge.wagesOwed)
        assertEquals("arrears repaid, the cushion kept back", 20, forge.reserves) // 60 in, 40 out to Finn; the larder was full, so no staples
        assertEquals(before + 40, finn.s.gold + finn.s.savings)
    }

    @Test
    fun `the lone trade provides - a livelihood is the one coin that is created`() {
        val w = econWorld()
        val hawk = w.npcById["hawk"]!!

        val before = coinInPockets(w)
        w.economy.payWage(hawk, 2)

        assertEquals(42, hawk.s.gold)
        assertEquals(1, w.economy.livelihoodPaid)
        assertEquals("the written-down exception: the work itself became coin", 2, coinInPockets(w) - before)
    }

    @Test
    fun `dependants earn nothing however long they work`() {
        val w = econWorld()
        val kip = w.npcById["kip"]!!

        w.economy.payWage(kip, 2)

        assertEquals(8, kip.s.gold)
        assertEquals(0, w.economy.livelihoodPaid)
        assertEquals(0, w.economy.wagesPaid)
    }

    // ------------------------------------------------------------ rent

    @Test
    fun `rent is charged once a day however often the books open`() {
        val w = econWorld()
        val hawk = w.npcById["hawk"]!!
        val oskar = w.npcById["oskar"]!!

        w.economy.tick(5, 9f)
        assertEquals(37, hawk.s.gold)
        assertEquals(43, oskar.s.gold)

        w.economy.tick(5, 12f)
        w.economy.tick(5, 23f)
        assertEquals("the same day is never charged twice", 37, hawk.s.gold)

        w.economy.tick(6, 9f)
        assertEquals(34, hawk.s.gold)
        assertEquals(46, oskar.s.gold)
    }

    @Test
    fun `a wait of days is settled once per day, bounded at a week`() {
        val w = econWorld()
        val hawk = w.npcById["hawk"]!!

        w.economy.tick(3, 9f)
        assertEquals(37, hawk.s.gold)

        // Seventeen days away: the books settle seven of them, not seventeen.
        w.economy.tick(20, 9f)
        assertEquals(37 - 21, hawk.s.gold)
    }

    @Test
    fun `rent a tenant cannot pay becomes debt, counted and owed`() {
        val w = econWorld()
        val hawk = w.npcById["hawk"]!!
        val oskar = w.npcById["oskar"]!!
        hawk.s.gold = 0

        w.economy.tick(5, 9f)

        assertEquals(3, hawk.s.debt)
        assertEquals(3, w.economy.rentArrears)
        assertEquals("the landlord was paid nothing", 40, oskar.s.gold)
    }

    @Test
    fun `the household purse pays the roof before any pocket does`() {
        val w = econWorld()
        val hawk = w.npcById["hawk"]!!
        val hh = w.households.of("hawk")!!
        hh.purse = 5

        w.economy.tick(5, 9f)

        assertEquals("the purse paid the day's rent", 2, hh.purse)
        assertEquals("Hawk's own pocket was not asked", 40, hawk.s.gold)
    }

    @Test
    fun `a lease pays its landlord from the trade's till`() {
        val w = econWorld()
        w.properties.startLease("forge", "wren", 0.0)
        val hawk = w.npcById["hawk"]!!

        w.economy.tick(5, 9f)

        val forge = w.economy.ledgerForBuilding("forge")!!
        assertEquals("the till's rent of 2 went out", 28, forge.reserves)
        assertEquals(2, forge.expenses)
        assertEquals("Hawk owns the forge: +2, less his own roof's 3", 39, hawk.s.gold)
        assertEquals("the cottage's 3 and the forge's 2", 5, w.economy.rentCollected)
    }

    // ------------------------------------------------------------ purses and purchases

    @Test
    fun `a purse fatter than comfort asks nothing of its earners`() {
        val w = econWorld()
        val finn = w.npcById["finn"]!!
        val hh = w.households.of("finn")!!
        hh.purse = EconomySystem.PURSE_COMFORT

        w.economy.payWage(finn, 3)

        assertEquals(EconomySystem.PURSE_COMFORT, hh.purse)
        assertEquals(8, finn.s.gold)
    }

    @Test
    fun `shopping fills the till and the larder, and a broke purse is refused`() {
        val w = econWorld()
        val finn = w.npcById["finn"]!!
        val store = w.economy.ledgerForBuilding("store")!!
        val hh = w.households.of("finn")!!
        hh.food = 50f

        assertTrue(w.economy.villagerShopping(finn))
        assertEquals(3, finn.s.gold)
        assertEquals(42, store.reserves)
        assertEquals(2, store.revenue)
        assertEquals("staples home", 58f, hh.food, 0.01f)

        // A purse that cannot pay is refused and counted, never wished affordable.
        finn.s.gold = 1
        assertFalse(w.economy.villagerShopping(finn))
        assertEquals(1, w.economy.unaffordablePurchases)
        assertEquals(42, store.reserves)

        // The keeper does not shop at their own counter.
        assertFalse(w.economy.villagerShopping(w.npcById["oskar"]!!))
        assertEquals(42, store.reserves)
    }

    @Test
    fun `the staples the purse buys are charged to the purse once a day`() {
        val w = econWorld()
        val hh = w.households.of("finn")!!
        hh.food = 10f
        hh.purse = 10

        w.economy.tick(5, 9f)
        assertEquals(6, hh.purse)
        assertEquals(40f, hh.food, 0.01f)
        assertEquals(44, w.economy.ledgerForBuilding("store")!!.reserves)

        w.economy.tick(5, 20f)
        assertEquals("once a day", 6, hh.purse)

        // A thin larder again the next day: the purse shops once more.
        hh.food = 10f
        w.economy.tick(6, 9f)
        assertEquals(2, hh.purse)
    }

    @Test
    fun `a player's purchase reaches the operator's till, a stranger's their pocket`() {
        val w = econWorld()

        // Wren keeps the forge's till, so the price lands there, not in her pocket.
        w.economy.saleTo(w.npcById["wren"]!!, 12)
        assertEquals(42, w.economy.ledgerForBuilding("forge")!!.reserves)
        assertEquals(30, w.npcById["wren"]!!.s.gold)

        // Hawk keeps no till: his take is his own.
        w.economy.saleTo(w.npcById["hawk"]!!, 5)
        assertEquals(45, w.npcById["hawk"]!!.s.gold)
    }

    @Test
    fun `a transfer moves coin whole, conserves it, and refuses what is not there`() {
        val w = econWorld()

        assertTrue(w.economy.transfer("hawk", "oskar", 10))
        assertEquals(30, w.npcById["hawk"]!!.s.gold)
        assertEquals(50, w.npcById["oskar"]!!.s.gold)
        assertEquals(213, coinInPockets(w))

        assertFalse(w.economy.transfer("hawk", "oskar", 31))
        assertEquals(30, w.npcById["hawk"]!!.s.gold)
        assertFalse("no one pays themselves", w.economy.transfer("hawk", "hawk", 1))

        // The player's purse takes part on the same terms.
        w.p.gold = 7
        assertTrue(w.economy.transfer("player", "hawk", 7))
        assertEquals(37, w.npcById["hawk"]!!.s.gold)
        assertEquals(0, w.p.gold)
    }

    // ------------------------------------------------------------ readings

    @Test
    fun `the wealth reading classifies means and margin in plain words`() {
        assertEquals(Wealth.DESTITUTE, Wealth.classify(0, 0))
        assertEquals(Wealth.DESTITUTE, Wealth.classify(50, -1))
        assertEquals(Wealth.POOR, Wealth.classify(10, 0))
        assertEquals(Wealth.POOR, Wealth.classify(24, 5))
        assertEquals(Wealth.MODEST, Wealth.classify(25, 0))
        assertEquals(Wealth.MODEST, Wealth.classify(99, 5))
        assertEquals(Wealth.COMFORTABLE, Wealth.classify(100, 0))
        assertEquals(Wealth.COMFORTABLE, Wealth.classify(349, 5))
        assertEquals(Wealth.WEALTHY, Wealth.classify(350, 0))
        assertEquals(Wealth.WEALTHY, Wealth.classify(999, 5))
        assertEquals(Wealth.AFFLUENT, Wealth.classify(1000, 0))
        for (word in Wealth.ALL) assertTrue(Wealth.label(word).isNotEmpty())
    }

    @Test
    fun `means, income and obligations are readings of the ledger, never records`() {
        val w = econWorld()
        val hawk = w.npcById["hawk"]!!
        val wren = w.npcById["wren"]!!

        // Hawk: 40 in the pocket, a third of an empty purse, no till, no debt yet.
        assertEquals(40, w.economy.meansOf(hawk))
        assertEquals("2 a day for nine hours", 18, w.economy.incomeOf(hawk))
        assertEquals("his roof's rent of 3 and little Kip's keep", 5, w.economy.obligationsOf(hawk))

        // Wren can call on her till besides her pocket.
        assertEquals(60, w.economy.meansOf(wren))

        // Hawk's wage of 18 carries his rent of 3 with plenty to spare; a rent of 7
        // would leave him less than a third.
        assertTrue(w.economy.canAffordRent(hawk, 3))
        assertFalse(w.economy.canAffordRent(hawk, 7))
        assertTrue(w.economy.canAfford(hawk, 40))
        assertFalse(w.economy.canAfford(hawk, 41))

        // Taking the cottage's lease would ask six days of its rent up front.
        assertEquals(18, w.economy.depositFor("cottage"))
        assertEquals(0, w.economy.depositFor("barn"))
    }

    // ------------------------------------------------------------ persistence

    @Test
    fun `a save round-trips its ledgers without staking twice`() {
        val w = econWorld()
        val restored = SaveStore.decode(SaveStore.encode(w.save))
        assertEquals(2, restored.businesses.size)
        assertEquals(30, restored.businesses["prop:forge"]!!.reserves)
        assertEquals("wren", restored.businesses["prop:forge"]!!.operatorId)

        // Binding the restored world re-reads the same tills: no second float is staked.
        val again = econWorld(save = restored)
        assertEquals(30, again.economy.ledgerForBuilding("forge")!!.reserves)
        assertEquals(30, again.npcById["wren"]!!.s.gold)
        assertEquals(restored.businesses.size, again.save.businesses.size)
    }

    @Test
    fun `a v6 save migrates with its books empty and ready`() {
        val migrated = SaveMigrations.migrate(
            Json.parseToJsonElement("""{"version": 6, "minutes": 100.0}""").jsonObject
        )

        assertEquals(8, SaveMigrations.versionOf(migrated))
        assertEquals(0, (migrated["businesses"]!!.jsonObject).size)
        // Nothing of the old save was disturbed.
        assertEquals(100.0, migrated["minutes"]!!.jsonPrimitive.content.toDouble(), 0.001)
    }
}
