<script setup lang="ts">
import { ref, computed } from 'vue';
import { httpsCheck } from '../api/client';
import CliCopy from '../components/CliCopy.vue';
import { buildCli } from '../utils/cli';

interface SslInfo {
  protocol: string;
  cipherSuite: string;
}

interface CertificateInfo {
  index: number;
  subject: string;
  issuer: string;
  commonName: string | null;
  issuerCommonName: string | null;
  subjectAltNames: string[];
  validFrom: string;
  validUntil: string;
  validFromEpochMs: number;
  validUntilEpochMs: number;
  daysRemaining: number;
  expired: boolean;
  serialNumber: string;
  signatureAlgorithm: string;
  publicKeyType: string;
  publicKeySize: number | null;
  isCA: boolean;
  keyUsage: string[];
  extendedKeyUsage: string[];
  sha256Fingerprint: string;
  selfSigned: boolean;
}

interface HttpsResult {
  url: string;
  statusCode: number;
  responseTimeMs: number;
  error?: string;
  ssl?: SslInfo;
  certificateChain?: CertificateInfo[];
  certificateError?: string | null;
  headers?: Record<string, string>;
}

const host = ref('');
const result = ref<HttpsResult | null>(null);
const error = ref('');
const loading = ref(false);
const activeTab = ref<'chain' | 'details' | 'json'>('chain');
const selectedCertIndex = ref<number | null>(null);
const cliCommand = computed(() => buildCli(['nortools', 'https', '--json', host.value]));
const cliDisabled = computed(() => !host.value);

const chain = computed(() => result.value?.certificateChain ?? []);
const hasChain = computed(() => chain.value.length > 0);
const selectedCert = computed(() => {
  if (!chain.value.length) return null;
  if (selectedCertIndex.value == null) return chain.value[0];
  return chain.value.find(c => c.index === selectedCertIndex.value) || chain.value[0];
});

const chainSummary = computed(() => {
  if (!chain.value.length) return null;
  const expired = chain.value.some(c => c.expired);
  const minDays = Math.min(...chain.value.map(c => c.daysRemaining));
  return {
    expired,
    minDays,
    count: chain.value.length,
    leaf: chain.value[0],
    root: chain.value[chain.value.length - 1],
  };
});

async function check() {
  if (!host.value) return;
  loading.value = true;
  error.value = '';
  result.value = null;
  selectedCertIndex.value = null;
  activeTab.value = 'chain';
  try {
    result.value = await httpsCheck(host.value) as HttpsResult;
    if (result.value?.certificateChain?.length) {
      selectedCertIndex.value = result.value.certificateChain[0].index;
    }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'An error occurred';
  } finally {
    loading.value = false;
  }
}

function certRole(cert: CertificateInfo, idx: number, total: number): string {
  if (idx === 0) return 'Leaf';
  if (idx === total - 1) return cert.selfSigned ? 'Root' : 'Top CA';
  return 'Intermediate';
}

function statusColor(expired: boolean): string {
  return expired ? '#dc2626' : '#16a34a';
}
</script>

