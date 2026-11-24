package app.rork.p7buzvrzcw3frddi8y50ou.sim.systems

import app.rork.p7buzvrzcw3frddi8y50ou.sim.BusinessSave
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Calendar
import app.rork.p7buzvrzcw3frddi8y50ou.sim.FinanceEvent
import app.rork.p7buzvrzcw3frddi8y50ou.sim.LifeStages
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Npc
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PLAYER_ID
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PropRels
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertyRelationship
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Wealth
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World
import kotlin.math.max
import kotlin.math.min

/**
 * The vale's bookkeeper: one system through which every coin changes hands.
 *
 * The rules it keeps are plain. Money is never made or destroyed by a transfer — every coin
 * has a source and a destination. A wage is paid by whoever the paper says employs the
 * worker, out of their business's till first and their own pocket second, and a till that
 * cannot raise the wage falls into arrears rather than minting coin. Rent moves from the
 * tenant's household to the landlord once a day, however time jumps. Wants that cannot be
 * paid for are refused and counted, never wished into affordability.
 *
 * One deliberate exception is written down here rather than hidden: a worker with no
 * employer and no till — the fisher at the river bank, the miner at the dig — earns their
 * wage from the work itself. That is primary production, the field becoming coin, and it is
 * the only place the vale's money supply grows.
 *
 * There is no second clock: the books are kept in [tick], called from the world's own slow
 * tick, day-keyed and bounded, so waiting, sleeping and years-away catch-up all settle the
 * same day once.
 */
class EconomySystem(private val w: World) {
    private val save get() = w.save

    // Counters for the diagnostics report.
    var wagesPaid = 0
        private set
    var wagesInArrears = 0
        private set
    var rentCollected = 0
        private set
    var rentArrears = 0
        private set
    var transfers = 0
        private set
    var unaffordablePurchases = 0
        private set
    var livelihoodPaid = 0
        private set
    var salesTaken = 0
        private set

    /** The last day the daily books were kept; transient, so a fresh session re-opens them. */
    private var lastBookDay = -1

    // ------------------------------------------------------------ binding

    /**
     * Stakes the tills and reads the rents, once the properties exist. A business with a
     * live record gains its ledger the first time it is seen — the operator putting up half
     * their loose coin (capped) as the till's float, a transfer and never a minting — and a
     * property whose pack now asks rent gains it, unless the world's own paper already says
     * otherwise.
     */
    fun bind() {
        for (d in w.content.properties.values) {
            if (d.building.isEmpty()) continue
            val p = w.properties.propertyOf(d.building) ?: continue
            if (d.rent > 0 && p.rentPerDay == 0) p.rentPerDay = d.rent
            if (d.price > 0 && p.price == 0) p.price = d.price
        }
        for (r in save.propertyRels) {
            if (r.kind == PropRels.BUSINESS && r.isActive()) {
                val p = save.properties.firstOrNull { it.id == r.propertyId } ?: continue
                ensureLedger(p.buildingId, r.partyId)
            }
        }
    }

    // ------------------------------------------------------------ ledgers

    /** The till at a property, when one is kept there. */
    fun ledgerOf(propertyId: String): BusinessSave? = save.businesses[propertyId]

    /** The till at a building, when its property keeps one. */
    fun ledgerForBuilding(buildingId: String): BusinessSave? =
        w.properties.propertyOf(buildingId)?.let { save.businesses[it.id] }

    /** The till a person runs, wherever it stands. */
    fun ledgerOperatedBy(operatorId: String): BusinessSave? =
        save.businesses.values.firstOrNull { it.operatorId == operatorId }

    /** The till of the business [n] themselves run, when they run one. */
    private fun ownLedger(n: Npc): BusinessSave? = ledgerOperatedBy(n.def.id)

    /**
     * The till at a building, created the first time the world sees the trade there. The
     * operator stakes the float from their own purse; with no operator there is no till.
     */
    private fun ensureLedger(buildingId: String, operatorId: String): BusinessSave? {
        val prop = w.properties.propertyOf(buildingId) ?: return null
        save.businesses[prop.id]?.let {
            // A trade that has changed hands takes its till with it, float and all:
            // the paper names the keeper, and no new coin is staked or returned.
            if (it.operatorId != operatorId) it.operatorId = operatorId
            return it
        }
        val ledger = BusinessSave(id = prop.id, operatorId = operatorId)
        save.businesses[prop.id] = ledger
        if (operatorId == PLAYER_ID) {
            // The player's trade is floated from their own purse, like anyone's.
            val stake = min(w.p.gold / 2, STAKE_CAP)
            if (stake > 0) {
                w.p.gold -= stake
                ledger.reserves += stake
                playerHistory("stake", -stake, "", "put up the float for the ${prop.name.ifEmpty { prop.buildingId }}")
            }
        } else w.npcById[operatorId]?.let { op ->
            val stake = min(op.s.gold / 2, STAKE_CAP)
            if (stake > 0) {
                op.s.gold -= stake
                ledger.reserves += stake
                history(op, "stake", -stake, "", "put up the float for the ${prop.name.ifEmpty { prop.buildingId }}")
            }
        }
        return ledger
    }

