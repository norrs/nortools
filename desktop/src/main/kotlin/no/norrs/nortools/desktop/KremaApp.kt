package no.norrs.nortools.desktop

import build.krema.core.Krema
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import no.norrs.nortools.tools.blocklist.blacklist.BlacklistCheckCommand
import no.norrs.nortools.tools.blocklist.blocklist.BlocklistCheckCommand
import no.norrs.nortools.tools.blocklist.cert.CertLookupCommand
import no.norrs.nortools.tools.blocklist.ipseckey.IpseckeyLookupCommand
import no.norrs.nortools.tools.blocklist.loc.LocLookupCommand
import no.norrs.nortools.tools.composite.bulk.BulkLookupCommand
import no.norrs.nortools.tools.composite.compliance.ComplianceCommand
import no.norrs.nortools.tools.composite.deliverability.DeliverabilityCommand
import no.norrs.nortools.tools.composite.dmarcreport.DmarcReportCommand
import no.norrs.nortools.tools.composite.domainhealth.DomainHealthCommand
import no.norrs.nortools.tools.composite.mailflow.MailFlowCommand
import no.norrs.nortools.tools.dns.a.ALookupCommand
import no.norrs.nortools.tools.dns.aaaa.AaaaLookupCommand
import no.norrs.nortools.tools.dns.cname.CnameLookupCommand
import no.norrs.nortools.tools.dns.mx.MxLookupCommand
import no.norrs.nortools.tools.dns.ns.NsLookupCommand
import no.norrs.nortools.tools.dns.ptr.PtrLookupCommand
import no.norrs.nortools.tools.dns.soa.SoaLookupCommand
import no.norrs.nortools.tools.dns.srv.SrvLookupCommand
import no.norrs.nortools.tools.dns.txt.TxtLookupCommand
import no.norrs.nortools.tools.dnssec.dnskey.DnskeyLookupCommand
import no.norrs.nortools.tools.dnssec.ds.DsLookupCommand
import no.norrs.nortools.tools.dnssec.nsec.NsecLookupCommand
import no.norrs.nortools.tools.dnssec.nsec3param.Nsec3paramLookupCommand
import no.norrs.nortools.tools.dnssec.rrsig.RrsigLookupCommand
import no.norrs.nortools.tools.email.bimi.BimiLookupCommand
import no.norrs.nortools.tools.email.dkim.DkimLookupCommand
import no.norrs.nortools.tools.email.dmarc.DmarcLookupCommand
import no.norrs.nortools.tools.email.dmarcgenerator.DmarcGeneratorCommand
import no.norrs.nortools.tools.email.headeranalyzer.HeaderAnalyzerCommand
import no.norrs.nortools.tools.email.mtasts.MtaStsLookupCommand
import no.norrs.nortools.tools.email.smtp.SmtpTestCommand
import no.norrs.nortools.tools.email.spf.SpfLookupCommand
import no.norrs.nortools.tools.email.spfgenerator.SpfGeneratorCommand
import no.norrs.nortools.tools.email.tlsrpt.TlsrptLookupCommand
import no.norrs.nortools.tools.network.http.HttpCheckCommand
import no.norrs.nortools.tools.network.https.HttpsCheckCommand
import no.norrs.nortools.tools.network.ping.PingCommand
import no.norrs.nortools.tools.network.tcp.TcpCheckCommand
import no.norrs.nortools.tools.network.trace.TraceCommand
import no.norrs.nortools.tools.util.dnshealth.DnsHealthCommand
import no.norrs.nortools.tools.util.dnspropagation.DnsPropagationCommand
import no.norrs.nortools.tools.util.emailextract.EmailExtractCommand
import no.norrs.nortools.tools.util.passwordgen.PasswordGenCommand
import no.norrs.nortools.tools.util.subnetcalc.SubnetCalcCommand
import no.norrs.nortools.tools.util.whatismyip.WhatIsMyIpCommand
import no.norrs.nortools.tools.whois.arin.ArinLookupCommand
import no.norrs.nortools.tools.whois.asn.AsnLookupCommand
import no.norrs.nortools.tools.whois.whois.WhoisLookupCommand
import no.norrs.nortools.web.startServer
import java.util.Properties
import kotlin.system.exitProcess

