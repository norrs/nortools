import { existsSync, readdirSync, readFileSync, statSync } from "node:fs"
import path from "node:path"

const contentRoot = path.resolve(process.cwd(), "content")
const staticRoot = path.resolve(process.cwd(), "static")
const layoutsRoot = path.resolve(process.cwd(), "layouts")
const commandOutputRoot = path.resolve(process.cwd(), "data", "command-output")
const repoRoot = path.resolve(process.cwd(), "..")
const smokeArgsRoot = path.resolve(repoRoot, "cli_native", "smoke", "args")
const errors = []

function walk(dir) {
  const entries = []
  for (const name of readdirSync(dir)) {
    const full = path.join(dir, name)
    const stat = statSync(full)
    if (stat.isDirectory()) {
      if (name === "public") continue
      entries.push(...walk(full))
    } else if (name.endsWith(".md")) {
      entries.push(full)
    }
  }
  return entries
}

function walkFiles(dir, predicate) {
  if (!existsSync(dir)) return []
  const entries = []
  for (const name of readdirSync(dir)) {
    const full = path.join(dir, name)
    const stat = statSync(full)
    if (stat.isDirectory()) {
      entries.push(...walkFiles(full, predicate))
    } else if (predicate(full)) {
      entries.push(full)
    }
  }
  return entries
}

function checkMermaidFenceBalance(file, text) {
  const openings = [...text.matchAll(/^```mermaid\s*$/gm)].length
  if (openings === 0) return

  const fenceCount = [...text.matchAll(/^```\s*$/gm)].length
  if (fenceCount < openings) {
    errors.push(`${path.relative(repoRoot, file)} has an unclosed mermaid fence`)
  }
}

function checkScreenshotLinks(file, text) {
  const matches = text.matchAll(/\]\((\/screenshots\/[^)]+)\)/g)
  for (const match of matches) {
    const target = path.join(staticRoot, match[1])
    if (!existsSync(target)) {
      errors.push(`${path.relative(repoRoot, file)} references missing screenshot ${match[1]}`)
    }
  }
}

function checkGeneratedReferences() {
  if (!existsSync(path.join(contentRoot, "reference", "cli.md"))) {
    errors.push("reference/cli.md was not generated")
  }
  if (!existsSync(path.join(contentRoot, "reference", "ui-routes.md"))) {
    errors.push("reference/ui-routes.md was not generated")
  }
  if (!existsSync(path.join(contentRoot, "reference", "standards.md"))) {
    errors.push("reference/standards.md was not generated")
  }
  if (!existsSync(path.join(staticRoot, "generated", "tool-inventory.json"))) {
    errors.push("tool inventory JSON was not generated")
  }
}

function smokeCommands() {
  return readdirSync(smokeArgsRoot)
    .filter((name) => name.endsWith(".args"))
    .map((name) => {
      const command = readFileSync(path.join(smokeArgsRoot, name), "utf8")
        .split(/\r?\n/)
        .map((line) => line.trim())
        .find(Boolean)
      return { name, command }
    })
    .filter((entry) => entry.command)
}

function checkGeneratedCommandPages() {
  for (const entry of smokeCommands()) {
    const outputFile = path.join(commandOutputRoot, `${entry.command}.json`)
    if (!existsSync(outputFile)) {
      errors.push(`missing captured output snapshot for ${entry.command}`)
      continue
    }
    const output = JSON.parse(readFileSync(outputFile, "utf8"))
    if (output.exitCode !== 0) {
      errors.push(`captured output for ${entry.command} has non-zero exitCode ${output.exitCode}`)
    }
    const pageFile = path.join(contentRoot, "tools", `${entry.command}.md`)
    if (!existsSync(pageFile)) {
      errors.push(`generated command page missing for ${entry.command}`)
    }
  }
}

function checkNoEndUserBazelSnippets(file, text) {
  if (file.includes(`${path.sep}getting-started${path.sep}install.md`)) return
  if (text.includes("bazelisk run")) {
    errors.push(`${path.relative(repoRoot, file)} contains bazelisk run outside contributor-focused docs`)
  }
}

function checkNoRawAbsoluteSiteLinks(file, text) {
  const rawMatches = text.matchAll(/\b(?:href|src)="\/(?!\/)([^"]+)"/g)
  for (const match of rawMatches) {
    errors.push(`${path.relative(repoRoot, file)} uses raw absolute site link /${match[1]}; use a relative link or Hugo relURL`)
  }

  const markdownMatches = text.matchAll(/\]\(\/(?!\/)([^)]+)\)/g)
  for (const match of markdownMatches) {
    if (match[1].startsWith("screenshots/")) continue
    errors.push(`${path.relative(repoRoot, file)} uses absolute markdown link /${match[1]}; use a relative link`)
  }
}

function checkSectionIndexTitle(file, text) {
  if (!file.endsWith(`${path.sep}_index.md`)) return
  const frontMatter = text.match(/^---\r?\n([\s\S]*?)\r?\n---/)
  if (!frontMatter || !/^title:\s*.+$/m.test(frontMatter[1])) {
    errors.push(`${path.relative(repoRoot, file)} must define Hugo front matter title`)
  }
}

for (const file of walk(contentRoot)) {
  const text = readFileSync(file, "utf8")
  checkMermaidFenceBalance(file, text)
  checkScreenshotLinks(file, text)
  checkNoEndUserBazelSnippets(file, text)
  checkNoRawAbsoluteSiteLinks(file, text)
  checkSectionIndexTitle(file, text)
}

for (const file of walkFiles(layoutsRoot, (name) => name.endsWith(".html"))) {
  const text = readFileSync(file, "utf8")
  const leadingSlashRelUrl = text.match(/{{\s*"\/[^"]+"\s*\|\s*relURL\s*}}/g)
  if (leadingSlashRelUrl) {
    errors.push(`${path.relative(repoRoot, file)} passes a leading-slash literal to relURL; use a path relative to baseURL`)
  }
}

checkGeneratedReferences()
checkGeneratedCommandPages()

if (errors.length > 0) {
  for (const error of errors) {
    console.error(`ERROR: ${error}`)
  }
  process.exit(1)
}

console.log("docs-site checks passed")
