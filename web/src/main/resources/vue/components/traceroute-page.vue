<template id="traceroute-page">
  <div class="tool-view traceroute-page">
    <h2>Traceroute (Visual)</h2>
    <p class="desc">Trace the network path with ASN info and hop diagram. Choose IP lookup mode for map labeling.</p>
    <form @submit.prevent="trace" class="lookup-form">
      <input v-model="host" placeholder="Enter host (e.g. google.com)" class="input" />
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Tracing...' : 'Trace Route' }}
      </button>
    </form>

    <div class="lookup-mode">
      <span class="lookup-label">IP lookup mode:</span>
      <label class="geo-toggle">
        <input type="radio" value="geo" v-model="lookupMode" />
        Geo lookup
      </label>
      <label class="geo-toggle">
        <input type="radio" value="asn-country" v-model="lookupMode" />
        ASN country lookup
      </label>
    </div>
    <div class="geo-hint">Geo lookup uses ip-api.com free API (rate-limited to 45 req/min, non-commercial use only).</div>
    <label class="geo-toggle">
      <input type="checkbox" v-model="ptrLookup" />
      Enable PTR lookup (reverse DNS)
    </label>

    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="trace-results">
      <div class="tabs">
        <button :class="['tab', { active: activeTab === 'diagram' }]" @click="activeTab = 'diagram'">Hop Diagram</button>
        <button :class="['tab', { active: activeTab === 'map' }]" @click="showMap()">World Map</button>
        <button :class="['tab', { active: activeTab === 'json' }]" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'diagram'" class="tab-panel diagram-panel">
        <div class="hop-summary">{{ result.hopCount }} hops to <strong>{{ result.host }}</strong></div>
        <div class="hop-list">
          <template v-for="(hop, idx) in result.hops" :key="hop.hop">
            <div v-if="hop.asn && (idx === 0 || hop.asn !== result.hops[idx - 1]?.asn)" class="asn-header">
              {{ hop.asn }} {{ hop.asName || '' }}
            </div>
            <div class="hop-row">
              <div class="hop-node">
                <div v-if="idx > 0" class="node-line" :style="{ background: latencyColor(hop.rttAvg) }"></div>
                <div v-else class="node-line spacer"></div>
                <div class="node-circle" :style="{ background: latencyColor(hop.rttAvg) }">{{ hop.hop }}</div>
                <div v-if="idx < result.hops.length - 1" class="node-line" :style="{ background: latencyColor(hop.rttAvg) }"></div>
                <div v-else-if="traceComplete" class="node-end">END</div>
                <div v-else class="node-line spacer"></div>
              </div>
              <div class="hop-bar" :style="{ width: (hop.rttAvg != null ? Math.min(Math.max(hop.rttAvg / 5, 4), 200) : 0) + 'px', background: latencyColor(hop.rttAvg) }"></div>
              <div class="hop-info">
                <span class="hop-host">{{ hop.host === '*' ? '* * *' : hop.host }}</span>
                <span v-if="hop.ip && hop.ip !== '*'" class="hop-ip">({{ hop.ip }})</span>
                <span v-if="hop.rttAvg != null" class="hop-rtt" :style="{ color: latencyColor(hop.rttAvg) }">{{ hop.rttAvg.toFixed(1) }} ms</span>
                <span v-if="hop.ptr" class="hop-ptr">PTR: {{ hop.ptr }}</span>
                <span v-if="lookupMode === 'geo' && (hop.city || hop.country)" class="hop-geo hop-geo--pin">
                  <span class="hop-geo-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none">
                      <path d="M12 22s7-6.2 7-12a7 7 0 1 0-14 0c0 5.8 7 12 7 12Z" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
                      <circle cx="12" cy="10" r="2.6" stroke="currentColor" stroke-width="1.8"/>
                    </svg>
                  </span>
                  {{ hop.city ? hop.city + ', ' : '' }}{{ hop.country || '' }}
                </span>
                <span v-if="lookupMode === 'asn-country' && hop.asnCountry" class="hop-geo hop-geo--asn">
                  <span class="hop-geo-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none">
                      <circle cx="12" cy="12" r="8" stroke="currentColor" stroke-width="1.8"/>
                      <path d="M4 12h16M12 4c2.4 2.2 3.8 5.1 3.8 8S14.4 17.8 12 20M12 4c-2.4 2.2-3.8 5.1-3.8 8S9.6 17.8 12 20" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
                    </svg>
                  </span>
                  ASN Country: {{ hop.asnCountry }}
                </span>
              </div>
            </div>
          </template>
        </div>
      </div>

      <div v-show="activeTab === 'map'" class="tab-panel map-panel">
        <div id="trace-map" class="map-container"></div>
        <div v-if="!result.hops.some((h) => h.lat != null && h.lon != null)" class="map-empty">
          No map coordinates were returned for this trace.
        </div>
      </div>

      <div v-show="activeTab === 'json'" class="tab-panel">
        <pre class="json-pre">{{ JSON.stringify(result, null, 2) }}</pre>
      </div>
    </div>
  </div>
