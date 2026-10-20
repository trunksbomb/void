package world.gregs.voidps.network

import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.io.readUByte
import kotlinx.io.readUShort
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import world.gregs.voidps.cache.secure.RSA
import world.gregs.voidps.cache.secure.Xtea
import world.gregs.voidps.network.client.Client
import world.gregs.voidps.network.client.Instruction
import world.gregs.voidps.network.client.IsaacCipher
import world.gregs.voidps.network.client.instruction.LobbyWorldListRefresh
import world.gregs.voidps.network.login.AccountLoader
import world.gregs.voidps.network.login.LobbySession
import world.gregs.voidps.network.login.protocol.Decoder
import world.gregs.voidps.network.login.protocol.decode.LobbyWorldListRefreshDecoder
import world.gregs.voidps.network.login.protocol.encode.loginLobby
import world.gregs.voidps.network.login.protocol.encode.logout
import world.gregs.voidps.network.login.protocol.readString
import world.gregs.voidps.network.login.protocol.writeByte
import world.gregs.voidps.network.login.protocol.writeShort
import world.gregs.voidps.network.login.protocol.writeText
import java.math.BigInteger

class LobbyProtocolTest {
    private val modulus = BigInteger("ea3680fdebf2621da7a33601ba39925ee203b3fc80775cd3727bf27fd8c0791c803e0bdb42b8b5257567177f8569024569da9147cef59009ed016af6007e57a556f1754f09ca84dd39a03287f7e41e8626fd78ab3b53262bd63f2e37403a549980bf3077bd402b82ef5fac269eb3c04d2a9b7712a67a018321ceba6c3bfb8f7f", 16)
    private val key = BigInteger("8330565e649c16d32f841f0b26a97ad044def821164045b176adf0ae25d5e1c0d2206ef9b8ccc7429d194ab33622149096f3436f2a80a7d6b77794d7087dbc4f21239a4012b18afa3d1bede29d63f33bc553885f7117aa5d842231fae613d6e612c651249e66b7c67d565b21e68202798ccdbd0cc6dea3f6d033e719cb75ea01", 16)

    @Test
    fun `lobby success reply matches the native client field order`() = runTest {
        val output = ByteChannel(autoFlush = true)
        val client = Client(output, IsaacCipher(IntArray(4)), null, "localhost")
        client.loginLobby("Tester", 2, true, 16, "127.0.0.1")
        assertEquals(2, (output.readByte().toInt() and 0xff))
        val size = (output.readByte().toInt() and 0xff)
        val packet = output.readPacket(size)
        assertEquals(2, packet.readUByte().toInt())
        repeat(4) { assertEquals(0, packet.readUByte().toInt()) }
        assertEquals(32767, packet.readUShort().toInt())
        repeat(3) { assertEquals(0, packet.readUShort().toInt()) }
        assertEquals(0, packet.readInt())
        assertEquals(0, packet.readUByte().toInt())
        repeat(2) { assertEquals(0, packet.readUShort().toInt()) }
        assertEquals(0, packet.readUByte().toInt())
        assertEquals(0, packet.readUByte().toInt())
        assertEquals("Tester", packet.readString())
        assertEquals(0, packet.readUByte().toInt())
        assertEquals(0, packet.readInt())
        assertEquals(16, packet.readUShort().toInt())
        assertEquals(0, packet.readUByte().toInt())
        assertEquals("127.0.0.1", packet.readString())
        assertEquals(0L, packet.remaining)
        client.logout(true)
        client.logout()
        client.flush()
        assertEquals(60, (output.readByte().toInt() and 0xff))
        assertEquals(55, (output.readByte().toInt() and 0xff))
    }

    @Test
    fun `lobby request authenticates without a world slot and routes refresh`() = runTest {
        var worldLoads = 0
        var closes = 0
        var refreshed = false
        val accounts = object : AccountLoader {
            override fun used(username: String) = false
            override fun exists(username: String) = false
            override fun password(username: String): String? = null
            override suspend fun load(client: Client, username: String, passwordHash: String, displayMode: Int): SendChannel<Instruction>? {
                worldLoads++
                return null
            }
            override suspend fun loadLobby(client: Client, username: String, passwordHash: String): LobbySession {
                client.loginLobby(username, 0, false, 16, "127.0.0.1")
                val session = object : LobbySession {
                    override suspend fun handle(instruction: Instruction) {
                        assertEquals(LobbyWorldListRefresh(42), instruction)
                        refreshed = true
                        client.disconnect()
                    }
                    override suspend fun close() {
                        closes++
                    }
                }
                client.onDisconnecting { session.close() }
                return session
            }
        }
        val protocol = arrayOfNulls<Decoder>(256).apply { this[30] = LobbyWorldListRefreshDecoder() }
        val server = LoginServer(protocol, 634, 1, modulus, BigInteger("10001", 16), accounts)
        val input = ByteChannel(autoFlush = true)
        val output = ByteChannel(autoFlush = true)
        val job = launch { server.connect(input, output, "localhost") }
        val rsa = buildPacket {
            writeByte(10)
            repeat(4) { writeInt(0) }
            writeLong(0L)
            writeText("password")
        }.readByteArray().let { RSA.crypt(it, modulus, key) }
        val xtea = buildPacket {
            writeText("tester")
            writeByte(0) // language, not social login
            writeByte(0) // settings, not display mode
        }.readByteArray().also { Xtea.encipher(it, 0, it.size, IntArray(4)) }
        input.writeByte(19)
        input.writeShort(6 + rsa.size + xtea.size)
        input.writeInt(634)
        input.writeShort(rsa.size)
        input.writeFully(rsa)
        input.writeFully(xtea)
        assertEquals(0, (output.readByte().toInt() and 0xff))
        assertEquals(2, (output.readByte().toInt() and 0xff))
        output.readPacket((output.readByte().toInt() and 0xff))
        assertEquals(0, worldLoads)
        assertTrue(server.online.isEmpty())
        val cipher = IsaacCipher(IntArray(4))
        input.writeByte(30 + cipher.nextInt())
        input.writeInt(42)
        job.join()
        assertTrue(refreshed)
        assertEquals(1, closes)
        assertTrue(output.isClosedForWrite)
        job.cancelAndJoin()
    }
}
