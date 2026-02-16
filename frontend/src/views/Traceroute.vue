<script setup lang="ts">
import { computed, ref, nextTick, onBeforeUnmount } from 'vue';
import CliCopy from '../components/CliCopy.vue';
import { buildCli } from '../utils/cli';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';

interface Hop {
  hop: number; host: string; ip: string;
  rttRaw: string; rttAvg: number | null;
  ptr?: string;
  asn?: string; asName?: string; prefix?: string; asnCountry?: string;
  lat?: number; lon?: number;
  city?: string; region?: string; country?: string; countryCode?: string;
  isp?: string; org?: string;
}
interface TraceResult { host: string; maxHops: number; hopCount: number; hops: Hop[]; error?: string; }
type LookupMode = 'geo' | 'asn-country';

const host = ref('');
const lookupMode = ref<LookupMode>('geo');
const ptrLookup = ref(true);
const result = ref<TraceResult | null>(null);
const error = ref('');
const loading = ref(false);
const traceComplete = ref(false);
const activeTab = ref<'diagram' | 'map' | 'json'>('diagram');
let mapInstance: L.Map | null = null;
let traceSource: EventSource | null = null;
const cliCommand = computed(() => buildCli(['nortools', 'trace', '--json', host.value]));
const cliDisabled = computed(() => !host.value);

function latencyColor(ms: number | null): string {
  if (ms == null) return '#64748b';
  if (ms < 30) return '#34d399';
  if (ms < 80) return '#a3e635';
  if (ms < 150) return '#fbbf24';
  if (ms < 300) return '#f97316';
  return '#ef4444';
}

function closeTraceSource() {
  if (traceSource) {
    traceSource.close();
    traceSource = null;
  }
}

function upsertHop(hop: Hop) {
  if (!result.value) return;
  const idx = result.value.hops.findIndex((h) => h.hop === hop.hop);
  if (idx >= 0) {
    result.value.hops[idx] = hop;
  } else {
    result.value.hops.push(hop);
    result.value.hops.sort((a, b) => a.hop - b.hop);
  }
  result.value.hopCount = result.value.hops.length;
}

async function trace() {
  if (!host.value) return;
  closeTraceSource();
  loading.value = true;
  traceComplete.value = false;
  error.value = '';
  result.value = { host: host.value, maxHops: 15, hopCount: 0, hops: [] };
  activeTab.value = 'diagram';
  if (mapInstance) { mapInstance.remove(); mapInstance = null; }

  const qs = new URLSearchParams();
  qs.set('lookupMode', lookupMode.value);
  if (!ptrLookup.value) qs.set('ptr', 'false');
  const url = `/api/trace-visual-stream/${encodeURIComponent(host.value)}${qs.toString() ? `?${qs.toString()}` : ''}`;
  traceSource = new EventSource(url);

  traceSource.addEventListener('start', (ev) => {
    const msg = JSON.parse((ev as MessageEvent).data) as { host?: string; maxHops?: number };
    if (result.value) {
      if (msg.host) result.value.host = msg.host;
      if (typeof msg.maxHops === 'number') result.value.maxHops = msg.maxHops;
    }
  });

  traceSource.addEventListener('hop', (ev) => {
    const hop = JSON.parse((ev as MessageEvent).data) as Hop;
    upsertHop(hop);
  });

  traceSource.addEventListener('done', (ev) => {
    const data = JSON.parse((ev as MessageEvent).data) as TraceResult;
    result.value = data;
    loading.value = false;
    traceComplete.value = true;
    closeTraceSource();
  });

  traceSource.addEventListener('error', (ev) => {
    const msg = JSON.parse((ev as MessageEvent).data) as { message?: string };
    error.value = msg.message || 'Trace stream failed';
    loading.value = false;
    traceComplete.value = false;
    closeTraceSource();
  });

  traceSource.onerror = () => {
    if (!loading.value) return;
    error.value = 'Trace stream disconnected';
    loading.value = false;
    traceComplete.value = false;
    closeTraceSource();
  };
}