private const val FALLBACK_APP_VERSION = "0.0.0"
private const val DEFAULT_UPDATER_ENDPOINT =
    "https://github.com/norrs/nortools/releases/latest/download/nortools-update-{{target}}.json"
private const val PINNED_UPDATER_PUBLIC_KEY_B64 = "MCowBQYDK2VwAyEAEAaiTgA8yZq2JopBfuPZvnF/rxVYGX6buzz7Ndo8wAw="

fun main(args: Array<String>) {
    val hasUiFlag = args.contains("--ui")
    if (args.contains("--help") || args.contains("-h")) {
        printRootHelp()
        return
    }
    if (!hasUiFlag && runCliIfRequested(args)) {
        return
    }

    val devMode = args.contains("--dev")

    // Start the embedded Javalin web server (Vue SPA + API routes)
    // Port 0 picks a random available port
    val server = startServer(port = 0)
    val serverUrl = "http://localhost:${server.port()}"
    println("[NorTools] Embedded server running at $serverUrl")
    val buildInfo = resolveBuildInfo()
    println("[NorTools] Version resolved to ${buildInfo.version}")
    logBuildInfo(buildInfo)

    val app = Krema.app()
        .title("NorTools")
        .version(buildInfo.version)
        .identifier("no.norrs.nortools")
        .size(1200, 800)
        .minSize(900, 600)
        .debug(devMode)
        .devUrl(serverUrl)
        .updaterConfig(buildUpdaterConfig(devMode))

    println("[NorTools] Opening desktop window")
    app.run()

    // Shut down the server when the window closes
    server.stop()
}

private data class BuildInfo(
    val version: String,
    val rawVersion: String?,
    val tag: String?,
    val commit: String?,
    val target: String?,
)

private fun resolveBuildInfo(): BuildInfo {
    val props = loadBuildProperties()
    val rawVersion = props.firstNonBlank(
        "build.krema.version",
        "stable.krema.version",
        "STABLE_KREMA_VERSION",
    )
        ?: System.getenv("STABLE_KREMA_VERSION")
    val normalized = normalizeVersion(rawVersion) ?: FALLBACK_APP_VERSION
    return BuildInfo(
        version = normalized,
        rawVersion = rawVersion,
        tag = props.firstNonBlank("build.tag", "STABLE_GIT_TAG", "git.tag"),
        commit = props.firstNonBlank("git.commit", "STABLE_GIT_COMMIT", "build.changelist"),
        target = updaterTargetForCurrentPlatform(),
    )
}

private fun logBuildInfo(buildInfo: BuildInfo) {
    println(
        "[NorTools] Build info: version=${buildInfo.version}, rawVersion=${buildInfo.rawVersion ?: "n/a"}, " +
            "tag=${buildInfo.tag ?: "n/a"}, commit=${buildInfo.commit ?: "n/a"}, target=${buildInfo.target ?: "n/a"}"
    )
}

private fun normalizeVersion(value: String?): String? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    if (trimmed.equals("unknown", ignoreCase = true)) return null

    // Normalize to stable semver core and drop any prerelease/build suffix.
    var normalized = trimmed.removePrefix("v").removePrefix("V")
    normalized = normalized.replace(".+", ".").replace("+", ".")
    normalized = normalized.replace(Regex("\\.{2,}"), ".").trim('.')
    normalized = normalized.substringBefore("-")

    return normalized.ifBlank { null }
}

private fun loadBuildProperties(): Properties {
    val candidates = loadPropertiesByResourceNames(
        "git-build-info.properties",
        "web/git-build-info.properties",
        "build-data.properties",
        "web/build-data.properties",
    )
    if (candidates.isEmpty()) return Properties()
    return candidates.maxByOrNull { scoreBuildProperties(it) } ?: candidates.first()
}

private fun loadPropertiesByResourceNames(vararg names: String): List<Properties> {
    val loader = Thread.currentThread().contextClassLoader
    val candidates = mutableListOf<Properties>()
    for (name in names) {
        val resources = loader.getResources(name)
        while (resources.hasMoreElements()) {
            val url = resources.nextElement()
            val props = Properties()
            try {
                url.openStream().use { props.load(it) }
                candidates.add(props)
            } catch (_: Exception) {
                // Skip unreadable candidates.
            }
        }
    }
    return candidates
}

