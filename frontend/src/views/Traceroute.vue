<script setup lang="ts">
import { ref, nextTick, onBeforeUnmount } from 'vue';
import { traceroute } from '../api/client';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';

interface Hop {
  hop: number; host: string; ip: string;
  rttRaw: string; rttAvg: number | null;
  asn?: string; asName?: string; prefix?: string; asnCountry?: string;
  lat?: number; lon?: number;
  city?: string; region?: string; country?: string; countryCode?: string;
  isp?: string; org?: string;
}
interface TraceResult { host: string; maxHops: number; hopCount: number; hops: Hop[]; error?: string; }

const host = ref('');
const geo = ref(false);
const result = ref<TraceResult | null>(null);
const error = ref('');
const loading = ref(false);
const activeTab = ref<'diagram' | 'map' | 'json'>('diagram');
let mapInstance: L.Map | null = null;

function latencyColor(ms: number | null): string {
  if (ms == null) return '#64748b';
  if (ms < 30) return '#34d399';
  if (ms < 80) return '#a3e635';
  if (ms < 150) return '#fbbf24';
  if (ms < 300) return '#f97316';
  return '#ef4444';
}

const hasGeo = () => (result.value?.hops || []).some(h => h.lat != null && h.lon != null);

async function trace() {
  if (!host.value) return;
  loading.value = true;
  error.value = '';
  result.value = null;
  activeTab.value = 'diagram';
  if (mapInstance) { mapInstance.remove(); mapInstance = null; }
  try {
    const data = await traceroute(host.value, geo.value) as TraceResult;
    if (data.error) { error.value = data.error; return; }
    result.value = data;
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'An error occurred';
  } finally {
    loading.value = false;
  }
}

