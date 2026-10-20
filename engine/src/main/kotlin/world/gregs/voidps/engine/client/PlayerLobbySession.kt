package world.gregs.voidps.engine.client

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import world.gregs.voidps.engine.data.SaveQueue
import world.gregs.voidps.engine.data.Settings
import world.gregs.voidps.engine.data.definition.AccountDefinitions
import world.gregs.voidps.engine.entity.World
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.Players
import world.gregs.voidps.engine.entity.character.player.chat.clan.ClanRank
import world.gregs.voidps.engine.entity.character.player.name
import world.gregs.voidps.engine.entity.character.player.rights
import world.gregs.voidps.network.client.Client
import world.gregs.voidps.network.client.ConnectionQueue
import world.gregs.voidps.network.client.Instruction
import world.gregs.voidps.network.client.instruction.*
import world.gregs.voidps.network.login.LobbySession
import world.gregs.voidps.network.login.protocol.encode.*

/** Account data in the lobby never enters Players or runs world spawn/despawn scripts. */
class PlayerLobbySession(
    private val player: Player,
    private val client: Client,
    private val accounts: AccountDefinitions,
    private val saves: SaveQueue,
    private val queue: ConnectionQueue,
    private val gameContext: CoroutineDispatcher,
) : LobbySession {
    private var dirty = false
    private var closed = false

    fun start() {
        client.loginLobby(player.name, player.rights.ordinal, World.members, Settings["world.id", 16], Settings["world.address", "127.0.0.1"])
        sendWorlds()
        sendSocial()
        client.sendPrivateStatus(listOf("on", "friends", "off").indexOf(player["private_status", "on"]).coerceAtLeast(0))
        client.sendPublicStatus(listOf("on", "friends", "off", "hide").indexOf(player["public_status", "on"]).coerceAtLeast(0), listOf("on", "friends", "off").indexOf(player["trade_status", "on"]).coerceAtLeast(0))
        client.flush()
    }

    private fun sendWorlds() {
        client.sendWorldList(
            Settings["world.id", 16],
            if (World.members) WorldFlag.MEMBERS else 0,
            activity = Settings["world.activity", ""],
            address = Settings["world.address", "127.0.0.1"],
            players = Players.size,
            country = Settings["world.country", 0],
            location = Settings["world.location", "Local"],
        )
    }

    private fun sendSocial() {
        client.sendFriendsList(
            player.friends.mapNotNull { (name, rank) ->
                accounts.getByAccount(name)?.let { account ->
                    val online = Players.findByAccount(name)
                    val visible = online != null &&
                        online["private_status", "on"] != "off" &&
                        (online["private_status", "on"] != "friends" || online.friends.containsKey(player.accountName)) &&
                        !online.ignores.contains(player.accountName)
                    Friend(account.displayName, account.previousName, rank = rank.value, world = if (visible) Settings["world.id", 16] else 0, worldName = Settings["world.name", "World 1"])
                }
            },
        )
        client.sendIgnoreList(player.ignores.mapNotNull { accounts.getByAccount(it)?.let { account -> account.displayName to account.previousName } })
    }

    override suspend fun handle(instruction: Instruction) = withContext(gameContext) {
        queue.await()
        if (closed) return@withContext
        when (instruction) {
            is LobbyWorldListRefresh -> sendWorlds()
            is LobbyOnlineStatus -> {
                val public = listOf("on", "friends", "off", "hide").getOrNull(instruction.public)
                val private = listOf("on", "friends", "off").getOrNull(instruction.private)
                val trade = listOf("on", "friends", "off").getOrNull(instruction.trade)
                if (public != null && private != null && trade != null) {
                    player["public_status"] = public
                    player["private_status"] = private
                    player["trade_status"] = trade
                    dirty = true
                }
            }
            is FriendAdd -> {
                val account = accounts.get(instruction.friendsName)
                if (account != null && account.accountName != player.accountName && player.friends.size < 200 && account.accountName !in player.ignores) {
                    player.friends.putIfAbsent(account.accountName, ClanRank.Friend)
                    dirty = true
                    sendSocial()
                }
            }
            is FriendDelete -> {
                accounts.get(instruction.friendsName)?.let { player.friends.remove(it.accountName) }
                dirty = true
                sendSocial()
            }
            is IgnoreAdd -> {
                val account = accounts.get(instruction.name)
                if (account != null && account.accountName != player.accountName && player.ignores.size < 100 && account.accountName !in player.friends && account.accountName !in player.ignores) {
                    player.ignores.add(account.accountName)
                    dirty = true
                    sendSocial()
                }
            }
            is IgnoreDelete -> {
                accounts.get(instruction.name)?.let { player.ignores.remove(it.accountName) }
                dirty = true
                sendSocial()
            }
            is ChatPrivate, is QuickChatPrivate, is ClanChatJoin -> client.message("Enter a world to use chat.", 0)
            // Play and lobby logout are handled locally by the native client's scripts.
            // Other world instructions must not be dispatched for a lobby account.
            else -> Unit
        }
        client.flush()
    }

    override suspend fun close() = withContext(gameContext) {
        if (closed) return@withContext
        closed = true
        if (dirty) saves.save(player)
    }
}