    // ------------------------------------------------------------ wages

    /**
     * Settles a wage of [coins] whole gold earned by hours already worked. The accrual that
     * produced it is hours-based and monotonic, so a settlement happens exactly once per
     * coin however many simulation tiers or time jumps delivered it.
     */
    fun payWage(n: Npc, coins: Int) {
        if (coins <= 0) return
        // The little ones are kept from the wage lists at the accrual too; this is the second gate.
        if (LifeStages.isDependant(LifeStages.stageOf(n.def.age))) return
        val received = settle(n, coins)
        if (received <= 0) return
        contribute(n, received)
    }

    /** Routes a wage to its payer and the worker's pocket. Returns what actually arrived. */
    private fun settle(n: Npc, coins: Int): Int {
        val employment = employmentOf(n)
        if (employment != null && employment.principalId.isNotEmpty()) {
            return payFromEmployer(employment, n, coins)
        }
        ownLedger(n)?.let { return payFromTill(it, n, coins, employerId = n.def.id) }
        return livelihood(n, coins)
    }

    /** The worker's live employment record, at their workplace first. */
    private fun employmentOf(n: Npc): PropertyRelationship? {
        val rels = save.propertyRels.filter {
            it.kind == PropRels.EMPLOYMENT && it.partyId == n.def.id && it.isActive()
        }
        if (rels.isEmpty()) return null
        val ref = n.def.workplace.ifEmpty { n.prof.workplace }
        val building = if (ref.isEmpty()) null else w.content.buildings[ref] ?: w.content.buildingFor(ref)
        val prop = building?.let { w.properties.propertyOf(it.id) }
        return rels.firstOrNull { prop != null && it.propertyId == prop.id } ?: rels.first()
    }

    /**
     * The employer pays: their till at the workplace first, their own pocket second, and a
     * shortfall becomes arrears owed to the payroll — never coin from nowhere.
     */
    private fun payFromEmployer(employment: PropertyRelationship, worker: Npc, coins: Int): Int {
        ledgerOf(employment.propertyId)?.let { return payFromTill(it, worker, coins, employment.principalId) }
        val employer = if (employment.principalId == PLAYER_ID) null else w.npcById[employment.principalId]
        if (employment.principalId == PLAYER_ID || employer != null) {
            val purse = if (employment.principalId == PLAYER_ID) w.p.gold else employer!!.s.gold
            val pay = min(purse, coins)
            if (employment.principalId == PLAYER_ID) w.p.gold -= pay else employer!!.s.gold -= pay
            worker.s.gold += pay
            worker.s.lastPaidDay = Calendar.day(save.minutes)
            if (pay > 0) {
                transfers++
                history(worker, "wage", pay, employment.principalId, "paid by their employer")
                historyNote(employment.principalId, "wage", -pay, worker.def.id, "wages owed to ${worker.def.name}")
            }
            if (pay < coins) {
                wagesInArrears++
                history(worker, "arrears", coins - pay, "", "their employer could not raise the rest")
            } else wagesPaid++
            return pay
        }
        // An employer the world no longer knows: the work still pays, as livelihood.
        return livelihood(worker, coins)
    }

    /**
     * A till pays its payroll: reserves first, the operator's pocket second (never for their
     * own wage — a proprietor paying themselves from their own trousers moves nothing), and
     * the rest is owed until the reserves recover.
     */
    private fun payFromTill(ledger: BusinessSave, worker: Npc, coins: Int, employerId: String): Int {
        val fromReserves = min(ledger.reserves, coins)
        ledger.reserves -= fromReserves
        if (fromReserves > 0) ledger.expenses += fromReserves
        var raised = fromReserves
        val employer = w.npcById[employerId]
        if (raised < coins && employer != null && employer.def.id != worker.def.id) {
            val fromPocket = min(employer.s.gold, coins - raised)
            employer.s.gold -= fromPocket
            raised += fromPocket
        }
        val short = coins - raised
        if (short > 0) {
            ledger.wagesOwed += short
            wagesInArrears++
        } else wagesPaid++
        ledger.wagesPaid += raised
        worker.s.gold += raised
        worker.s.lastPaidDay = Calendar.day(save.minutes)
        history(worker, if (short > 0) "arrears" else "wage", raised, employerId, "from the till at ${ledger.id}")
        return raised
    }

