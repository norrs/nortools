<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { aboutInfo } from '../api/client';

interface AboutBuildInfo {
  target?: string;
  mainClass?: string;
  buildTime?: string;
  buildTimestamp?: string;
  gitCommit?: string;
  gitShortCommit?: string;
  gitBranch?: string;
  gitDirty?: string;
}

interface AboutResponse {
  appName: string;
  version: string;
  build: AboutBuildInfo;
  credits: string;
  inspiration: string;
  rfc: string;
}

const loading = ref(false);
const error = ref('');
const about = ref<AboutResponse | null>(null);
const rfcHtml = computed(() => renderMarkdown(about.value?.rfc ?? ''));

function escapeHtml(input: string): string {
  return input
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function sanitizeUrl(url: string): string {
  const trimmed = url.trim();
  if (/^https?:\/\//i.test(trimmed) || /^mailto:/i.test(trimmed)) return trimmed;
  return '#';
}

function renderInlineMarkdown(input: string): string {
  let out = escapeHtml(input);
  out = out.replace(/`([^`]+)`/g, '<code>$1</code>');
  out = out.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  out = out.replace(/\*([^*]+)\*/g, '<em>$1</em>');
  out = out.replace(/\[([^\]]+)]\(([^)]+)\)/g, (_m, text: string, url: string) => {
    const safe = sanitizeUrl(url);
    return `<a href="${safe}" target="_blank" rel="noopener noreferrer">${escapeHtml(text)}</a>`;
  });
  return out;
}

function isTableDelimiterRow(line: string): boolean {
  const normalized = line.trim().replace(/^\|/, '').replace(/\|$/, '');
  const cells = normalized.split('|').map((c) => c.trim());
  if (!cells.length) return false;
  return cells.every((cell) => /^:?-{3,}:?$/.test(cell));
}

function parseTableCells(line: string): string[] {
  const normalized = line.trim().replace(/^\|/, '').replace(/\|$/, '');
  return normalized.split('|').map((c) => c.trim());
}

function renderMarkdown(input: string): string {
  const lines = input.replace(/\r\n/g, '\n').split('\n');
  const parts: string[] = [];
  let inCodeBlock = false;
  const codeLines: string[] = [];
  let listMode: 'ul' | 'ol' | null = null;

  const closeList = () => {
    if (!listMode) return;
    parts.push(listMode === 'ul' ? '</ul>' : '</ol>');
    listMode = null;
  };

  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (line.trimStart().startsWith('```')) {
      closeList();
      if (inCodeBlock) {
        parts.push(`<pre><code>${escapeHtml(codeLines.join('\n'))}</code></pre>`);
        codeLines.length = 0;
        inCodeBlock = false;
      } else {
        inCodeBlock = true;
      }
      i += 1;
      continue;
    }
    if (inCodeBlock) {
      codeLines.push(line);
      i += 1;
      continue;
    }

    if (!line.trim()) {
      closeList();
      i += 1;
      continue;
    }

    const next = i + 1 < lines.length ? lines[i + 1] : '';
    if (line.includes('|') && isTableDelimiterRow(next)) {
      closeList();
      const header = parseTableCells(line);
      parts.push('<table><thead><tr>');
      for (const cell of header) {
        parts.push(`<th>${renderInlineMarkdown(cell)}</th>`);
      }
      parts.push('</tr></thead><tbody>');
      i += 2;
      while (i < lines.length) {
        const row = lines[i];
        if (!row.trim() || !row.includes('|')) break;
        const cells = parseTableCells(row);
        parts.push('<tr>');
        for (const cell of cells) {
          parts.push(`<td>${renderInlineMarkdown(cell)}</td>`);
        }
        parts.push('</tr>');
        i += 1;
      }
      parts.push('</tbody></table>');
      continue;
    }

    const heading = line.match(/^(#{1,6})\s+(.*)$/);
    if (heading) {
      closeList();
      const level = heading[1].length;
      parts.push(`<h${level}>${renderInlineMarkdown(heading[2])}</h${level}>`);
      i += 1;
      continue;
    }

    const quote = line.match(/^>\s?(.*)$/);
    if (quote) {
      closeList();
      parts.push(`<blockquote>${renderInlineMarkdown(quote[1])}</blockquote>`);
      i += 1;
      continue;
    }

    const ol = line.match(/^\d+\.\s+(.*)$/);
    if (ol) {
      if (listMode !== 'ol') {
        closeList();
        parts.push('<ol>');
        listMode = 'ol';
      }
      parts.push(`<li>${renderInlineMarkdown(ol[1])}</li>`);
      i += 1;
      continue;
    }

    const ul = line.match(/^[-*+]\s+(.*)$/);
    if (ul) {
      if (listMode !== 'ul') {
        closeList();
        parts.push('<ul>');
        listMode = 'ul';
      }
      parts.push(`<li>${renderInlineMarkdown(ul[1])}</li>`);
      i += 1;
      continue;
    }

    closeList();
    parts.push(`<p>${renderInlineMarkdown(line)}</p>`);
    i += 1;
  }

  closeList();
  if (inCodeBlock) {
    parts.push(`<pre><code>${escapeHtml(codeLines.join('\n'))}</code></pre>`);
  }
  return parts.join('\n');
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    about.value = await aboutInfo() as AboutResponse;
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to load about info';
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="tool-view">
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
        <div class="doc md-doc" v-html="rfcHtml" />
      </section>
    </div>
  </div>
</template>

<style scoped>
.tool-view { padding: 1rem 0; }
h2 { margin-bottom: 0.25rem; }
.desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.error { color: #d32f2f; margin-bottom: 1rem; }
.about-grid { display: grid; gap: 1rem; }
.card { background: white; border-radius: 8px; padding: 1rem; box-shadow: 0 1px 2px rgba(0,0,0,0.06); }
.kv { display: grid; grid-template-columns: 120px 1fr; gap: 0.75rem; margin: 0.35rem 0; }
.kv span { color: #64748b; }
.doc { white-space: pre-wrap; word-break: break-word; font-size: 0.85rem; line-height: 1.45; background: #f8fafc; border-radius: 6px; padding: 0.75rem; }
.md-doc { white-space: normal; overflow-x: auto; }
.md-doc :deep(h1), .md-doc :deep(h2), .md-doc :deep(h3) { margin: 0.4rem 0 0.5rem; line-height: 1.25; }
.md-doc :deep(h4), .md-doc :deep(h5), .md-doc :deep(h6) { margin: 0.35rem 0 0.45rem; line-height: 1.25; }
.md-doc :deep(p), .md-doc :deep(ul), .md-doc :deep(ol), .md-doc :deep(blockquote) { margin: 0.5rem 0; }
.md-doc :deep(ul), .md-doc :deep(ol) { padding-left: 1.25rem; }
.md-doc :deep(li) { margin: 0.2rem 0; }
.md-doc :deep(blockquote) { border-left: 3px solid #cbd5e1; padding-left: 0.6rem; color: #334155; }
.md-doc :deep(code) { background: #e2e8f0; border-radius: 4px; padding: 0.08rem 0.25rem; }
.md-doc :deep(pre) { background: #0f172a; color: #e2e8f0; border-radius: 6px; padding: 0.75rem; overflow-x: auto; }
.md-doc :deep(pre code) { background: transparent; padding: 0; }
.md-doc :deep(a) { color: #1d4ed8; }
.md-doc :deep(table) { width: 100%; border-collapse: collapse; margin: 0.6rem 0; font-size: 0.84rem; }
.md-doc :deep(th), .md-doc :deep(td) { border: 1px solid #cbd5e1; padding: 0.45rem 0.55rem; text-align: left; vertical-align: top; }
.md-doc :deep(th) { background: #e2e8f0; color: #1e293b; font-weight: 700; }
.md-doc :deep(tr:nth-child(even) td) { background: #f8fafc; }
</style>
