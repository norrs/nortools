<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import CliCopy from '../components/CliCopy.vue';
import { buildCli } from '../utils/cli';

type PingMode = 'fixed' | 'continuous';
type SampleStatus = 'reply' | 'timeout';

interface PingSample {
  seq: number;
  from: string | null;
  timeMs: number | null;
  status: SampleStatus;
  message: string;
}

interface PingSummary {
  host: string;
  packetsSent: number;
  packetsReceived: string | null;
  packetLoss: string;
  minRtt: string | null;
  avgRtt: string | null;
  maxRtt: string | null;
  status: string;
}

interface LiveStats {
  sent: number;
  replies: number;
  timeouts: number;
  packetLoss: string;
  minRtt: string | null;
  avgRtt: string | null;
  maxRtt: string | null;
}

const host = ref('');
const count = ref(4);
const mode = ref<PingMode>('fixed');
const timeoutSeconds = ref(5);
const samples = ref<PingSample[]>([]);
const result = ref<PingSummary | null>(null);
const error = ref('');
const running = ref(false);
const activeTab = ref<'live' | 'json'>('live');
let pingSource: EventSource | null = null;
const cliCommand = computed(() => buildCli([
  'nortools',
  'ping',
  '--json',
  '--count',
  String(mode.value === 'fixed' ? count.value : 4),
  host.value,
]));
const cliDisabled = computed(() => !host.value || mode.value === 'continuous');

const rttValues = computed(() => {
  return samples.value
    .filter((s) => s.status === 'reply' && s.timeMs != null)
    .map((s) => s.timeMs!);
});

const liveStats = computed<LiveStats>(() => {
  const sent = samples.value.length;
  const replies = samples.value.filter((s) => s.status === 'reply').length;
  const timeouts = sent - replies;
  const values = rttValues.value;
  const avg = values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
  const loss = sent > 0 ? ((timeouts * 100) / sent).toFixed(1) : '0.0';
  return {
    sent,
    replies,
    timeouts,
    packetLoss: `${loss}%`,
    minRtt: values.length ? `${Math.min(...values).toFixed(1)}ms` : null,
    avgRtt: avg != null ? `${avg.toFixed(1)}ms` : null,
    maxRtt: values.length ? `${Math.max(...values).toFixed(1)}ms` : null,
  };
});

const chartData = computed(() => {
  const windowed = samples.value.slice(-120);
  const width = 740;
  const height = 180;
  const margin = { top: 10, right: 16, bottom: 28, left: 44 };
  const plotWidth = width - margin.left - margin.right;
  const plotHeight = height - margin.top - margin.bottom;

  if (!windowed.length) {
    return {
      width,
      height,
      margin,
      probeDots: [] as Array<{ x: number; y: number; status: SampleStatus; seq: number; timeMs: number | null; from: string | null }>,
      linePolyline: '',
      yTicks: [] as Array<{ y: number; label: string }>,
      xTicks: [] as Array<{ x: number; label: string }>,
      baselineY: margin.top + plotHeight,
    };
  }

  const replies = windowed.filter((s) => s.timeMs != null).map((s) => s.timeMs!);
  const maxObserved = replies.length ? Math.max(...replies) : 10;
  const yMax = Math.max(10, Math.ceil(maxObserved / 5) * 5);
  const baselineY = margin.top + plotHeight;
  const xStep = windowed.length > 1 ? plotWidth / (windowed.length - 1) : 0;

  const probeDots = windowed.map((s, i) => {
    const x = margin.left + i * xStep;
    const y = s.timeMs == null
      ? baselineY
      : baselineY - (s.timeMs / yMax) * plotHeight;
    return { x, y, status: s.status, seq: s.seq, timeMs: s.timeMs, from: s.from };
  });

  const linePolyline = probeDots
    .filter((_, idx) => windowed[idx].timeMs != null)
    .map((p) => `${p.x},${p.y}`)
    .join(' ');

  const yTicks = Array.from({ length: 5 }, (_, i) => {
    const value = (yMax / 4) * i;
    const y = baselineY - (value / yMax) * plotHeight;
    return { y, label: `${Math.round(value)} ms` };
  });

  const firstSeq = windowed[0].seq;
  const midSeq = windowed[Math.floor((windowed.length - 1) / 2)].seq;
  const lastSeq = windowed[windowed.length - 1].seq;
  const xTicks = [
    { x: margin.left, label: `#${firstSeq}` },
    { x: margin.left + plotWidth / 2, label: `#${midSeq}` },
    { x: margin.left + plotWidth, label: `#${lastSeq}` },
  ];

  return {
    width,
    height,
    margin,
    probeDots,
    linePolyline,
    yTicks,
    xTicks,
    baselineY,
  };
});

