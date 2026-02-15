<script setup lang="ts">
import { ref, computed } from 'vue';
import { dnssecChain } from '../api/client';

interface DnskeyInfo {
  keyTag: number; algorithm: number; algorithmName: string;
  flags: number; role: string; protocol: number;
  keyLength: number; ttl: number; keyBase64: string;
}
interface DsInfo {
  keyTag: number; algorithm: number; algorithmName: string;
  digestType: number; digestTypeName: string; digest: string; ttl: number;
}
interface RrsigInfo {
  typeCovered: string; algorithm: number; algorithmName: string;
  labels: number; origTTL: number; expiration: string; inception: string;
  keyTag: number; signerName: string;
}
interface ChainLink {
  dsKeyTag: number; dsDigestType: string;
  matchesDnskey: boolean; dnskeyRole: string | null; dnskeyAlgorithm: string | null;
}
interface ZoneInfo {
  zone: string; adFlag?: boolean;
  dnskeys: DnskeyInfo[]; dsRecords: DsInfo[]; rrsigs: RrsigInfo[];
  nsRecords: string[]; chainLinks: ChainLink[];
  delegationStatus: string; hasDnskey: boolean; hasDs: boolean; dsMatchesDnskey: boolean;
  nsec3?: boolean | null; nsec3Params?: string;
  dnskeyError?: string; dsError?: string;
}
interface ChainResult { domain: string; zones: ZoneInfo[]; chainSecure: boolean; }

const domain = ref('');
const result = ref<ChainResult | null>(null);
const error = ref('');
const loading = ref(false);
const activeTab = ref<'chain' | 'details' | 'json'>('chain');
const selectedZone = ref<string | null>(null);

async function lookup() {
  if (!domain.value) return;
  loading.value = true;
  error.value = '';
  result.value = null;
  selectedZone.value = null;
  activeTab.value = 'chain';
  try {
    result.value = await dnssecChain(domain.value) as ChainResult;
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'An error occurred';
  } finally {
    loading.value = false;
  }
}

const selectedZoneInfo = computed(() => {
  if (!result.value || !selectedZone.value) return null;
  return result.value.zones.find(z => z.zone === selectedZone.value) || null;
});

function statusColor(status: string): string {
  if (status.startsWith('Secure')) return '#16a34a';
  if (status.startsWith('Bogus')) return '#dc2626';
  return '#d97706';
}

function selectZone(zone: string) {
  selectedZone.value = zone;
  activeTab.value = 'details';
}
</script>

