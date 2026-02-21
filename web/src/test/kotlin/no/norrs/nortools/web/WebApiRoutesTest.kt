package no.norrs.nortools.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

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
            "GET /api/network-interfaces",
            "GET /api/password",
            "GET /api/ping-stream/{host}",
            "GET /api/ping/{host}",
            "GET /api/rpki-route/{ip}",
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
            "POST /api/email-extract",
        )

        assertEquals(
            expected,
            actual,
            "Web API route manifest is out of sync with WebPortal.kt. Update this test when routes change.",
        )
    }

    private fun readWebPortalSource(): String {
        val testSrcDir = System.getenv("TEST_SRCDIR")
        val workspace = System.getenv("TEST_WORKSPACE") ?: "_main"
        val candidates = listOfNotNull(
            Paths.get("web/src/main/kotlin/no/norrs/nortools/web/WebPortal.kt"),
            testSrcDir?.let { Paths.get(it, workspace, "web/src/main/kotlin/no/norrs/nortools/web/WebPortal.kt") },
            testSrcDir?.let { Paths.get(it, "_main", "web/src/main/kotlin/no/norrs/nortools/web/WebPortal.kt") },
        )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("Could not locate WebPortal.kt from candidates: $candidates")
        return Files.readString(path)
    }
}
