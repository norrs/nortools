<template id="rpki-route-page">
  <div class="tool-view rpki-route-page">
    <h2>RPKI Route Validation</h2>
    <p class="desc">Validate route announcement origin (ASN + prefix) using Team Cymru + Routinator (IPv4 and IPv6).</p>

    <div class="mode-row">
      <label class="mode-option">
        <input type="radio" value="auto" v-model="mode" />
        <span>Auto (IP/domain)</span>
      </label>
      <label class="mode-option">
        <input type="radio" value="manual" v-model="mode" />
        <span>Manual (IP/prefix + ASN)</span>
      </label>
    </div>

    <form @submit.prevent="validate" class="lookup-form">
      <input v-if="mode === 'auto'" v-model.trim="query" placeholder="IPv4/IPv6 or domain (e.g. 1.1.1.1, 2606:4700:4700::1111, cloudflare.com)" class="input" />
      <template v-else>
        <input v-model.trim="manualRoute" placeholder="IP or prefix (e.g. 1.1.1.0/24, 2001:db8::/32)" class="input" />
        <input v-model.trim="manualAsn" placeholder="ASN (e.g. AS13335)" class="input input-asn" />
      </template>
      <button type="submit" :disabled="loading || !canSubmit()" class="btn">
        {{ loading ? 'Validating...' : 'Validate Route' }}
      </button>
    </form>

    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="result-card">
      <div class="summary-row">
        <div class="pair"><span>{{ result.inputType === 'manual' ? 'Route Input' : 'Input' }}</span><strong>{{ result.input || result.ip }}</strong></div>
        <div class="pair"><span>{{ result.inputType === 'manual' ? 'Anchor IP' : 'IP Used' }}</span><strong>{{ result.ip }}</strong></div>
        <div class="pair"><span>ASN</span><strong>{{ result.asn || 'Unknown' }}</strong></div>
        <div class="pair"><span>Prefix</span><strong>{{ result.prefix || 'Unknown' }}</strong></div>
        <div class="pair">
          <span>Validation</span>
          <strong :class="statusClass(result.validationState)">{{ result.validationState }}</strong>
        </div>
      </div>

      <div class="details">
        <p><strong>Source:</strong> {{ result.validationSource }}</p>
        <p v-if="result.inputType === 'domain'"><strong>Resolved:</strong> {{ result.input }} -> {{ result.ip }}</p>
        <p v-if="result.inputType === 'manual'"><strong>Lookup:</strong> Manual route+ASN validation (Team Cymru skipped)</p>
        <p v-if="result.validationReason"><strong>Reason:</strong> {{ result.validationReason }}</p>
        <p v-if="result.validationDetails"><strong>Details:</strong> {{ result.validationDetails }}</p>
      </div>

      <div class="tabs">
        <button :class="['tab', { active: activeTab === 'overview' }]" @click="activeTab = 'overview'">Overview</button>
        <button v-if="hasVrpObjects(result)" :class="['tab', { active: activeTab === 'objects' }]" @click="activeTab = 'objects'">
          Matching Objects
        </button>
        <button :class="['tab', { active: activeTab === 'json' }]" @click="activeTab = 'json'">JSON</button>
      </div>
      <div v-show="activeTab === 'overview'" class="tab-panel">
        <div v-if="hasVrpObjects(result)" class="vrp-counts">
          <div class="vrp-chip">
            <span>Matched</span>
            <strong>{{ vrpCount(result, 'matched') }}</strong>
          </div>
          <div class="vrp-chip">
            <span>ASN Mismatch</span>
            <strong>{{ vrpCount(result, 'unmatchedAs') }}</strong>
          </div>
          <div class="vrp-chip">
            <span>Length Mismatch</span>
            <strong>{{ vrpCount(result, 'unmatchedLength') }}</strong>
          </div>
        </div>
        <p v-else class="muted">Routinator did not return matching-object details for this route.</p>
      </div>
      <div v-show="activeTab === 'objects'" class="tab-panel">
        <div v-for="group in vrpGroups(result)" :key="group.key" class="vrp-group">
          <div class="vrp-group-head">
            <h4>{{ group.label }}</h4>
            <span class="vrp-badge">{{ group.items.length }}</span>
          </div>
          <p v-if="!group.items.length" class="muted">No objects in this group.</p>
          <div v-else class="vrp-table-wrap">
            <table class="vrp-table">
              <thead>
                <tr>
                  <th v-for="column in group.columns" :key="`${group.key}-h-${column}`">{{ humanizeKey(column) }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(item, idx) in group.items" :key="`${group.key}-${idx}`">
                  <td v-for="column in group.columns" :key="`${group.key}-${idx}-${column}`">
                    <code>{{ item[column] || 'n/a' }}</code>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
      <div v-show="activeTab === 'json'" class="tab-panel">
        <pre class="result">{{ JSON.stringify(result, null, 2) }}</pre>
      </div>
    </div>
  </div>
</template>

<script>
app.component("rpki-route-page", {
  template: "#rpki-route-page",
  data() {
    return {
      mode: "auto",
      query: "",
      manualRoute: "",
      manualAsn: "",
      loading: false,
      error: "",
      result: null,
      activeTab: "overview",
    }
  },
  methods: {
    canSubmit() {
      if (this.mode === "manual") return Boolean(this.manualRoute && this.manualAsn)
      return Boolean(this.query)
    },
    statusClass(state) {
      const normalized = String(state || "").toLowerCase()
      if (normalized === "valid") return "state-valid"
      if (normalized === "invalid") return "state-invalid"
      if (normalized === "not_found") return "state-not-found"
      return "state-unknown"
    },
    hasVrpObjects(result) {
      const vrps = result && result.validationVrpObjects
      if (!vrps) return false
      return this.vrpCount(result, "matched") > 0 || this.vrpCount(result, "unmatchedAs") > 0 || this.vrpCount(result, "unmatchedLength") > 0
    },
    vrpCount(result, key) {
      const vrps = result && result.validationVrpObjects
      if (!vrps || !Array.isArray(vrps[key])) return 0
      return vrps[key].length
    },
    vrpGroups(result) {
      if (!result || !result.validationVrpObjects) return []
      const groups = [
        { key: "matched", label: "Matched VRPs", items: this.normalizeItems(result.validationVrpObjects.matched) },
        { key: "unmatchedAs", label: "ASN Mismatch VRPs", items: this.normalizeItems(result.validationVrpObjects.unmatchedAs) },
        { key: "unmatchedLength", label: "Max-Length Mismatch VRPs", items: this.normalizeItems(result.validationVrpObjects.unmatchedLength) },
      ]
      return groups.map((group) => ({ ...group, columns: this.groupColumns(group.items) }))
    },
    normalizeItems(items) {
      if (!Array.isArray(items)) return []
      return items.filter((item) => item && typeof item === "object")
    },
    groupColumns(items) {
      const columns = []
      const seen = new Set()
      for (const item of items) {
        for (const key of Object.keys(item)) {
          if (!seen.has(key)) {
            seen.add(key)
            columns.push(key)
          }
        }
      }
      if (!columns.length) return ["value"]
      return columns
    },
    humanizeKey(key) {
      return String(key || "")
        .replace(/_/g, " ")
        .replace(/\b\w/g, (c) => c.toUpperCase())
    },
    async validate() {
      if (!this.canSubmit()) return
      this.loading = true
      this.error = ""
      this.result = null
      try {
        const endpoint = this.mode === "manual"
          ? `/api/rpki-route/${encodeURIComponent(this.manualRoute)}?asn=${encodeURIComponent(this.manualAsn)}`
          : `/api/rpki-route/${encodeURIComponent(this.query)}`
        const res = await fetch(endpoint)
        if (!res.ok) throw new Error(`API error: ${res.status} ${res.statusText}`)
        const payload = await res.json()
        if (payload && payload.error) throw new Error(payload.error)
        this.result = payload
        this.activeTab = this.hasVrpObjects(this.result) ? "overview" : "json"
      } catch (e) {
        this.error = e instanceof Error ? e.message : "Validation failed"
      } finally {
        this.loading = false
      }
    },
  },
})
</script>

<style>
.rpki-route-page .tool-view { padding: 1rem 0; }
.rpki-route-page h2 { margin-bottom: 0.25rem; }
.rpki-route-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.rpki-route-page .mode-row { display: flex; gap: 1rem; flex-wrap: wrap; margin-bottom: 0.55rem; }
.rpki-route-page .mode-option { display: inline-flex; align-items: center; gap: 0.35rem; font-size: 0.82rem; color: #334155; cursor: pointer; }
.rpki-route-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
.rpki-route-page .input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; }
.rpki-route-page .input-asn { max-width: 12rem; }
.rpki-route-page .btn { padding: 0.5rem 1.2rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; }
.rpki-route-page .btn:hover { background: #2a2a4e; }
.rpki-route-page .btn:disabled { opacity: 0.6; cursor: not-allowed; }
.rpki-route-page .error { color: #d32f2f; margin-bottom: 1rem; }
.rpki-route-page .result-card { background: white; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; }
.rpki-route-page .summary-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 0.6rem; padding: 0.85rem 1rem; border-bottom: 1px solid #e5e7eb; }
.rpki-route-page .pair { min-width: 0; }
.rpki-route-page .pair span { display: block; font-size: 0.75rem; color: #64748b; }
.rpki-route-page .pair strong { display: block; font-size: 0.95rem; color: #0f172a; line-height: 1.25; overflow-wrap: anywhere; word-break: break-word; }
.rpki-route-page .details { padding: 0.7rem 1rem; border-bottom: 1px solid #e5e7eb; }
.rpki-route-page .details p { margin: 0.25rem 0; font-size: 0.86rem; color: #334155; }
.rpki-route-page .tabs { display: flex; border-bottom: 1px solid #e5e7eb; background: #f8fafc; }
.rpki-route-page .tab { border: none; background: none; padding: 0.55rem 0.9rem; cursor: pointer; color: #64748b; font-size: 0.82rem; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.rpki-route-page .tab.active { color: #111827; font-weight: 600; border-bottom-color: #111827; }
.rpki-route-page .tab-panel { padding: 0.9rem 1rem; }
.rpki-route-page .muted { margin: 0; font-size: 0.83rem; color: #64748b; }
.rpki-route-page .vrp-counts { display: flex; gap: 0.5rem; flex-wrap: wrap; }
.rpki-route-page .vrp-chip { border: 1px solid #cbd5e1; border-radius: 999px; background: #f8fafc; padding: 0.35rem 0.7rem; display: inline-flex; gap: 0.4rem; align-items: center; }
.rpki-route-page .vrp-chip span { color: #475569; font-size: 0.78rem; }
.rpki-route-page .vrp-chip strong { color: #0f172a; font-size: 0.84rem; }
.rpki-route-page .vrp-group + .vrp-group { margin-top: 0.9rem; }
.rpki-route-page .vrp-group-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.45rem; }
.rpki-route-page .vrp-group h4 { margin: 0; font-size: 0.84rem; color: #0f172a; }
.rpki-route-page .vrp-badge { font-size: 0.75rem; color: #334155; background: #e2e8f0; padding: 0.15rem 0.48rem; border-radius: 999px; }
.rpki-route-page .vrp-table-wrap { overflow-x: auto; }
.rpki-route-page .vrp-table { width: 100%; border-collapse: collapse; font-size: 0.78rem; }
.rpki-route-page .vrp-table th,
.rpki-route-page .vrp-table td { border: 1px solid #e2e8f0; text-align: left; padding: 0.35rem 0.45rem; }
.rpki-route-page .vrp-table th { background: #f8fafc; color: #334155; font-weight: 600; white-space: nowrap; }
.rpki-route-page .vrp-table code { color: #0f172a; font-size: 0.76rem; }
.rpki-route-page .result { background: #0f172a; color: #e2e8f0; padding: 0.9rem; border-radius: 6px; overflow-x: auto; font-size: 0.82rem; }
.rpki-route-page .state-valid { color: #166534; }
.rpki-route-page .state-invalid { color: #b91c1c; }
.rpki-route-page .state-not-found { color: #92400e; }
.rpki-route-page .state-unknown { color: #1e3a8a; }
@media (max-width: 720px) {
  .rpki-route-page .summary-row { grid-template-columns: 1fr; }
  .rpki-route-page .lookup-form { flex-direction: column; }
  .rpki-route-page .input-asn { max-width: none; }
}
</style>
