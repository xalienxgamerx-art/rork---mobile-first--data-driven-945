package app.rork.p7buzvrzcw3frddi8y50ou.sim.systems

import app.rork.p7buzvrzcw3frddi8y50ou.sim.Calendar
import app.rork.p7buzvrzcw3frddi8y50ou.sim.CommunityEventSave
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Condition
import app.rork.p7buzvrzcw3frddi8y50ou.sim.EVENT_OPEN
import app.rork.p7buzvrzcw3frddi8y50ou.sim.LifeStages
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PropRels
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PLAYER_ID
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Prosperity
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SettlementSave
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Wealth
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World
import kotlin.math.max
import kotlin.math.min

/**
 * The settlement: a persistent community whose condition emerges from the records the other
 * systems already keep. It owns almost nothing — the people belong to the save's npc and
 * household records, the roofs to the properties, the trades to the tills — and each day it
 * reads those records and asks what kind of place this has become.
 *
 * What it does own is what cannot be reconstructed: the settlement's identity and standing
 * (reputation, moved slowly and for reasons), the community's open and past events, the day
 * the books were last kept, and the persistence memory that keeps conditions from flickering.
 *
 * There is no second clock: the books are kept in [tick], called from the world's slow tick,
 * day-keyed and bounded, so waiting, sleeping and years-away catch-up all settle each day
 * once — and distant settlements live on exactly as near ones do, since every calculation
 * reads records, not frames.
 */
class SettlementSystem(private val w: World) {
    private val save get() = w.save
    private val content get() = w.content

    // Counters for the diagnostics report.
    var eventsBegun = 0
        private set
    var eventsEnded = 0
        private set

    // ------------------------------------------------------------ binding

    /**
     * Brings the save's settlements in line with the content: pack-declared settlements are
     * instantiated once (the save stays authoritative afterwards, so a renamed or removed
     * pack cannot erase a community's history), and a saved settlement keeps its record even
     * when no loaded pack defines it — its people will simply have gone somewhere else.
     */
    fun bind() {
        for (def in content.settlements.values) {
            if (byId(def.id) != null) {
                val s = byId(def.id)!!
                if (s.name.isEmpty()) s.name = def.name
                if (s.kind.isEmpty()) s.kind = def.kind
                continue
            }
            val s = SettlementSave(id = def.id, name = def.name, kind = def.kind)
            s.sanitize()
            save.settlements += s
        }
    }

    fun byId(id: String): SettlementSave? = save.settlements.firstOrNull { it.id == id }

    // ------------------------------------------------------------ membership (derived)

    /** The buildings of a settlement: those whose ground lies inside its radius. */
    fun buildingsOf(s: SettlementSave): List<app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef> {
        val def = content.settlements[s.id] ?: return emptyList()
        val r2 = def.radius * def.radius
        return content.buildings.values.filter {
            val dx = it.x - def.x
            val dz = it.z - def.z
            dx * dx + dz * dz <= r2
        }
    }

    /** Whether a building id stands in this settlement. */
    fun buildingIn(s: SettlementSave, buildingId: String): Boolean =
        buildingsOf(s).any { it.id == buildingId }

    /** The settlement whose ground a building stands on, when one claims it. */
    fun settlementOfBuilding(buildingId: String): SettlementSave? {
        val id = content.buildings.canonical(buildingId)
        return save.settlements.firstOrNull { buildingIn(it, id) }
    }

    // ------------------------------------------------------------ the player's place

    /**
     * The building of the player's roof, when they keep one — a residence or a tenancy in
     * their name. Owning a deed says nothing about living under it, the same as for anyone.
     */
    fun homeOfPlayer(): app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef? {
        val roof = save.propertyRels.firstOrNull {
            it.isActive() && it.partyId == PLAYER_ID &&
                (it.kind == PropRels.RESIDENCE || it.kind == PropRels.TENANCY)
        } ?: return null
        val buildingId = roof.propertyId.removePrefix("prop:")
        return content.buildings[buildingId] ?: content.buildingFor(buildingId)
    }

    /**
     * The settlement the player's roof stands in, when they have one. The player is never
     * counted among a settlement's souls — the books read households and the roofless, and
     * the player is neither — but where they keep their roof is a fact worth reporting.
     */
    fun settlementOfPlayer(): SettlementSave? {
        val home = homeOfPlayer() ?: return null
        for (s in save.settlements) {
            if (buildingIn(s, home.id)) return s
        }
        return null
    }

    // ------------------------------------------------------------ the day's books