async function showMap() {
  activeTab.value = 'map';
  if (mapInstance) return;
  await nextTick();
  const el = document.getElementById('trace-map');
  if (!el || !result.value) return;
  const hops = result.value.hops.filter((h) => h.lat != null && h.lon != null);
  if (!hops.length) return;

  const map = L.map(el, { scrollWheelZoom: true }).setView([hops[0].lat!, hops[0].lon!], 3);
  L.tileLayer('https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png', {
    attribution: '&copy; OSM &amp; CARTO', maxZoom: 19,
  }).addTo(map);
  mapInstance = map;

  const bounds: L.LatLngTuple[] = [];
  for (let i = 0; i < hops.length; i++) {
    const h = hops[i];
    const c = latencyColor(h.rttAvg);
    const icon = L.divIcon({
      className: '',
      html: `<div style="width:24px;height:24px;border-radius:50%;background:${c};border:2px solid #1a1a2e;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:700;color:#0f172a;box-shadow:0 1px 4px rgba(0,0,0,.3)">${h.hop}</div>`,
      iconSize: [24, 24], iconAnchor: [12, 12],
    });
    const marker = L.marker([h.lat!, h.lon!], { icon }).addTo(map);
    let popup = `<div style="font-size:12px;line-height:1.5"><b>Hop ${h.hop}</b><br>${h.host}`;
    if (h.ip && h.ip !== '*') popup += ` (${h.ip})`;
    if (h.rttAvg != null) popup += `<br><b style="color:${c}">${h.rttAvg.toFixed(1)} ms</b>`;
    if (h.ptr) popup += `<br>PTR: ${h.ptr}`;
    if (h.asn) popup += `<br>${h.asn} ${h.asName || ''}`;
    if (lookupMode.value === 'asn-country' && h.asnCountry) popup += `<br>ASN Country: ${h.asnCountry}`;
    if (lookupMode.value === 'geo' && (h.city || h.country)) popup += `<br>${h.city ? h.city + ', ' : ''}${h.country || ''}`;
    popup += '</div>';
    marker.bindPopup(popup);
    bounds.push([h.lat!, h.lon!]);

    if (i < hops.length - 1) {
      const next = hops[i + 1];
      L.polyline([[h.lat!, h.lon!], [next.lat!, next.lon!]], {
        color: latencyColor(next.rttAvg), weight: 3, opacity: 0.7,
      }).addTo(map);
    }
  }

  if (bounds.length > 1) map.fitBounds(bounds, { padding: [30, 30] });
}

onBeforeUnmount(() => {
  closeTraceSource();
  if (mapInstance) { mapInstance.remove(); mapInstance = null; }
});
</script>

<template>
  <div class="tool-view">
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
    <CliCopy :command="cliCommand" :disabled="cliDisabled" />
  </div>
</template>

<style scoped>
.tool-view { padding: 1rem 0; }
h2 { margin-bottom: 0.25rem; }
.desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.lookup-form { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; }
.input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; }
.btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; }
.btn:hover { background: #2a2a4e; }
.btn:disabled { opacity: 0.6; }
.error { color: #d32f2f; margin-bottom: 1rem; }

.geo-toggle { display: flex; align-items: center; gap: 0.5rem; font-size: 0.85rem; color: #555; cursor: pointer; margin-bottom: 0.25rem; }
.geo-toggle input { width: auto; accent-color: #1a1a2e; }
.lookup-mode { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 0.35rem; flex-wrap: wrap; }
.lookup-label { font-size: 0.8rem; color: #666; }
.geo-hint { font-size: 0.7rem; color: #999; margin-bottom: 1rem; margin-left: 1.5rem; }

.trace-results { background: white; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }

.tabs { display: flex; gap: 0; border-bottom: 2px solid #ddd; background: #f8f9fa; }
.tab { padding: 10px 20px; cursor: pointer; font-size: 0.85rem; color: #888; border: none; background: none; border-bottom: 2px solid transparent; margin-bottom: -2px; }
.tab.active { color: #1a1a2e; border-bottom-color: #1a1a2e; font-weight: 600; }
.tab:hover:not(.active) { color: #555; }

.tab-panel { padding: 16px; }
.diagram-panel { overflow-x: auto; }

.hop-summary { font-size: 0.8rem; color: #888; margin-bottom: 12px; }
.hop-summary strong { color: #1a1a2e; }

.hop-list { display: flex; flex-direction: column; gap: 2px; }

.asn-header { font-size: 0.7rem; color: #1a73e8; padding: 6px 0 2px; border-top: 1px solid #eee; margin-top: 4px; }

.hop-row { display: flex; align-items: center; gap: 0; min-height: 40px; }

.hop-node { width: 52px; display: flex; flex-direction: column; align-items: center; flex-shrink: 0; }
.node-line { width: 2px; height: 4px; }
.node-line.spacer { background: transparent; }
.node-circle { width: 26px; height: 26px; border-radius: 50%; flex-shrink: 0; display: flex; align-items: center; justify-content: center; font-size: 0.7rem; font-weight: 700; color: #0f172a; line-height: 1; }
.node-end { margin-top: 4px; font-size: 0.6rem; font-weight: 700; color: #555; letter-spacing: 0.02em; }

.hop-bar { height: 6px; border-radius: 3px; flex-shrink: 0; margin-right: 8px; }

.hop-info { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 0.8rem; min-width: 0; }
.hop-host { color: #1a1a2e; font-weight: 500; }
.hop-ip { color: #888; }
.hop-rtt { font-weight: 600; }
.hop-ptr { color: #4b5563; font-size: 0.75rem; }
.hop-geo { color: #888; font-size: 0.75rem; display: inline-flex; align-items: center; gap: 0.25rem; }
.hop-geo-icon { width: 0.85rem; height: 0.85rem; display: inline-flex; color: #6b7280; }
.hop-geo-icon svg { width: 100%; height: 100%; }
.hop-geo--pin .hop-geo-icon { color: #1f6feb; }
.hop-geo--asn .hop-geo-icon { color: #8b5cf6; }

.map-panel { padding: 0; }
.map-container { height: 450px; width: 100%; }
.map-empty { padding: 0.75rem 1rem; font-size: 0.8rem; color: #666; }

.json-pre { font-family: monospace; font-size: 0.8rem; white-space: pre-wrap; word-break: break-all; line-height: 1.5; max-height: 500px; overflow: auto; }
</style>
