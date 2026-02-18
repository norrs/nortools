<template id="dnssec-lookup-page">
  <div class="tool-view dnssec-lookup-page">
    <h2>DNSSEC Authentication Chain</h2>
    <p class="desc">Visualize the DNSSEC trust chain from root to domain - similar to DNSViz.</p>
    <form @submit.prevent="lookup" class="lookup-form">
      <input v-model="domain" placeholder="Enter domain (e.g. norrs.no)" class="input" />
      <select v-model="resolverPreset" class="select resolver-select">
        <option v-for="r in resolverOptions" :key="r.value" :value="r.value">{{ r.label }}</option>
      </select>
      <input
        v-if="resolverPreset === 'custom'"
        v-model="customResolver"
        placeholder="Custom DNS resolver (IP or host)"
        class="input resolver-input"
      />
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Analyzing...' : 'Analyze Chain' }}
      </button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="result && result.resolvers && result.resolvers.length" class="resolver-info">
      Resolver{{ result.resolvers.length > 1 ? 's' : '' }}:
      <strong>{{ result.resolvers.join(', ') }}</strong>
    </div>

    <div v-if="result" class="chain-results">
      <div class="chain-banner" :style="{ borderLeftColor: result.chainSecure ? '#16a34a' : '#d97706' }">
        <span class="chain-icon">{{ result.chainSecure ? '🔒' : '⚠️' }}</span>
        <div>
          <strong>{{ result.domain }}</strong> -
          <span :style="{ color: result.chainSecure ? '#16a34a' : '#d97706' }">
            {{ result.chainSecure ? 'Fully Secure Chain' : 'Chain Not Fully Secure' }}
          </span>
          <div class="chain-subtitle">{{ result.zones.length }} zone levels analyzed</div>
        </div>
      </div>

      <div class="tabs">
        <button :class="['tab', { active: activeTab === 'chain' }]" @click="activeTab = 'chain'">🔗 Chain Diagram</button>
        <button :class="['tab', { active: activeTab === 'details' }]" @click="activeTab = 'details'">📋 Record Details</button>
        <button :class="['tab', { active: activeTab === 'json' }]" @click="activeTab = 'json'">{ } JSON</button>
      </div>

      <div v-show="activeTab === 'chain'" class="tab-panel chain-panel">
        <div class="chain-diagram">
          <template v-for="(zone, idx) in result.zones" :key="zone.zone">
            <div class="zone-level" @click="selectZone(zone.zone)">
              <div class="zone-header">
                <span class="zone-name">{{ zone.zone === '.' ? 'Root (.)' : zone.zone }}</span>
                <span class="zone-status" :style="{ background: statusColor(zone.delegationStatus) }">
                  {{ zone.delegationStatus }}
                </span>
              </div>

              <div class="record-nodes">
                <div v-for="key in zone.dnskeys" :key="'dk-' + key.keyTag" class="node dnskey-node" :class="{ ksk: key.flags === 257 }">
                  <div class="node-type">{{ key.role }}</div>
                  <div class="node-tag">id: {{ key.keyTag }}</div>
                  <div class="node-alg">{{ key.algorithmName }}</div>
                  <div class="node-size">{{ key.keyLength }} bit</div>
                </div>
                <div v-if="zone.dnskeys.length === 0" class="no-records">No DNSKEY</div>
              </div>

              <div v-if="zone.dsRecords.length > 0" class="record-nodes ds-row">
                <div v-for="ds in zone.dsRecords" :key="'ds-' + ds.keyTag + '-' + ds.digestType" class="node ds-node">
                  <div class="node-type">DS</div>
                  <div class="node-tag">id: {{ ds.keyTag }}</div>
                  <div class="node-alg">{{ ds.digestTypeName }}</div>
                </div>
              </div>

              <div v-if="zone.chainLinks.length > 0" class="chain-links">
                <div v-for="link in zone.chainLinks" :key="'cl-' + link.dsKeyTag" class="chain-link" :class="{ valid: link.matchesDnskey, invalid: !link.matchesDnskey }">
                  <span v-if="link.matchesDnskey">✓ DS {{ link.dsKeyTag }} → {{ link.dnskeyRole }} ({{ link.dnskeyAlgorithm }})</span>
                  <span v-else>✗ DS {{ link.dsKeyTag }} - no matching DNSKEY</span>
                </div>
              </div>

              <div v-if="zone.nsRecords.length > 0" class="ns-info">
                NS: {{ zone.nsRecords.slice(0, 3).join(', ') }}{{ zone.nsRecords.length > 3 ? ` +${zone.nsRecords.length - 3} more` : '' }}
              </div>
            </div>

            <div v-if="idx < result.zones.length - 1" class="zone-arrow">
              <div class="arrow-line"></div>
              <div class="arrow-label">delegates to</div>
              <div class="arrow-head">▼</div>
            </div>
          </template>
        </div>

        <div class="legend">
          <span><span class="legend-box ksk-color"></span> KSK (Key Signing Key)</span>
          <span><span class="legend-box zsk-color"></span> ZSK (Zone Signing Key)</span>
          <span><span class="legend-box ds-color"></span> DS (Delegation Signer)</span>
          <span class="legend-hint">Click a zone for details</span>
        </div>
      </div>

      <div v-show="activeTab === 'details'" class="tab-panel details-panel">
        <div v-if="!selectedZone" class="select-hint">Select a zone from the chain diagram or choose below:</div>
        <div class="zone-selector">
          <button
            v-for="zone in result.zones"
            :key="zone.zone"
            :class="['zone-btn', { active: selectedZone === zone.zone }]"
            @click="selectedZone = zone.zone"
          >
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

          <div v-if="selectedZoneInfo.nsRecords.length > 0" class="detail-section">
            <h4>NS Records ({{ selectedZoneInfo.nsRecords.length }})</h4>
            <ul class="ns-list">
              <li v-for="ns in selectedZoneInfo.nsRecords" :key="ns">{{ ns }}</li>
            </ul>
          </div>

          <div v-if="selectedZoneInfo.nsec3 != null" class="detail-section">
            <h4>Authenticated Denial</h4>
            <p>{{ selectedZoneInfo.nsec3 ? 'NSEC3' : 'NSEC' }}{{ selectedZoneInfo.nsec3Params ? ': ' + selectedZoneInfo.nsec3Params : '' }}</p>
          </div>
        </div>
      </div>

      <div v-show="activeTab === 'json'" class="tab-panel">
        <pre class="json-pre">{{ JSON.stringify(result, null, 2) }}</pre>
      </div>
    </div>
    <div class="cli-copy">
      <div class="cli-label">CLI (JSON)</div>
      <div class="cli-row">
        <code class="cli-command">{{ cliCommand }}</code>
        <button class="cli-btn" :disabled="!domain" @click="copyCli">
          {{ copied ? "Copied" : "Copy CLI Command" }}
        </button>
      </div>
    </div>
  </div>
