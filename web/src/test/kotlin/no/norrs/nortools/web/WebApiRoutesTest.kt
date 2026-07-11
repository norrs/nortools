package no.norrs.nortools.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class WebApiRoutesTest {

    @Test
    fun `all api routes are explicitly covered by route manifest`() {
        val source = readWebPortalSource()
        val routeRegex = "\\b(get|post)\\(\"(/api/[^\"]+)".toRegex()

        val actual = routeRegex.findAll(source)
            .map { "${it.groupValues[1].uppercase()} ${it.groupValues[2]}" }
            .toSortedSet()

        val expected = sortedSetOf(
            "GET /api/about",
            "GET /api/blacklist/{ip}",
            "GET /api/dkim-discover/{domain}",
            "GET /api/dkim/{selector}/{domain}",
            "GET /api/dmarc-generator",
            "GET /api/dmarc/{domain}",
            "GET /api/dns-health/{domain}",
            "GET /api/dns/{type}/{domain}",
            "GET /api/dnssec-chain/{domain}",
            "GET /api/dnssec/{type}/{domain}",
            "GET /api/domain-health/{domain}",
            "GET /api/http/{url}",
            "GET /api/https/{host}",
            "GET /api/iperf/client/jobs/{id}",
            "GET /api/iperf/discover",
            "GET /api/iperf/public-servers",
            "GET /api/iperf/server/status",
            "GET /api/iperf/status",
            "GET /api/network-interfaces",
            "GET /api/password",
            "GET /api/ping-stream/{host}",
            "GET /api/ping/{host}",
            "GET /api/rpki-route/{ip}",
            "GET /api/samba-browse/discover",
            "GET /api/samba-browse/{host}",
            "GET /api/reverse/{ip}",
            "GET /api/spf-generator",
            "GET /api/spf/{domain}",
            "GET /api/subnet/{cidr}",
            "GET /api/tcp/{host}/{port}",
            "GET /api/trace-visual-stream/{host}",
            "GET /api/trace-visual/{host}",
            "GET /api/trace/{host}",
            "GET /api/whatismyip",
            "GET /api/whois/{query}",
            "GET /api/zeroconf/dashboard",
            "GET /api/zeroconf/dashboard/refresh",
            "GET /api/zeroconf/device/{id}/details",
            "GET /api/zeroconf/device/{id}/documents/{index}",
            "GET /api/zeroconf/description",
            "GET /api/zeroconf/mdns/listen",
            "GET /api/zeroconf/mdns/query/{name}",
            "GET /api/zeroconf/llmnr/listen",
            "GET /api/zeroconf/llmnr/query/{name}",
            "GET /api/zeroconf/netbios/listen",
            "GET /api/zeroconf/netbios/node-status/{host}",
            "GET /api/zeroconf/netbios/query/{name}",
            "GET /api/zeroconf/ssdp/listen",
            "GET /api/zeroconf/ssdp/search",
            "GET /api/zeroconf/wsd/listen",
            "GET /api/zeroconf/wsd/probe",
            "POST /api/email-extract",
            "POST /api/iperf/client",
            "POST /api/iperf/client/start",
            "POST /api/iperf/server/start",
            "POST /api/iperf/server/stop",
        )

        assertEquals(
            expected,
            actual,
            "Web API route manifest is out of sync with WebPortal.kt. Update this test when routes change.",
        )
    }

    private fun readWebPortalSource(): String {
        val path = TestRunfiles.resolve("web/src/main/kotlin/no/norrs/nortools/web/WebPortal.kt")
            ?: error("Could not locate WebPortal.kt in runfiles")
        return Files.readString(path)
    }
}
