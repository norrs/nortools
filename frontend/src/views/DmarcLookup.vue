<script setup lang="ts">
import { computed, ref } from 'vue';
import { dmarcLookup } from '../api/client';
import CliCopy from '../components/CliCopy.vue';
import { buildCli } from '../utils/cli';

interface DmarcLookupResult {
  domain: string;
  found: boolean;
  record: string | null;
  policy: string;
  subdomainPolicy: string;
  pct: string;
  dkimAlignment: string;
  spfAlignment: string;
  rua: string;
  ruf: string;
  ttl: number | null;
}

const domain = ref('');
const result = ref<DmarcLookupResult | null>(null);
const error = ref('');
const loading = ref(false);
const activeTab = ref<'report' | 'json'>('report');
const cliCommand = computed(() => buildCli(['nortools', 'dmarc', '--json', domain.value]));
const cliDisabled = computed(() => !domain.value);
const parsedTags = computed(() => parseDmarcTags(result.value?.record));
const tagRows = computed(() =>
  Object.entries(parsedTags.value).map(([key, value]) => ({
    key,
    value,
    meaning: explainTag(key, value),
  })),
);
const hasRecord = computed(() => !!result.value?.record);
const dmarcRecommendations = computed(() => recommendationsForDmarc(parsedTags.value));

async function lookup() {
  if (!domain.value) return;
  loading.value = true;
  error.value = '';
  result.value = null;
  activeTab.value = 'report';
  try {
    result.value = await dmarcLookup(domain.value) as DmarcLookupResult;
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'An error occurred';
  } finally {
    loading.value = false;
  }
}

function parseDmarcTags(record?: string | null): Record<string, string> {
  if (!record) return {};
  const tags: Record<string, string> = {};
  for (const part of record.split(';')) {
    const trimmed = part.trim();
    if (!trimmed.includes('=')) continue;
    const [key, value] = trimmed.split('=', 2);
    tags[key.trim().toLowerCase()] = value.trim();
  }
  return tags;
}

function explainTag(tag: string, value: string): string {
  switch (tag) {
    case 'v':
      return value === 'DMARC1' ? 'DMARC version 1 (valid).' : 'Unexpected DMARC version.';
    case 'p':
      if (value === 'none') return 'Monitor-only policy for the main domain.';
      if (value === 'quarantine') return 'Failing mail should go to spam/quarantine.';
      if (value === 'reject') return 'Failing mail should be rejected.';
      return 'Requested DMARC policy.';
    case 'sp':
      if (value === 'none') return 'Subdomains are monitor-only.';
      if (value === 'quarantine') return 'Failing subdomain mail should be quarantined.';
      if (value === 'reject') return 'Failing subdomain mail should be rejected.';
      return 'Subdomain policy override.';
    case 'adkim':
      return value === 's' ? 'Strict DKIM alignment.' : 'Relaxed DKIM alignment.';
    case 'aspf':
      return value === 's' ? 'Strict SPF alignment.' : 'Relaxed SPF alignment.';
    case 'pct':
      return `Policy applies to ${value}% of messages.`;
    case 'fo':
      return 'Forensic/failure reporting options.';
    case 'rf':
      return 'Failure report format.';
    case 'ri':
      return `Aggregate report interval: ${value} seconds.`;
    case 'rua':
      return 'Aggregate DMARC report destination(s).';
    case 'ruf':
      return 'Forensic/failure report destination(s).';
    default:
      return 'Custom/optional DMARC tag.';
  }
}

function recommendationsForDmarc(tags: Record<string, string>): string[] {
  if (!Object.keys(tags).length) {
    return ['Publish a DMARC record at `_dmarc.<domain>` with `v=DMARC1; p=none` as a safe starting point.'];
  }
  const tips: string[] = [];
  const policy = tags.p;
  const pct = Number(tags.pct ?? '100');
  const hasRua = Boolean(tags.rua);

  if (!policy) tips.push('Add mandatory policy tag `p=` (none, quarantine, or reject).');
  if (policy === 'none') tips.push('After monitoring, move to enforcement with `p=quarantine` or `p=reject`.');
  if (policy && !['none', 'quarantine', 'reject'].includes(policy)) tips.push('Use a valid DMARC policy value (`none`, `quarantine`, or `reject`).');
  if (!hasRua) tips.push('Add `rua=mailto:...` to receive aggregate reports.');
  if (!Number.isNaN(pct) && pct < 100) tips.push('Increase `pct` to 100 after rollout so policy applies to all mail.');
  if (!tags.sp && (policy === 'quarantine' || policy === 'reject')) tips.push('Set `sp=` explicitly to control subdomain behavior.');
  if (!tags.adkim) tips.push('Set `adkim=s` if you need strict DKIM alignment (keep relaxed if compatibility is needed).');
  if (!tags.aspf) tips.push('Set `aspf=s` if you need strict SPF alignment (keep relaxed if compatibility is needed).');

  return tips;
}
</script>