</template>

<script>
app.component("dnssec-lookup-page", {
  template: "#dnssec-lookup-page",
  data() {
    return {
      domain: "",
      resolverPreset: "auto",
      customResolver: "",
      result: null,
      error: "",
      loading: false,
      activeTab: "chain",
      selectedZone: null,
      copied: false,
      resolverOptions: [
        { value: "auto", label: "Local network resolver (auto-detect)" },
        { value: "1.1.1.1", label: "Cloudflare (1.1.1.1)" },
        { value: "8.8.8.8", label: "Google (8.8.8.8)" },
        { value: "9.9.9.9", label: "Quad9 (9.9.9.9)" },
        { value: "208.67.222.222", label: "OpenDNS (208.67.222.222)" },
        { value: "94.140.14.14", label: "AdGuard (94.140.14.14)" },
        { value: "custom", label: "Custom resolver..." },
      ],
    };
  },
  computed: {
    selectedResolver() {
      if (this.resolverPreset === "auto") return "";
      if (this.resolverPreset === "custom") return this.customResolver.trim();
      return this.resolverPreset;
    },
    selectedZoneInfo() {
      if (!this.result || !this.selectedZone) return null;
      return this.result.zones.find((z) => z.zone === this.selectedZone) || null;
    },
    cliCommand() {
      const parts = ["nortools", "dnssec-chain", "--json"];
      if (this.selectedResolver) parts.push("--server", this.selectedResolver);
      if (this.domain) parts.push(this.domain);
      return parts.join(" ");
    },
  },
  methods: {
    async copyCli() {
      if (!this.domain) return;
      try {
        await navigator.clipboard.writeText(this.cliCommand);
        this.copied = true;
        setTimeout(() => { this.copied = false; }, 1500);
      } catch (_) {
        this.copied = false;
      }
    },
    statusColor(status) {
      if (status && status.startsWith("Secure")) return "#16a34a";
      if (status && status.startsWith("Bogus")) return "#dc2626";
      return "#d97706";
    },
    selectZone(zone) {
      this.selectedZone = zone;
      this.activeTab = "details";
    },
    async lookup() {
      if (!this.domain) return;
      this.loading = true;
      this.error = "";
      this.result = null;
      this.selectedZone = null;
      this.activeTab = "chain";
      try {
        const params = new URLSearchParams();
        if (this.selectedResolver) params.set("server", this.selectedResolver);
        const query = params.toString();
        const response = await fetch(`/api/dnssec-chain/${encodeURIComponent(this.domain)}${query ? `?${query}` : ""}`);
        if (!response.ok) throw new Error(`API error: ${response.status} ${response.statusText}`);
        this.result = await response.json();
      } catch (e) {
        this.error = e instanceof Error ? e.message : "An error occurred";
      } finally {
        this.loading = false;
      }
    },
  },
});
</script>

