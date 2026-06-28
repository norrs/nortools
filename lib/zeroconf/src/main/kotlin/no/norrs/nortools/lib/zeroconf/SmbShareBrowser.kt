package no.norrs.nortools.lib.zeroconf

import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.mssrvs.dto.NetShareInfo0
import com.rapid7.client.dcerpc.mssrvs.dto.NetShareInfo1
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.time.Duration
import java.util.concurrent.TimeUnit

data class SmbBrowseCredential(
    val mode: String,
    val authenticationContext: AuthenticationContext,
)

data class SmbBrowseShare(
    val name: String,
    val type: String,
    val comment: String = "",
)

data class SmbBrowseResult(
    val host: String,
    val attempted: Boolean,
    val dialect: String? = null,
    val signingRequired: Boolean = false,
    val signingEnabled: Boolean = false,
    val encryptionSupported: Boolean = false,
    val serverGuid: String? = null,
    val authenticationMode: String? = null,
    val authenticationStatus: String? = null,
    val shares: List<SmbBrowseShare> = emptyList(),
    val note: String? = null,
    val error: String? = null,
)

class SmbShareBrowser(
    private val timeout: Duration = Duration.ofSeconds(5),
) {
    fun browse(host: String, credentials: List<SmbBrowseCredential> = defaultCredentials()): SmbBrowseResult {
        var client: SMBClient? = null
        var connection: com.hierynomus.smbj.connection.Connection? = null
        var session: com.hierynomus.smbj.session.Session? = null
        try {
            val config = SmbConfig.builder()
                .withDialects(
                    SMB2Dialect.SMB_3_1_1,
                    SMB2Dialect.SMB_3_0_2,
                    SMB2Dialect.SMB_3_0,
                    SMB2Dialect.SMB_2_1,
                    SMB2Dialect.SMB_2_0_2,
                )
                .withTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .withSoTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .build()
            client = SMBClient(config)
            connection = runCatching { client.connect(host) }.getOrElse { error ->
                return SmbBrowseResult(
                    host = host,
                    attempted = true,
                    error = error.message ?: "SMB connection failed",
                )
            }

            val connectionInfo = connection.connectionContext
            val dialect = connection.negotiatedProtocol.dialect.name
            val signingRequired = connectionInfo.isServerRequiresSigning()
            val signingEnabled = connectionInfo.isServerSigningEnabled()
            val serverGuid = connectionInfo.serverGuid?.toString()
            val encryptionSupported = connectionInfo.supportsEncryption()

            var lastError: String? = null
            for (credential in credentials.ifEmpty { defaultCredentials() }) {
                try {
                    session = connection.authenticate(credential.authenticationContext)
                    val shares = enumerateShares(session)
                    return SmbBrowseResult(
                        host = host,
                        attempted = true,
                        dialect = dialect,
                        signingRequired = signingRequired,
                        signingEnabled = signingEnabled,
                        encryptionSupported = encryptionSupported,
                        serverGuid = serverGuid,
                        authenticationMode = credential.mode,
                        authenticationStatus = when {
                            session.isAnonymous -> "anonymous"
                            session.isGuest -> "guest"
                            else -> "authenticated"
                        },
                        shares = shares,
                        note = if (shares.isEmpty()) "Connected, but no shares were returned." else null,
                    )
                } catch (error: Throwable) {
                    lastError = error.message ?: error::class.java.simpleName
                } finally {
                    runCatching { session?.logoff() }
                    session = null
                }
            }

            return SmbBrowseResult(
                host = host,
                attempted = true,
                dialect = dialect,
                signingRequired = signingRequired,
                signingEnabled = signingEnabled,
                encryptionSupported = encryptionSupported,
                serverGuid = serverGuid,
                authenticationStatus = "denied",
                note = "No configured SMB session was accepted.",
                error = lastError,
            )
        } catch (error: Throwable) {
            return SmbBrowseResult(
                host = host,
                attempted = true,
                error = error.message ?: error::class.java.simpleName,
            )
        } finally {
            runCatching { session?.logoff() }
            runCatching { connection?.close(true) }
            runCatching { client?.close() }
        }
    }

    companion object {
        fun defaultCredentials(): List<SmbBrowseCredential> =
            listOf(
                SmbBrowseCredential("anonymous", AuthenticationContext.anonymous()),
                SmbBrowseCredential("guest", AuthenticationContext.guest()),
                SmbBrowseCredential("empty-user", AuthenticationContext("", CharArray(0), "")),
                SmbBrowseCredential("guest-empty-password", AuthenticationContext("guest", CharArray(0), "")),
                SmbBrowseCredential("guest-workgroup", AuthenticationContext("guest", CharArray(0), "WORKGROUP")),
            )

        fun usernamePasswordCredential(
            username: String,
            password: String,
            domain: String = "",
        ): SmbBrowseCredential =
            SmbBrowseCredential(
                mode = if (domain.isBlank()) "username-password" else "domain-username-password",
                authenticationContext = AuthenticationContext(username, password.toCharArray(), domain),
            )
    }

    private fun enumerateShares(session: com.hierynomus.smbj.session.Session): List<SmbBrowseShare> {
        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
        val service = ServerService(transport)
        return runCatching {
            service.getShares1().map(::toSmbShare)
        }.getOrElse {
            service.getShares0().map(::toSmbShare)
        }.filterNot { share -> share.name.isBlank() }
            .sortedWith(compareBy<SmbBrowseShare> { it.name.lowercase() })
    }

    private fun toSmbShare(share: NetShareInfo0): SmbBrowseShare =
        SmbBrowseShare(name = share.netName ?: "", type = "Unknown")

    private fun toSmbShare(share: NetShareInfo1): SmbBrowseShare {
        val typeValue = share.type
        val baseType = when (typeValue and 0xFF) {
            0 -> "Disk"
            1 -> "Printer"
            2 -> "Device"
            3 -> "IPC"
            else -> "Unknown"
        }
        val flags = buildList {
            if ((typeValue and 0x80000000.toInt()) != 0) add("Special")
            if ((typeValue and 0x40000000) != 0) add("Temporary")
        }
        val renderedType = if (flags.isEmpty()) baseType else "$baseType (${flags.joinToString(", ")})"
        return SmbBrowseShare(
            name = share.netName ?: "",
            type = renderedType,
            comment = share.remark ?: "",
        )
    }
}
