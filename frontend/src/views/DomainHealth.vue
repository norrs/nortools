<script setup lang="ts">
import { computed, ref } from 'vue';
import { domainHealth } from '../api/client';
import CliCopy from '../components/CliCopy.vue';
import { buildCli } from '../utils/cli';

interface CheckSummary {
  pass: number;
  warn: number;
  fail: number;
  info: number;
  total?: number | null;
}

interface DomainHealthCheck {
  check: string;
  status: string;
  detail: string;
}

interface DomainHealthResult {
  domain: string;
  overallStatus: string;
  summary: CheckSummary;
  checks: DomainHealthCheck[];
}

const domain = ref('');
const result = ref<DomainHealthResult | null>(null);
const error = ref('');
const loading = ref(false);
const activeTab = ref<'report' | 'json'>('report');
const cliCommand = computed(() => buildCli(['nortools', 'domain-health', '--json', domain.value]));
const cliDisabled = computed(() => !domain.value);
const checkGroups = computed(() => {
  if (!result.value) return [];

  const groups = [
    {
      title: 'DNS',
      checks: result.value.checks.filter((c) => ['SOA Record', 'NS Records', 'A Record', 'AAAA Record', 'DNSSEC', 'CAA Record'].includes(c.check)),
    },
    {
      title: 'Email',
      checks: result.value.checks.filter((c) => ['MX Records', 'SPF Record', 'DMARC Record'].includes(c.check)),
    },
    {
      title: 'Web',
      checks: result.value.checks.filter((c) => ['HTTPS'].includes(c.check)),
    },
    {
      title: 'Other',
      checks: result.value.checks.filter((c) => ![
        'SOA Record', 'NS Records', 'A Record', 'AAAA Record', 'DNSSEC', 'CAA Record',
        'MX Records', 'SPF Record', 'DMARC Record', 'HTTPS',
      ].includes(c.check)),
    },
  ];

  return groups.filter((g) => g.checks.length > 0);
});

async function check() {
  if (!domain.value) return;
  loading.value = true;
  error.value = '';
  result.value = null;
  activeTab.value = 'report';
  try {
    result.value = await domainHealth(domain.value) as DomainHealthResult;
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
  <div class="tool-view">
    <h2>Domain Health Check</h2>
    <p class="desc">Comprehensive domain health check — DNS, email auth, HTTPS, and more.</p>
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
        <div v-for="group in checkGroups" :key="group.title" class="section">
          <h3>{{ group.title }}</h3>
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
.tool-view { padding: 1rem 0; }
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
.label { color: #64748b; font-size: 0.72rem; display: block; }
.section { margin-bottom: 1rem; }
.section:last-child { margin-bottom: 0; }
h3 { font-size: 0.92rem; margin-bottom: 0.5rem; color: #0f172a; }
.check-list { border: 1px solid #e2e8f0; border-radius: 6px; overflow: hidden; }
.check-item { display: flex; gap: 0.6rem; padding: 0.55rem 0.65rem; border-bottom: 1px solid #f1f5f9; }
.check-item:last-child { border-bottom: none; }
.check-content p { margin-top: 0.2rem; color: #475569; font-size: 0.82rem; }
.status-pill { font-size: 0.72rem; font-weight: 700; min-width: 46px; text-align: center; padding: 0.2rem 0.4rem; border-radius: 999px; border: 1px solid transparent; height: fit-content; }
.status-pass { color: #166534; background: #dcfce7; border-color: #86efac; }
.status-warn { color: #92400e; background: #fef3c7; border-color: #fcd34d; }
.status-fail { color: #b91c1c; background: #fee2e2; border-color: #fca5a5; }
.status-info { color: #1e3a8a; background: #dbeafe; border-color: #93c5fd; }
.result { background: #0f172a; color: #e2e8f0; padding: 1rem; border-radius: 6px; overflow-x: auto; font-size: 0.8rem; }

@media (max-width: 920px) {
  .lookup-form { flex-direction: column; }
  .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
