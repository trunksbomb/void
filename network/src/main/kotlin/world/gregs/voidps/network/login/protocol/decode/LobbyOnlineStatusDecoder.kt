package world.gregs.voidps.network.login.protocol.decode

import kotlinx.io.Source
import kotlinx.io.readUByte
import world.gregs.voidps.network.client.instruction.LobbyOnlineStatus
import world.gregs.voidps.network.login.protocol.Decoder

class LobbyOnlineStatusDecoder : Decoder(3) {
    override suspend fun decode(packet: Source) = LobbyOnlineStatus(packet.readUByte().toInt(), packet.readUByte().toInt(), packet.readUByte().toInt())
}