private fun Properties.firstNonBlank(vararg keys: String): String? {
    for (key in keys) {
        val value = getProperty(key)?.trim()
        if (!value.isNullOrEmpty()) return value
    }
    return null
}

private fun scoreBuildProperties(props: Properties): Int {
    var score = 0
    if (!props.firstNonBlank("build.krema.version", "stable.krema.version", "STABLE_KREMA_VERSION").isNullOrBlank()) score += 500
    if (!props.firstNonBlank("git.commit", "STABLE_GIT_COMMIT", "build.changelist").isNullOrBlank()) score += 200
    if (!props.firstNonBlank("build.target", "main.class").isNullOrBlank()) score += 100
    return score
}

private fun buildUpdaterConfig(devMode: Boolean): Map<String, Any> {
    if (devMode) return emptyMap()
    if (System.getenv("NORTOOLS_DISABLE_UPDATER") == "1") {
        println("[NorTools] Updater disabled: NORTOOLS_DISABLE_UPDATER=1")
        return emptyMap()
    }
    if (PINNED_UPDATER_PUBLIC_KEY_B64.isBlank()) {
        println("[NorTools] Updater disabled: PINNED_UPDATER_PUBLIC_KEY_B64 is not configured")
        return emptyMap()
    }

    val endpoint =
        System.getenv("NORTOOLS_UPDATER_ENDPOINT")
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_UPDATER_ENDPOINT
    val resolvedEndpoint = resolveUpdaterEndpoint(endpoint)
    if (resolvedEndpoint == null) {
        println("[NorTools] Updater disabled: unsupported platform for updater target resolution")
        return emptyMap()
    }
    println(
        "[NorTools] Updater configured: endpoint=$resolvedEndpoint, target=${updaterTargetForCurrentPlatform() ?: "n/a"}"
    )

    return mapOf(
        "endpoints" to listOf(resolvedEndpoint),
        "pubkey" to PINNED_UPDATER_PUBLIC_KEY_B64,
        "timeout" to 30,
        // Explicitly checked from the web UI.
        "checkOnStartup" to false,
    )
}

private fun resolveUpdaterEndpoint(endpoint: String): String? {
    if (!endpoint.contains("{{target}}")) return endpoint
    val target = updaterTargetForCurrentPlatform() ?: return null
    return endpoint.replace("{{target}}", target)
}

private fun updaterTargetForCurrentPlatform(): String? {
    val os = System.getProperty("os.name").lowercase()
    val archRaw = System.getProperty("os.arch").lowercase()
    val arch = when (archRaw) {
        "x86_64", "amd64", "x64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> return null
    }
    return when {
        os.contains("win") -> "windows-$arch"
        os.contains("mac") || os.contains("darwin") -> "darwin-$arch"
        os.contains("linux") -> "linux-$arch"
        else -> null
    }
}

private fun runCliIfRequested(args: Array<String>): Boolean {
    val filtered = args.filterNot { it == "--ui" || it == "--dev" }
    val cmdIndex = filtered.indexOfFirst { it.isNotBlank() && !it.startsWith("-") }
    if (cmdIndex == -1) return false

    val cmdName = filtered[cmdIndex]
    val cmdArgs = filtered.filterIndexed { idx, _ -> idx != cmdIndex }.toTypedArray()
    val command = createCommand(cmdName)
    if (command == null) {
        printUnknownCommand(cmdName)
        return true
    }

    command.main(cmdArgs)
    return true
}

