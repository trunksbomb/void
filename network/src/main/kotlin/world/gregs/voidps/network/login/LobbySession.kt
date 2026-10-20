package world.gregs.voidps.network.login

import world.gregs.voidps.network.client.Instruction

/** An authenticated account outside the game world. */
interface LobbySession {
    suspend fun handle(instruction: Instruction)

    suspend fun close()
}
