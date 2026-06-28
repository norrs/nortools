package no.norrs.nortools.web

import io.javalin.http.Context
import no.norrs.nortools.lib.zeroconf.SmbBrowseCredential
import no.norrs.nortools.lib.zeroconf.SmbShareBrowser
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SambaBrowseResponse(
    val host: String,
    val attempted: Boolean,
    val dialect: String? = null,
    val signingRequired: Boolean = false,
    val signingEnabled: Boolean = false,
    val encryptionSupported: Boolean = false,
    val serverGuid: String? = null,
    val authenticationMode: String? = null,
    val authenticationStatus: String? = null,
    val shares: List<SambaBrowseShareResponse> = emptyList(),
    val note: String? = null,
    val error: String? = null,
)

data class SambaBrowseShareResponse(
    val name: String,
    val type: String,
    val comment: String = "",
)

data class SambaBrowseDiscoveryResponse(
    val generatedAt: String,
    val hostCount: Int,
    val hosts: List<SambaBrowseDiscoveredHost>,
)

data class SambaBrowseDiscoveredHost(
    val address: String,
    val hostname: String? = null,
    val wsdTcpOpen: Boolean = false,
    val browse: SambaBrowseResponse? = null,
)

fun sambaBrowse(ctx: Context) {
    val host = ctx.pathParam("host").trim()
    val username = ctx.queryParam("username")?.trim().orEmpty()
    val password = ctx.queryParam("password").orEmpty()
    val domain = ctx.queryParam("domain")?.trim().orEmpty()
    val timeoutSeconds = ctx.queryParam("timeout")
        ?.toLongOrNull()
        ?.coerceIn(1, 60)
        ?: 10L

    if (host.isBlank()) {
        ctx.jsonResult(
            SambaBrowseResponse(
                host = host,
                attempted = false,
                error = "Host is required.",
            ),
        )
        return
    }

    val credentials: List<SmbBrowseCredential> = if (username.isBlank()) {
        SmbShareBrowser.defaultCredentials()
    } else {
        listOf(SmbShareBrowser.usernamePasswordCredential(username, password, domain))
    }

    val result = SmbShareBrowser(timeout = Duration.ofSeconds(timeoutSeconds))
        .browse(host, credentials)

    ctx.jsonResult(
        SambaBrowseResponse(
            host = result.host,
            attempted = result.attempted,
            dialect = result.dialect,
            signingRequired = result.signingRequired,
            signingEnabled = result.signingEnabled,
            encryptionSupported = result.encryptionSupported,
            serverGuid = result.serverGuid,
            authenticationMode = result.authenticationMode,
            authenticationStatus = result.authenticationStatus,
            shares = result.shares.map { share ->
                SambaBrowseShareResponse(
                    name = share.name,
                    type = share.type,
                    comment = share.comment,
                )
            },
            note = result.note,
            error = result.error,
        ),
    )
}

fun sambaBrowseDiscover(ctx: Context) {
    val includeShares = ctx.queryParam("shares")?.equals("false", ignoreCase = true) != true
    val timeoutSeconds = ctx.queryParam("timeout")
        ?.toLongOrNull()
        ?.coerceIn(1, 15)
        ?: 3L
    val hits = scanLocalSmbHosts()

    val hosts = if (includeShares && hits.isNotEmpty()) {
        browseDiscoveredHosts(hits, timeoutSeconds)
    } else {
        hits.map { hit ->
            SambaBrowseDiscoveredHost(
                address = hit.address,
                hostname = hit.hostname,
                wsdTcpOpen = hit.wsdTcpOpen,
            )
        }
    }

    ctx.jsonResult(
        SambaBrowseDiscoveryResponse(
            generatedAt = Instant.now().toString(),
            hostCount = hosts.size,
            hosts = hosts.sortedWith(compareBy<SambaBrowseDiscoveredHost> { it.hostname ?: it.address }.thenBy { it.address }),
        ),
    )
}

private fun browseDiscoveredHosts(hits: List<SmbSweepHit>, timeoutSeconds: Long): List<SambaBrowseDiscoveredHost> {
    val executor = Executors.newFixedThreadPool(minOf(8, hits.size)) { runnable ->
        Thread(runnable, "samba-browse-discovery-worker").apply { isDaemon = true }
    }
    return try {
        executor.invokeAll(
            hits.map { hit ->
                Callable {
                    val result = SmbShareBrowser(timeout = Duration.ofSeconds(timeoutSeconds))
                        .browse(hit.address)
                    SambaBrowseDiscoveredHost(
                        address = hit.address,
                        hostname = hit.hostname,
                        wsdTcpOpen = hit.wsdTcpOpen,
                        browse = SambaBrowseResponse(
                            host = result.host,
                            attempted = result.attempted,
                            dialect = result.dialect,
                            signingRequired = result.signingRequired,
                            signingEnabled = result.signingEnabled,
                            encryptionSupported = result.encryptionSupported,
                            serverGuid = result.serverGuid,
                            authenticationMode = result.authenticationMode,
                            authenticationStatus = result.authenticationStatus,
                            shares = result.shares.map { share ->
                                SambaBrowseShareResponse(
                                    name = share.name,
                                    type = share.type,
                                    comment = share.comment,
                                )
                            },
                            note = result.note,
                            error = result.error,
                        ),
                    )
                }
            },
            20,
            TimeUnit.SECONDS,
        ).mapNotNull { future ->
            runCatching { future.get() }.getOrNull()
        }
    } finally {
        executor.shutdownNow()
    }
}
