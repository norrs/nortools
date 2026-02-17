<script setup lang="ts">
import { computed, ref } from 'vue';
import { blacklistCheck } from '../api/client';
import CliCopy from '../components/CliCopy.vue';
import { buildCli } from '../utils/cli';

interface BlocklistResult {
  server: string;
  listed: boolean;
  reason: string | null;
}

interface BlocklistResponse {
  ip: string;
  totalChecked: number;
  listedOn: number;
  clean: boolean;
  results: BlocklistResult[];
}

const ip = ref('');
const result = ref<BlocklistResponse | null>(null);
const error = ref('');
const loading = ref(false);
const activeTab = ref<'report' | 'json'>('report');
const cliCommand = computed(() => buildCli(['nortools', 'blacklist', '--json', ip.value]));
const cliDisabled = computed(() => !ip.value);
const listedResults = computed(() => result.value?.results.filter((r) => r.listed) ?? []);
const cleanResults = computed(() => result.value?.results.filter((r) => !r.listed) ?? []);
const cleanCount = computed(() => (result.value ? result.value.totalChecked - result.value.listedOn : 0));

async function check() {
  if (!ip.value) return;
  loading.value = true;
  error.value = '';
  result.value = null;
  activeTab.value = 'report';
  try {
    result.value = await blacklistCheck(ip.value) as BlocklistResponse;
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'An error occurred';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="tool-view">
    <h2>Blacklist Check</h2>
    <p class="desc">Check if an IP is listed on DNS-based blacklists.</p>
    <form @submit.prevent="check" class="lookup-form">
      <input v-model="ip" placeholder="IP address (e.g. 1.2.3.4)" class="input" />
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Checking...' : 'Check' }}
      </button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="blacklist-results">
      <div class="status-banner">
        <div>
          <div class="label">IP</div>
          <strong>{{ result.ip }}</strong>
        </div>
        <div class="overall" :class="result.clean ? 'status-pass' : 'status-fail'">
          {{ result.clean ? 'CLEAN' : `${result.listedOn} LISTED` }}
        </div>
      </div>

      <div class="summary-grid">
        <div class="summary-card total"><span>Checked</span><strong>{{ result.totalChecked }}</strong></div>
        <div class="summary-card fail"><span>Listed</span><strong>{{ result.listedOn }}</strong></div>
        <div class="summary-card pass"><span>Not Listed</span><strong>{{ cleanCount }}</strong></div>
      </div>

      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'report' }" @click="activeTab = 'report'">Report</button>
        <button class="tab" :class="{ active: activeTab === 'json' }" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'report'" class="tab-panel">
        <div class="section">
          <h3>Listed On</h3>
          <div v-if="listedResults.length === 0" class="empty">No listings found on checked blocklists.</div>
          <div v-else class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>DNSBL</th>
                  <th>Reason</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in listedResults" :key="row.server">
                  <td><code>{{ row.server }}</code></td>
                  <td>{{ row.reason || 'Listed (no TXT reason provided)' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="section">
          <h3>Checked And Clean</h3>
          <div class="clean-list">
            <span v-for="row in cleanResults" :key="row.server" class="chip">{{ row.server }}</span>
          </div>
        </div>
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
.blacklist-results { background: white; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; margin-bottom: 1rem; }
.status-banner { display: flex; justify-content: space-between; align-items: center; padding: 0.9rem 1rem; background: #f8fafc; border-bottom: 1px solid #e5e7eb; }
.overall { font-weight: 700; padding: 0.35rem 0.7rem; border-radius: 999px; font-size: 0.76rem; border: 1px solid transparent; }
.status-pass { color: #166534; background: #dcfce7; border-color: #86efac; }
.status-fail { color: #b91c1c; background: #fee2e2; border-color: #fca5a5; }
.summary-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0.5rem; padding: 0.8rem 1rem; border-bottom: 1px solid #e5e7eb; }
.summary-card { border: 1px solid #e5e7eb; border-radius: 6px; padding: 0.45rem 0.55rem; display: flex; justify-content: space-between; align-items: baseline; }
.summary-card span { font-size: 0.72rem; color: #475569; }
.summary-card strong { font-size: 1.05rem; }
.summary-card.total strong { color: #1f2937; }
.summary-card.fail strong { color: #b91c1c; }
.summary-card.pass strong { color: #166534; }
.tabs { display: flex; border-bottom: 1px solid #e5e7eb; background: #f8fafc; }
.tab { border: none; background: none; padding: 0.55rem 0.9rem; cursor: pointer; color: #64748b; font-size: 0.82rem; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.tab.active { color: #111827; font-weight: 600; border-bottom-color: #111827; }
.tab-panel { padding: 0.9rem 1rem; }
.section { margin-bottom: 1rem; }
.section:last-child { margin-bottom: 0; }
h3 { font-size: 0.92rem; margin-bottom: 0.5rem; color: #0f172a; }
.label { color: #64748b; font-size: 0.72rem; display: block; }
.empty { color: #475569; }
.table-wrap { overflow-x: auto; border: 1px solid #e2e8f0; border-radius: 6px; }
table { width: 100%; border-collapse: collapse; font-size: 0.84rem; }
th, td { text-align: left; padding: 0.5rem 0.55rem; border-bottom: 1px solid #f1f5f9; vertical-align: top; }
th { background: #f8fafc; color: #334155; font-weight: 600; }
.clean-list { display: flex; flex-wrap: wrap; gap: 0.4rem; }
.chip { border: 1px solid #d1fae5; background: #ecfdf5; color: #065f46; border-radius: 999px; padding: 0.2rem 0.5rem; font-size: 0.76rem; }
.result { background: #0f172a; color: #e2e8f0; padding: 1rem; border-radius: 6px; overflow-x: auto; font-size: 0.8rem; }

@media (max-width: 920px) {
  .lookup-form { flex-direction: column; }
  .summary-grid { grid-template-columns: 1fr; }
}
</style>