private fun createCommand(name: String): CliktCommand? = when (name) {
    "a" -> ALookupCommand()
    "aaaa" -> AaaaLookupCommand()
    "arin" -> ArinLookupCommand()
    "asn" -> AsnLookupCommand()
    "bimi" -> BimiLookupCommand()
    "blacklist" -> BlacklistCheckCommand()
    "blocklist" -> BlocklistCheckCommand()
    "bulk" -> BulkLookupCommand()
    "cert" -> CertLookupCommand()
    "cname" -> CnameLookupCommand()
    "compliance" -> ComplianceCommand()
    "dkim" -> DkimLookupCommand()
    "dmarc" -> DmarcLookupCommand()
    "dmarc-generator" -> DmarcGeneratorCommand()
    "dmarc-report" -> DmarcReportCommand()
    "dns-health" -> DnsHealthCommand()
    "dns-propagation" -> DnsPropagationCommand()
    "dnskey" -> DnskeyLookupCommand()
    "domain-health" -> DomainHealthCommand()
    "ds" -> DsLookupCommand()
    "email-extract" -> EmailExtractCommand()
    "deliverability" -> DeliverabilityCommand()
    "header-analyzer" -> HeaderAnalyzerCommand()
    "http" -> HttpCheckCommand()
    "https" -> HttpsCheckCommand()
    "ipseckey" -> IpseckeyLookupCommand()
    "loc" -> LocLookupCommand()
    "mailflow" -> MailFlowCommand()
    "mta-sts" -> MtaStsLookupCommand()
    "mx" -> MxLookupCommand()
    "nsec" -> NsecLookupCommand()
    "nsec3param" -> Nsec3paramLookupCommand()
    "ns" -> NsLookupCommand()
    "password-gen" -> PasswordGenCommand()
    "ping" -> PingCommand()
    "ptr" -> PtrLookupCommand()
    "rrsig" -> RrsigLookupCommand()
    "smtp" -> SmtpTestCommand()
    "soa" -> SoaLookupCommand()
    "spf" -> SpfLookupCommand()
    "spf-generator" -> SpfGeneratorCommand()
    "srv" -> SrvLookupCommand()
    "subnet-calc" -> SubnetCalcCommand()
    "tcp" -> TcpCheckCommand()
    "tlsrpt" -> TlsrptLookupCommand()
    "trace" -> TraceCommand()
    "txt" -> TxtLookupCommand()
    "whatismyip" -> WhatIsMyIpCommand()
    "whois" -> WhoisLookupCommand()
    else -> null
}

private fun printUnknownCommand(name: String) {
    val commands = listOf(
        "a", "aaaa", "arin", "asn", "bimi", "blacklist", "blocklist", "bulk", "cert", "cname",
        "compliance", "dkim", "dmarc", "dmarc-generator", "dmarc-report", "dns-health",
        "dns-propagation", "dnskey", "domain-health", "ds", "email-extract", "deliverability",
        "header-analyzer", "http", "https", "ipseckey", "loc", "mailflow", "mta-sts", "mx",
        "nsec", "nsec3param", "ns", "password-gen", "ping", "ptr", "rrsig", "smtp", "soa",
        "spf", "spf-generator", "srv", "subnet-calc", "tcp", "tlsrpt", "trace", "txt",
        "whatismyip", "whois",
    )
    System.err.println("Unknown command: $name")
    System.err.println("Available commands:")
    System.err.println(commands.joinToString(", "))
    System.err.println("Tip: add --ui to force the desktop UI.")
    exitProcess(2)
}

private fun printRootHelp() {
    val commands = listOf(
        "a", "aaaa", "arin", "asn", "bimi", "blacklist", "blocklist", "bulk", "cert", "cname",
        "compliance", "dkim", "dmarc", "dmarc-generator", "dmarc-report", "dns-health",
        "dns-propagation", "dnskey", "domain-health", "ds", "email-extract", "deliverability",
        "header-analyzer", "http", "https", "ipseckey", "loc", "mailflow", "mta-sts", "mx",
        "nsec", "nsec3param", "ns", "password-gen", "ping", "ptr", "rrsig", "smtp", "soa",
        "spf", "spf-generator", "srv", "subnet-calc", "tcp", "tlsrpt", "trace", "txt",
        "whatismyip", "whois",
    )
    println("NorTools")
    println()
    println("Usage:")
    println("  nortools <command> [args]")
    println("  nortools --ui [--dev]")
    println()
    println("CLI invocation:")
    println("  nortools dkim --json --discover nrk.no")
    println()
    println("Options:")
    println("  --ui        Force desktop UI")
    println("  --dev       Desktop dev mode")
    println("  --help, -h  Show this help")
    println()
    println("Commands:")
    println(commands.joinToString(", "))
}
