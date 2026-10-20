package world.gregs.voidps.network.login.protocol.encode

import io.ktor.utils.io.*
import world.gregs.voidps.network.Response
import world.gregs.voidps.network.client.Client
import world.gregs.voidps.network.login.protocol.writeByte
import world.gregs.voidps.network.login.protocol.writeShort
import world.gregs.voidps.network.login.protocol.writeText

/** Revision 634 lobby login response, read by Class88 login stage 9, mode 1. */
fun Client.loginLobby(displayName: String, rights: Int, member: Boolean, world: Int, address: String) {
    val size = 32 + Client.string(displayName) + Client.string(address)
    require(size <= 255)
    send(-1) {
        writeByte(Response.SUCCESS)
        writeByte(size)
        writeByte(rights)
        writeByte(0) // player moderator level
        writeByte(false) // underage
        writeByte(false) // parental chat consent required
        writeByte(false) // parental advertising consent required
        writeShort(if (member) 32767 else 0) // membership days
        writeShort(0) // recovery questions age
        writeShort(0) // unread messages
        writeShort(0) // last login age
        writeInt(0) // last login IPv4 address
        writeByte(0) // email status
        writeShort(0) // credit card expiry
        writeShort(0) // grace period
        writeByte(false) // date of birth requested
        writeByte(0)
        writeText(displayName)
        writeByte(0) // account status
        writeInt(0) // account status expiry
        writeShort(world)
        writeByte(0)
        writeText(address)
        flush()
    }
}
