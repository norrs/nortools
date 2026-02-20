<template id="about-page">
  <div class="tool-view about-page">
    <h2>About</h2>
    <p class="desc">Build information, credits, inspiration, and RFC notes.</p>

    <div v-if="loading">Loading...</div>
    <div v-else-if="error" class="error">{{ error }}</div>

    <div v-else-if="about" class="about-grid">
      <section class="card">
        <h3>{{ about.appName }}</h3>
        <div class="kv"><span>Version</span><strong>{{ about.version }}</strong></div>
        <div class="kv"><span>Git Commit</span><code>{{ about.build.gitCommit || 'unknown' }}</code></div>
        <div class="kv"><span>Git Short</span><code>{{ about.build.gitShortCommit || 'unknown' }}</code></div>
        <div class="kv"><span>Git Branch</span><code>{{ about.build.gitBranch || 'unknown' }}</code></div>
        <div class="kv"><span>Git Dirty</span><code>{{ about.build.gitDirty || 'unknown' }}</code></div>
        <div class="kv"><span>Build Time</span><code>{{ about.build.buildTime || 'unknown' }}</code></div>
        <div class="kv"><span>Build Target</span><code>{{ about.build.target || 'unknown' }}</code></div>
        <div class="kv"><span>Main Class</span><code>{{ about.build.mainClass || 'unknown' }}</code></div>
      </section>

      <section class="card">
        <h3>Credits</h3>
        <pre class="doc">{{ about.credits }}</pre>
      </section>

      <section class="card">
        <h3>Inspiration</h3>
        <pre class="doc">{{ about.inspiration }}</pre>
      </section>

      <section class="card">
        <h3>RFC</h3>
        <div class="doc md-doc" v-html="rfcHtml" @click="handleMarkdownLinkClick"></div>
      </section>
    </div>
  </div>
</template>

<script>
app.component("about-page", {
  template: "#about-page",
  data() {
    return {
      loading: false,
      error: "",
      about: null,
    }
  },
  computed: {
    rfcHtml() {
      return this.renderMarkdown(this.about?.rfc ?? "")
    },
  },
  mounted() {
    this.load()
  },
  methods: {
    hasKremaBridge() {
      return !!(window.krema && typeof window.krema.invoke === "function")
    },
    escapeHtml(input) {
      return String(input)
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;')
    },
    sanitizeUrl(url) {
      const trimmed = String(url || "").trim()
      if (/^https?:\/\//i.test(trimmed) || /^mailto:/i.test(trimmed)) return trimmed
      return '#'
    },
    renderInlineMarkdown(input) {
      let out = this.escapeHtml(String(input || ""))
      out = out.replace(/`([^`]+)`/g, '<code>$1</code>')
      out = out.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      out = out.replace(/\*([^*]+)\*/g, '<em>$1</em>')
      out = out.replace(/\[([^\]]+)]\(([^)]+)\)/g, (_m, text, url) => {
        const safe = this.sanitizeUrl(url)
        return `<a href="${safe}" target="_blank" rel="noopener noreferrer">${this.escapeHtml(text)}</a>`
      })
      return out
    },
    isTableDelimiterRow(line) {
      const normalized = String(line || "").trim().replace(/^\|/, '').replace(/\|$/, '')
      const cells = normalized.split('|').map((c) => c.trim())
      if (!cells.length) return false
      return cells.every((cell) => /^:?-{3,}:?$/.test(cell))
    },
    parseTableCells(line) {
      const normalized = String(line || "").trim().replace(/^\|/, '').replace(/\|$/, '')
      return normalized.split('|').map((c) => c.trim())
    },
    renderMarkdown(input) {
      const lines = String(input || "").replace(/\r\n/g, '\n').split('\n')
      const parts = []
      let inCodeBlock = false
      const codeLines = []
      let listMode = null

      const closeList = () => {
        if (!listMode) return
        parts.push(listMode === 'ul' ? '</ul>' : '</ol>')
        listMode = null
      }

      let i = 0
      while (i < lines.length) {
        const line = lines[i]
        if (line.trimStart().startsWith('```')) {
          closeList()
          if (inCodeBlock) {
            parts.push(`<pre><code>${this.escapeHtml(codeLines.join('\n'))}</code></pre>`)
            codeLines.length = 0
            inCodeBlock = false
          } else {
            inCodeBlock = true
          }
          i += 1
          continue
        }
        if (inCodeBlock) {
          codeLines.push(line)
          i += 1
          continue
        }

        if (!line.trim()) {
          closeList()
          i += 1
          continue
        }

        const next = i + 1 < lines.length ? lines[i + 1] : ''
        if (line.includes('|') && this.isTableDelimiterRow(next)) {
          closeList()
          const header = this.parseTableCells(line)
          parts.push('<table><thead><tr>')
          for (const cell of header) {
            parts.push(`<th>${this.renderInlineMarkdown(cell)}</th>`)
          }
          parts.push('</tr></thead><tbody>')
          i += 2
          while (i < lines.length) {
            const row = lines[i]
            if (!row.trim() || !row.includes('|')) break
            const cells = this.parseTableCells(row)
            parts.push('<tr>')
            for (const cell of cells) {
              parts.push(`<td>${this.renderInlineMarkdown(cell)}</td>`)
            }
            parts.push('</tr>')
            i += 1
          }
          parts.push('</tbody></table>')
          continue
        }

        const heading = line.match(/^(#{1,6})\s+(.*)$/)
        if (heading) {
          closeList()
          const level = heading[1].length
          parts.push(`<h${level}>${this.renderInlineMarkdown(heading[2])}</h${level}>`)
          i += 1
          continue
        }

        const quote = line.match(/^>\s?(.*)$/)
        if (quote) {
          closeList()
          parts.push(`<blockquote>${this.renderInlineMarkdown(quote[1])}</blockquote>`)
          i += 1
          continue
        }

        const ol = line.match(/^\d+\.\s+(.*)$/)
        if (ol) {
          if (listMode !== 'ol') {
            closeList()
            parts.push('<ol>')
            listMode = 'ol'
          }
          parts.push(`<li>${this.renderInlineMarkdown(ol[1])}</li>`)
          i += 1
          continue
        }

        const ul = line.match(/^[-*+]\s+(.*)$/)
        if (ul) {
          if (listMode !== 'ul') {
            closeList()
            parts.push('<ul>')
            listMode = 'ul'
          }
          parts.push(`<li>${this.renderInlineMarkdown(ul[1])}</li>`)
          i += 1
          continue
        }

        closeList()
        parts.push(`<p>${this.renderInlineMarkdown(line)}</p>`)
        i += 1
      }

      closeList()
      if (inCodeBlock) {
        parts.push(`<pre><code>${this.escapeHtml(codeLines.join('\n'))}</code></pre>`)
      }
      return parts.join('\n')
    },
    async openExternalUrl(url) {
      if (this.hasKremaBridge()) {
        await window.krema.invoke("shell:openUrl", { url: String(url) })
        return
      }
      window.open(String(url), "_blank", "noopener,noreferrer")
    },
    handleMarkdownLinkClick(event) {
      const target = event && event.target
      if (!target || typeof target.closest !== "function") return
      const anchor = target.closest("a[href]")
      if (!anchor) return
      const href = String(anchor.getAttribute("href") || "").trim()
      if (!href || href === "#") return
      event.preventDefault()
      this.openExternalUrl(href).catch(() => {
        window.open(href, "_blank", "noopener,noreferrer")
      })
    },
    async load() {
      this.loading = true
      this.error = ''
      try {
        const res = await fetch('/api/about')
        if (!res.ok) throw new Error(`API error: ${res.status} ${res.statusText}`)
        this.about = await res.json()
      } catch (e) {
        this.error = e instanceof Error ? e.message : 'Failed to load about info'
      } finally {
        this.loading = false
      }
    },
  },
})
</script>