<style>

.dnssec-lookup-page .tool-view { padding: 1rem 0; 
}
.dnssec-lookup-page h2 { margin-bottom: 0.25rem; 
}
.dnssec-lookup-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; 
}
.dnssec-lookup-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; 
}
.dnssec-lookup-page .input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; 
}
.dnssec-lookup-page .select { padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; 
}
.dnssec-lookup-page .resolver-select { min-width: 280px; 
}
.dnssec-lookup-page .resolver-input { min-width: 240px; flex: 0.7; 
}
.dnssec-lookup-page .btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; 
}
.dnssec-lookup-page .btn:hover  { background: #2a2a4e; 
}
.dnssec-lookup-page .btn:disabled  { opacity: 0.6; 
}
.dnssec-lookup-page .error { color: #d32f2f; margin-bottom: 1rem; 
}
.dnssec-lookup-page .resolver-info { margin-bottom: 0.75rem; color: #334155; font-size: 0.9rem; 
}

.dnssec-lookup-page .chain-results { background: white; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.1); 
}

.dnssec-lookup-page .chain-banner { display: flex; align-items: center; gap: 12px; padding: 16px 20px; border-left: 4px solid; background: #f8f9fa; 
}
.dnssec-lookup-page .chain-icon { font-size: 1.5rem; 
}
.dnssec-lookup-page .chain-subtitle { font-size: 0.75rem; color: #888; margin-top: 2px; 
}