    /** The written-down exception: work at a spot feeds its worker. The field becomes coin. */
    private fun livelihood(n: Npc, coins: Int): Int {
        n.s.gold += coins
        n.s.lastPaidDay = Calendar.day(save.minutes)
        livelihoodPaid++
        history(n, "livelihood", coins, "", "the work at ${n.workName.ifEmpty { n.prof.name }} provides")
        return coins
    }

    // ------------------------------------------------------------ households

    /**
     * An earner provides for the roof over them: a steady share of every wage into the
     * household purse, read from their generosity and nothing else. The dependants pay
     * nothing, and a purse already full asks nothing.
     */
    private fun contribute(n: Npc, received: Int) {
        if (received <= 0) return
        val hh = w.households.of(n.def.id) ?: return
        if (hh.purse >= PURSE_COMFORT) return
        val share = (received * (0.25f + n.trait("generosity") * 0.25f)).toInt()
        if (share <= 0) return
        val move = min(share, received)
        n.s.gold -= move
        hh.purse += move
        history(n, "household", -move, "", "provided for the household")
    }

    // ------------------------------------------------------------ purchases

    /**
     * A villager at the counter: coin to the shop's till (or its keeper's pocket) and
     * staples home to the larder. A want a purse cannot pay for is refused and counted.
     */
    fun villagerShopping(n: Npc, price: Int = SHOP_PRICE): Boolean {
        if (n.s.gold < price) {
            unaffordablePurchases++
            return false
        }
        val shop = w.shop ?: return false
        val operator = w.properties.businessOf(shop.id)
        if (operator == n.def.id) return false // the keeper does not shop at their own counter
        val ledger = ledgerForBuilding(shop.id)
        when {
            ledger != null -> {
                ledger.reserves += price
                ledger.revenue += price
                salesTaken++
            }
            operator != null && operator != PLAYER_ID -> {
                w.npcById[operator]?.let { it.s.gold += price }
            }
            else -> return false // no keeper, no sale: the coin stays where it was
        }
        n.s.gold -= price
        transfers++
        history(n, "purchase", -price, operator ?: "", "bought staples at the ${shop.name}")
        w.households.of(n.def.id)?.let { hh -> hh.food = (hh.food + 8f).coerceIn(0f, 100f) }
        return true
    }

    /**
     * A supper and a bed at the inn: the keeper's till when they keep one, their pocket when
     * they do not, and nothing charged when no keeper exists to be paid.
     */
    fun payForMeal(n: Npc, keeper: Npc?, price: Int = MEAL_PRICE): Boolean {
        if (keeper == null) return false
        if (n.s.gold < price) {
            unaffordablePurchases++
            return false
        }
        if (keeper.def.id == n.def.id) return false
        val ledger = ledgerOperatedBy(keeper.def.id)
        if (ledger != null) {
            ledger.reserves += price
            ledger.revenue += price
            salesTaken++
        } else {
            keeper.s.gold += price
        }
        n.s.gold -= price
        transfers++
        history(n, "purchase", -price, keeper.def.id, "ate at the ${keeper.workName.ifEmpty { "inn" }}")
        return true
    }

    /** A player's purchase reaches the trader's till when they keep one, their pocket otherwise. */
    fun saleTo(n: Npc, price: Int) {
        val ref = n.def.workplace.ifEmpty { n.prof.workplace }
        val building = if (ref.isEmpty()) null else w.content.buildings[ref] ?: w.content.buildingFor(ref)
        val ledger = building?.let { ledgerForBuilding(it.id) }
        if (ledger != null && ledger.operatorId == n.def.id) {
            ledger.reserves += price
            ledger.revenue += price
            salesTaken++
            return
        }
        n.s.gold += price
    }