<style>
.about-page { padding: 1rem 0; }
.about-page h2 { margin-bottom: 0.25rem; }
.about-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.about-page .error { color: #d32f2f; margin-bottom: 1rem; }
.about-page .about-grid { display: grid; gap: 1rem; }
.about-page .card { background: white; border-radius: 8px; padding: 1rem; box-shadow: 0 1px 2px rgba(0,0,0,0.06); }
.about-page .kv { display: grid; grid-template-columns: 120px 1fr; gap: 0.75rem; margin: 0.35rem 0; }
.about-page .kv span { color: #64748b; }
.about-page .doc { white-space: pre-wrap; word-break: break-word; font-size: 0.85rem; line-height: 1.45; background: #f8fafc; border-radius: 6px; padding: 0.75rem; }
.about-page .md-doc { white-space: normal; overflow-x: auto; }
.about-page .md-doc h1,
.about-page .md-doc h2,
.about-page .md-doc h3 { margin: 0.4rem 0 0.5rem; line-height: 1.25; }
.about-page .md-doc h4,
.about-page .md-doc h5,
.about-page .md-doc h6 { margin: 0.35rem 0 0.45rem; line-height: 1.25; }
.about-page .md-doc p,
.about-page .md-doc ul,
.about-page .md-doc ol,
.about-page .md-doc blockquote { margin: 0.5rem 0; }
.about-page .md-doc ul,
.about-page .md-doc ol { padding-left: 1.25rem; }
.about-page .md-doc li { margin: 0.2rem 0; }
.about-page .md-doc blockquote { border-left: 3px solid #cbd5e1; padding-left: 0.6rem; color: #334155; }
.about-page .md-doc code { background: #e2e8f0; border-radius: 4px; padding: 0.08rem 0.25rem; }
.about-page .md-doc pre { background: #0f172a; color: #e2e8f0; border-radius: 6px; padding: 0.75rem; overflow-x: auto; }
.about-page .md-doc pre code { background: transparent; padding: 0; }
.about-page .md-doc a { color: #1d4ed8; }
.about-page .md-doc table { width: 100%; border-collapse: collapse; margin: 0.6rem 0; font-size: 0.84rem; }
.about-page .md-doc th,
.about-page .md-doc td { border: 1px solid #cbd5e1; padding: 0.45rem 0.55rem; text-align: left; vertical-align: top; }
.about-page .md-doc th { background: #e2e8f0; color: #1e293b; font-weight: 700; }
.about-page .md-doc tr:nth-child(even) td { background: #f8fafc; }
</style>
