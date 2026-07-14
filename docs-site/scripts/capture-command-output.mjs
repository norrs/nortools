import { mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs"
import path from "node:path"
import { spawnSync } from "node:child_process"

const repoRoot = path.resolve(process.cwd(), "..")
const outputRoot = path.resolve(process.cwd(), "data", "command-output")

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
      return { name, command: args[0], args }
    })
    .filter((entry) => entry.command)
    .sort((a, b) => a.command.localeCompare(b.command) || a.name.localeCompare(b.name))
}

function localArgs(args) {
  return args.map((arg) => {
    if (arg.startsWith("_main/")) return arg.slice("_main/".length)
    if (arg.startsWith("_main\\")) return arg.slice("_main\\".length)
    return arg
  })
}

function run(command, args, options = {}) {
  const env = { ...process.env, ...(options.env ?? {}) }
  if (options.cleanRunfilesEnv) {
    for (const key of Object.keys(env)) {
      if (key.startsWith("RUNFILES_") || key === "JAVA_RUNFILES" || key === "TEST_SRCDIR") {
        delete env[key]
      }
    }
  }

  const result = spawnSync(command, args, {
    cwd: repoRoot,
    encoding: "utf8",
    env,
    timeout: options.timeout ?? 45000,
    shell: process.platform === "win32",
  })
  return {
    status: result.status,
    signal: result.signal,
    stdout: result.stdout ?? "",
    stderr: result.stderr ?? "",
    error: result.error?.message ?? null,
  }
}

function desktopJar() {
  return path.join(repoRoot, "bazel-bin", "desktop", "desktop_jar_deploy.jar")
}

console.log("Building //desktop:desktop_jar_deploy.jar before capturing command output...")
const build = run("bazelisk", ["build", "//desktop:desktop_jar_deploy.jar"], { timeout: 120000 })
if (build.status !== 0) {
  process.stderr.write(build.stdout)
  process.stderr.write(build.stderr)
  process.exit(build.status ?? 1)
}

ensureDir(outputRoot)
const jar = desktopJar()
const examples = smokeExamples()
let failures = 0

for (const entry of examples) {
  const args = localArgs(entry.args)
  const result = run("java", ["-jar", jar, ...args], { cleanRunfilesEnv: true })
  const output = result.stdout.trimEnd()
  const errorOutput = result.stderr.trimEnd()
  const snapshot = {
    command: entry.command,
    title: titleFromCommand(entry.command),
    smokeArgsFile: entry.name,
    invocation: ["nortools", ...args].join(" "),
    capturedAt: new Date().toISOString(),
    exitCode: result.status,
    timedOut: result.signal === "SIGTERM",
    output: output || errorOutput || result.error || "",
  }

  writeFileSync(path.join(outputRoot, `${entry.command}.json`), JSON.stringify(snapshot, null, 2))

  if (result.status !== 0) {
    failures += 1
    console.error(`WARN: ${entry.command} exited with ${result.status}`)
  } else {
    console.log(`captured ${entry.command}`)
  }
}

if (failures > 0) {
  console.error(`Captured ${examples.length} commands with ${failures} non-zero exits. Snapshots were still written.`)
} else {
  console.log(`Captured ${examples.length} command outputs.`)
}
