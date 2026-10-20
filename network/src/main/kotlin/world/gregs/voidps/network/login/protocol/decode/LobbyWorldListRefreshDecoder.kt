package world.gregs.voidps.network.login.protocol.decode

import kotlinx.io.Source
import world.gregs.voidps.network.client.instruction.LobbyWorldListRefresh
import world.gregs.voidps.network.login.protocol.Decoder

class LobbyWorldListRefreshDecoder : Decoder(4) {
    override suspend fun decode(packet: Source) = LobbyWorldListRefresh(packet.readInt())
}
