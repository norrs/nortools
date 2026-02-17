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

interface RemediationHelp {
  text: string;
  helpPath?: string;
  helpLabel?: string;
}

const domain = ref('');
const result = ref<DomainHealthResult | null>(null);
const error = ref('');
const loading = ref(false);
const activeTab = ref<'report' | 'json'>('report');
const cliCommand = computed(() => buildCli(['nortools', 'domain-health', '--json', domain.value]));
const cliDisabled = computed(() => !domain.value);
const dmarcRecordInDomainHealth = computed(() => {
  if (!result.value) return null;
  const dmarcCheck = result.value.checks.find((c) => c.check === 'Mail / DMARC Record');
  if (!dmarcCheck?.detail?.startsWith('v=DMARC1')) return null;
  return dmarcCheck.detail;
});
const dmarcTagsInDomainHealth = computed(() => parseDmarcTags(dmarcRecordInDomainHealth.value));
const dmarcRowsInDomainHealth = computed(() =>
  Object.entries(dmarcTagsInDomainHealth.value).map(([key, value]) => ({
    key,
    value,
    meaning: explainDmarcTag(key, value),
  })),
);

const checkGroups = computed(() => {
  if (!result.value) return [];

  const isMailCheck = (name: string) =>
    name.startsWith('Mail /') ||
    ['MX Records', 'SPF Record', 'DMARC Record'].includes(name);

  const groups = [
    {
      title: 'DNS',
      checks: result.value.checks.filter((c) =>
        ['SOA Record', 'NS Records', 'A Record', 'AAAA Record', 'DNSSEC', 'CAA Record'].includes(c.check),
      ),
    },
    {
      title: 'Mail',
      checks: result.value.checks.filter((c) => isMailCheck(c.check)),
    },
    {
      title: 'Web',
      checks: result.value.checks.filter((c) => ['HTTPS'].includes(c.check)),
    },
    {
      title: 'Other',
      checks: result.value.checks.filter(
        (c) =>
          ![
            'SOA Record',
            'NS Records',
            'A Record',
            'AAAA Record',
            'DNSSEC',
            'CAA Record',
            'HTTPS',
          ].includes(c.check) && !isMailCheck(c.check),
      ),
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

function remediationForCheck(check: string, status: string, detail: string): RemediationHelp | null {
  if (status === 'PASS') return null;

  if (check === 'Mail / MX Records') {
    return { text: 'Publish valid MX records for receiving mail, or use Null MX (`MX 0 .`) if this domain should not receive email.' };
  }
  if (check === 'Mail / MX Host Address Records') {
    return { text: 'Each MX hostname should resolve to at least one A or AAAA record.' };
  }
  if (check === 'Mail / IPv6 Support On MX') {
    return { text: 'Add AAAA records on MX hosts to improve IPv6 reachability and standards compliance.' };
  }
  if (check === 'Mail / SPF Record') {
    return { text: 'Publish exactly one SPF TXT record starting with `v=spf1` and ending in `-all` or `~all`.' };
  }
  if (check === 'Mail / Single SPF Record') {
    return { text: 'Keep only one SPF record for the domain. Multiple SPF records cause SPF evaluation failures.' };
  }
  if (check === 'Mail / DMARC Record') {
    return { text: 'Publish a DMARC TXT record at `_dmarc.<domain>` with at least `v=DMARC1; p=...`.' };
  }
  if (check === 'Mail / DMARC Policy') {
    return { text: 'Move from `p=none` to enforcement (`p=quarantine` or `p=reject`) after monitoring.' };
  }
  if (check === 'Mail / DMARC Reporting') {
    return { text: 'Configure `rua=` (and optionally `ruf=`) so you receive DMARC reports and can monitor abuse.' };
  }
  if (check === 'Mail / DKIM (Common Selectors)') {
    return { text: 'Ensure your mail provider publishes DKIM keys under your domain and that messages are signed.' };
  }
  if (check === 'Mail / MTA-STS DNS') {
    return {
      text: 'Publish `_mta-sts.<domain> TXT "v=STSv1; id=..."` to enable MTA-STS.',
      helpPath: '/help/mta-sts-dns',
      helpLabel: 'MTA-STS DNS Help',
    };
  }
  if (check === 'Mail / MTA-STS Policy HTTPS') {
    return { text: 'Serve `https://mta-sts.<domain>/.well-known/mta-sts.txt` over valid HTTPS with `version`, `mode`, and `max_age`.' };
  }
  if (check === 'Mail / TLS-RPT Record') {
    return { text: 'Publish `_smtp._tls.<domain> TXT "v=TLSRPTv1; rua=mailto:..."` for TLS reporting.' };
  }
  if (check === 'Mail / STARTTLS Advertised' || check === 'Mail / STARTTLS Handshake Command') {
    return { text: 'Enable STARTTLS on SMTP port 25 and ensure the handshake succeeds for external senders.' };
  }
  if (check === 'Mail / DANE TLSA Record') {
    return { text: 'If you use DNSSEC for mail transport security, publish valid TLSA records at `_25._tcp.<mx-host>`.' };
  }
  if (check === 'Mail / PTR For Primary MX') {
    return { text: 'Configure reverse DNS (PTR) for MX IPs and keep forward/reverse DNS consistent.' };
  }
  if (check === 'NS Records') {
    return { text: 'Use at least two authoritative nameservers on separate infrastructure.' };
  }
  if (check === 'SOA Record') {
    return { text: 'Ensure the zone is correctly delegated and authoritative nameservers return a valid SOA record.' };
  }
  if (check === 'HTTPS') {
    return { text: 'Make sure HTTPS is reachable with a valid certificate and stable 2xx/3xx response.' };
  }
  if (check === 'DNSSEC') {
    return { text: 'Enable DNSSEC and publish DS records at the parent zone if you want signed DNS validation.' };
  }
  if (check === 'CAA Record') {
    return { text: 'Consider adding CAA to restrict which certificate authorities may issue certs for this domain.' };
  }

  if (status === 'FAIL') return { text: `Needs attention: ${detail}` };
  return null;
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

function explainDmarcTag(tag: string, value: string): string {
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
                <template v-if="remediationForCheck(item.check, item.status, item.detail)">
                  <p class="hint">{{ remediationForCheck(item.check, item.status, item.detail)?.text }}</p>
                  <router-link
                    v-if="remediationForCheck(item.check, item.status, item.detail)?.helpPath"
                    :to="remediationForCheck(item.check, item.status, item.detail)?.helpPath || '/help/mta-sts-dns'"
                    class="help-link"
                  >
                    {{ remediationForCheck(item.check, item.status, item.detail)?.helpLabel || 'Learn more' }}
                  </router-link>
                </template>
                <div
                  v-if="item.check === 'Mail / DMARC Record' && dmarcRecordInDomainHealth"
                  class="dmarc-inline-panel"
                >
                  <div class="dmarc-title">DMARC Explanation</div>
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
                        <tr v-for="row in dmarcRowsInDomainHealth" :key="row.key">
                          <td><code>{{ row.key }}</code></td>
                          <td><code>{{ row.value }}</code></td>
                          <td>{{ row.meaning }}</td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                  <div class="raw-wrap">
                    <div class="label">Raw DMARC record</div>
                    <pre class="raw-pre">{{ dmarcRecordInDomainHealth }}</pre>
                  </div>
                </div>
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
.check-content .hint { color: #334155; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.35rem 0.45rem; margin-top: 0.35rem; }
.help-link { display: inline-block; margin-top: 0.35rem; font-size: 0.8rem; color: #1d4ed8; text-decoration: none; }
.help-link:hover { text-decoration: underline; }
.dmarc-inline-panel { border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.65rem; margin-top: 0.6rem; background: #fcfdff; }
.dmarc-title { font-size: 0.82rem; font-weight: 600; color: #0f172a; margin-bottom: 0.5rem; }
.raw-wrap { margin-top: 0.55rem; }
.raw-pre { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.7rem; overflow-x: auto; font-size: 0.78rem; }
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
