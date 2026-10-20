package world.gregs.voidps.network.client.instruction

import world.gregs.voidps.network.client.Instruction

data class LobbyWorldListRefresh(val checksum: Int) : Instruction