<template>
  <div class="tool-view">
    <h2>HTTPS / SSL Check</h2>
    <p class="desc">Inspect TLS settings and visualize the SSL certificate chain.</p>
    <form @submit.prevent="check" class="lookup-form">
      <input v-model="host" placeholder="Host (e.g. google.com)" class="input" />
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Checking...' : 'Check' }}
      </button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="chain-results">
      <div class="chain-banner" :style="{ borderLeftColor: hasChain ? (chainSummary?.expired ? '#dc2626' : '#16a34a') : '#94a3b8' }">
        <span class="chain-icon">{{ hasChain ? (chainSummary?.expired ? '⚠️' : '🔒') : 'ℹ️' }}</span>
        <div class="banner-content">
          <div class="banner-title">
            <strong>{{ host }}</strong>
            <span class="status-pill" :style="{ background: hasChain ? (chainSummary?.expired ? '#dc2626' : '#16a34a') : '#94a3b8' }">
              {{ hasChain ? (chainSummary?.expired ? 'Expired certs' : 'All certs valid') : 'No chain data' }}
            </span>
          </div>
          <div class="banner-subtitle">
            {{ chainSummary?.count ?? 0 }} certs in chain
            <span v-if="chainSummary"> · min {{ chainSummary.minDays }} days remaining</span>
            <span v-if="result.ssl"> · {{ result.ssl.protocol }} / {{ result.ssl.cipherSuite }}</span>
          </div>
          <div v-if="result.certificateError" class="banner-error">Certificate error: {{ result.certificateError }}</div>
        </div>
      </div>

      <div class="tabs">
        <button :class="['tab', { active: activeTab === 'chain' }]" @click="activeTab = 'chain'">🔗 Chain Diagram</button>
        <button :class="['tab', { active: activeTab === 'details' }]" @click="activeTab = 'details'">📋 Certificate Details</button>
        <button :class="['tab', { active: activeTab === 'json' }]" @click="activeTab = 'json'">{ } JSON</button>
      </div>

      <div v-show="activeTab === 'chain'" class="tab-panel chain-panel">
        <div v-if="chain.length === 0" class="no-chain">No certificate chain data available.</div>
        <div v-else class="chain-diagram">
          <template v-for="(cert, idx) in chain" :key="cert.index">
            <div class="cert-node" @click="selectedCertIndex = cert.index">
              <div class="cert-header">
                <span class="cert-role">{{ certRole(cert, idx, chain.length) }}</span>
                <span class="cert-status" :style="{ color: statusColor(cert.expired) }">
                  {{ cert.expired ? 'Expired' : 'Valid' }}
                </span>
              </div>
              <div class="cert-cn">{{ cert.commonName || '(no CN)' }}</div>
              <div class="cert-issuer">Issuer: {{ cert.issuerCommonName || cert.issuer }}</div>
              <div class="cert-valid">
                {{ cert.validFrom }} → {{ cert.validUntil }}
              </div>
              <div class="cert-meta">
                <span>SANs: {{ cert.subjectAltNames.length ? cert.subjectAltNames.join(', ') : '-' }}</span>
                <span>Key: {{ cert.publicKeyType }}{{ cert.publicKeySize ? ` ${cert.publicKeySize} bit` : '' }}</span>
              </div>
            </div>
            <div v-if="idx < chain.length - 1" class="cert-arrow">
              <div class="arrow-line"></div>
              <div class="arrow-head">▼</div>
            </div>
          </template>
        </div>
      </div>

      <div v-show="activeTab === 'details'" class="tab-panel details-panel">
        <div v-if="chain.length > 0" class="cert-selector">
          <button v-for="cert in chain" :key="`cert-${cert.index}`"
            :class="['cert-btn', { active: selectedCert?.index === cert.index }]"
            @click="selectedCertIndex = cert.index">
            {{ cert.commonName || cert.subject }}
          </button>
        </div>
        <div v-if="selectedCert" class="cert-details">
          <h3>{{ selectedCert.commonName || selectedCert.subject }}</h3>
          <div class="detail-row"><span class="label">Subject</span><span class="val">{{ selectedCert.subject }}</span></div>
          <div class="detail-row"><span class="label">Issuer</span><span class="val">{{ selectedCert.issuer }}</span></div>
          <div class="detail-row"><span class="label">Valid</span><span class="val">{{ selectedCert.validFrom }} → {{ selectedCert.validUntil }}</span></div>
          <div class="detail-row"><span class="label">Days Remaining</span><span class="val">{{ selectedCert.daysRemaining }}</span></div>
          <div class="detail-row"><span class="label">Expired</span><span class="val">{{ selectedCert.expired ? 'Yes' : 'No' }}</span></div>
          <div class="detail-row"><span class="label">Serial</span><span class="val mono">{{ selectedCert.serialNumber }}</span></div>
          <div class="detail-row"><span class="label">Signature</span><span class="val">{{ selectedCert.signatureAlgorithm }}</span></div>
          <div class="detail-row"><span class="label">Public Key</span><span class="val">{{ selectedCert.publicKeyType }}{{ selectedCert.publicKeySize ? ` (${selectedCert.publicKeySize} bit)` : '' }}</span></div>
          <div class="detail-row"><span class="label">CA</span><span class="val">{{ selectedCert.isCA ? 'Yes' : 'No' }}</span></div>
          <div class="detail-row"><span class="label">SANs</span><span class="val">{{ selectedCert.subjectAltNames.length ? selectedCert.subjectAltNames.join(', ') : '-' }}</span></div>
          <div class="detail-row"><span class="label">Key Usage</span><span class="val">{{ selectedCert.keyUsage.length ? selectedCert.keyUsage.join(', ') : '-' }}</span></div>
          <div class="detail-row"><span class="label">Extended Usage</span><span class="val">{{ selectedCert.extendedKeyUsage.length ? selectedCert.extendedKeyUsage.join(', ') : '-' }}</span></div>
          <div class="detail-row"><span class="label">SHA-256</span><span class="val mono">{{ selectedCert.sha256Fingerprint }}</span></div>
        </div>
      </div>

      <div v-show="activeTab === 'json'" class="tab-panel json-panel">
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
.chain-results { background: white; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
.chain-banner { display: flex; align-items: center; gap: 12px; padding: 16px 20px; border-left: 4px solid; background: #f8f9fa; }
.chain-icon { font-size: 1.5rem; }
.banner-content { display: flex; flex-direction: column; gap: 4px; }
.banner-title { display: flex; align-items: center; gap: 10px; }
.status-pill { color: white; padding: 2px 8px; border-radius: 999px; font-size: 0.75rem; }
.banner-subtitle { font-size: 0.8rem; color: #666; }
.banner-error { color: #b91c1c; font-size: 0.8rem; }
.tabs { display: flex; gap: 8px; border-bottom: 1px solid #eee; padding: 0 12px; background: #fafafa; }
.tab { background: none; border: none; padding: 10px 12px; cursor: pointer; font-size: 0.9rem; color: #555; }
.tab.active { color: #111; border-bottom: 2px solid #1a1a2e; font-weight: 600; }
.tab-panel { padding: 16px; }
.chain-panel { overflow-x: auto; }
.chain-diagram { display: flex; flex-direction: column; align-items: center; gap: 0; }
.cert-node { width: min(640px, 100%); border: 1px solid #e5e7eb; border-radius: 8px; padding: 12px 14px; background: #fff; box-shadow: 0 1px 2px rgba(0,0,0,0.05); cursor: pointer; }
.cert-header { display: flex; justify-content: space-between; font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.04em; color: #6b7280; margin-bottom: 6px; }
.cert-role { font-weight: 600; }
.cert-status { font-weight: 600; }
.cert-cn { font-size: 1.05rem; font-weight: 600; margin-bottom: 4px; color: #111827; }
.cert-issuer { font-size: 0.85rem; color: #4b5563; margin-bottom: 6px; }
.cert-valid { font-size: 0.8rem; color: #6b7280; margin-bottom: 8px; }
.cert-meta { display: flex; gap: 12px; font-size: 0.8rem; color: #374151; }
.cert-arrow { display: flex; flex-direction: column; align-items: center; margin: 8px 0; color: #9ca3af; }
.arrow-line { width: 2px; height: 14px; background: #d1d5db; }
.arrow-head { font-size: 0.8rem; margin-top: 2px; }
.no-chain { color: #6b7280; }
.details-panel { display: flex; flex-direction: column; gap: 12px; }
.cert-selector { display: flex; flex-wrap: wrap; gap: 6px; }
.cert-btn { border: 1px solid #e5e7eb; background: #f9fafb; padding: 6px 10px; border-radius: 6px; cursor: pointer; font-size: 0.8rem; }
.cert-btn.active { background: #1a1a2e; color: white; border-color: #1a1a2e; }
.cert-details { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 14px; }
.cert-details h3 { margin: 0 0 8px 0; }
.detail-row { display: grid; grid-template-columns: 140px 1fr; gap: 8px; padding: 4px 0; }
.label { color: #6b7280; font-size: 0.85rem; }
.val { color: #111827; font-size: 0.85rem; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace; }
.json-panel .result { background: #111827; color: #e5e7eb; padding: 1rem; border-radius: 6px; overflow-x: auto; font-size: 0.8rem; }

@media (max-width: 720px) {
  .lookup-form { flex-direction: column; }
  .detail-row { grid-template-columns: 1fr; }
  .cert-node { width: 100%; }
  .banner-title { flex-direction: column; align-items: flex-start; }
}
</style>
