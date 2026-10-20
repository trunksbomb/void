package world.gregs.voidps.engine.client

import com.github.michaelbull.logging.InlineLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import world.gregs.voidps.engine.data.AccountManager
import world.gregs.voidps.engine.data.SaveQueue
import world.gregs.voidps.engine.data.Settings
import world.gregs.voidps.engine.data.Storage
import world.gregs.voidps.engine.data.definition.AccountDefinitions
import world.gregs.voidps.engine.entity.World
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.Players
import world.gregs.voidps.engine.entity.character.player.name
import world.gregs.voidps.engine.entity.character.player.rights
import world.gregs.voidps.engine.event.AuditLog
import world.gregs.voidps.network.Response
import world.gregs.voidps.network.client.Client
import world.gregs.voidps.network.client.ConnectionQueue
import world.gregs.voidps.network.client.Instruction
import world.gregs.voidps.network.login.AccountLoader
import world.gregs.voidps.network.login.LobbySession
import world.gregs.voidps.network.login.protocol.encode.login

/**
 * Checks password is valid for a player account before logging in
 * Keeps track of the players online, prevents duplicate login attempts
 */
class PlayerAccountLoader(
    private val queue: ConnectionQueue,
    private val storage: Storage,
    private val accounts: AccountManager,
    private val saveQueue: SaveQueue,
    private val accountDefinitions: AccountDefinitions,
    private val gameContext: CoroutineDispatcher,
) : AccountLoader {
    private val logger = InlineLogger()

    var update: Boolean = false

    override fun used(username: String) = accountDefinitions.get(username) != null

    override fun exists(username: String): Boolean = storage.exists(username)

    override fun password(username: String): String? = accountDefinitions.getByAccount(username)?.passwordHash

    /**
     * @return flow of instructions for the player to be controlled with
     */
    override suspend fun load(client: Client, username: String, passwordHash: String, displayMode: Int): SendChannel<Instruction>? {
        try {
            if (!awaitSave(username)) {
                client.disconnect(Response.COULD_NOT_COMPLETE_LOGIN)
                return null
            }
            if (update) {
                client.disconnect(Response.GAME_UPDATE)
                return null
            }
            val save = storage.load(username)
            if (save != null && banned(save.variables)) {
                client.disconnect(Response.ACCOUNT_DISABLED)
                return null
            }
            var player = save?.toPlayer()
            if (player == null) {
                if (!Settings["development.accountCreation", false]) {
                    client.disconnect(Response.INVALID_CREDENTIALS)
                    return null
                }
                player = accounts.create(username, passwordHash)
            }
            if (player["lobby_first_world", false]) {
                player.clear("lobby_first_world")
                player["new_player"] = true
            }
            logger.info { "Player $username loaded and queued for login." }
            connect(player, client, displayMode)
            return player.instructions
        } catch (e: IllegalStateException) {
            logger.trace(e) { "Error loading player account" }
            client.disconnect(Response.COULD_NOT_COMPLETE_LOGIN)
            return null
        }
    }

    /** Drain world departure before reading storage, then wait for the queued save. */
    private suspend fun awaitSave(username: String): Boolean {
        withContext(gameContext) { queue.await() }
        return withTimeoutOrNull(10_000L) {
            while (saveQueue.saving(username)) {
                saveQueue.run()
                delay(25)
            }
            true
        } ?: false
    }

    override suspend fun loadLobby(client: Client, username: String, passwordHash: String): LobbySession? {
        if (update) {
            client.disconnect(Response.GAME_UPDATE)
            return null
        }
        if (!awaitSave(username)) {
            client.disconnect(Response.COULD_NOT_COMPLETE_LOGIN)
            return null
        }
        val save = withContext(Dispatchers.IO) { storage.load(username) }
        if (save != null && banned(save.variables)) {
            client.disconnect(Response.ACCOUNT_DISABLED)
            return null
        }
        if (save == null && !Settings["development.accountCreation", false]) {
            client.disconnect(Response.INVALID_CREDENTIALS)
            return null
        }
        val player = save?.toPlayer() ?: accounts.create(username, passwordHash)
        if (save == null) {
            player["lobby_first_world"] = true
            withContext(gameContext) {
                queue.await()
                accountDefinitions.add(player)
                saveQueue.save(player)
            }
            if (!awaitSave(username)) {
                client.disconnect(Response.COULD_NOT_COMPLETE_LOGIN)
                return null
            }
        }
        val session = PlayerLobbySession(player, client, accountDefinitions, saveQueue, queue, gameContext)
        client.onDisconnecting { session.close() }
        withContext(gameContext) {
            queue.await()
            session.start()
        }
        return session
    }

    private fun banned(variables: Map<String, Any>): Boolean {
        val until = (variables["banned_until"] as? Number)?.toInt() ?: return false
        return until > (System.currentTimeMillis() / 1000).toInt()
    }

    suspend fun connect(player: Player, client: Client, displayMode: Int = 0, viewport: Boolean = true) {
        accounts.setup(player, client, displayMode, viewport)
        withContext(gameContext) {
            queue.await()
            val existing = Players.findByAccount(player.accountName)
            if (existing != null) {
                logger.warn { "Logging out stale session for ${player.accountName} before login." }
                accounts.logout(existing, safely = false)
                client.disconnect(Response.ACCOUNT_ONLINE)
                return@withContext
            }
            if (!accounts.index(player)) {
                logger.warn { "Error setting up account" }
                client.disconnect(Response.WORLD_FULL)
                return@withContext
            }
            logger.info { "${if (viewport) "Player" else "Bot"} logged in ${player.accountName} index ${player.index}." }
            client.login(player.name, player.index, player.rights.ordinal, member = World.members, membersWorld = World.members)
            accounts.spawn(player, client)
            AuditLog.event(player, "connected", player.tile)
        }
    }
}