function summaryFromLiveStats(): PingSummary {
  return {
    host: host.value,
    packetsSent: liveStats.value.sent,
    packetsReceived: String(liveStats.value.replies),
    packetLoss: liveStats.value.packetLoss,
    minRtt: liveStats.value.minRtt,
    avgRtt: liveStats.value.avgRtt,
    maxRtt: liveStats.value.maxRtt,
    status: liveStats.value.replies > 0 ? 'Reachable' : 'Unreachable',
  };
}

const jsonView = computed(() => {
  return {
    host: host.value,
    mode: mode.value,
    timeoutSeconds: timeoutSeconds.value,
    running: running.value,
    summary: result.value ?? (samples.value.length ? summaryFromLiveStats() : null),
    pongs: samples.value.map((s) => ({
      seq: s.seq,
      status: s.status,
      from: s.from,
      timeMs: s.timeMs,
      message: s.message,
    })),
  };
});

function parseSamplePayload(raw: unknown): PingSample {
  const obj = (raw && typeof raw === 'object') ? raw as Record<string, unknown> : {};
  const seqRaw = obj.seq;
  const fromRaw = obj.from;
  const statusRaw = obj.status;
  const timeMsRaw = obj.timeMs;
  const timeRaw = obj.time;

  let timeMs: number | null = null;
  if (typeof timeMsRaw === 'number' && Number.isFinite(timeMsRaw)) {
    timeMs = timeMsRaw;
  } else if (typeof timeRaw === 'string') {
    const parsed = Number.parseFloat(timeRaw.replace('ms', '').trim());
    if (Number.isFinite(parsed)) timeMs = parsed;
  }

  const status: SampleStatus =
    statusRaw === 'reply' || (timeMs != null)
      ? 'reply'
      : 'timeout';

  const seq =
    typeof seqRaw === 'number' && Number.isFinite(seqRaw)
      ? seqRaw
      : (samples.value.length + 1);

  const from = typeof fromRaw === 'string' ? fromRaw : null;

  return {
    seq,
    from,
    timeMs,
    status,
    message: typeof obj.message === 'string'
      ? obj.message
      : (status === 'reply' ? 'Reply received' : 'No response'),
  };
}

function stop(updateSummary: boolean = true) {
  if (updateSummary && running.value && samples.value.length) {
    result.value = summaryFromLiveStats();
  }
  if (pingSource) {
    pingSource.close();
    pingSource = null;
  }
  running.value = false;
}

function runPing() {
  if (!host.value) return;
  stop(false);
  activeTab.value = 'live';
  running.value = true;
  error.value = '';
  result.value = null;

  samples.value = [];
  const qs = new URLSearchParams();
  qs.set('timeout', String(timeoutSeconds.value));
  if (mode.value === 'fixed') {
    qs.set('count', String(count.value));
  } else {
    qs.set('continuous', 'true');
  }
  const url = `/api/ping-stream/${encodeURIComponent(host.value)}?${qs.toString()}`;
  pingSource = new EventSource(url);

  pingSource.addEventListener('sample', (ev) => {
    const payload = JSON.parse((ev as MessageEvent).data) as unknown;
    const sample = parseSamplePayload(payload);
    samples.value.push(sample);
    if (samples.value.length > 500) samples.value.shift();
  });

  pingSource.addEventListener('summary', (ev) => {
    result.value = JSON.parse((ev as MessageEvent).data) as PingSummary;
  });

  pingSource.addEventListener('done', (ev) => {
    result.value = JSON.parse((ev as MessageEvent).data) as PingSummary;
    stop(false);
  });

  pingSource.addEventListener('error', (ev) => {
    try {
      const payload = JSON.parse((ev as MessageEvent).data) as { message?: string };
      error.value = payload.message || 'Ping failed';
    } catch {
      if (running.value) error.value = 'Ping stream disconnected';
    }
    stop(false);
  });

  pingSource.onerror = () => {
    if (!running.value) return;
    error.value = 'Ping stream disconnected';
    stop(false);
  };
}

onBeforeUnmount(() => stop(false));
</script>