    /**
     * Keeps the settlement's books once a day, however often the world asks and however far
     * the clock has jumped. Each pass reads the records, notices what changed, and lets the
     * settlement's condition, prosperity and reputation move accordingly — bounded, so a
     * year away settles as thirty days of quiet catch-up, never sixty.
     */
    fun tick(day: Int) {
        if (save.settlements.isEmpty()) return
        var last = save.settlements.minOf { if (it.lastSimDay < 0) day - 1 else it.lastSimDay }
        last = max(last, day - CATCH_UP_DAYS)
        var d = last + 1
        while (d <= day) {
            for (s in save.settlements) keepBooks(s, d)
            d++
        }
    }

    private fun keepBooks(s: SettlementSave, day: Int) {
        if (s.lastSimDay >= day) return
        val first = s.lastSimDay < 0
        val prevPopulation = s.lastPopulation
        s.lastSimDay = day
        val stats = statsOf(s)

        // Arrivals and departures, seen against the books' last count.
        if (!first && prevPopulation >= 0 && stats.population != prevPopulation) {
            val kind = if (stats.population > prevPopulation) "arrival" else "departure"
            val diff = kotlin.math.abs(stats.population - prevPopulation)
            openEvent(s, kind, day, if (kind == "arrival") {
                "$diff ${if (diff == 1) "person has" else "people have"} come to live in ${s.name}."
            } else {
                "$diff ${if (diff == 1) "person has" else "people have"} left ${s.name}."
            })
        }
        s.lastPopulation = stats.population

        // The trades: a new one is news, a closed one is loss, the reopened debt repaid.
        for (b in stats.operatingBusinesses) {
            if (b !in s.knownBusinesses) {
                s.knownBusinesses.add(b)
                if (!first) {
                    val prop = save.properties.firstOrNull { it.buildingId == b }
                    openEvent(s, "business_opened", day, "A trade is kept at the ${prop?.name?.ifEmpty { b } ?: b} again.")
                }
            }
        }
        for (b in s.knownBusinesses.toList()) {
            if (b !in stats.operatingBusinesses) {
                s.knownBusinesses.remove(b)
                if (!first) {
                    val prop = save.properties.firstOrNull { it.buildingId == b }
                    openEvent(s, "business_closed", day, "The trade at the ${prop?.name?.ifEmpty { b } ?: b} has stopped.")
                }
            }
        }

        // The larders: a shortage is hardship; its ending is worth noting too.
        if (stats.foodShortage) {
            openEvent(s, "food_shortage", day, "The larders of ${s.name} are running thin.")
        } else {
            if (closeEvent(s, "food_shortage", day)) {
                openEvent(s, "recovery", day, "The larders of ${s.name} are filled again.")
            }
        }

        // The harvest, when the calendar turns to Autumn — once a year, and only then.
        if (Calendar.season(save.minutes) == "Autumn") {
            val year = Calendar.day(save.minutes) / 360
            val had = s.events.any { it.type == "harvest" && it.startDay / 360 == year }
            if (!had) openEvent(s, "harvest", day, "The harvest is in, and ${s.name} keeps the feast.")
        }

        // Conditions hold or clear through their persistence windows.
        updateConditions(s, stats, day, prevPopulation)

        // Prosperity: a raw reading of the whole condition, folded slowly into the old.
        s.prosperityScore = (s.prosperityScore * (100 - PROSPERITY_ALPHA) + stats.prosperityRaw * PROSPERITY_ALPHA) / 100

        // Reputation drifts a step toward what the settlement has become; events already
        // moved it directly, so a quiet day moves it quietly.
        val target = reputationTarget(s, stats)
        if (s.reputation < target) s.reputation = min(100, s.reputation + 1)
        else if (s.reputation > target) s.reputation = max(0, s.reputation - 1)

        s.sanitize()
    }

    // ------------------------------------------------------------ events

    /** Opens an event unless one of its kind is already open. Returns true when it was news. */
    private fun openEvent(s: SettlementSave, type: String, day: Int, text: String): Boolean {
        if (s.events.any { it.type == type && it.endDay == EVENT_OPEN }) return false
        s.events += CommunityEventSave(
            id = "$type:${s.id}:$day:${s.events.size}",
            type = type, settlementId = s.id, startDay = day, text = text,
        )
        eventsBegun++
        when (type) {
            "arrival" -> moveReputation(s, 2, "new faces in the village")
            "departure" -> moveReputation(s, -2, "people leaving")
            "business_opened" -> moveReputation(s, 2, "a trade reopened")
            "business_closed" -> moveReputation(s, -4, "a trade lost")
            "food_shortage" -> moveReputation(s, -3, "the larders thin")
            "recovery" -> moveReputation(s, 3, "the larders filled")
            "harvest" -> moveReputation(s, 3, "the harvest feast")
        }
        return true
    }