    /**
     * Coin from one hand to another — the player included. Nothing is made, nothing
     * destroyed: the move happens whole or not at all.
     */
    fun transfer(fromId: String, toId: String, amount: Int, note: String = ""): Boolean {
        if (amount <= 0 || fromId == toId) return false
        val from = goldOf(fromId) ?: return false
        if (goldOf(toId) == null) return false
        if (from < amount) {
            unaffordablePurchases++
            return false
        }
        moveGold(fromId, -amount)
        moveGold(toId, amount)
        transfers++
        historyNote(fromId, "transfer", -amount, toId, note)
        historyNote(toId, "transfer", amount, fromId, note)
        return true
    }

    /** A person's loose coin by id — a villager's or the player's. */
    private fun goldOf(id: String): Int? = when (id) {
        PLAYER_ID -> w.p.gold
        else -> w.npcById[id]?.s?.gold
    }

    private fun moveGold(id: String, delta: Int) {
        if (id == PLAYER_ID) w.p.gold += delta else w.npcById[id]?.let { it.s.gold += delta }
    }

    private fun historyNote(id: String, kind: String, amount: Int, other: String, note: String) {
        if (id == PLAYER_ID) {
            playerHistory(kind, amount, other, note)
            return
        }
        val n = w.npcById[id] ?: return
        history(n, kind, amount, other, note)
    }

    /** One line in the player's own book of accounts, trimmed from the front like any other. */
    private fun playerHistory(kind: String, amount: Int, other: String, note: String) {
        w.p.finHistory += FinanceEvent(save.minutes, kind, amount, other, note)
        while (w.p.finHistory.size > MAX_FIN_HISTORY) w.p.finHistory.removeAt(0)
    }

    // ------------------------------------------------------------ the daily books

    /**
     * The once-a-day books: rents, arrears repaid, staples bought, savings set aside. Called
     * from the world's slow tick, so it runs about once a second and keeps itself to one
     * pass per day; a gap of days (a long wait, a fast sleep) is settled in one bounded
     * catch-up, never day by day and never twice.
     */
    fun tick(day: Int, hour: Float) {
        if (hour < 8f) return // the books open at eight
        if (lastBookDay < 0) lastBookDay = day - 1
        if (day <= lastBookDay) return
        val days = (day - lastBookDay).coerceAtMost(CATCH_UP_DAYS)
        lastBookDay = day
        collectRents(days)
        repayArrears()
        buyStaples(day)
        sweepSavings()
    }

    private fun collectRents(days: Int) {
        for (r in save.propertyRels.toList()) {
            if (!r.isActive()) continue
            val prop = save.properties.firstOrNull { it.id == r.propertyId } ?: continue
            val due = prop.rentPerDay * days
            if (due <= 0) continue
            when (r.kind) {
                PropRels.TENANCY -> collectHouseholdRent(r, prop, due)
                PropRels.LEASE -> collectLeaseRent(r, prop, due)
            }
        }
    }

    /** A tenant's roof: the household purse first, the tenant's pocket second, the rest is debt. */
    private fun collectHouseholdRent(rel: PropertyRelationship, prop: app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertySave, due: Int) {
        if (rel.partyId == PLAYER_ID) {
            collectPlayerRent(prop, due)
            return
        }
        val tenant = w.npcById[rel.partyId] ?: return
        val hh = w.households.of(rel.partyId)
        var raised = 0
        if (hh != null && hh.purse > 0) {
            val fromPurse = min(hh.purse, due)
            hh.purse -= fromPurse
            raised += fromPurse
        }
        if (raised < due && tenant.s.gold > 0) {
            val fromPocket = min(tenant.s.gold, due - raised)
            tenant.s.gold -= fromPocket
            raised += fromPocket
        }
        if (raised > 0) {
            payLandlord(prop, raised)
            rentCollected += raised
            history(tenant, "rent", -raised, landlordIdOf(prop), "the roof over them")
        }
        val short = due - raised
        if (short > 0) {
            tenant.s.debt += short
            rentArrears += short
            hh?.let { w.households.note(it, "The rent fell behind by $short coin.") }
        }
    }

    /** The player's roof: loose coin first, set-aside savings second, the rest becomes debt — like anyone's. */
    private fun collectPlayerRent(prop: app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertySave, due: Int) {
        var raised = 0
        if (w.p.gold > 0) {
            val fromPocket = min(w.p.gold, due)
            w.p.gold -= fromPocket
            raised += fromPocket
        }
        if (raised < due && w.p.savings > 0) {
            val fromSavings = min(w.p.savings, due - raised)
            w.p.savings -= fromSavings
            raised += fromSavings
        }
        if (raised > 0) {
            payLandlord(prop, raised)
            rentCollected += raised
            playerHistory("rent", -raised, landlordIdOf(prop), "the roof over you")
        }
        val short = due - raised
        if (short > 0) {
            w.p.debt += short
            rentArrears += short
        }
    }