</template>

<script>
app.component("traceroute-page", {
  template: "#traceroute-page",
  data() {
    return {
      host: "",
      lookupMode: "geo",
      ptrLookup: true,
      result: null,
      error: "",
      loading: false,
      traceComplete: false,
      activeTab: "diagram",
      mapInstance: null,
      traceSource: null,
      leafletReady: false,
    }
  },
  beforeUnmount() {
    this.closeTraceSource()
    if (this.mapInstance) {
      this.mapInstance.remove()
      this.mapInstance = null
    }
  },
  methods: {
    latencyColor(ms) {
      if (ms == null) return '#64748b'
      if (ms < 30) return '#34d399'
      if (ms < 80) return '#a3e635'
      if (ms < 150) return '#fbbf24'
      if (ms < 300) return '#f97316'
      return '#ef4444'
    },
    closeTraceSource() {
      if (this.traceSource) {
        this.traceSource.close()
        this.traceSource = null
      }
    },
    upsertHop(hop) {
      if (!this.result) return
      const idx = this.result.hops.findIndex((h) => h.hop === hop.hop)
      if (idx >= 0) {
        this.result.hops[idx] = hop
      } else {
        this.result.hops.push(hop)
        this.result.hops.sort((a, b) => a.hop - b.hop)
      }
      this.result.hopCount = this.result.hops.length
    },
    async ensureLeaflet() {
      if (window.L) {
        this.leafletReady = true
        return
      }
      if (!document.getElementById('leaflet-css')) {
        const link = document.createElement('link')
        link.id = 'leaflet-css'
        link.rel = 'stylesheet'
        link.href = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'
        document.head.appendChild(link)
      }
      if (!document.getElementById('leaflet-js')) {
        await new Promise((resolve, reject) => {
          const s = document.createElement('script')
          s.id = 'leaflet-js'
          s.src = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'
          s.onload = resolve
          s.onerror = reject
          document.head.appendChild(s)
        })
      }
      this.leafletReady = !!window.L
    },
    async trace() {
      if (!this.host) return
      this.closeTraceSource()
      this.loading = true
      this.traceComplete = false
      this.error = ''
      this.result = { host: this.host, maxHops: 15, hopCount: 0, hops: [] }
      this.activeTab = 'diagram'
      if (this.mapInstance) {
        this.mapInstance.remove()
        this.mapInstance = null
      }

      const qs = new URLSearchParams()
      qs.set('lookupMode', this.lookupMode)
      if (!this.ptrLookup) qs.set('ptr', 'false')
      const url = `/api/trace-visual-stream/${encodeURIComponent(this.host)}${qs.toString() ? `?${qs.toString()}` : ''}`
      this.traceSource = new EventSource(url)

      this.traceSource.addEventListener('start', (ev) => {
        const msg = JSON.parse(ev.data)
        if (this.result) {
          if (msg.host) this.result.host = msg.host
          if (typeof msg.maxHops === 'number') this.result.maxHops = msg.maxHops
        }
      })

      this.traceSource.addEventListener('hop', (ev) => {
        this.upsertHop(JSON.parse(ev.data))
      })

      this.traceSource.addEventListener('done', (ev) => {
        this.result = JSON.parse(ev.data)
        this.loading = false
        this.traceComplete = true
        this.closeTraceSource()
      })

      this.traceSource.addEventListener('error', (ev) => {
        try {
          const msg = JSON.parse(ev.data)
          this.error = msg.message || 'Trace stream failed'
        } catch {
          this.error = 'Trace stream failed'
        }
        this.loading = false
        this.traceComplete = false
        this.closeTraceSource()
      })

      this.traceSource.onerror = () => {
        if (!this.loading) return
        this.error = 'Trace stream disconnected'
        this.loading = false
        this.traceComplete = false
        this.closeTraceSource()
      }
    },
    async showMap() {
      this.activeTab = 'map'
      if (this.mapInstance) return
      await this.$nextTick()
      await this.ensureLeaflet()
      if (!this.leafletReady || !this.result) return
      const el = document.getElementById('trace-map')
      if (!el) return
      const hops = this.result.hops.filter((h) => h.lat != null && h.lon != null)
      if (!hops.length) return
      const L = window.L
      const map = L.map(el, { scrollWheelZoom: true }).setView([hops[0].lat, hops[0].lon], 3)
      L.tileLayer('https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png', {
        attribution: '&copy; OSM &amp; CARTO', maxZoom: 19,
      }).addTo(map)
      this.mapInstance = map

      const bounds = []
      for (let i = 0; i < hops.length; i++) {
        const h = hops[i]
        const c = this.latencyColor(h.rttAvg)
        const icon = L.divIcon({
          className: '',
          html: `<div style="width:24px;height:24px;border-radius:50%;background:${c};border:2px solid #1a1a2e;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:700;color:#0f172a;box-shadow:0 1px 4px rgba(0,0,0,.3)">${h.hop}</div>`,
          iconSize: [24, 24], iconAnchor: [12, 12],
        })
        const marker = L.marker([h.lat, h.lon], { icon }).addTo(map)
        let popup = `<div style="font-size:12px;line-height:1.5"><b>Hop ${h.hop}</b><br>${h.host}`
        if (h.ip && h.ip !== '*') popup += ` (${h.ip})`
        if (h.rttAvg != null) popup += `<br><b style="color:${c}">${h.rttAvg.toFixed(1)} ms</b>`
        if (h.ptr) popup += `<br>PTR: ${h.ptr}`
        if (h.asn) popup += `<br>${h.asn} ${h.asName || ''}`
        if (this.lookupMode === 'asn-country' && h.asnCountry) popup += `<br>ASN Country: ${h.asnCountry}`
        if (this.lookupMode === 'geo' && (h.city || h.country)) popup += `<br>${h.city ? h.city + ', ' : ''}${h.country || ''}`
        popup += '</div>'
        marker.bindPopup(popup)
        bounds.push([h.lat, h.lon])

        if (i < hops.length - 1) {
          const next = hops[i + 1]
          L.polyline([[h.lat, h.lon], [next.lat, next.lon]], {
            color: this.latencyColor(next.rttAvg), weight: 3, opacity: 0.7,
          }).addTo(map)
        }
      }
      if (bounds.length > 1) map.fitBounds(bounds, { padding: [30, 30] })
    },
  },
})
</script>

