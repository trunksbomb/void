package world.gregs.voidps.network.client.instruction

import world.gregs.voidps.network.client.Instruction

data class LobbyOnlineStatus(val public: Int, val private: Int, val trade: Int) : Instruction