    /** A lease: the trade's till pays its rent, and a short till owes the landlord. */
    private fun collectLeaseRent(rel: PropertyRelationship, prop: app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertySave, due: Int) {
        val ledger = ledgerOf(prop.id) ?: return
        if (rel.partyId != ledger.operatorId) return // the paper and the till tell different stories
        val pay = min(ledger.reserves, due)
        if (pay > 0) {
            ledger.reserves -= pay
            ledger.expenses += pay
            payLandlord(prop, pay)
            rentCollected += pay
        }
        val short = due - pay
        if (short > 0) {
            ledger.rentOwed += short
            rentArrears += short
        }
    }

    private fun landlordIdOf(prop: app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertySave): String =
        save.propertyRels.firstOrNull {
            it.kind == PropRels.OWNERSHIP && it.propertyId == prop.id && it.isActive()
        }?.partyId ?: ""

    /** The landlord is paid what was raised: in their pocket, the player's, or the common coffer when no one holds the paper. */
    private fun payLandlord(prop: app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertySave, amount: Int) {
        val landlord = landlordIdOf(prop)
        if (amount <= 0) return
        when (landlord) {
            PLAYER_ID -> w.p.gold += amount
            "" -> w.settlements.settlementOfBuilding(prop.buildingId)?.let { it.coffer += amount }
            else -> w.npcById[landlord]?.let { it.s.gold += amount }
        }
    }

    /** A till that owed wages or rent and has recovered pays what it owes, to its payroll. */
    private fun repayArrears() {
        for (ledger in save.businesses.values.toList()) {
            if (ledger.wagesOwed <= 0 && ledger.rentOwed <= 0) continue
            val spare = ledger.reserves - RESERVE_CUSHION
            if (spare <= 0) continue
            // The landlord's arrears first: the roof outlasts the payroll's patience.
            if (ledger.rentOwed > 0) {
                val pay = min(min(ledger.rentOwed, spare), ledger.reserves)
                val prop = save.properties.firstOrNull { it.id == ledger.id } ?: continue
                ledger.reserves -= pay
                ledger.rentOwed -= pay
                payLandlord(prop, pay)
                rentCollected += pay
            }
            if (ledger.wagesOwed > 0 && ledger.reserves > RESERVE_CUSHION) {
                val pay = min(ledger.wagesOwed, ledger.reserves - RESERVE_CUSHION)
                val payroll = save.propertyRels.filter {
                    it.kind == PropRels.EMPLOYMENT && it.propertyId == ledger.id && it.isActive()
                }.mapNotNull { w.npcById[it.partyId] }
                val recipients = if (payroll.isEmpty()) listOfNotNull(w.npcById[ledger.operatorId]) else payroll
                val each = if (recipients.isEmpty()) 0 else pay / recipients.size
                var moved = 0
                for (r in recipients) {
                    r.s.gold += each
                    moved += each
                    history(r, "arrears", each, "", "the till at ${ledger.id} made its arrears good")
                }
                if (moved > 0) {
                    ledger.reserves -= moved
                    ledger.wagesOwed -= moved
                    ledger.wagesPaid += moved
                }
            }
        }
    }

    /** A thin larder and a purse with coin: the staples are bought once a day, till to till. */
    private fun buyStaples(day: Int) {
        val shop = w.shop ?: return
        val ledger = ledgerForBuilding(shop.id) ?: return
        for (hh in save.households) {
            if (hh.members.isEmpty() || hh.food >= LARDER_LOW) continue
            if (hh.lastShopDay == day) continue
            if (hh.purse < STAPLES_PRICE) {
                unaffordablePurchases++
                continue
            }
            hh.purse -= STAPLES_PRICE
            hh.lastShopDay = day
            ledger.reserves += STAPLES_PRICE
            ledger.revenue += STAPLES_PRICE
            salesTaken++
            hh.food = (hh.food + 30f).coerceIn(0f, 100f)
            w.households.note(hh, "The purse bought staples for the larder.")
        }
    }