async function showMap() {
  activeTab.value = 'map';
  if (mapInstance) return;
  await nextTick();
  const el = document.getElementById('trace-map');
  if (!el || !result.value) return;
  const hops = result.value.hops.filter(h => h.lat != null && h.lon != null);
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
    if (h.asn) popup += `<br>${h.asn} ${h.asName || ''}`;
    if (h.city || h.country) popup += `<br>📍 ${h.city ? h.city + ', ' : ''}${h.country || ''}`;
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

onBeforeUnmount(() => { if (mapInstance) { mapInstance.remove(); mapInstance = null; } });
</script>

<template>
  <div class="tool-view">
    <h2>Traceroute (Visual)</h2>
    <p class="desc">Trace the network path with ASN info and hop diagram. Optionally enable geolocation for world map view.</p>
    <form @submit.prevent="trace" class="lookup-form">
      <input v-model="host" placeholder="Enter host (e.g. google.com)" class="input" />
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Tracing...' : 'Trace Route' }}
      </button>
    </form>
    <label class="geo-toggle">
      <input type="checkbox" v-model="geo" />
      Enable geolocation lookup (world map)
    </label>
    <div class="geo-hint">Uses <a href="https://ip-api.com" target="_blank">ip-api.com</a> free API — rate-limited to 45 req/min. Enables the 🗺️ World Map tab.</div>

    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="trace-results">
      <!-- Tabs -->
      <div class="tabs">
        <button :class="['tab', { active: activeTab === 'diagram' }]" @click="activeTab = 'diagram'">📊 Hop Diagram</button>
        <button v-if="hasGeo()" :class="['tab', { active: activeTab === 'map' }]" @click="showMap()">🗺️ World Map</button>
        <button :class="['tab', { active: activeTab === 'json' }]" @click="activeTab = 'json'">📋 JSON</button>
      </div>

      <!-- Hop Diagram -->
      <div v-show="activeTab === 'diagram'" class="tab-panel diagram-panel">
        <div class="hop-summary">{{ result.hopCount }} hops to <strong>{{ result.host }}</strong></div>
        <div class="hop-list">
          <template v-for="(hop, idx) in result.hops" :key="hop.hop">
            <div v-if="hop.asn && (idx === 0 || hop.asn !== result.hops[idx - 1]?.asn)" class="asn-header">
              {{ hop.asn }} {{ hop.asName || '' }}
            </div>
            <div class="hop-row">
              <div class="hop-node">
                <div class="node-line" :style="{ background: latencyColor(hop.rttAvg) }"></div>
                <div class="node-circle" :style="{ background: latencyColor(hop.rttAvg) }">{{ hop.hop }}</div>
                <div class="node-line" :style="{ background: latencyColor(hop.rttAvg) }"></div>
              </div>
              <div class="hop-bar" :style="{ width: (hop.rttAvg != null ? Math.min(Math.max(hop.rttAvg / 5, 4), 200) : 0) + 'px', background: latencyColor(hop.rttAvg) }"></div>
              <div class="hop-info">
                <span class="hop-host">{{ hop.host === '*' ? '* * *' : hop.host }}</span>
                <span v-if="hop.ip && hop.ip !== '*'" class="hop-ip">({{ hop.ip }})</span>
                <span v-if="hop.rttAvg != null" class="hop-rtt" :style="{ color: latencyColor(hop.rttAvg) }">{{ hop.rttAvg.toFixed(1) }} ms</span>
                <span v-if="hop.city || hop.country" class="hop-geo">📍 {{ hop.city ? hop.city + ', ' : '' }}{{ hop.country || '' }}</span>
              </div>
            </div>
          </template>
        </div>
        <!-- Legend -->
        <div class="legend">
          <span><span class="dot" style="background:#34d399"></span> &lt;30ms</span>
          <span><span class="dot" style="background:#a3e635"></span> &lt;80ms</span>
          <span><span class="dot" style="background:#fbbf24"></span> &lt;150ms</span>
          <span><span class="dot" style="background:#f97316"></span> &lt;300ms</span>
          <span><span class="dot" style="background:#ef4444"></span> &gt;300ms</span>
          <span><span class="dot" style="background:#64748b"></span> No data</span>
        </div>
      </div>

      <!-- World Map -->
      <div v-show="activeTab === 'map'" class="tab-panel map-panel">
        <div id="trace-map" class="map-container"></div>
      </div>

      <!-- JSON -->
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
.lookup-form { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; }
.input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; }
.btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; }
.btn:hover { background: #2a2a4e; }
.btn:disabled { opacity: 0.6; }
.error { color: #d32f2f; margin-bottom: 1rem; }

.geo-toggle { display: flex; align-items: center; gap: 0.5rem; font-size: 0.85rem; color: #555; cursor: pointer; margin-bottom: 0.25rem; }
.geo-toggle input { width: auto; accent-color: #1a1a2e; }
.geo-hint { font-size: 0.7rem; color: #999; margin-bottom: 1rem; margin-left: 1.5rem; }
.geo-hint a { color: #999; }

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
.node-circle { width: 26px; height: 26px; border-radius: 50%; flex-shrink: 0; display: flex; align-items: center; justify-content: center; font-size: 0.7rem; font-weight: 700; color: #0f172a; line-height: 1; }

.hop-bar { height: 6px; border-radius: 3px; flex-shrink: 0; margin-right: 8px; }

.hop-info { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 0.8rem; min-width: 0; }
.hop-host { color: #1a1a2e; font-weight: 500; }
.hop-ip { color: #888; }
.hop-rtt { font-weight: 600; }
.hop-geo { color: #888; font-size: 0.75rem; }

.legend { display: flex; gap: 12px; margin-top: 16px; font-size: 0.7rem; color: #888; flex-wrap: wrap; }
.dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; vertical-align: middle; }

.map-panel { padding: 0; }
.map-container { height: 450px; width: 100%; }

.json-pre { font-family: monospace; font-size: 0.8rem; white-space: pre-wrap; word-break: break-all; line-height: 1.5; max-height: 500px; overflow: auto; }
</style>