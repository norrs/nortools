package no.norrs.nortools.tools.whois.asn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class RoutinatorRouteValidatorTest {

    @Test
    fun `parseRoutinatorValidateJson parses valid state`() {
        val json = """
            {
              "validated_route":{"origin_asn":"AS13335","prefix":"1.1.1.0/24"},
              "validity":{"state":"valid","description":"At least one VRP Matches the Route"}
            }
        """.trimIndent()

        val result = parseRoutinatorValidateJson(json)
        assertNotNull(result)
        assertEquals("VALID", result!!.state)
        assertEquals("routinator", result.source)
        assertTrue(result.details!!.contains("At least one VRP"))
        assertNull(result.vrpObjects)
    }

    @Test
    fun `parseRoutinatorValidateJson parses invalid state`() {
        val json = """
            {
              "validated_route":{"origin_asn":"AS64500","prefix":"203.0.113.0/24"},
              "validity":{"state":"invalid","reason":"asn","description":"At least one VRP Covers the Route Prefix, but no VRP ASN matches the route origin ASN"}
            }
        """.trimIndent()

        val result = parseRoutinatorValidateJson(json)
        assertNotNull(result)
        assertEquals("INVALID", result!!.state)
        assertEquals("asn", result.reason)
        assertNull(result.vrpObjects)
    }

    @Test
    fun `parseRoutinatorValidateJson parses not-found state`() {
        val json = """
            {
              "validated_route":{"origin_asn":"AS64500","prefix":"198.51.100.0/24"},
              "validity":{"state":"not-found","reason":"none","description":"No VRP Covers the Route Prefix"}
            }
        """.trimIndent()

        val result = parseRoutinatorValidateJson(json)
        assertNotNull(result)
        assertEquals("NOT_FOUND", result!!.state)
        assertEquals("none", result.reason)
        assertNull(result.vrpObjects)
    }

    @Test
    fun `parseRoutinatorValidateJson parses newer validated_routes format`() {
        val json = """
            {
              "validated_routes":[
                {
                  "route":{"origin_asn":"AS13335","prefix":"1.1.1.0/24"},
                  "validity":{
                    "state":"invalid",
                    "description":"At least one VRP Covers the Route Prefix, but no VRP ASN matches the route origin ASN",
                    "VRPs":{"matched":[],"unmatched_as":[{"asn":"AS64512"}],"unmatched_length":[]}
                  }
                }
              ]
            }
        """.trimIndent()

        val result = parseRoutinatorValidateJson(json)
        assertNotNull(result)
        assertEquals("INVALID", result!!.state)
        assertTrue(result.details!!.contains("VRPs:"))
        assertNotNull(result.vrpObjects)
        assertEquals(0, result.vrpObjects!!.matched.size)
        assertEquals(1, result.vrpObjects!!.unmatchedAs.size)
        assertEquals(0, result.vrpObjects!!.unmatchedLength.size)
        assertEquals("AS64512", result.vrpObjects!!.unmatchedAs.first()["asn"])
    }

    @Test
    fun `parseRoutinatorValidateJson parses matching objects from top-level validity`() {
        val json = """
            {
              "validity":{
                "state":"invalid",
                "matched":[{"asn":"AS13335","prefix":"1.1.1.0/24","max_length":24}],
                "unmatched_as":[{"asn":"AS64496","prefix":"1.1.1.0/24","max_length":24}],
                "unmatched_length":[{"asn":"AS13335","prefix":"1.1.1.0/24","max_length":23}]
              }
            }
        """.trimIndent()

        val result = parseRoutinatorValidateJson(json)
        assertNotNull(result)
        assertEquals("INVALID", result!!.state)
        assertNotNull(result.vrpObjects)
        assertEquals(1, result.vrpObjects!!.matched.size)
        assertEquals(1, result.vrpObjects!!.unmatchedAs.size)
        assertEquals(1, result.vrpObjects!!.unmatchedLength.size)
        assertEquals("23", result.vrpObjects!!.unmatchedLength.first()["max_length"])
    }

    @Test
    fun `routinatorResourceCandidates maps linux amd64`() {
        val candidates = routinatorResourceCandidates("Linux", "amd64")
        assertEquals(listOf("native/routinator/linux-x64/routinator"), candidates)
    }

    @Test
    fun `validate returns unavailable when binary cannot be resolved`() {
        val validator = RoutinatorRouteValidator(
            explicitBinary = "/does/not/exist/routinator",
            envBinary = null,
            commandExecutor = CommandExecutor { _, _ ->
                CommandExecutionResult(exitCode = 127, stdout = "", stderr = "not found")
            },
            osName = "Linux",
            archName = "x86_64",
        )

        val result = validator.validate(prefix = "1.1.1.0/24", asn = "13335")
        assertEquals("UNAVAILABLE", result.state)
        assertEquals("binary-missing", result.reason)
    }

    @Test
    fun `validate uses explicit binary and parses JSON output`() {
        val executable = File.createTempFile("routinator-test", ".bin")
        executable.writeText("#!/bin/sh\nexit 0\n")
        executable.setExecutable(true, true)

        val seenCommands = mutableListOf<List<String>>()
        val validator = RoutinatorRouteValidator(
            explicitBinary = executable.absolutePath,
            envBinary = null,
            commandExecutor = CommandExecutor { command, _ ->
                seenCommands += command
                when {
                    command.lastOrNull() == "--version" -> {
                        CommandExecutionResult(0, "routinator 0.15.0", "")
                    }
                    command.contains("validate") -> {
                        CommandExecutionResult(
                            0,
                            """{"validated_routes":[{"route":{"origin_asn":"AS13335","prefix":"1.1.1.0/24"},"validity":{"state":"valid"}}]}""",
                            "",
                        )
                    }
                    else -> CommandExecutionResult(1, "", "unexpected command")
                }
            },
            osName = "Linux",
            archName = "x86_64",
        )

        val result = validator.validate(prefix = "1.1.1.0/24", asn = "AS13335")
        assertEquals("VALID", result.state)
        assertEquals("explicit", result.source)
        assertTrue(seenCommands.any { it.contains("--json") })
        assertTrue(seenCommands.any { it.contains("--asn") })
        assertTrue(seenCommands.any { it.contains("--prefix") })
    }

    @Test
    fun `validate falls back to legacy CLI args when new flags are unsupported`() {
        val executable = File.createTempFile("routinator-test", ".bin")
        executable.writeText("#!/bin/sh\nexit 0\n")
        executable.setExecutable(true, true)

        val seenCommands = mutableListOf<List<String>>()
        val validator = RoutinatorRouteValidator(
            explicitBinary = executable.absolutePath,
            envBinary = null,
            commandExecutor = CommandExecutor { command, _ ->
                seenCommands += command
                when {
                    command.lastOrNull() == "--version" -> {
                        CommandExecutionResult(0, "routinator 0.14.0", "")
                    }
                    command.contains("--json") -> {
                        CommandExecutionResult(2, "", "error: unexpected argument '--json' found")
                    }
                    command.contains("--output-format") -> {
                        CommandExecutionResult(0, """{"validity":{"state":"valid"}}""", "")
                    }
                    else -> CommandExecutionResult(1, "", "unexpected command")
                }
            },
            osName = "Linux",
            archName = "x86_64",
        )

        val result = validator.validate(prefix = "1.1.1.0/24", asn = "AS13335")
        assertEquals("VALID", result.state)
        assertEquals("explicit", result.source)
        assertTrue(seenCommands.any { it.contains("--json") })
        assertTrue(seenCommands.any { it.contains("--output-format") })
    }

    @Test
    fun `validate falls back to positional json when output-format is unsupported`() {
        val executable = File.createTempFile("routinator-test", ".bin")
        executable.writeText("#!/bin/sh\nexit 0\n")
        executable.setExecutable(true, true)

        val seenCommands = mutableListOf<List<String>>()
        val validator = RoutinatorRouteValidator(
            explicitBinary = executable.absolutePath,
            envBinary = null,
            commandExecutor = CommandExecutor { command, _ ->
                seenCommands += command
                when {
                    command.lastOrNull() == "--version" -> {
                        CommandExecutionResult(0, "routinator 0.13.0", "")
                    }
                    command.contains("--output-format") -> {
                        CommandExecutionResult(2, "", "error: unexpected argument '--output-format' found")
                    }
                    command.contains("--json") && command.contains("--asn") -> {
                        CommandExecutionResult(2, "", "error: unexpected argument '--asn' found")
                    }
                    command.contains("--json") && !command.contains("--asn") -> {
                        CommandExecutionResult(0, """{"validity":{"state":"valid"}}""", "")
                    }
                    else -> CommandExecutionResult(1, "", "unexpected command")
                }
            },
            osName = "Linux",
            archName = "x86_64",
        )

        val result = validator.validate(prefix = "1.1.1.0/24", asn = "AS13335")
        assertEquals("VALID", result.state)
        assertTrue(seenCommands.any { it.contains("--output-format") })
        assertTrue(seenCommands.any { it.contains("--json") && !it.contains("--asn") })
    }

    @Test
    fun `validate returns first non-compatibility error without masking it`() {
        val executable = File.createTempFile("routinator-test", ".bin")
        executable.writeText("#!/bin/sh\nexit 0\n")
        executable.setExecutable(true, true)

        val seenCommands = mutableListOf<List<String>>()
        val validator = RoutinatorRouteValidator(
            explicitBinary = executable.absolutePath,
            envBinary = null,
            commandExecutor = CommandExecutor { command, _ ->
                seenCommands += command
                when {
                    command.lastOrNull() == "--version" -> {
                        CommandExecutionResult(0, "routinator 0.15.1", "")
                    }
                    command.contains("validate") -> {
                        CommandExecutionResult(2, "", "error: invalid value '2001:67c:550::1/48' for '--prefix <PREFIX>': non-zero host portion")
                    }
                    else -> CommandExecutionResult(1, "", "unexpected command")
                }
            },
            osName = "Linux",
            archName = "x86_64",
        )

        val result = validator.validate(prefix = "2001:67c:550::1/48", asn = "AS202068")
        assertEquals("UNAVAILABLE", result.state)
        assertEquals("execution-failed", result.reason)
        assertTrue(result.details!!.contains("non-zero host portion"))
        assertEquals(2, seenCommands.size)
        assertTrue(seenCommands.last().contains("--asn"))
    }

    @Test
    fun `validate discovers bundled binary from user dir`() {
        val tempDir = Files.createTempDirectory("routinator-bundled-test").toFile()
        val bundled = File(tempDir, "routinator")
        bundled.writeText("stub")

        val originalUserDir = System.getProperty("user.dir")
        try {
            System.setProperty("user.dir", tempDir.absolutePath)
            val validator = RoutinatorRouteValidator(
                explicitBinary = null,
                envBinary = null,
                commandExecutor = CommandExecutor { command, _ ->
                    when {
                        command.lastOrNull() == "--version" && command.firstOrNull() == bundled.absolutePath -> {
                            CommandExecutionResult(0, "routinator 0.15.0", "")
                        }
                        command.contains("validate") && command.firstOrNull() == bundled.absolutePath -> {
                            CommandExecutionResult(0, """{"validity":{"state":"valid"}}""", "")
                        }
                        else -> CommandExecutionResult(127, "", "not found")
                    }
                },
                osName = "Linux",
                archName = "x86_64",
            )

            val result = validator.validate(prefix = "1.1.1.0/24", asn = "AS13335")
            assertEquals("VALID", result.state)
            assertEquals("bundled", result.source)
        } finally {
            if (originalUserDir != null) {
                System.setProperty("user.dir", originalUserDir)
            }
            tempDir.deleteRecursively()
        }
    }
}