    /** Coin beyond a working cushion drifts into savings, where it still counts but cannot leak. */
    private fun sweepSavings() {
        for (n in w.npcs) {
            val cushion = if (n.prof.trader) TRADER_CUSHION else SAVINGS_CUSHION
            val spare = n.s.gold - cushion
            if (spare <= 0) continue
            val move = (spare / 2).coerceAtLeast(1)
            n.s.gold -= move
            n.s.savings += move
            history(n, "savings", -move, "", "set aside")
        }
    }

    // ------------------------------------------------------------ readings

    /** Everything a person can call on, less what they owe. Derived, never stored. */
    fun meansOf(n: Npc): Int {
        val hh = w.households.of(n.def.id)
        val share = hh?.let { it.purse / max(1, hh.members.size) } ?: 0
        val till = ownLedger(n)?.reserves ?: 0
        return n.s.gold + n.s.savings + share + till - n.s.debt
    }

    /** What a full day of their trade brings in. */
    fun incomeOf(n: Npc): Int = n.prof.wage * (n.prof.workEnd - n.prof.workStart).coerceAtLeast(1)

    /** What a day costs them: roof, bread by their trade's reckoning, and mouths that depend on them. */
    fun obligationsOf(n: Npc): Int {
        var rent = 0
        for (r in save.propertyRels) {
            if (!r.isActive() || r.partyId != n.def.id) continue
            if (r.kind != PropRels.TENANCY && r.kind != PropRels.LEASE) continue
            save.properties.firstOrNull { it.id == r.propertyId }?.let { rent += it.rentPerDay }
        }
        val hh = w.households.of(n.def.id)
        val dependants = hh?.members?.count {
            it != n.def.id && LifeStages.isDependant(LifeStages.stageOf(w.npcById[it]?.def?.age ?: 30))
        } ?: 0
        return rent + n.prof.livingCost + dependants * DEPENDANT_COST
    }

    /** How well-off someone is, from their means and their margin — a reading, not a record. */
    fun classify(n: Npc): String = Wealth.classify(meansOf(n), incomeOf(n) - obligationsOf(n))

    /** What they can pay today: loose coin plus savings, never an overdraft. */
    fun canAfford(n: Npc, price: Int): Boolean = n.s.gold + n.s.savings >= price

    /** Everything the player can call on, less what they owe: purse, savings, their own till. Derived, never stored. */
    fun meansOfPlayer(): Int =
        w.p.gold + w.p.savings + (ledgerOperatedBy(PLAYER_ID)?.reserves ?: 0) - w.p.debt

    /** What the player can pay today: loose coin plus savings, never an overdraft. */
    fun canAffordPlayer(price: Int): Boolean = w.p.gold + w.p.savings >= price

    /** Whether the player can carry a rent: means enough to cover a month of it. */
    fun canAffordRentPlayer(rentPerDay: Int): Boolean = meansOfPlayer() >= rentPerDay * 30

    /** What the player's own paper asks per day — tenancies and leases together. */
    fun playerObligations(): Int {
        var rent = 0
        for (r in save.propertyRels) {
            if (!r.isActive() || r.partyId != PLAYER_ID) continue
            if (r.kind != PropRels.TENANCY && r.kind != PropRels.LEASE) continue
            save.properties.firstOrNull { it.id == r.propertyId }?.let { rent += it.rentPerDay }
        }
        return rent
    }

    /**
     * Whether they can carry a rent: a steady income with a third to spare, or means enough
     * to cover a month. The margin is the point — a wage that barely covers the roof is not
     * an affordable one.
     */
    fun canAffordRent(n: Npc, rentPerDay: Int): Boolean =
        incomeOf(n) >= rentPerDay * 3 || meansOf(n) >= rentPerDay * 30

    /** What taking a lease would ask up front: half a month's rent as the deposit. */
    fun depositFor(buildingId: String): Int {
        val rent = w.properties.propertyOf(buildingId)?.rentPerDay ?: return 0
        return rent * DEPOSIT_DAYS
    }

    // ------------------------------------------------------------ the player's roof

    /** Whether a building could be a roof at all: only homes, inns and mixed uses are housing. */
    fun isHousing(buildingId: String): Boolean {
        val def = w.content.buildings[w.content.buildings.canonical(buildingId)] ?: return false
        return def.classification in SettlementSystem.HOUSING_CLASSIFICATIONS
    }

