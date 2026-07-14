import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import path from "node:path"

const repoRoot = path.resolve(process.cwd(), "..")
const contentRoot = path.resolve(process.cwd(), "content")
const staticRoot = path.resolve(process.cwd(), "static")
const commandOutputRoot = path.resolve(process.cwd(), "data", "command-output")

function readText(relativePath) {
  return readFileSync(path.join(repoRoot, relativePath), "utf8")
}

function ensureDir(dir) {
  mkdirSync(dir, { recursive: true })
}

function titleFromCommand(command) {
  const acronyms = new Map([
    ["a", "A"],
    ["aaaa", "AAAA"],
    ["arin", "ARIN"],
    ["asn", "ASN"],
    ["bimi", "BIMI"],
    ["cname", "CNAME"],
    ["dkim", "DKIM"],
    ["dmarc", "DMARC"],
    ["dns", "DNS"],
    ["dnskey", "DNSKEY"],
    ["dnssec", "DNSSEC"],
    ["ds", "DS"],
    ["http", "HTTP"],
    ["https", "HTTPS"],
    ["ipseckey", "IPSECKEY"],
    ["loc", "LOC"],
    ["mta", "MTA"],
    ["mx", "MX"],
    ["ns", "NS"],
    ["nsec", "NSEC"],
    ["nsec3param", "NSEC3PARAM"],
    ["ptr", "PTR"],
    ["rrsig", "RRSIG"],
    ["smtp", "SMTP"],
    ["soa", "SOA"],
    ["spf", "SPF"],
    ["srv", "SRV"],
    ["tcp", "TCP"],
    ["tlsrpt", "TLSRPT"],
    ["txt", "TXT"],
    ["whois", "WHOIS"],
  ])
  return command
    .split("-")
    .map((part) => acronyms.get(part) ?? part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ")
}

function smokeExamples() {
  const argsDir = path.join(repoRoot, "cli_native", "smoke", "args")
  return readdirSync(argsDir)
    .filter((name) => name.endsWith(".args"))
    .map((name) => {
      const args = readFileSync(path.join(argsDir, name), "utf8")
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter(Boolean)
      const command = args[0]
      const commandArgs = args.slice(1).filter((arg) => arg !== "--json")
      return {
        name,
        command,
        example: ["nortools", command, ...commandArgs].join(" "),
        jsonExample: ["nortools", command, "--json", ...commandArgs].join(" "),
        jsonArgs: args,
      }
    })
    .filter((entry) => entry.command)
    .sort((a, b) => a.command.localeCompare(b.command))
}

function commandOutput(command) {
  const file = path.join(commandOutputRoot, `${command}.json`)
  if (!existsSync(file)) return null
  return JSON.parse(readFileSync(file, "utf8"))
}

function uiRoutes() {
  const webPortal = readText("web/src/main/kotlin/no/norrs/nortools/web/WebPortal.kt")
  const routePattern = /get\("([^"]+)",\s*VueComponent\("([^"]+)"\)\)/g
  const routes = []
  let match
  while ((match = routePattern.exec(webPortal)) !== null) {
    routes.push({ path: match[1], component: match[2] })
  }
  return routes.sort((a, b) => a.path.localeCompare(b.path))
}

function copyScreenshots() {
  const sourceDir = path.join(repoRoot, "docs", "screenshots")
  const targetDir = path.join(staticRoot, "screenshots")
  ensureDir(targetDir)
  for (const name of readdirSync(sourceDir).filter((item) => item.endsWith(".png"))) {
    copyFileSync(path.join(sourceDir, name), path.join(targetDir, name))
  }
}

function copyAssets() {
  const targetDir = path.join(staticRoot, "assets")
  ensureDir(targetDir)
  const logo = path.join(repoRoot, "docs", "assets", "nortools-logo.png")
  if (existsSync(logo)) {
    copyFileSync(logo, path.join(targetDir, "nortools-logo.png"))
  }
}

function writeCliReference(examples) {
  const lines = [
    "# CLI Reference",
    "",
    "This page is generated from native CLI smoke examples.",
    "",
    "Use `--json` for machine-readable output.",
    "",
  ]

  for (const entry of examples) {
    lines.push(`## ${titleFromCommand(entry.command)}`)
    lines.push("")
    lines.push("```bash")
    lines.push(entry.example)
    lines.push(entry.jsonExample)
    lines.push("```")
    lines.push("")
  }

  writeFileSync(path.join(contentRoot, "reference", "cli.md"), lines.join("\n"))
}

function writeCommandPages(examples) {
  const toolsRoot = path.join(contentRoot, "tools")
  for (const file of readdirSync(toolsRoot).filter((name) => name.endsWith(".md"))) {
    rmSync(path.join(toolsRoot, file), { force: true })
  }

  for (const entry of examples) {
    const output = commandOutput(entry.command)
    const lines = [
      "---",
      `title: ${titleFromCommand(entry.command)}`,
      "---",
      "",
      `# ${titleFromCommand(entry.command)}`,
      "",
      `Command page generated from \`${entry.name}\`.`,
      "",
      "## Example Command",
      "",
      "```bash",
      output?.invocation ?? entry.jsonExample,
      "```",
      "",
      "## Example Response",
      "",
    ]

    if (output) {
      if (output.exitCode !== 0) {
        lines.push(`This command exited with code \`${output.exitCode}\` when the documentation snapshot was captured.`)
        lines.push("")
      }
      lines.push("```json")
      lines.push(output.output || "(no output)")
      lines.push("```")
      lines.push("")
      lines.push(`Snapshot source: \`${output.smokeArgsFile}\`.`)
    } else {
      lines.push("Command output has not been captured yet.")
      lines.push("")
      lines.push("Run `bazelisk run //docs-site:capture_command_output` from the repository root, then rebuild the docs.")
    }

    lines.push("")
    writeFileSync(path.join(toolsRoot, `${entry.command}.md`), lines.join("\n"))
  }
}

function writeUiRoutes(routes) {
  const lines = [
    "# UI Routes",
    "",
    "This page is generated from the Javalin Vue route declarations.",
    "",
    "| Route | Component |",
    "|---|---|",
    ...routes.map((route) => `| \`${route.path}\` | \`${route.component}\` |`),
    "",
  ]
  writeFileSync(path.join(contentRoot, "reference", "ui-routes.md"), lines.join("\n"))
}

function writeStandardsReference() {
  const rfc = readText("RFC.md")
  writeFileSync(
    path.join(contentRoot, "reference", "standards.md"),
    [
      "# Standards Reference",
      "",
      "This page mirrors the repository standards reference.",
      "",
      rfc,
    ].join("\n"),
  )
}

function writeInventory(examples, routes) {
  ensureDir(path.join(staticRoot, "generated"))
  writeFileSync(
    path.join(staticRoot, "generated", "tool-inventory.json"),
    JSON.stringify({ generatedAt: new Date().toISOString(), cli: examples, uiRoutes: routes }, null, 2),
  )
}

ensureDir(path.join(contentRoot, "reference"))
copyScreenshots()
copyAssets()
const examples = smokeExamples()
const routes = uiRoutes()
writeCliReference(examples)
writeCommandPages(examples)
writeUiRoutes(routes)
writeStandardsReference()
writeInventory(examples, routes)
