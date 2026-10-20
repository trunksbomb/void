package world.gregs.voidps.engine.client

import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import world.gregs.voidps.engine.data.*
import world.gregs.voidps.engine.data.config.AccountDefinition
import world.gregs.voidps.engine.data.definition.AccountDefinitions
import world.gregs.voidps.engine.data.exchange.Claim
import world.gregs.voidps.engine.data.exchange.OpenOffers
import world.gregs.voidps.engine.data.exchange.PriceHistory
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.Players
import world.gregs.voidps.engine.entity.character.player.chat.clan.Clan
import world.gregs.voidps.engine.script.KoinMock
import world.gregs.voidps.network.Response
import world.gregs.voidps.network.client.Client
import world.gregs.voidps.network.client.ConnectionQueue
import world.gregs.voidps.network.login.protocol.encode.login
import world.gregs.voidps.type.Tile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerAccountLoaderTest : KoinMock() {

    private lateinit var queue: ConnectionQueue
    private lateinit var storage: Storage
    private lateinit var saveQueue: SaveQueue
    private lateinit var accounts: AccountManager
    private lateinit var definitions: AccountDefinitions
    private lateinit var loader: PlayerAccountLoader
    private var playerSave: PlayerSave? = null

    @BeforeEach
    fun setup() {
        playerSave = null
        queue = mockk(relaxed = true)
        storage = object : Storage {
            override fun names(): Map<String, AccountDefinition> = emptyMap()

            override fun clans(): Map<String, Clan> = emptyMap()

            override fun save(accounts: List<PlayerSave>) {
            }

            override fun offers(days: Int): OpenOffers = OpenOffers()

            override fun saveOffers(offers: OpenOffers) {
            }

            override fun claims(): Map<Int, Claim> = emptyMap()

            override fun saveClaims(claims: Map<Int, Claim>) {
            }

            override fun priceHistory(): Map<String, PriceHistory> = emptyMap()

            override fun savePriceHistory(history: Map<String, PriceHistory>) {
            }

            override fun saveReport(report: AbuseReport) {
            }

            override fun exists(accountName: String): Boolean = false

            override fun load(accountName: String): PlayerSave? = playerSave

            override fun accounts(): List<PlayerSave> = listOfNotNull(playerSave)
        }
        saveQueue = SaveQueue(storage, scope = TestScope())
        definitions = AccountDefinitions(mutableMapOf("name" to AccountDefinition("name", "oldName", "", "hash")), mutableMapOf("accountname" to "name"))
        accounts = mockk(relaxed = true)
        loader = PlayerAccountLoader(queue, storage, accounts, saveQueue, definitions, UnconfinedTestDispatcher())
    }

    @Test
    fun `Get password`() {
        assertEquals("hash", loader.password("accountName"))
        assertNull(loader.password("name2"))
    }

    @Test
    fun `Successful login`() = runTest {
        val client: Client = mockk(relaxed = true)
        playerSave = PlayerSave("name", "hash", Tile.EMPTY, intArrayOf(), emptyList(), intArrayOf(), true, intArrayOf(), intArrayOf(), emptyMap(), emptyMap(), emptyMap(), emptyList(), arrayOf(), emptyList(), emptyMap(), emptyMap(), emptyList())
        coEvery { queue.await() } just Runs

        val instructions = loader.load(client, "name", "pass", 2)
        assertNotNull(instructions)
    }

    @Test
    fun `Can't login if banned`() = runTest {
        val client: Client = mockk(relaxed = true)
        playerSave = PlayerSave("name", "hash", Tile.EMPTY, intArrayOf(), emptyList(), intArrayOf(), true, intArrayOf(), intArrayOf(), mapOf("banned_until" to Int.MAX_VALUE), emptyMap(), emptyMap(), emptyList(), arrayOf(), emptyList(), emptyMap(), emptyMap(), emptyList())

        val instructions = loader.load(client, "name", "pass", 2)
        assertNull(instructions)
        coVerify { client.disconnect(Response.ACCOUNT_DISABLED) }
    }

    @Test
    fun `Can login once ban expires`() = runTest {
        val client: Client = mockk(relaxed = true)
        playerSave = PlayerSave("name", "hash", Tile.EMPTY, intArrayOf(), emptyList(), intArrayOf(), true, intArrayOf(), intArrayOf(), mapOf("banned_until" to 1), emptyMap(), emptyMap(), emptyList(), arrayOf(), emptyList(), emptyMap(), emptyMap(), emptyList())
        coEvery { queue.await() } just Runs

        val instructions = loader.load(client, "name", "pass", 2)
        assertNotNull(instructions)
    }

    @Test
    fun `Login waits for departure save before loading the account`() = runTest {
        saveQueue = SaveQueue(storage, scope = backgroundScope)
        loader = PlayerAccountLoader(queue, storage, accounts, saveQueue, definitions, UnconfinedTestDispatcher(testScheduler))
        playerSave = PlayerSave("name", "hash", Tile.EMPTY, intArrayOf(), emptyList(), intArrayOf(), true, intArrayOf(), intArrayOf(), emptyMap(), emptyMap(), emptyMap(), emptyList(), arrayOf(), emptyList(), emptyMap(), emptyMap(), emptyList())
        saveQueue.save(Player(accountName = "name"))
        val client: Client = mockk(relaxed = true)

        val instructions = loader.load(client, "name", "pass", 2)
        assertNotNull(instructions)
        assertEquals(false, saveQueue.saving("name"))
        coVerify(exactly = 0) { client.disconnect(Response.ACCOUNT_ONLINE) }
    }

    @Test
    fun `Lobby login does not spawn or index a world player`() = runTest {
        val client: Client = mockk(relaxed = true)
        playerSave = PlayerSave("name", "hash", Tile.EMPTY, intArrayOf(), emptyList(), intArrayOf(), true, intArrayOf(), intArrayOf(), emptyMap(), emptyMap(), emptyMap(), emptyList(), arrayOf(), emptyList(), emptyMap(), emptyMap(), emptyList())
        val session = loader.loadLobby(client, "name", "pass")
        assertNotNull(session)
        verify(exactly = 0) { accounts.index(any()) }
        verify(exactly = 0) { accounts.spawn(any(), any()) }
        session.close()
    }

    @Test
    fun `First world login preserves setup for an account created in the lobby`() = runTest {
        val definitionsBefore = world.gregs.voidps.engine.data.definition.VariableDefinitions.definitions
        world.gregs.voidps.engine.data.definition.VariableDefinitions.set(definitionsBefore + ("lobby_first_world" to world.gregs.voidps.engine.data.config.VariableDefinition.CustomVariableDefinition(world.gregs.voidps.engine.client.variable.BooleanValues, false, true)))
        try {
            playerSave = PlayerSave("name", "hash", Tile.EMPTY, intArrayOf(), emptyList(), intArrayOf(), true, intArrayOf(), intArrayOf(), mapOf("lobby_first_world" to true), emptyMap(), emptyMap(), emptyList(), arrayOf(), emptyList(), emptyMap(), emptyMap(), emptyList())
            val client: Client = mockk(relaxed = true)
            assertNotNull(loader.load(client, "name", "pass", 2))
            verify { accounts.setup(match { it["new_player", false] && !it.contains("lobby_first_world") }, client, 2, true) }
        } finally {
            world.gregs.voidps.engine.data.definition.VariableDefinitions.set(definitionsBefore)
        }
    }

    @Test
    fun `Lobby rejects banned accounts`() = runTest {
        val client: Client = mockk(relaxed = true)
        playerSave = PlayerSave("name", "hash", Tile.EMPTY, intArrayOf(), emptyList(), intArrayOf(), true, intArrayOf(), intArrayOf(), mapOf("banned_until" to Int.MAX_VALUE), emptyMap(), emptyMap(), emptyList(), arrayOf(), emptyList(), emptyMap(), emptyMap(), emptyList())
        assertNull(loader.loadLobby(client, "name", "pass"))
        coVerify { client.disconnect(Response.ACCOUNT_DISABLED) }
    }

    @Test
    fun `Connect initiates and awaits spawn`() = runTest {
        mockkStatic("world.gregs.voidps.network.login.protocol.encode.LoginEncoderKt")
        val client: Client = mockk(relaxed = true)
        val player = Player(index = 4, accountName = "name", passwordHash = "\$2a\$10\$cPB7bqICWrOILrWnXuYNDu1EsbZal9AjxYMbmpMOtI1kwruazGiby", variables = mutableMapOf("display_name" to "name"))
        coEvery { queue.await() } just Runs
        every { accounts.index(any()) } returns true

        loader.connect(player, client, 2)

        coVerify {
            queue.await()
            client.login("name", 4, 0, member = false, membersWorld = false)
            accounts.spawn(player, client)
        }
    }

    @Test
    fun `Can't login while an earlier session is still in the world`() = runTest {
        mockkStatic("world.gregs.voidps.network.login.protocol.encode.LoginEncoderKt")
        mockkObject(Players)
        val client: Client = mockk(relaxed = true)
        val ghost = Player(index = 7, accountName = "name")
        every { Players.findByAccount("name") } returns ghost
        try {
            val player = Player(index = 4, accountName = "name", variables = mutableMapOf("display_name" to "name"))
            every { accounts.index(any()) } returns true

            loader.connect(player, client, 2)

            coVerify {
                accounts.logout(ghost, safely = false)
                client.disconnect(Response.ACCOUNT_ONLINE)
            }
            coVerify(exactly = 0) { accounts.spawn(player, client) }
        } finally {
            unmockkObject(Players)
        }
    }

    @Test
    fun `World full`() = runTest {
        mockkStatic("world.gregs.voidps.network.login.protocol.encode.LoginEncoderKt")
        val client: Client = mockk(relaxed = true)
        val player = Player(index = 4, accountName = "name", passwordHash = "\$2a\$10\$cPB7bqICWrOILrWnXuYNDu1EsbZal9AjxYMbmpMOtI1kwruazGiby", variables = mutableMapOf("display_name" to "name"))
        every { accounts.index(player) } returns false

        loader.connect(player, client, 2)

        coVerify {
            client.disconnect(Response.WORLD_FULL)
        }
    }
}