    /**
     * What the freehold of a building would ask, so far as anyone can say: the paper's own
     * price first, then a month of its rent, then the usual sum for a roof in the vale.
     * Null when the building is no housing at all.
     */
    fun askingPrice(buildingId: String): Int? {
        val bid = w.content.buildings.canonical(buildingId)
        if (!isHousing(bid)) return null
        w.properties.propertyOf(bid)?.let { p ->
            if (p.price > 0) return p.price
            if (p.rentPerDay > 0) return p.rentPerDay * PRICE_RENT_DAYS
        }
        val rent = w.content.properties.values
            .firstOrNull { it.building == bid }?.rent?.takeIf { it > 0 } ?: 0
        return if (rent > 0) rent * PRICE_RENT_DAYS else HOUSE_PRICE
    }

    /**
     * The player buys a home outright. Only housing is sold; a home an active owner holds is
     * not for sale — there is no bargaining in this world yet — and one the player already
     * holds is simply theirs already. The price is paid whole, gold then savings, to the
     * owner or, when no one holds the paper, to the settlement's common coffer: coin is
     * never minted for a deed. Buying the roof one rents ends the tenancy, its deposit
     * returned as far as the former landlord can pay it. Residence is separate paper:
     * buying does not move anyone in.
     */
    fun buyHome(buildingId: String): String {
        val bid = w.content.buildings.canonical(buildingId)
        if (!isHousing(bid)) return "That is no home to buy."
        val prop = w.properties.ensureProperty(bid) ?: return "That is no home to buy."
        val what = propName(prop)
        when (val owner = w.properties.ownerOf(bid)) {
            PLAYER_ID -> return "You hold the freehold of the $what already."
            null -> {}
            else -> return "${w.npcById[owner]?.def?.name ?: owner} is not selling the $what."
        }
        val price = askingPrice(bid) ?: return "That is no home to buy."
        if (!canAffordPlayer(price)) {
            unaffordablePurchases++
            return "You cannot raise $price coin for the $what."
        }
        // A tenancy on this very roof ends with the purchase, its deposit back from the seller.
        val wasTenant = w.properties.homesOf(PLAYER_ID).any { it.buildingId == bid }
        spendPlayer(price)
        val seller = w.properties.ownerOf(bid)
        if (seller == null) {
            w.settlements.settlementOfBuilding(bid)?.let { it.coffer += price }
        } else {
            moveGold(seller, price)
            historyNote(seller, "sale", price, PLAYER_ID, "sold the freehold of the $what")
        }
        playerHistory("house", -price, seller ?: "", "bought the freehold of the $what")
        transfers++
        if (wasTenant) {
            // The deposit is returned while the seller is still the landlord of record.
            w.properties.endTenancy(bid, PLAYER_ID, save.minutes)
            returnDeposit(bid)
        }
        w.properties.transferOwnership(bid, PLAYER_ID, save.minutes, "sold for $price coin")
        return "You buy the freehold of the $what for $price coin."
    }

    /**
     * The player takes a tenancy: the deposit — six days of the rent — paid to the landlord
     * or, when no one holds the paper, to the common coffer, and the paper written in their
     * name. Renting a roof they own is refused; taking the same tenancy twice changes
     * nothing. Residence is separate paper: renting does not move anyone in.
     */
    fun rentHome(buildingId: String): String {
        val bid = w.content.buildings.canonical(buildingId)
        if (!isHousing(bid)) return "That is no home to rent."
        val prop = w.properties.ensureProperty(bid) ?: return "That is no home to rent."
        val what = propName(prop)
        if (w.properties.ownerOf(bid) == PLAYER_ID) return "The $what is yours already."
        if (w.properties.homesOf(PLAYER_ID).any { it.buildingId == bid }) return "You rent the $what already."
        val rent = prop.rentPerDay
        if (rent <= 0) return "No one is letting rooms at the $what."
        val deposit = depositFor(bid)
        if (!canAffordPlayer(deposit)) {
            unaffordablePurchases++
            return "You cannot raise the $deposit coin deposit for the $what."
        }
        spendPlayer(deposit)
        val landlord = w.properties.ownerOf(bid)
        if (landlord == null) {
            w.settlements.settlementOfBuilding(bid)?.let { it.coffer += deposit }
        } else {
            moveGold(landlord, deposit)
            historyNote(landlord, "deposit", deposit, PLAYER_ID, "took the deposit for the $what")
        }
        playerHistory("deposit", -deposit, landlord ?: "", "put down the deposit for the $what")
        transfers++
        w.properties.startTenancy(bid, PLAYER_ID, save.minutes, "deposit $deposit coin")
        return "You take the tenancy of the $what — $rent coin the day, $deposit down."
    }