.dnssec-lookup-page .tabs { display: flex; gap: 0; border-bottom: 2px solid #ddd; background: #f8f9fa; 
}
.dnssec-lookup-page .tab { padding: 10px 20px; cursor: pointer; font-size: 0.85rem; color: #888; border: none; background: none; border-bottom: 2px solid transparent; margin-bottom: -2px; }
.dnssec-lookup-page .tab.active { color: #1a1a2e; border-bottom-color: #1a1a2e; font-weight: 600; 
}
.dnssec-lookup-page .tab:hover:not(.active)  { color: #555; 
}

.dnssec-lookup-page .tab-panel { padding: 20px; 
}
.dnssec-lookup-page .chain-panel { overflow-x: auto; 
}

.dnssec-lookup-page .chain-diagram { display: flex; flex-direction: column; align-items: center; gap: 0; 
}

.dnssec-lookup-page .zone-level { border: 2px solid #e2e8f0; border-radius: 12px; padding: 16px; min-width: 340px; max-width: 600px; width: 100%; cursor: pointer; transition: border-color 0.2s, box-shadow 0.2s; 
}
.dnssec-lookup-page .zone-level:hover  { border-color: #94a3b8; box-shadow: 0 2px 8px rgba(0,0,0,0.08); 
}

.dnssec-lookup-page .zone-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; 
}
.dnssec-lookup-page .zone-name { font-weight: 700; font-size: 1rem; color: #1a1a2e; 
}
.dnssec-lookup-page .zone-status { font-size: 0.7rem; padding: 3px 10px; border-radius: 12px; color: white; font-weight: 600; 
}