<template>
  <div class="tool-view">
    <h2>DNSSEC Authentication Chain</h2>
    <p class="desc">Visualize the DNSSEC trust chain from root to domain — similar to DNSViz.</p>
    <form @submit.prevent="lookup" class="lookup-form">
      <input v-model="domain" placeholder="Enter domain (e.g. norrs.no)" class="input" />
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Analyzing...' : 'Analyze Chain' }}
      </button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="chain-results">
      <!-- Overall status banner -->
      <div class="chain-banner" :style="{ borderLeftColor: result.chainSecure ? '#16a34a' : '#d97706' }">
        <span class="chain-icon">{{ result.chainSecure ? '🔒' : '⚠️' }}</span>
        <div>
          <strong>{{ result.domain }}</strong> —
          <span :style="{ color: result.chainSecure ? '#16a34a' : '#d97706' }">
            {{ result.chainSecure ? 'Fully Secure Chain' : 'Chain Not Fully Secure' }}
          </span>
          <div class="chain-subtitle">{{ result.zones.length }} zone levels analyzed</div>
        </div>
      </div>

      <!-- Tabs -->
      <div class="tabs">
        <button :class="['tab', { active: activeTab === 'chain' }]" @click="activeTab = 'chain'">🔗 Chain Diagram</button>
        <button :class="['tab', { active: activeTab === 'details' }]" @click="activeTab = 'details'">📋 Record Details</button>
        <button :class="['tab', { active: activeTab === 'json' }]" @click="activeTab = 'json'">{ } JSON</button>
      </div>

      <!-- Chain Diagram Tab -->
      <div v-show="activeTab === 'chain'" class="tab-panel chain-panel">
        <div class="chain-diagram">
          <template v-for="(zone, idx) in result.zones" :key="zone.zone">
            <!-- Zone level -->
            <div class="zone-level" @click="selectZone(zone.zone)">
              <div class="zone-header">
                <span class="zone-name">{{ zone.zone === '.' ? 'Root (.)' : zone.zone }}</span>
                <span class="zone-status" :style="{ background: statusColor(zone.delegationStatus) }">
                  {{ zone.delegationStatus }}
                </span>
              </div>

              <!-- DNSKEY nodes -->
              <div class="record-nodes">
                <div v-for="key in zone.dnskeys" :key="'dk-' + key.keyTag" class="node dnskey-node" :class="{ ksk: key.flags === 257 }">
                  <div class="node-type">{{ key.role }}</div>
                  <div class="node-tag">id: {{ key.keyTag }}</div>
                  <div class="node-alg">{{ key.algorithmName }}</div>
                  <div class="node-size">{{ key.keyLength }} bit</div>
                </div>
                <div v-if="zone.dnskeys.length === 0" class="no-records">No DNSKEY</div>
              </div>


              <!-- DS nodes (from parent) -->
              <div v-if="zone.dsRecords.length > 0" class="record-nodes ds-row">
                <div v-for="ds in zone.dsRecords" :key="'ds-' + ds.keyTag + '-' + ds.digestType" class="node ds-node">
                  <div class="node-type">DS</div>
                  <div class="node-tag">id: {{ ds.keyTag }}</div>
                  <div class="node-alg">{{ ds.digestTypeName }}</div>
                </div>
              </div>

              <!-- Chain links -->
              <div v-if="zone.chainLinks.length > 0" class="chain-links">
                <div v-for="link in zone.chainLinks" :key="'cl-' + link.dsKeyTag" class="chain-link" :class="{ valid: link.matchesDnskey, invalid: !link.matchesDnskey }">
                  <span v-if="link.matchesDnskey">✓ DS {{ link.dsKeyTag }} → {{ link.dnskeyRole }} ({{ link.dnskeyAlgorithm }})</span>
                  <span v-else>✗ DS {{ link.dsKeyTag }} — no matching DNSKEY</span>
                </div>
              </div>

              <!-- NS records -->
              <div v-if="zone.nsRecords.length > 0" class="ns-info">
                NS: {{ zone.nsRecords.slice(0, 3).join(', ') }}{{ zone.nsRecords.length > 3 ? ` +${zone.nsRecords.length - 3} more` : '' }}
              </div>
            </div>

            <!-- Arrow between zones -->
            <div v-if="idx < result.zones.length - 1" class="zone-arrow">
              <div class="arrow-line"></div>
              <div class="arrow-label">delegates to</div>
              <div class="arrow-head">▼</div>
            </div>
          </template>
        </div>

        <!-- Legend -->
        <div class="legend">
          <span><span class="legend-box ksk-color"></span> KSK (Key Signing Key)</span>
          <span><span class="legend-box zsk-color"></span> ZSK (Zone Signing Key)</span>
          <span><span class="legend-box ds-color"></span> DS (Delegation Signer)</span>
          <span class="legend-hint">Click a zone for details</span>
        </div>
      </div>

      <!-- Record Details Tab -->
      <div v-show="activeTab === 'details'" class="tab-panel details-panel">
        <div v-if="!selectedZone" class="select-hint">Select a zone from the chain diagram or choose below:</div>
        <div class="zone-selector">
          <button v-for="zone in result.zones" :key="zone.zone"
            :class="['zone-btn', { active: selectedZone === zone.zone }]"
            @click="selectedZone = zone.zone">
            {{ zone.zone === '.' ? 'Root (.)' : zone.zone }}
          </button>
        </div>

        <div v-if="selectedZoneInfo" class="zone-details">
          <h3>{{ selectedZoneInfo.zone === '.' ? 'Root (.)' : selectedZoneInfo.zone }}</h3>
          <div class="detail-status" :style="{ color: statusColor(selectedZoneInfo.delegationStatus) }">
            Delegation: {{ selectedZoneInfo.delegationStatus }}
          </div>
          <div v-if="selectedZoneInfo.adFlag != null" class="detail-ad">
            AD flag: <strong>{{ selectedZoneInfo.adFlag ? 'Set ✓' : 'Not set' }}</strong>
          </div>

          <!-- DNSKEY table -->
          <div v-if="selectedZoneInfo.dnskeys.length > 0" class="detail-section">
            <h4>DNSKEY Records ({{ selectedZoneInfo.dnskeys.length }})</h4>
            <div v-for="key in selectedZoneInfo.dnskeys" :key="key.keyTag" class="detail-card">
              <div class="detail-row"><span class="label">Role</span><span class="val" :class="{ ksk: key.flags === 257 }">{{ key.role }} (flags={{ key.flags }})</span></div>
              <div class="detail-row"><span class="label">Key Tag</span><span class="val">{{ key.keyTag }}</span></div>
              <div class="detail-row"><span class="label">Algorithm</span><span class="val">{{ key.algorithmName }} ({{ key.algorithm }})</span></div>
              <div class="detail-row"><span class="label">Protocol</span><span class="val">{{ key.protocol }}</span></div>
              <div class="detail-row"><span class="label">Key Length</span><span class="val">{{ key.keyLength }} bits</span></div>
              <div class="detail-row"><span class="label">TTL</span><span class="val">{{ key.ttl }}s</span></div>
              <div class="detail-row"><span class="label">Key (truncated)</span><span class="val mono">{{ key.keyBase64 }}</span></div>
            </div>
          </div>

          <!-- DS table -->
          <div v-if="selectedZoneInfo.dsRecords.length > 0" class="detail-section">
            <h4>DS Records ({{ selectedZoneInfo.dsRecords.length }})</h4>
            <div v-for="ds in selectedZoneInfo.dsRecords" :key="ds.keyTag + '-' + ds.digestType" class="detail-card ds-card">
              <div class="detail-row"><span class="label">Key Tag</span><span class="val">{{ ds.keyTag }}</span></div>
              <div class="detail-row"><span class="label">Algorithm</span><span class="val">{{ ds.algorithmName }} ({{ ds.algorithm }})</span></div>
              <div class="detail-row"><span class="label">Digest Type</span><span class="val">{{ ds.digestTypeName }} ({{ ds.digestType }})</span></div>
              <div class="detail-row"><span class="label">TTL</span><span class="val">{{ ds.ttl }}s</span></div>
              <div class="detail-row"><span class="label">Digest</span><span class="val mono">{{ ds.digest }}</span></div>
            </div>
          </div>

          <!-- RRSIG table -->
          <div v-if="selectedZoneInfo.rrsigs.length > 0" class="detail-section">
            <h4>RRSIG Records ({{ selectedZoneInfo.rrsigs.length }})</h4>
            <div v-for="(sig, i) in selectedZoneInfo.rrsigs" :key="i" class="detail-card rrsig-card">
              <div class="detail-row"><span class="label">Type Covered</span><span class="val">{{ sig.typeCovered }}</span></div>
              <div class="detail-row"><span class="label">Algorithm</span><span class="val">{{ sig.algorithmName }} ({{ sig.algorithm }})</span></div>
              <div class="detail-row"><span class="label">Key Tag</span><span class="val">{{ sig.keyTag }}</span></div>
              <div class="detail-row"><span class="label">Signer</span><span class="val">{{ sig.signerName }}</span></div>
              <div class="detail-row"><span class="label">Expiration</span><span class="val">{{ sig.expiration }}</span></div>
              <div class="detail-row"><span class="label">Inception</span><span class="val">{{ sig.inception }}</span></div>
              <div class="detail-row"><span class="label">Labels</span><span class="val">{{ sig.labels }}</span></div>
              <div class="detail-row"><span class="label">Original TTL</span><span class="val">{{ sig.origTTL }}s</span></div>
            </div>
          </div>

          <!-- NS records -->
          <div v-if="selectedZoneInfo.nsRecords.length > 0" class="detail-section">
            <h4>NS Records ({{ selectedZoneInfo.nsRecords.length }})</h4>
            <ul class="ns-list">
              <li v-for="ns in selectedZoneInfo.nsRecords" :key="ns">{{ ns }}</li>
            </ul>
          </div>

          <!-- NSEC3 -->
          <div v-if="selectedZoneInfo.nsec3 != null" class="detail-section">
            <h4>Authenticated Denial</h4>
            <p>{{ selectedZoneInfo.nsec3 ? 'NSEC3' : 'NSEC' }}{{ selectedZoneInfo.nsec3Params ? ': ' + selectedZoneInfo.nsec3Params : '' }}</p>
          </div>
        </div>
      </div>

      <!-- JSON Tab -->
      <div v-show="activeTab === 'json'" class="tab-panel">
        <pre class="json-pre">{{ JSON.stringify(result, null, 2) }}</pre>
      </div>
    </div>
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
.chain-subtitle { font-size: 0.75rem; color: #888; margin-top: 2px; }

.tabs { display: flex; gap: 0; border-bottom: 2px solid #ddd; background: #f8f9fa; }
.tab { padding: 10px 20px; cursor: pointer; font-size: 0.85rem; color: #888; border: none; background: none; border-bottom: 2px solid transparent; margin-bottom: -2px; }
.tab.active { color: #1a1a2e; border-bottom-color: #1a1a2e; font-weight: 600; }
.tab:hover:not(.active) { color: #555; }

.tab-panel { padding: 20px; }
.chain-panel { overflow-x: auto; }

/* Chain Diagram */
.chain-diagram { display: flex; flex-direction: column; align-items: center; gap: 0; }

.zone-level { border: 2px solid #e2e8f0; border-radius: 12px; padding: 16px; min-width: 340px; max-width: 600px; width: 100%; cursor: pointer; transition: border-color 0.2s, box-shadow 0.2s; }
.zone-level:hover { border-color: #94a3b8; box-shadow: 0 2px 8px rgba(0,0,0,0.08); }

.zone-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.zone-name { font-weight: 700; font-size: 1rem; color: #1a1a2e; }
.zone-status { font-size: 0.7rem; padding: 3px 10px; border-radius: 12px; color: white; font-weight: 600; }

.record-nodes { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 6px; }
.node { border-radius: 8px; padding: 8px 12px; font-size: 0.75rem; line-height: 1.4; min-width: 100px; text-align: center; }
.dnskey-node { background: #dbeafe; border: 1px solid #93c5fd; }
.dnskey-node.ksk { background: #fef3c7; border: 1px solid #fbbf24; }
.ds-node { background: #d1fae5; border: 1px solid #6ee7b7; }
.node-type { font-weight: 700; font-size: 0.7rem; text-transform: uppercase; color: #475569; }
.node-tag { font-weight: 600; color: #1e293b; }
.node-alg { color: #64748b; font-size: 0.7rem; }
.node-size { color: #94a3b8; font-size: 0.65rem; }
.no-records { color: #94a3b8; font-size: 0.8rem; font-style: italic; }
.ds-row { margin-top: 4px; }

.chain-links { margin-top: 6px; }
.chain-link { font-size: 0.75rem; padding: 2px 0; }
.chain-link.valid { color: #16a34a; }
.chain-link.invalid { color: #dc2626; }

.ns-info { font-size: 0.7rem; color: #94a3b8; margin-top: 6px; }

.zone-arrow { display: flex; flex-direction: column; align-items: center; padding: 4px 0; }
.arrow-line { width: 2px; height: 16px; background: #cbd5e1; }
.arrow-label { font-size: 0.65rem; color: #94a3b8; padding: 2px 0; }
.arrow-head { color: #cbd5e1; font-size: 0.8rem; line-height: 1; }

.legend { display: flex; gap: 16px; margin-top: 20px; font-size: 0.75rem; color: #64748b; flex-wrap: wrap; justify-content: center; }
.legend-box { display: inline-block; width: 14px; height: 14px; border-radius: 3px; vertical-align: middle; margin-right: 4px; }
.ksk-color { background: #fef3c7; border: 1px solid #fbbf24; }
.zsk-color { background: #dbeafe; border: 1px solid #93c5fd; }
.ds-color { background: #d1fae5; border: 1px solid #6ee7b7; }
.legend-hint { color: #94a3b8; font-style: italic; }

/* Record Details */
.select-hint { color: #94a3b8; font-size: 0.85rem; margin-bottom: 12px; }
.zone-selector { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
.zone-btn { padding: 6px 16px; border: 1px solid #e2e8f0; border-radius: 6px; background: white; cursor: pointer; font-size: 0.8rem; color: #475569; }
.zone-btn.active { background: #1a1a2e; color: white; border-color: #1a1a2e; }
.zone-btn:hover:not(.active) { background: #f1f5f9; }

.zone-details h3 { margin: 0 0 8px; font-size: 1.1rem; color: #1a1a2e; }
.detail-status { font-weight: 600; font-size: 0.9rem; margin-bottom: 4px; }
.detail-ad { font-size: 0.8rem; color: #64748b; margin-bottom: 12px; }

.detail-section { margin-top: 16px; }
.detail-section h4 { font-size: 0.85rem; color: #475569; margin: 0 0 8px; padding-bottom: 4px; border-bottom: 1px solid #e2e8f0; }

.detail-card { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px 14px; margin-bottom: 8px; }
.detail-card.ds-card { border-left: 3px solid #6ee7b7; }
.detail-card.rrsig-card { border-left: 3px solid #a78bfa; }
.detail-row { display: flex; gap: 8px; padding: 2px 0; font-size: 0.8rem; }
.detail-row .label { color: #94a3b8; min-width: 110px; flex-shrink: 0; }
.detail-row .val { color: #1e293b; word-break: break-all; }
.detail-row .val.ksk { color: #b45309; font-weight: 600; }
.detail-row .val.mono { font-family: monospace; font-size: 0.75rem; }

.ns-list { margin: 0; padding-left: 20px; font-size: 0.8rem; color: #475569; }
.ns-list li { padding: 2px 0; }

.json-pre { font-family: monospace; font-size: 0.8rem; white-space: pre-wrap; word-break: break-all; line-height: 1.5; max-height: 500px; overflow: auto; }
</style>