    /**
     * The player gives up a roof: their residence ended, their tenancy with it if they held
     * one, the deposit returned as far as the landlord can pay it. Ownership is not touched —
     * selling a freehold is not for this milestone.
     */
    fun leaveHome(buildingId: String): String {
        val bid = w.content.buildings.canonical(buildingId)
        val prop = w.properties.propertyOf(bid) ?: return "You keep no home there."
        val what = propName(prop)
        val hadTenancy = w.properties.homesOf(PLAYER_ID).any { it.buildingId == bid }
        val wasResident = w.properties.residentsOf(bid, includeHousehold = false).contains(PLAYER_ID)
        if (!hadTenancy && !wasResident) return "You keep no home there."
        if (wasResident) w.properties.removeResident(bid, PLAYER_ID, save.minutes)
        if (hadTenancy) {
            w.properties.endTenancy(bid, PLAYER_ID, save.minutes)
            returnDeposit(bid)
        }
        return "You give up the $what."
    }

    /** The player's purse pays, gold first, savings second. Returns what was actually taken. */
    private fun spendPlayer(price: Int): Int {
        var left = price
        if (left > 0 && w.p.gold > 0) {
            val fromGold = min(w.p.gold, left)
            w.p.gold -= fromGold
            left -= fromGold
        }
        if (left > 0 && w.p.savings > 0) {
            val fromSavings = min(w.p.savings, left)
            w.p.savings -= fromSavings
            left -= fromSavings
        }
        return price - left
    }

    /**
     * A player's deposit comes back when a tenancy ends: from the landlord's pocket as far
     * as it reaches, or from the common coffer when the paper belongs to no one. A landlord
     * short of the sum returns what they have — the world mints no deposit debts.
     */
    private fun returnDeposit(buildingId: String) {
        val deposit = depositFor(buildingId)
        if (deposit <= 0) return
        val prop = w.properties.propertyOf(buildingId) ?: return
        val what = propName(prop)
        when (val landlord = landlordIdOf(prop)) {
            PLAYER_ID -> {} // one's own paper owes itself nothing
            "" -> w.settlements.settlementOfBuilding(buildingId)?.let {
                val back = min(deposit, it.coffer)
                if (back > 0) {
                    it.coffer -= back
                    moveGold(PLAYER_ID, back)
                    playerHistory("deposit", back, "", "the deposit came back with the $what")
                }
            }
            else -> {
                val back = min(deposit, goldOf(landlord) ?: 0)
                if (back > 0) {
                    moveGold(landlord, -back)
                    moveGold(PLAYER_ID, back)
                    historyNote(landlord, "deposit", -back, PLAYER_ID, "returned the deposit for the $what")
                    playerHistory("deposit", back, landlord, "the deposit came back with the $what")
                }
            }
        }
    }

    /** A property's name, falling to its building's and then to its id. */
    private fun propName(prop: app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertySave): String =
        prop.name.ifEmpty { w.content.buildings[prop.buildingId]?.name ?: prop.buildingId }

    /** One line in a person's book of accounts, trimmed from the front. */
    private fun history(n: Npc, kind: String, amount: Int, other: String, note: String) {
        n.s.finHistory += FinanceEvent(save.minutes, kind, amount, other, note)
        while (n.s.finHistory.size > MAX_FIN_HISTORY) n.s.finHistory.removeAt(0)
    }

    companion object {
        /** The float an operator stakes their till with: half their loose coin, capped. */
        const val STAKE_CAP = 50
        /** The books settle at most this many days in one catch-up, however long the wait. */
        const val CATCH_UP_DAYS = 7
        const val SHOP_PRICE = 2
        const val MEAL_PRICE = 3
        const val STAPLES_PRICE = 4
        /** A larder below this sends the purse shopping. */
        const val LARDER_LOW = 35f
        /** A purse fatter than this asks nothing of its earners. */
        const val PURSE_COMFORT = 40
        const val DEPENDANT_COST = 2
        const val SAVINGS_CUSHION = 40
        /** A trader keeps more to hand: their trade is done in loose coin. */
        const val TRADER_CUSHION = 80
        /** A till keeps this much back before it pays off its arrears. */
        const val RESERVE_CUSHION = 10
        const val DEPOSIT_DAYS = 6
        const val MAX_FIN_HISTORY = 12
        /** An undeclared freehold asks a month of its rent. */
        const val PRICE_RENT_DAYS = 30
        /** The usual sum for a roof with no rent and no declared price. */
        const val HOUSE_PRICE = 100
    }
}
