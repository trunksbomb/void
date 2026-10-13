package content.bot.behaviour.setup

import content.area.wilderness.inPvp
import content.bot.behaviour.action.BotAction
import content.bot.behaviour.action.BotCloseInterface
import content.bot.behaviour.action.BotGoTo
import content.bot.behaviour.action.BotGoToNearest
import content.bot.behaviour.action.BotIntEntry
import content.bot.behaviour.action.BotInteractNpc
import content.bot.behaviour.action.BotInteractObject
import content.bot.behaviour.action.BotInterfaceOption
import content.bot.behaviour.condition.BotEquipmentSetup
import content.bot.behaviour.condition.BotInArea
import content.bot.behaviour.condition.BotInterfaceOpen
import content.bot.behaviour.condition.BotInventorySetup
import content.bot.behaviour.condition.BotItem
import content.bot.behaviour.condition.BotVariable
import content.bot.behaviour.condition.Condition
import content.entity.npc.shop.stock.Price
import content.entity.player.bank.bank
import content.entity.player.bank.isNote
import content.entity.player.bank.noted
import content.entity.player.bank.ownsItem
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.equip.equipped
import world.gregs.voidps.engine.entity.character.player.skill.level.Level.hasRequirements
import world.gregs.voidps.engine.entity.character.player.skill.level.Level.hasRequirementsToUse
import world.gregs.voidps.engine.entity.item.Item
import world.gregs.voidps.engine.entity.item.slot
import world.gregs.voidps.engine.inv.Inventory
import world.gregs.voidps.engine.inv.add
import world.gregs.voidps.engine.inv.inventory
import world.gregs.voidps.engine.inv.stack.ItemDependentStack
import world.gregs.voidps.network.login.protocol.visual.update.player.EquipSlot

object DynamicResolvers {

    fun ids() = setOf("withdraw_from_bank", "equip_from_bank", "go_to_area", "buy_from_shop")

    // location, npc
    val shopItems = mutableMapOf<String, MutableList<Pair<String, String>>>()
    val sampleItems = mutableMapOf<String, MutableList<Pair<String, String>>>()

    fun resolver(player: Player, condition: Condition): Resolver? = when (condition) {
        is BotInArea -> Resolver("go_to_area", -1, actions = listOf(BotGoTo(condition.id)))
        is BotEquipmentSetup -> if (player.inPvp) null else resolveEquipment(player, condition.items)
        is BotInventorySetup -> if (player.inPvp) null else resolveInventory(player, condition.items)
        else -> null
    }

    private fun resolveInventory(player: Player, items: List<BotItem>): Resolver? {
        var resolver = withdraw(player, items)
        if (resolver != null) {
            return resolver
        }
        resolver = buyItems(player, items)
        if (resolver != null) {
            return resolver
        }
        resolver = depositItems(player, items)
        if (resolver != null) {
            return resolver
        }
        return null
    }

    private fun buyItems(player: Player, items: List<BotItem>): Resolver? {
        for (entry in items) {
            val amount = entry.min ?: 1
            if (entry.ids.any { id -> player.inventory.contains(id, amount) }) {
                continue
            }
            for (id in entry.ids) {
                for ((location, npc) in sampleItems[id] ?: emptyList()) {
                    val actions = mutableListOf<BotAction>()
                    actions.add(BotGoTo(location))
                    actions.add(BotInteractNpc("Trade", npc, success = BotInterfaceOpen("shop")))
                    var remaining = amount
                    while (remaining > 0) {
                        val amount = when {
                            remaining >= 50 -> 50
                            remaining >= 10 -> 10
                            remaining >= 5 -> 5
                            else -> 1
                        }
                        actions.add(BotInterfaceOption("Take-$amount", "shop:sample:$id"))
                        remaining -= amount
                    }
                    actions.add(BotCloseInterface)
                    val spaces = if (player.inventory.stackable(id)) 1 else amount
                    return Resolver(
                        id = "take_from_shop",
                        weight = 20,
                        setup = listOf(BotInventorySetup(listOf(BotItem(setOf("empty"), min = spaces)))),
                        actions = actions,
                        produces = setOf("item:$id"),
                    )
                }
                for ((location, npc) in shopItems[id] ?: continue) {
                    val actions = mutableListOf<BotAction>()
                    val price = Price.of(id)
                    if (!player.ownsItem("coins", price * amount)) {
                        continue
                    }
                    actions.add(BotGoTo(location))
                    actions.add(BotInteractNpc("Trade", npc, success = BotInterfaceOpen("shop")))
                    var remaining = amount
                    while (remaining > 0) {
                        val amount = when {
                            remaining >= 500 -> 500
                            remaining >= 50 -> 50
                            remaining >= 10 -> 10
                            remaining >= 5 -> 5
                            else -> 1
                        }
                        actions.add(BotInterfaceOption("Buy-$amount", "shop:stock:$id"))
                        remaining -= amount
                    }
                    actions.add(BotCloseInterface)
                    val spaces = if (player.inventory.stackable(id)) 1 else amount
                    return Resolver(
                        id = "buy_from_shop",
                        weight = 25,
                        setup = listOf(BotInventorySetup(listOf(BotItem(setOf("coins"), min = price * amount), BotItem(setOf("empty"), min = spaces)))),
                        actions = actions,
                        produces = setOf("item:$id"),
                    )
                }
            }
        }
        return null
    }

    internal fun valid(player: Player, item: Item, entry: BotItem): Boolean {
        if (item.isEmpty()) {
            return false
        }
        if (!entry.ids.contains(item.id)) {
            return false
        }
        if (entry.usable && !player.hasRequirementsToUse(item)) {
            return false
        }
        if (entry.equippable && !player.hasRequirements(item)) {
            return false
        }
        return true
    }