.dnssec-lookup-page .record-nodes { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 6px; 
}
.dnssec-lookup-page .node { border-radius: 8px; padding: 8px 12px; font-size: 0.75rem; line-height: 1.4; min-width: 100px; text-align: center; 
}
.dnssec-lookup-page .dnskey-node { background: #dbeafe; border: 1px solid #93c5fd; }
.dnssec-lookup-page .dnskey-node.ksk { background: #fef3c7; border: 1px solid #fbbf24; 
}
.dnssec-lookup-page .ds-node { background: #d1fae5; border: 1px solid #6ee7b7; 
}
.dnssec-lookup-page .node-type { font-weight: 700; font-size: 0.7rem; text-transform: uppercase; color: #475569; 
}
.dnssec-lookup-page .node-tag { font-weight: 600; color: #1e293b; 
}
.dnssec-lookup-page .node-alg { color: #64748b; font-size: 0.7rem; 
}
.dnssec-lookup-page .node-size { color: #94a3b8; font-size: 0.65rem; 
}
.dnssec-lookup-page .no-records { color: #94a3b8; font-size: 0.8rem; font-style: italic; 
}
.dnssec-lookup-page .ds-row { margin-top: 4px; 
}

.dnssec-lookup-page .chain-links { margin-top: 6px; 
}
.dnssec-lookup-page .chain-link { font-size: 0.75rem; padding: 2px 0; }
.dnssec-lookup-page .chain-link.valid { color: #16a34a; }
.dnssec-lookup-page .chain-link.invalid { color: #dc2626; 
}

.dnssec-lookup-page .ns-info { font-size: 0.7rem; color: #94a3b8; margin-top: 6px; 
}

.dnssec-lookup-page .zone-arrow { display: flex; flex-direction: column; align-items: center; padding: 4px 0; 
}
.dnssec-lookup-page .arrow-line { width: 2px; height: 16px; background: #cbd5e1; 
}
.dnssec-lookup-page .arrow-label { font-size: 0.65rem; color: #94a3b8; padding: 2px 0; 
}
.dnssec-lookup-page .arrow-head { color: #cbd5e1; font-size: 0.8rem; line-height: 1; 
}

.dnssec-lookup-page .legend { display: flex; gap: 16px; margin-top: 20px; font-size: 0.75rem; color: #64748b; flex-wrap: wrap; justify-content: center; 
}
.dnssec-lookup-page .legend-box { display: inline-block; width: 14px; height: 14px; border-radius: 3px; vertical-align: middle; margin-right: 4px; 
}
.dnssec-lookup-page .ksk-color { background: #fef3c7; border: 1px solid #fbbf24; 
}
.dnssec-lookup-page .zsk-color { background: #dbeafe; border: 1px solid #93c5fd; 
}
.dnssec-lookup-page .ds-color { background: #d1fae5; border: 1px solid #6ee7b7; 
}
.dnssec-lookup-page .legend-hint { color: #94a3b8; font-style: italic; 
}

.dnssec-lookup-page .select-hint { color: #94a3b8; font-size: 0.85rem; margin-bottom: 12px; 
}
.dnssec-lookup-page .zone-selector { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; 
}
.dnssec-lookup-page .zone-btn { padding: 6px 16px; border: 1px solid #e2e8f0; border-radius: 6px; background: white; cursor: pointer; font-size: 0.8rem; color: #475569; }
.dnssec-lookup-page .zone-btn.active { background: #1a1a2e; color: white; border-color: #1a1a2e; 
}
.dnssec-lookup-page .zone-btn:hover:not(.active)  { background: #f1f5f9; }

.dnssec-lookup-page .zone-details h3 { margin: 0 0 8px; font-size: 1.1rem; color: #1a1a2e; 
}
.dnssec-lookup-page .detail-status { font-weight: 600; font-size: 0.9rem; margin-bottom: 4px; 
}
.dnssec-lookup-page .detail-ad { font-size: 0.8rem; color: #64748b; margin-bottom: 12px; 
}

.dnssec-lookup-page .detail-section { margin-top: 16px; }
.dnssec-lookup-page .detail-section h4 { font-size: 0.85rem; color: #475569; margin: 0 0 8px; padding-bottom: 4px; border-bottom: 1px solid #e2e8f0; 
}

.dnssec-lookup-page .detail-card { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px 14px; margin-bottom: 8px; }
.dnssec-lookup-page .detail-card.ds-card { border-left: 3px solid #6ee7b7; }
.dnssec-lookup-page .detail-card.rrsig-card { border-left: 3px solid #a78bfa; 
}
.dnssec-lookup-page .detail-row { display: flex; gap: 8px; padding: 2px 0; font-size: 0.8rem; }
.dnssec-lookup-page .detail-row .label { color: #94a3b8; min-width: 110px; flex-shrink: 0; }
.dnssec-lookup-page .detail-row .val { color: #1e293b; word-break: break-all; }
.dnssec-lookup-page .detail-row .val.ksk { color: #b45309; font-weight: 600; }
.dnssec-lookup-page .detail-row .val.mono { font-family: monospace; font-size: 0.75rem; 
}

.dnssec-lookup-page .ns-list { margin: 0; padding-left: 20px; font-size: 0.8rem; color: #475569; }
.dnssec-lookup-page .ns-list li { padding: 2px 0; 
}

.dnssec-lookup-page .json-pre { font-family: monospace; font-size: 0.8rem; white-space: pre-wrap; word-break: break-all; line-height: 1.5; max-height: 500px; overflow: auto; 
}
.dnssec-lookup-page .cli-copy { margin-top: 1.25rem; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 0.75rem 1rem; width: 100%; 
}
.dnssec-lookup-page .cli-label { font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; margin-bottom: 0.5rem; 
}
.dnssec-lookup-page .cli-row { display: flex; gap: 0.75rem; align-items: center; flex-wrap: wrap; 
}
.dnssec-lookup-page .cli-command { background: #0f172a; color: #e2e8f0; padding: 0.5rem 0.75rem; border-radius: 6px; font-size: 0.85rem; overflow-x: auto; flex: 1; min-width: 200px; 
}
.dnssec-lookup-page .cli-btn { padding: 0.45rem 0.9rem; background: #1a1a2e; color: white; border: none; border-radius: 6px; cursor: pointer; font-size: 0.85rem; white-space: nowrap; 
}
.dnssec-lookup-page .cli-btn:hover  { background: #2a2a4e; 
}
.dnssec-lookup-page .cli-btn:disabled  { opacity: 0.6; cursor: not-allowed; }
</style>
