package world.gregs.voidps.network.login.protocol.encode

import world.gregs.voidps.network.client.Client
import world.gregs.voidps.network.login.Protocol.LOGOUT

fun Client.logout(toLobby: Boolean = false) = send(if (toLobby) world.gregs.voidps.network.login.Protocol.LOGOUT_LOBBY else LOGOUT) {}
