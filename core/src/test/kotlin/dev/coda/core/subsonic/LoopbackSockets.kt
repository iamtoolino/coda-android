package dev.coda.core.subsonic

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/** Test-only: connects to loopback ports chosen per requested port, so default ports (80/443) map to local servers. */
internal class Remap(private val ports: Map<Int, Int>) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val address = endpoint as InetSocketAddress
            super.connect(InetSocketAddress(address.address, ports[address.port] ?: address.port), timeout)
        }
    }
    override fun createSocket(host: String?, port: Int) = throw UnsupportedOperationException()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
    override fun createSocket(host: InetAddress?, port: Int) = throw UnsupportedOperationException()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
}

/** Test-only: maps each requested `ip:port` to a loopback port (several synthetic addresses for one host name). */
internal class AddressRemap(private val ports: Map<String, Int>) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val address = endpoint as InetSocketAddress
            val port = ports["${address.address.hostAddress}:${address.port}"] ?: error("unmapped ${address.port}")
            super.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeout)
        }
    }
    override fun createSocket(host: String?, port: Int) = throw UnsupportedOperationException()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
    override fun createSocket(host: InetAddress?, port: Int) = throw UnsupportedOperationException()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
}
