<script setup lang="ts">
import { computed, ref } from 'vue';
import { whoisLookup } from '../api/client';
import CliCopy from '../components/CliCopy.vue';
import { buildCli } from '../utils/cli';

interface WhoisResponse {
  query: string;
  server: string;
  fields: Record<string, string>;
  raw: string;
}

const query = ref('');
const result = ref<WhoisResponse | null>(null);
const error = ref('');
const loading = ref(false);
const activeTab = ref<'overview' | 'raw' | 'json'>('overview');
const cliCommand = computed(() => buildCli(['nortools', 'whois', '--json', query.value]));
const cliDisabled = computed(() => !query.value);

const summaryFields = computed(() => {
  if (!result.value) return [] as Array<{ key: string; value: string }>;
  const keys = [
    'Domain Name', 'Registrar', 'Creation Date', 'Updated Date', 'Registry Expiry Date',
    'Registrant Organization', 'Registrant Country', 'NetName', 'OrgName', 'CIDR', 'NetRange', 'DNSSEC',
  ];
  return keys
    .filter((k) => result.value?.fields?.[k])
    .map((k) => ({ key: k, value: result.value!.fields[k] }));
});

const otherFields = computed(() => {
  if (!result.value) return [] as Array<{ key: string; value: string }>;
  const used = new Set(summaryFields.value.map((f) => f.key));
  return Object.entries(result.value.fields)
    .filter(([key]) => !used.has(key))
    .map(([key, value]) => ({ key, value }));
});

async function lookup() {
  if (!query.value) return;
  loading.value = true;
  error.value = '';
  result.value = null;
  activeTab.value = 'overview';
  try {
    result.value = await whoisLookup(query.value) as WhoisResponse;
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'An error occurred';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="tool-view">
    <h2>WHOIS Lookup</h2>
    <p class="desc">Look up domain or IP registration information.</p>
    <form @submit.prevent="lookup" class="lookup-form">
      <input v-model="query" placeholder="Domain or IP (e.g. example.com)" class="input" />
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Looking up...' : 'Lookup' }}
      </button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="whois-results">
      <div class="whois-top">
        <div>
          <div class="label">Query</div>
          <div class="value">{{ result.query }}</div>
        </div>
        <div>
          <div class="label">WHOIS Server</div>
          <div class="value mono">{{ result.server }}</div>
        </div>
      </div>

      <div class="tabs">
        <button :class="['tab', { active: activeTab === 'overview' }]" @click="activeTab = 'overview'">Overview</button>
        <button :class="['tab', { active: activeTab === 'raw' }]" @click="activeTab = 'raw'">Raw WHOIS</button>
        <button :class="['tab', { active: activeTab === 'json' }]" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'overview'" class="tab-panel">
        <div v-if="summaryFields.length" class="field-grid">
          <div v-for="f in summaryFields" :key="f.key" class="field-card">
            <div class="field-key">{{ f.key }}</div>
            <div class="field-val">{{ f.value }}</div>
          </div>
        </div>
        <div v-else class="empty">No parsed fields found. Check Raw WHOIS tab.</div>

        <details v-if="otherFields.length" class="other-section">
          <summary>Other Parsed Fields ({{ otherFields.length }})</summary>
          <div class="other-list">
            <div v-for="f in otherFields" :key="f.key" class="other-row">
              <span class="other-key">{{ f.key }}</span>
              <span class="other-val">{{ f.value }}</span>
            </div>
          </div>
        </details>
      </div>

      <div v-show="activeTab === 'raw'" class="tab-panel">
        <pre class="raw-pre">{{ result.raw }}</pre>
      </div>

      <div v-show="activeTab === 'json'" class="tab-panel">
        <pre class="result">{{ JSON.stringify(result, null, 2) }}</pre>
      </div>
    </div>

    <CliCopy :command="cliCommand" :disabled="cliDisabled" />
  </div>
</template>

<style scoped>
.tool-view { padding: 1rem 0; }
h2 { margin-bottom: 0.25rem; }
.desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
.input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; }
.btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; }
.btn:hover { background: #2a2a4e; }
.btn:disabled { opacity: 0.6; }
.error { color: #d32f2f; margin-bottom: 1rem; }
.whois-results { background: white; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; margin-bottom: 1rem; }
.whois-top { display: flex; justify-content: space-between; gap: 1rem; padding: 0.8rem 1rem; background: #f8fafc; border-bottom: 1px solid #e5e7eb; }
.label { font-size: 0.72rem; color: #64748b; }
.value { font-weight: 600; color: #0f172a; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.tabs { display: flex; border-bottom: 1px solid #e5e7eb; background: #f8fafc; }
.tab { border: none; background: none; padding: 0.55rem 0.9rem; cursor: pointer; color: #64748b; font-size: 0.82rem; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.tab.active { color: #111827; font-weight: 600; border-bottom-color: #111827; }
.tab-panel { padding: 0.9rem 1rem; }
.field-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 0.55rem; }
.field-card { border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.55rem 0.65rem; background: #fff; }
.field-key { font-size: 0.72rem; color: #64748b; margin-bottom: 0.22rem; }
.field-val { font-size: 0.85rem; color: #111827; word-break: break-word; }
.other-section { margin-top: 0.85rem; }
.other-section summary { cursor: pointer; color: #374151; font-size: 0.82rem; }
.other-list { margin-top: 0.5rem; border-top: 1px solid #f1f5f9; }
.other-row { display: grid; grid-template-columns: 210px 1fr; gap: 0.5rem; padding: 0.4rem 0; border-bottom: 1px solid #f8fafc; }
.other-key { color: #64748b; font-size: 0.8rem; }
.other-val { color: #111827; font-size: 0.82rem; word-break: break-word; }
.empty { color: #64748b; font-size: 0.85rem; }
.raw-pre { white-space: pre-wrap; word-break: break-word; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.8rem; line-height: 1.45; max-height: 520px; overflow: auto; background: #f8fafc; padding: 0.75rem; border-radius: 6px; }
.result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; }
</style>