    /** Closes the open event of a kind. Returns true when there was one to close. */
    private fun closeEvent(s: SettlementSave, type: String, day: Int): Boolean {
        val open = s.events.filter { it.type == type && it.endDay == EVENT_OPEN }
        if (open.isEmpty()) return false
        for (e in open) e.endDay = day
        eventsEnded++
        return true
    }

    private fun moveReputation(s: SettlementSave, delta: Int, why: String) {
        s.reputation = (s.reputation + delta).coerceIn(0, 100)
        s.reputationNote = why
    }

    /** Where the settlement's standing tends: its prosperity, less the weight of open troubles. */
    private fun reputationTarget(s: SettlementSave, stats: Stats): Int {
        var target = 35 + s.prosperityScore / 2
        for (e in s.events) {
            if (e.endDay != EVENT_OPEN) continue
            when (e.type) {
                "food_shortage" -> target -= 8
                "business_closed" -> target -= 6
                "departure" -> target -= 4
                "harvest" -> target += 5
                "recovery" -> target += 4
                "business_opened" -> target += 3
                "arrival" -> target += 2
            }
        }
        return target.coerceIn(0, 100)
    }

    // ------------------------------------------------------------ conditions

    /**
     * A condition becomes the settlement's truth only after holding for several days, and
     * clears the same way — so a day's fluctuation never reads as a change of fortune.
     */
    private fun updateConditions(s: SettlementSave, stats: Stats, day: Int, prevPopulation: Int) {
        val held = mutableSetOf<String>()
        if (prevPopulation >= 0 && stats.population > prevPopulation) held += Condition.GROWING
        if (prevPopulation >= 0 && stats.population < prevPopulation) held += Condition.DECLINING
        if (stats.housingConstrained) held += Condition.HOUSING_CONSTRAINED
        if (stats.foodShortage) held += Condition.RESOURCE_CONSTRAINED
        if (stats.economicallyStrained) held += Condition.ECONOMICALLY_STRAINED
        if (stats.operatingBusinesses.size >= COMMERCIALLY_ACTIVE_BUSINESSES) held += Condition.COMMERCIALLY_ACTIVE
        if (stats.missingServices.size >= underdevelopedAfter(s)) held += Condition.UNDERDEVELOPED

        for (c in Condition.ALL) {
            if (c in held) s.conditionDays[c] = (s.conditionDays[c] ?: 0) + 1
            else s.conditionDays[c] = 0
            val active = (s.conditionDays[c] ?: 0) >= HOLD_DAYS
            if (active && c !in s.conditions) {
                s.conditions += c
                if (c == Condition.DECLINING) openEvent(s, "hardship", day, "${s.name} is quietly losing its people.")
            } else if (!active && c in s.conditions && (s.conditionDays[c] ?: 0) == 0) {
                s.conditions -= c
            }
        }
    }

    /** A settlement is judged against what its kind should have: a hamlet needs less than a town. */
    private fun underdevelopedAfter(s: SettlementSave): Int = when (s.kind) {
        "hamlet" -> 2
        "village" -> 3
        "town" -> 4
        else -> 3
    } - 1

    // ------------------------------------------------------------ the readings