    private fun resolveEquipment(player: Player, equipment: Map<EquipSlot, BotItem>): Resolver? {
        val equipment = equipment.toMutableMap()
        var resolver = unequipItems(player, equipment)
        if (resolver != null) {
            return resolver
        }
        resolver = equipItems(player, equipment)
        if (resolver != null) {
            return resolver
        }
        resolver = resolveInventory(player, equipment.values.toList())
        if (resolver != null) {
            return resolver
        }
        return null
    }

    private fun withdraw(player: Player, items: List<BotItem>): Resolver? {
        // Deposit-all replaces the whole inventory. Plan that replacement before moving
        // anything, so an available subset cannot starve acquisition of missing items.
        val available = mutableMapOf<String, Long>()
        for (item in player.bank.items.asSequence() + player.inventory.items.asSequence()) {
            if (item.isEmpty() || item.def.getOrNull<Int>("unbankable")?.let { it != 0 } == true) {
                continue
            }
            val bankItem = if (item.isNote) item.noted ?: continue else item
            available[bankItem.id] = (available[bankItem.id] ?: 0L) + bankItem.amount
        }
        val planned = Inventory(Array(player.inventory.size) { Item.EMPTY }, stackRule = ItemDependentStack)
        val withdrawals = mutableListOf<Triple<BotItem, Item, Item>>()
        val requirements = items.map { entry ->
            if (entry.min == null && entry.max == null) entry.copy(min = 1) else entry
        }
        for (entry in requirements) {
            if (entry.ids.contains("empty") || Condition.contains(player, planned, listOf(entry))) {
                continue
            }
            val amount = entry.min ?: 0
            if (amount <= 0) {
                continue
            }
            var selected = false
            for ((id, count) in available) {
                val bankItem = Item(id)
                val output = when {
                    valid(player, bankItem, entry) -> bankItem
                    bankItem.noted?.let { valid(player, it, entry) } == true -> bankItem.noted!!
                    else -> continue
                }
                val needed = amount - planned.count(output.id)
                if (count < needed || !planned.add(output.id, needed)) {
                    continue
                }
                available[id] = count - needed
                withdrawals.add(Triple(entry.copy(min = needed), bankItem, output))
                selected = true
                break
            }
            if (!selected) {
                return null
            }
        }
        if (withdrawals.isEmpty() || !Condition.contains(player, planned, requirements)) {
            return null
        }
        val actions = mutableListOf<BotAction>(
            BotGoToNearest("bank"),
            BotInteractObject("Use-quickly", "bank_booth*", success = BotInterfaceOpen("bank")),
            BotInterfaceOption("Deposit carried items", "bank:carried", success = BotInventorySetup(listOf(BotItem(setOf("empty"), min = player.inventory.size)))),
        )
        for ((entry, item, output) in withdrawals) {
            actions.add(BotInterfaceOption("Toggle item/note withdrawl", "bank:note_mode", success = BotVariable("bank_notes", output.isNote, false)))
            withdraw(actions, entry, item)
        }
        actions.add(BotCloseInterface)
        return Resolver("withdraw_from_bank", weight = 20, actions = actions)
    }

    private fun depositItems(player: Player, items: List<BotItem>): Resolver? {
        val empty = items.firstOrNull { it.ids.contains("empty") } ?: return null
        val spaces = player.inventory.spaces
        if (empty.min != null && spaces > empty.min) {
            return null
        }
        val actions = mutableListOf(
            BotGoToNearest("bank"),
            BotInteractObject("Use-quickly", "bank_booth*", success = BotInterfaceOpen("bank")),
            BotInterfaceOption("Deposit carried items", "bank:carried", success = BotInventorySetup(listOf(BotItem(setOf("empty"), min = 28)))),
            BotCloseInterface,
        )
        return Resolver("deposit_all_bank", weight = 20, actions = actions)
    }

    private fun equipItems(player: Player, equipment: Map<EquipSlot, BotItem>): Resolver? {
        val actions = mutableListOf<BotAction>()
        val produces = mutableSetOf<String>()
        for (item in player.inventory.items) {
            val slot = item.slot
            if (slot == EquipSlot.None) {
                continue
            }
            val entry = equipment[slot] ?: continue
            if (!entry.ids.contains(item.id)) {
                continue
            }
            actions.add(BotInterfaceOption("Equip", "inventory:inventory:${item.id}", success = BotEquipmentSetup(mapOf(slot to entry))))
            produces.add("equipment:${item.id}")
        }
        if (actions.isNotEmpty()) {
            return Resolver("equip_items", weight = 15, actions = actions, produces = produces)
        }
        return null
    }

    private fun unequipItems(player: Player, equipment: Map<EquipSlot, BotItem>): Resolver? {
        val actions = mutableListOf<BotAction>()
        for ((slot, entry) in equipment) {
            if (!entry.ids.contains("empty")) {
                continue
            }
            val item = player.equipped(slot)
            if (item.isNotEmpty()) {
                actions.add(BotInterfaceOption("Remove", "worn_equipment:${slot.name.lowercase()}_slot:$item"))
            }
        }
        if (actions.isNotEmpty()) {
            return Resolver("unequip_items", weight = 10, actions = actions)
        }
        return null
    }

    private fun withdraw(actions: MutableList<BotAction>, entry: BotItem, item: Item) {
        if (entry.min != null && entry.min > 1) {
            actions.add(BotInterfaceOption("Withdraw-X", "bank:inventory:${item.id}"))
            actions.add(BotIntEntry(entry.min))
        } else {
            actions.add(BotInterfaceOption("Withdraw-1", "bank:inventory:${item.id}"))
        }
    }
}
