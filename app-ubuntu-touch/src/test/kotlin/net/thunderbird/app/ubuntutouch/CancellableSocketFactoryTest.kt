package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Test

class CancellableSocketFactoryTest {
    @Test
    fun `closed owner rejects new sockets while another owner remains usable`() {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 3000
            val address = InetSocketAddress(server.inetAddress, server.localPort)
            CancellableSocketFactory(JvmTrustedSocketFactory()).use { first ->
                CancellableSocketFactory(JvmTrustedSocketFactory()).use { testSubject ->
                    checkIndependentConnections(server, address, first, testSubject)
                }
            }
        }
    }

    private fun checkIndependentConnections(
        server: ServerSocket,
        address: InetSocketAddress,
        first: CancellableSocketFactory,
        testSubject: CancellableSocketFactory,
    ) {
        first.createPlainSocket().connect(address)
        server.accept().use { firstPeer ->
            val second = testSubject.createPlainSocket().apply { connect(address) }
            server.accept().use { secondPeer ->
                firstPeer.soTimeout = 3000
                secondPeer.soTimeout = 3000
                first.close()
                assertThat(firstPeer.getInputStream().read()).isEqualTo(-1)
                assertThat(
                    runCatching {
                        first.createPlainSocket()
                    }.exceptionOrNull() is InterruptedIOException,
                ).isTrue()
                second.getOutputStream().write(42)
                assertThat(secondPeer.getInputStream().read()).isEqualTo(42)
                testSubject.close()
                assertThat(secondPeer.getInputStream().read()).isEqualTo(-1)
            }
        }
    }
}