    /**
     * Everything the day's books saw, derived from the real records and stored nowhere. This
     * is what the diagnostics read, what the prosperity reading weighs, and what the tests
     * assert against.
     */
    fun statsOf(s: SettlementSave): Stats {
        val buildings = buildingsOf(s)
        val buildingIds = buildings.map { it.id }.toSet()

        // Roofs: housing-capable buildings, from the pack's own classification.
        val dwellings = buildings.filter { it.classification in HOUSING_CLASSIFICATIONS }
        val dwellingIds = dwellings.map { it.id }.toSet()

        // Households live where their roof stands; the roofless are someone's neighbours still.
        // A roof in another settlement houses its people there, not here as vagrants.
        val housed = save.households.filter { it.home in buildingIds }
        val settledRoofs = save.settlements.flatMap { other -> buildingsOf(other).map { b -> b.id } }.toSet()
        val homedAnywhere = save.households.filter { it.home in settledRoofs }.flatMap { it.members }.toSet()
        val unhoused = w.npcs.filter { it.def.id !in homedAnywhere }

        // Population is the people themselves, never a counter.
        val residents = housed.flatMap { it.members }.mapNotNull { w.npcById[it] } + unhoused
        val adults = residents.filter { !LifeStages.isDependant(LifeStages.stageOf(it.def.age)) }
        val children = residents.count { LifeStages.isDependant(LifeStages.stageOf(it.def.age)) }

        // Work: a live employment record, or a trade of one's own kept running.
        val employmentByPerson = save.propertyRels.filter {
            it.kind == PropRels.EMPLOYMENT && it.isActive()
        }.associateBy { it.partyId }
        val operatingProps = save.propertyRels.filter {
            it.kind == PropRels.BUSINESS && it.isActive() && it.propertyId.removePrefix("prop:") in buildingIds
        }
        val operatingBuildings = operatingProps.map { it.propertyId.removePrefix("prop:") }
        val operators = operatingProps.map { it.partyId }.toSet()
        val employed = adults.count { it.def.id in employmentByPerson || it.def.id in operators }

        // The tills' health: a till in arrears is a business under strain.
        val ledgers = operatingBuildings.mapNotNull { b ->
            save.properties.firstOrNull { it.buildingId == b }?.let { save.businesses[it.id] }
        }
        val strained = ledgers.count { it.wagesOwed > 0 || it.rentOwed > 0 || it.reserves < RESERVE_CUSHION }

        // Mixed use is a fact of the classification and the paper together.
        val mixedUse = dwellings.count { it.id in operatingBuildings || it.classification == "mixed_use" }

        // Food is the larders; goods are what the trades hold; both trace to real records.
        val avgLarder = if (housed.isEmpty()) 70f else housed.map { it.food }.average().toFloat()
        val goodsInStock = w.npcs.filter { it.def.id in operators }.sumOf { n -> n.s.stock.sumOf { it.count } }

        // Infrastructure: what stands, judged against the settlement's size.
        val civic = buildings.count { it.classification == "civic" }
        val storage = buildings.count { it.classification == "storage" }

        // The essential services a settlement of this kind should keep.
        val essentials = mapOf(
            "inn" to "an inn's bed and board",
            "shop" to "a general store",
            "smithy" to "a smith's forge",
            "apothecary" to "an apothecary's care",
        )
        val present = essentials.keys.count { type -> operatingBuildings.any { b -> b in buildingIds && content.buildings[b]?.type == type } }

        val housingConstrained = unhoused.isNotEmpty() || housed.size > dwellings.size
        val foodShortage = avgLarder < FOOD_LOW
        val unemployment = if (adults.isEmpty()) 0f else 1f - employed.toFloat() / adults.size
        val economicallyStrained = ledgers.any { it.wagesOwed > 0 || it.rentOwed > 0 } ||
            (adults.size >= 2 && unemployment > 0.5f)

        // Prosperity: households first (no rich man makes a village), then work, trades,
        // roofs, bread and services — weighted, normalized per head, and explained.
        val householdFinance = if (housed.isEmpty()) 50 else housed.map { hh ->
            hh.members.mapNotNull { w.npcById[it] }
                .map { n -> wealthPoints(w.economy.classify(n)) }
                .average().toInt()
        }.average().toInt()
        val employmentScore = (100f * employed / max(1, adults.size)).toInt()
        val businessScore = if (ledgers.isEmpty()) 50 else 100 - (strained * 100 / ledgers.size)
        val housingScore = (100 - unhoused.size * 25 - max(0, housed.size - dwellings.size) * 25).coerceIn(0, 100)
        val foodScore = avgLarder.toInt()
        val servicesScore = present * 100 / essentials.size
        val raw = (householdFinance * 25 + employmentScore * 20 + businessScore * 15 +
            housingScore * 15 + foodScore * 15 + servicesScore * 10) / 100

        val missingServices = essentials.entries.filter { (type, _) ->
            operatingBuildings.none { b -> b in buildingIds && content.buildings[b]?.type == type }
        }.map { it.value }

        return Stats(
            population = residents.size, adults = adults.size, children = children,
            employed = employed, unemployed = adults.size - employed,
            businessKeepers = operators.size, unhoused = unhoused.size,
            households = housed.size, dwellings = dwellings.size, vacantDwellings = dwellings.count { it.id !in housed.map { hh -> hh.home }.toSet() },
            mixedUse = mixedUse, operatingBusinesses = operatingBuildings, strainedTills = strained,
            avgLarder = avgLarder, goodsInStock = goodsInStock, civic = civic, storage = storage,
            missingServices = missingServices, housingConstrained = housingConstrained,
            foodShortage = foodShortage, economicallyStrained = economicallyStrained,
            prosperityRaw = raw,
            factors = mapOf(
                "households" to householdFinance, "employment" to employmentScore,
                "trades" to businessScore, "housing" to housingScore,
                "food" to foodScore, "services" to servicesScore,
            ),
        )
    }