<style>

.traceroute-page .tool-view { padding: 1rem 0; 
}
.traceroute-page h2 { margin-bottom: 0.25rem; 
}
.traceroute-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; 
}
.traceroute-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; 
}
.traceroute-page .input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; 
}
.traceroute-page .btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; 
}
.traceroute-page .btn:hover  { background: #2a2a4e; 
}
.traceroute-page .btn:disabled  { opacity: 0.6; 
}
.traceroute-page .error { color: #d32f2f; margin-bottom: 1rem; 
}
.traceroute-page .geo-toggle { display: flex; align-items: center; gap: 0.5rem; font-size: 0.85rem; color: #555; cursor: pointer; margin-bottom: 0.25rem; }
.traceroute-page .geo-toggle input { width: auto; accent-color: #1a1a2e; 
}
.traceroute-page .lookup-mode { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 0.35rem; flex-wrap: wrap; 
}
.traceroute-page .lookup-label { font-size: 0.8rem; color: #666; 
}
.traceroute-page .geo-hint { font-size: 0.7rem; color: #999; margin-bottom: 1rem; margin-left: 1.5rem; 
}
.traceroute-page .trace-results { background: white; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.1); 
}
.traceroute-page .tabs { display: flex; gap: 0; border-bottom: 2px solid #ddd; background: #f8f9fa; 
}
.traceroute-page .tab { padding: 10px 20px; cursor: pointer; font-size: 0.85rem; color: #888; border: none; background: none; border-bottom: 2px solid transparent; margin-bottom: -2px; }
.traceroute-page .tab.active { color: #1a1a2e; border-bottom-color: #1a1a2e; font-weight: 600; 
}
.traceroute-page .tab:hover:not(.active)  { color: #555; 
}
.traceroute-page .tab-panel { padding: 16px; 
}
.traceroute-page .diagram-panel { overflow-x: auto; 
}
.traceroute-page .hop-summary { font-size: 0.8rem; color: #888; margin-bottom: 12px; }
.traceroute-page .hop-summary strong { color: #1a1a2e; 
}
.traceroute-page .hop-list { display: flex; flex-direction: column; gap: 2px; 
}
.traceroute-page .asn-header { font-size: 0.7rem; color: #1a73e8; padding: 6px 0 2px; border-top: 1px solid #eee; margin-top: 4px; 
}
.traceroute-page .hop-row { display: flex; align-items: center; gap: 0; min-height: 40px; 
}
.traceroute-page .hop-node { width: 52px; display: flex; flex-direction: column; align-items: center; flex-shrink: 0; 
}
.traceroute-page .node-line { width: 2px; height: 4px; }
.traceroute-page .node-line.spacer { background: transparent; 
}
.traceroute-page .node-circle { width: 26px; height: 26px; border-radius: 50%; flex-shrink: 0; display: flex; align-items: center; justify-content: center; font-size: 0.7rem; font-weight: 700; color: #0f172a; line-height: 1; 
}
.traceroute-page .node-end { margin-top: 4px; font-size: 0.6rem; font-weight: 700; color: #555; letter-spacing: 0.02em; 
}
.traceroute-page .hop-bar { height: 6px; border-radius: 3px; flex-shrink: 0; margin-right: 8px; 
}
.traceroute-page .hop-info { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 0.8rem; min-width: 0; 
}
.traceroute-page .hop-host { color: #1a1a2e; font-weight: 500; 
}
.traceroute-page .hop-ip { color: #888; 
}
.traceroute-page .hop-rtt { font-weight: 600; 
}
.traceroute-page .hop-ptr { color: #4b5563; font-size: 0.75rem; 
}
.traceroute-page .hop-geo { color: #888; font-size: 0.75rem; display: inline-flex; align-items: center; gap: 0.25rem; 
}
.traceroute-page .hop-geo-icon { width: 0.85rem; height: 0.85rem; display: inline-flex; color: #6b7280; }
.traceroute-page .hop-geo-icon svg { width: 100%; height: 100%; }
.traceroute-page .hop-geo--pin .hop-geo-icon { color: #1f6feb; }
.traceroute-page .hop-geo--asn .hop-geo-icon { color: #8b5cf6; 
}
.traceroute-page .map-container { width: 100%; height: 480px; border-radius: 8px; border: 1px solid #e5e7eb; overflow: hidden; 
}
.traceroute-page .map-empty { margin-top: 0.8rem; color: #6b7280; font-size: 0.85rem; 
}
.traceroute-page .json-pre { margin: 0; }
</style>