<template>
  <div class="tool-view">
    <h2>DMARC Record Lookup</h2>
    <p class="desc">Check DMARC policy for a domain.</p>
    <form @submit.prevent="lookup" class="lookup-form">
      <input v-model="domain" placeholder="Enter domain (e.g. google.com)" class="input" />
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Looking up...' : 'Lookup' }}
      </button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="dmarc-results">
      <div class="status-banner">
        <div>
          <div class="label">Domain</div>
          <strong>{{ result.domain }}</strong>
        </div>
        <div class="overall" :class="hasRecord ? 'status-pass' : 'status-fail'">
          {{ hasRecord ? 'DMARC FOUND' : 'DMARC MISSING' }}
        </div>
      </div>

      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'report' }" @click="activeTab = 'report'">Report</button>
        <button class="tab" :class="{ active: activeTab === 'json' }" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'report'" class="tab-panel">
        <div v-if="hasRecord" class="section">
          <h3>Readable Summary</h3>
          <div class="summary-grid">
            <div><span class="label">Policy</span><strong>{{ result.policy }}</strong></div>
            <div><span class="label">Subdomain Policy</span><strong>{{ result.subdomainPolicy }}</strong></div>
            <div><span class="label">DKIM Alignment</span><strong>{{ result.dkimAlignment }}</strong></div>
            <div><span class="label">SPF Alignment</span><strong>{{ result.spfAlignment }}</strong></div>
            <div><span class="label">Applied %</span><strong>{{ result.pct }}%</strong></div>
            <div><span class="label">TTL</span><strong>{{ result.ttl ?? 'N/A' }}</strong></div>
          </div>
        </div>

        <div v-if="hasRecord" class="section">
          <h3>Tag Breakdown</h3>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Tag</th>
                  <th>Value</th>
                  <th>Meaning</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in tagRows" :key="row.key">
                  <td><code>{{ row.key }}</code></td>
                  <td><code>{{ row.value }}</code></td>
                  <td>{{ row.meaning }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div v-if="hasRecord" class="section">
          <h3>Raw Record</h3>
          <pre class="raw-pre">{{ result.record }}</pre>
        </div>

        <div class="section">
          <h3>Recommended Next Steps</h3>
          <ul class="tips">
            <li v-for="tip in dmarcRecommendations" :key="tip">{{ tip }}</li>
          </ul>
        </div>

        <div v-if="!hasRecord" class="empty">
          No DMARC TXT record was found at <code>_dmarc.{{ result.domain }}</code>.
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
.dmarc-results { background: white; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; margin-bottom: 1rem; }
.status-banner { display: flex; justify-content: space-between; align-items: center; padding: 0.9rem 1rem; background: #f8fafc; border-bottom: 1px solid #e5e7eb; }
.overall { font-weight: 700; padding: 0.35rem 0.7rem; border-radius: 999px; font-size: 0.76rem; border: 1px solid transparent; }
.tabs { display: flex; border-bottom: 1px solid #e5e7eb; background: #f8fafc; }
.tab { border: none; background: none; padding: 0.55rem 0.9rem; cursor: pointer; color: #64748b; font-size: 0.82rem; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.tab.active { color: #111827; font-weight: 600; border-bottom-color: #111827; }
.tab-panel { padding: 0.9rem 1rem; }
.section { margin-bottom: 1rem; }
.section:last-child { margin-bottom: 0; }
h3 { font-size: 0.92rem; margin-bottom: 0.5rem; color: #0f172a; }
.summary-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0.5rem; }
.summary-grid > div { border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.55rem 0.65rem; display: flex; justify-content: space-between; align-items: center; background: #fff; }
.label { color: #64748b; font-size: 0.72rem; display: block; }
.table-wrap { overflow-x: auto; border: 1px solid #e2e8f0; border-radius: 6px; }
table { width: 100%; border-collapse: collapse; font-size: 0.84rem; }
th, td { text-align: left; padding: 0.5rem 0.55rem; border-bottom: 1px solid #f1f5f9; vertical-align: top; }
th { background: #f8fafc; color: #334155; font-weight: 600; }
.raw-pre { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.75rem; overflow-x: auto; font-size: 0.8rem; }
.empty { color: #475569; }
.tips { margin-left: 1rem; color: #334155; font-size: 0.84rem; }
.tips li { margin-bottom: 0.3rem; }
.status-pass { color: #166534; background: #dcfce7; border-color: #86efac; }
.status-fail { color: #b91c1c; background: #fee2e2; border-color: #fca5a5; }
.result { background: #0f172a; color: #e2e8f0; padding: 1rem; border-radius: 6px; overflow-x: auto; font-size: 0.8rem; }

@media (max-width: 920px) {
  .lookup-form { flex-direction: column; }
  .summary-grid { grid-template-columns: 1fr; }
}
</style>