    private fun wealthPoints(word: String): Int = when (word) {
        Wealth.DESTITUTE -> 0
        Wealth.POOR -> 20
        Wealth.MODEST -> 40
        Wealth.COMFORTABLE -> 60
        Wealth.WEALTHY -> 80
        else -> 100
    }

    /** The settlement's development needs, read from the same records — for a later milestone to act on. */
    fun needsOf(s: SettlementSave): List<String> {
        val stats = statsOf(s)
        val needs = mutableListOf<String>()
        if (stats.housingConstrained) needs += "housing for ${max(1, stats.unhoused)} more roof"
        for (m in stats.missingServices) needs += "a $m"
        if (stats.population >= 5 && stats.storage * 8 < stats.population) needs += "more storage"
        if (stats.operatingBusinesses.size < 3) needs += "another trade to keep"
        return needs
    }

    /** The diagnostics' settlement section, in plain words. */
    fun diagnosticsLines(): List<String> {
        val lines = mutableListOf<String>()
        if (save.settlements.isEmpty()) return lines
        for (s in save.settlements) {
            val st = statsOf(s)
            lines += "${s.name} (${s.kind}): ${st.population} souls in ${st.households} roofs " +
                "(${st.adults} grown, ${st.children} little, ${st.unemployed} seeking work, ${st.unhoused} unhoused)"
            lines += "Housing: ${st.dwellings} dwellings, ${st.vacantDwellings} standing empty, ${st.mixedUse} shared with a trade" +
                if (st.housingConstrained) " — the roofs are full" else ""
            lines += "Trades: ${st.operatingBusinesses.size} kept (${st.strainedTills} of their tills under strain)" +
                if (st.missingServices.isNotEmpty()) "; the village lacks ${st.missingServices.joinToString()}" else ""
            lines += "Stores: larders at ${st.avgLarder.toInt()} of 100, ${st.goodsInStock} goods on the shelves; the well and ${st.storage} stores stand"
            lines += "Prosperity: ${Prosperity.label(Prosperity.classify(s.prosperityScore))} (${s.prosperityScore}/100) — " +
                st.factors.entries.joinToString(", ") { "${it.key} ${it.value}" }
            lines += "Reputation ${s.reputation}/100" + (if (s.reputationNote.isNotEmpty()) ", last moved by ${s.reputationNote}" else "")
            lines += "Conditions: " + (if (s.conditions.isEmpty()) "none held" else s.conditions.joinToString { Condition.label(it) })
            val open = s.events.filter { it.endDay == EVENT_OPEN }
            lines += "Events: ${open.size} open" + (if (open.isNotEmpty()) " — " + open.joinToString("; ") { it.text } else "")
        }
        return lines
    }

    /** One settlement's derived condition, read fresh each time it is asked for. */
    data class Stats(
        val population: Int,
        val adults: Int,
        val children: Int,
        val employed: Int,
        val unemployed: Int,
        val businessKeepers: Int,
        val unhoused: Int,
        val households: Int,
        val dwellings: Int,
        val vacantDwellings: Int,
        val mixedUse: Int,
        val operatingBusinesses: List<String>,
        val strainedTills: Int,
        val avgLarder: Float,
        val goodsInStock: Int,
        val civic: Int,
        val storage: Int,
        val missingServices: List<String>,
        val housingConstrained: Boolean,
        val foodShortage: Boolean,
        val economicallyStrained: Boolean,
        val prosperityRaw: Int,
        val factors: Map<String, Int>,
    )

    companion object {
        /** The books settle at most this many days in one catch-up, however long the wait. */
        const val CATCH_UP_DAYS = 30
        /** Prosperity folds this share of today's reading into the standing one. */
        const val PROSPERITY_ALPHA = 20
        /** A condition must hold this many days before the settlement believes it. */
        const val HOLD_DAYS = 3
        /** An average larder below this is a settlement going hungry. */
        const val FOOD_LOW = 30f
        /** This many running trades make a settlement commercially active. */
        const val COMMERCIALLY_ACTIVE_BUSINESSES = 3
        /** A till below this reserve, or owing anyone, is under strain. */
        const val RESERVE_CUSHION = 10

        /** Buildings a roof may be: homes, the inn's lodging, and shop-below-home mixed use. */
        val HOUSING_CLASSIFICATIONS = setOf("residential", "hospitality", "mixed_use")
    }
}
