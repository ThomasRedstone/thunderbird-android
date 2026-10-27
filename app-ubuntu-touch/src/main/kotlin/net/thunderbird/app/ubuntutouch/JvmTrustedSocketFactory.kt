package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.CertificateChainException
import com.fsck.k9.mail.ssl.TrustedSocketFactory
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import net.thunderbird.core.common.net.HostNameUtils.isLegalIPAddress
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier

/** Retains the backend's unconnected/direct-TLS and connected/STARTTLS socket contract. */
internal class JvmTrustedSocketFactory(
    private val trustManager: X509TrustManager = systemTrustManager(),
) : TrustedSocketFactory {
    override fun createSocket(socket: Socket?, host: String, port: Int, clientCertificateAlias: String?): Socket {
        require(clientCertificateAlias == null) { "Client certificate authentication is not available yet" }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(HostTrustManager(trustManager, host)), null)
        }
        val secure = if (socket == null) {
            context.socketFactory.createSocket()
        } else {
            context.socketFactory.createSocket(socket, host, port, true)
        } as SSLSocket
        secure.enabledProtocols = secure.enabledProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        if (isLegalIPAddress(host) == null) {
            secure.sslParameters = secure.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
        }
        return secure
    }
}

private class HostTrustManager(private val delegate: X509TrustManager, private val host: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            verifyChain(chain, authType)
            DefaultHostnameVerifier().verify(host, chain.first())
        } catch (exception: CertificateException) {
            throw CertificateChainException("Server certificate validation failed", chain, exception)
        } catch (exception: SSLException) {
            throw CertificateChainException("Server certificate identity does not match", chain, exception)
        }
    }

    private fun verifyChain(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("Server supplied no certificate")
        chain.first().checkValidity()
        delegate.checkServerTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkClientTrusted(chain, authType)
    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}

internal fun systemTrustManager(keyStore: KeyStore? = null): X509TrustManager =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
        .trustManagers.filterIsInstance<X509TrustManager>().single()
