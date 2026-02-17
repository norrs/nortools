<script setup lang="ts">
import { computed, ref } from 'vue';
import { dnsHealthCheck } from '../api/client';
import CliCopy from '../components/CliCopy.vue';
import { buildCli } from '../utils/cli';

interface CheckSummary {
  pass: number;
  warn: number;
  fail: number;
  info: number;
  total?: number | null;
}

interface SoaInfo {
  primary: string;
  admin: string;
  serial: number;
  refresh: number;
  retry: number;
  expire: number;
  minimum: number;
}

interface NameserverStatus {
  type: string;
  name: string;
  ttl: number | null;
  ip?: string | null;
  status: string;
  timeMs?: number | null;
  authoritative: boolean;
  responding: boolean;
  serial?: number | null;
}

interface DnsHealthCheck {
  category: string;
  check: string;
  status: string;
  detail: string;
}

interface DnsHealthResult {
  domain: string;
  overallStatus: string;
  soa: SoaInfo;
  nameservers: NameserverStatus[];
  summary: CheckSummary;
  categories: string[];
  checks: DnsHealthCheck[];
}

const domain = ref('');
const result = ref<DnsHealthResult | null>(null);
const error = ref('');
const loading = ref(false);
const activeTab = ref<'report' | 'json'>('report');
const cliCommand = computed(() => buildCli(['nortools', 'dns-health', '--json', domain.value]));
const cliDisabled = computed(() => !domain.value);
const checksByCategory = computed(() => {
  if (!result.value) return [];
  return result.value.categories.map((category) => ({
    category,
    checks: result.value!.checks.filter((check) => check.category === category),
  }));
});
const respondingNameservers = computed(() => result.value?.nameservers.filter((ns) => ns.responding).length ?? 0);

async function check() {
  if (!domain.value) return;
  loading.value = true;
  error.value = '';
  result.value = null;
  activeTab.value = 'report';
  try {
    result.value = await dnsHealthCheck(domain.value) as DnsHealthResult;
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'An error occurred';
  } finally {
    loading.value = false;
  }
}

function statusClass(status: string): string {
  return `status-${status.toLowerCase()}`;
}
</script>