<template>
  <div class="tool-view">
    <h2>Ping (Streaming)</h2>
    <p class="desc">Run live ping probes with fixed count or continuous mode. Click Stop to end early.</p>
    <form @submit.prevent="runPing" class="lookup-form">
      <input v-model="host" placeholder="Host (e.g. google.com)" class="input" />
      <select v-model="mode" class="input input-mode">
        <option value="fixed">Fixed probes</option>
        <option value="continuous">Until stopped</option>
      </select>
      <input v-if="mode === 'fixed'" v-model.number="count" type="number" min="1" max="200" class="input input-sm" />
      <input v-model.number="timeoutSeconds" type="number" min="1" max="30" class="input input-sm" title="Timeout (seconds)" />
      <button type="submit" :disabled="running" class="btn">
        {{ running ? 'Pinging...' : 'Start Ping' }}
      </button>
      <button type="button" :disabled="!running" class="btn btn-stop" @click="stop">Stop</button>
    </form>
    <div class="hint">Timeout value is per probe. Unanswered probes are shown as "No response".</div>
    <div v-if="mode === 'continuous'" class="hint cli-hint">CLI copy is disabled in continuous mode; switch to fixed probes to generate a CLI command.</div>
    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="samples.length || result" class="tabs">
      <button :class="['tab', { active: activeTab === 'live' }]" @click="activeTab = 'live'">Live</button>
      <button :class="['tab', { active: activeTab === 'json' }]" @click="activeTab = 'json'">JSON</button>
    </div>

    <div v-if="activeTab === 'live' && samples.length" class="results">
      <div class="stats">
        <div class="stat"><span>Sent</span><strong>{{ liveStats.sent }}</strong></div>
        <div class="stat"><span>Replies</span><strong>{{ liveStats.replies }}</strong></div>
        <div class="stat"><span>No Response</span><strong>{{ liveStats.timeouts }}</strong></div>
        <div class="stat"><span>Loss</span><strong>{{ liveStats.packetLoss }}</strong></div>
        <div class="stat"><span>Min RTT</span><strong>{{ liveStats.minRtt || '-' }}</strong></div>
        <div class="stat"><span>Avg RTT</span><strong>{{ liveStats.avgRtt || '-' }}</strong></div>
        <div class="stat"><span>Max RTT</span><strong>{{ liveStats.maxRtt || '-' }}</strong></div>
      </div>

      <div class="chart-wrap">
        <div class="chart-title">RTT Trend (last {{ Math.min(samples.length, 120) }} probes)</div>
        <svg :viewBox="`0 0 ${chartData.width} ${chartData.height}`" class="chart">
          <line
            v-for="(t, idx) in chartData.yTicks"
            :key="`yg-${idx}`"
            :x1="chartData.margin.left"
            :x2="chartData.width - chartData.margin.right"
            :y1="t.y"
            :y2="t.y"
            class="chart-grid"
          />
          <line
            :x1="chartData.margin.left"
            :x2="chartData.margin.left"
            :y1="chartData.margin.top"
            :y2="chartData.baselineY"
            class="chart-axis"
          />
          <line
            :x1="chartData.margin.left"
            :x2="chartData.width - chartData.margin.right"
            :y1="chartData.baselineY"
            :y2="chartData.baselineY"
            class="chart-axis"
          />

          <text
            v-for="(t, idx) in chartData.yTicks"
            :key="`yl-${idx}`"
            :x="chartData.margin.left - 6"
            :y="t.y + 3"
            class="chart-label chart-label-y"
          >
            {{ t.label }}
          </text>
          <text
            v-for="(t, idx) in chartData.xTicks"
            :key="`xl-${idx}`"
            :x="t.x"
            :y="chartData.height - 8"
            class="chart-label chart-label-x"
          >
            {{ t.label }}
          </text>

          <polyline v-if="chartData.linePolyline" :points="chartData.linePolyline" class="chart-line" />
          <g
            v-for="(d, idx) in chartData.probeDots"
            :key="`d-${idx}`"
            class="chart-probe-hit"
          >
            <circle
              :cx="d.x"
              :cy="d.y"
              class="chart-probe-hitbox"
              r="9"
            />
            <circle
              :cx="d.x"
              :cy="d.y"
              :class="['chart-probe-dot', d.status === 'timeout' ? 'chart-probe-dot-timeout' : 'chart-probe-dot-reply']"
              r="3.2"
            />
            <title>
              Probe #{{ d.seq }} - {{ d.status === 'reply' ? `${d.timeMs?.toFixed(1)} ms` : 'No response' }}{{ d.from ? ` (${d.from})` : '' }}
            </title>
          </g>
        </svg>
      </div>

      <div class="sample-list">
        <div v-for="sample in samples" :key="sample.seq" class="sample-row" :class="sample.status === 'timeout' ? 'timeout' : 'reply'">
          <span class="seq">#{{ sample.seq }}</span>
          <span class="status">{{ sample.status === 'reply' ? 'Reply' : 'No response' }}</span>
          <span class="from">{{ sample.from || '-' }}</span>
          <span class="time">{{ sample.timeMs != null ? sample.timeMs.toFixed(1) + ' ms' : '-' }}</span>
        </div>
      </div>
    </div>

    <pre v-if="activeTab === 'json' && (samples.length || result)" class="result">{{ JSON.stringify(jsonView, null, 2) }}</pre>
    <CliCopy :command="cliCommand" :disabled="cliDisabled" />
  </div>