<template>
  <div class="dns-health">
    <h2>DNS Health Check</h2>
    <p class="desc">Deep DNS validation with nameserver diagnostics, protocol checks, and zone consistency tests.</p>
    <form @submit.prevent="check" class="lookup-form">
      <input v-model="domain" placeholder="Enter domain (e.g. example.com)" class="input" />
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Checking...' : 'Check Health' }}
      </button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="result" class="health-results">
      <div class="status-banner">
        <div>
          <div class="label">Domain</div>
          <strong>{{ result.domain }}</strong>
        </div>
        <div class="overall" :class="statusClass(result.overallStatus)">
          {{ result.overallStatus }}
        </div>
      </div>

      <div class="summary-grid">
        <div class="summary-card pass"><span>PASS</span><strong>{{ result.summary.pass }}</strong></div>
        <div class="summary-card warn"><span>WARN</span><strong>{{ result.summary.warn }}</strong></div>
        <div class="summary-card fail"><span>FAIL</span><strong>{{ result.summary.fail }}</strong></div>
        <div class="summary-card info"><span>INFO</span><strong>{{ result.summary.info }}</strong></div>
        <div class="summary-card total"><span>TOTAL</span><strong>{{ result.summary.total ?? result.checks.length }}</strong></div>
      </div>

      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'report' }" @click="activeTab = 'report'">Report</button>
        <button class="tab" :class="{ active: activeTab === 'json' }" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'report'" class="tab-panel">
        <div class="quick-stats">
          <div><span class="label">Nameservers</span><strong>{{ result.nameservers.length }}</strong></div>
          <div><span class="label">Responding</span><strong>{{ respondingNameservers }}</strong></div>
          <div><span class="label">SOA Serial</span><strong>{{ result.soa.serial || 'N/A' }}</strong></div>
        </div>

        <div class="section">
          <h3>SOA</h3>
          <div class="soa-grid">
            <div><span class="label">Primary</span><code>{{ result.soa.primary || 'N/A' }}</code></div>
            <div><span class="label">Admin</span><code>{{ result.soa.admin || 'N/A' }}</code></div>
            <div><span class="label">Refresh</span>{{ result.soa.refresh }}s</div>
            <div><span class="label">Retry</span>{{ result.soa.retry }}s</div>
            <div><span class="label">Expire</span>{{ result.soa.expire }}s</div>
            <div><span class="label">Minimum</span>{{ result.soa.minimum }}s</div>
          </div>
        </div>

        <div class="section">
          <h3>Nameservers</h3>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Name</th>
                  <th>IP</th>
                  <th>Status</th>
                  <th>UDP</th>
                  <th>Auth</th>
                  <th>Time</th>
                  <th>Serial</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="ns in result.nameservers" :key="ns.name">
                  <td><code>{{ ns.name }}</code></td>
                  <td>{{ ns.ip || 'N/A' }}</td>
                  <td><span class="status-pill" :class="statusClass(ns.status)">{{ ns.status }}</span></td>
                  <td>{{ ns.responding ? 'Yes' : 'No' }}</td>
                  <td>{{ ns.authoritative ? 'Yes' : 'No' }}</td>
                  <td>{{ ns.timeMs != null ? `${ns.timeMs} ms` : 'N/A' }}</td>
                  <td>{{ ns.serial ?? 'N/A' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="section" v-for="group in checksByCategory" :key="group.category">
          <h3>{{ group.category }}</h3>
          <div class="check-list">
            <div v-for="item in group.checks" :key="item.check" class="check-item">
              <span class="status-pill" :class="statusClass(item.status)">{{ item.status }}</span>
              <div class="check-content">
                <strong>{{ item.check }}</strong>
                <p>{{ item.detail }}</p>
              </div>
            </div>
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
.dns-health { padding: 1rem 0; }
h2 { margin-bottom: 0.25rem; }
.desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
.input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; }
.btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; }
.btn:hover { background: #2a2a4e; }
.btn:disabled { opacity: 0.6; }
.error { color: #d32f2f; margin-bottom: 1rem; }
.health-results { background: white; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; margin-bottom: 1rem; }
.status-banner { display: flex; justify-content: space-between; align-items: center; padding: 0.9rem 1rem; background: #f8fafc; border-bottom: 1px solid #e5e7eb; }
.overall { font-weight: 700; padding: 0.35rem 0.7rem; border-radius: 999px; font-size: 0.78rem; }
.summary-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 0.5rem; padding: 0.8rem 1rem; border-bottom: 1px solid #e5e7eb; }
.summary-card { border: 1px solid #e5e7eb; border-radius: 6px; padding: 0.45rem 0.55rem; display: flex; justify-content: space-between; align-items: baseline; }
.summary-card span { font-size: 0.72rem; color: #475569; }
.summary-card strong { font-size: 1.05rem; }
.summary-card.pass strong { color: #166534; }
.summary-card.warn strong { color: #92400e; }
.summary-card.fail strong { color: #b91c1c; }
.summary-card.info strong { color: #0f172a; }
.summary-card.total strong { color: #1f2937; }
.tabs { display: flex; border-bottom: 1px solid #e5e7eb; background: #f8fafc; }
.tab { border: none; background: none; padding: 0.55rem 0.9rem; cursor: pointer; color: #64748b; font-size: 0.82rem; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.tab.active { color: #111827; font-weight: 600; border-bottom-color: #111827; }
.tab-panel { padding: 0.9rem 1rem; }
.quick-stats { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0.5rem; margin-bottom: 0.85rem; }
.quick-stats > div { border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.5rem 0.65rem; display: flex; justify-content: space-between; align-items: center; background: #fff; }
.label { color: #64748b; font-size: 0.72rem; display: block; }
.section { margin-bottom: 1rem; }
h3 { font-size: 0.92rem; margin-bottom: 0.5rem; color: #0f172a; }
.soa-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0.6rem; border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.65rem; }
.table-wrap { overflow-x: auto; border: 1px solid #e2e8f0; border-radius: 6px; }
table { width: 100%; border-collapse: collapse; font-size: 0.84rem; }
th, td { text-align: left; padding: 0.5rem 0.55rem; border-bottom: 1px solid #f1f5f9; }
th { background: #f8fafc; color: #334155; font-weight: 600; }
.check-list { border: 1px solid #e2e8f0; border-radius: 6px; overflow: hidden; }
.check-item { display: flex; gap: 0.6rem; padding: 0.55rem 0.65rem; border-bottom: 1px solid #f1f5f9; }
.check-item:last-child { border-bottom: none; }
.check-content p { margin-top: 0.2rem; color: #475569; font-size: 0.82rem; }
.status-pill { font-size: 0.72rem; font-weight: 700; min-width: 46px; text-align: center; padding: 0.2rem 0.4rem; border-radius: 999px; border: 1px solid transparent; height: fit-content; }
.status-pass { color: #166534; background: #dcfce7; border-color: #86efac; }
.status-warn { color: #92400e; background: #fef3c7; border-color: #fcd34d; }
.status-fail { color: #b91c1c; background: #fee2e2; border-color: #fca5a5; }
.status-info { color: #1e3a8a; background: #dbeafe; border-color: #93c5fd; }
.status-ok { color: #166534; background: #dcfce7; border-color: #86efac; }
.result { background: #0f172a; color: #e2e8f0; padding: 1rem; border-radius: 6px; overflow-x: auto; font-size: 0.8rem; }

@media (max-width: 920px) {
  .lookup-form { flex-direction: column; }
  .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .quick-stats { grid-template-columns: 1fr; }
  .soa-grid { grid-template-columns: 1fr; }
}
</style>