</template>

<style scoped>
.tool-view { padding: 1rem 0; }
h2 { margin-bottom: 0.25rem; }
.desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.lookup-form { display: flex; gap: 0.5rem; margin-bottom: 0.5rem; flex-wrap: wrap; }
.input { flex: 1; min-width: 180px; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; }
.input-sm { max-width: 80px; min-width: 60px; flex: 0; }
.input-mode { max-width: 150px; min-width: 130px; flex: 0; }
.btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; }
.btn:hover { background: #2a2a4e; }
.btn:disabled { opacity: 0.6; }
.btn-stop { background: #991b1b; }
.btn-stop:hover { background: #7f1d1d; }
.error { color: #d32f2f; margin-bottom: 1rem; }
.hint { color: #666; font-size: 0.8rem; margin-bottom: 1rem; }
.cli-hint { margin-top: -0.6rem; }
.tabs { display: flex; gap: 0; border-bottom: 2px solid #ddd; background: #f8f9fa; margin-bottom: 0.6rem; border-radius: 6px 6px 0 0; overflow: hidden; }
.tab { padding: 8px 14px; cursor: pointer; font-size: 0.8rem; color: #6b7280; border: none; background: none; border-bottom: 2px solid transparent; margin-bottom: -2px; }
.tab.active { color: #111827; border-bottom-color: #111827; font-weight: 600; }
.tab:hover:not(.active) { color: #374151; }

.results { background: white; border-radius: 8px; border: 1px solid #eee; margin-bottom: 1rem; overflow: hidden; }
.stats { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 0; border-bottom: 1px solid #eee; }
.stat { padding: 0.6rem 0.8rem; }
.stat span { display: block; color: #666; font-size: 0.72rem; margin-bottom: 0.2rem; }
.stat strong { color: #1a1a2e; font-size: 0.9rem; }
.sample-list { max-height: 340px; overflow-y: auto; }
.chart-wrap { padding: 0.6rem 0.8rem 0.2rem; border-bottom: 1px solid #eee; }
.chart-title { font-size: 0.72rem; color: #666; margin-bottom: 0.35rem; }
.chart { width: 100%; height: 170px; display: block; }
.chart-grid { stroke: #eceff3; stroke-width: 1; }
.chart-axis { stroke: #9ca3af; stroke-width: 1.2; }
.chart-label { fill: #6b7280; font-size: 9px; user-select: none; }
.chart-label-y { text-anchor: end; dominant-baseline: middle; }
.chart-label-x { text-anchor: middle; dominant-baseline: middle; }
.chart-line { fill: none; stroke: #1f6feb; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.chart-probe-dot { stroke: #ffffff; stroke-width: 0.9; }
.chart-probe-dot-reply { fill: #111827; }
.chart-probe-dot-timeout { fill: #b91c1c; }
.chart-probe-hit { cursor: crosshair; }
.chart-probe-hitbox { fill: transparent; pointer-events: all; }
.chart-probe-hit:hover .chart-probe-dot { stroke: #f59e0b; stroke-width: 1.4; }
.sample-row { display: grid; grid-template-columns: 52px 110px 1fr 90px; gap: 0.5rem; padding: 0.45rem 0.8rem; font-size: 0.82rem; border-top: 1px solid #f4f4f4; }
.sample-row.reply .status { color: #0f766e; }
.sample-row.timeout .status { color: #b91c1c; }
.seq, .time { color: #555; }
.from { color: #1a1a2e; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; }

@media (max-width: 1100px) {
  .stats { grid-template-columns: repeat(4, minmax(0, 1fr)); }
}

@media (max-width: 680px) {
  .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
