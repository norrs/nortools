<template id="ping-page">
  <div class="tool-view ping-page">
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
      <button type="submit" :disabled="running" class="btn">{{ running ? 'Pinging...' : 'Start Ping' }}</button>
      <button type="button" :disabled="!running" class="btn btn-stop" @click="stop">Stop</button>
    </form>
    <div class="hint">Timeout value is per probe. Unanswered probes are shown as "No response".</div>
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
          <line :x1="chartData.margin.left" :x2="chartData.margin.left" :y1="chartData.margin.top" :y2="chartData.baselineY" class="chart-axis" />
          <line :x1="chartData.margin.left" :x2="chartData.width - chartData.margin.right" :y1="chartData.baselineY" :y2="chartData.baselineY" class="chart-axis" />
          <text v-for="(t, idx) in chartData.yTicks" :key="`yl-${idx}`" :x="chartData.margin.left - 6" :y="t.y + 3" class="chart-label chart-label-y">{{ t.label }}</text>
          <text v-for="(t, idx) in chartData.xTicks" :key="`xl-${idx}`" :x="t.x" :y="chartData.height - 8" class="chart-label chart-label-x">{{ t.label }}</text>
          <polyline v-if="chartData.linePolyline" :points="chartData.linePolyline" class="chart-line" />
          <g v-for="(d, idx) in chartData.probeDots" :key="`d-${idx}`">
            <circle :cx="d.x" :cy="d.y" class="chart-probe-hitbox" r="9" />
            <circle :cx="d.x" :cy="d.y" :class="['chart-probe-dot', d.status === 'timeout' ? 'chart-probe-dot-timeout' : 'chart-probe-dot-reply']" r="3.2" />
            <title>Probe #{{ d.seq }} - {{ d.status === 'reply' ? `${d.timeMs?.toFixed(1)} ms` : 'No response' }}{{ d.from ? ` (${d.from})` : '' }}</title>
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

    <div class="cli-copy">
      <div class="cli-label">CLI (JSON)</div>
      <div class="cli-row">
        <code class="cli-command">{{ cliCommand }}</code>
        <button class="cli-btn" :disabled="cliDisabled" @click="copyCli(cliCommand, cliDisabled)">
          {{ copied ? 'Copied' : 'Copy CLI Command' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script>
app.component("ping-page", {
  template: "#ping-page",
  data() {
    return {
      host: "",
      count: 4,
      mode: "fixed",
      timeoutSeconds: 5,
      samples: [],
      result: null,
      error: "",
      running: false,
      activeTab: "live",
      pingSource: null,
      viewportWidth: (typeof window !== "undefined" ? window.innerWidth : 1600),
      handleResize: null,
      copied: false,
    }
  },
  computed: {
    cliCommand() {
      return `nortools ping --json --count ${this.mode === 'fixed' ? this.count : 4} ${this.host || '<host>'}`
    },
    cliDisabled() {
      return !this.host || this.mode === 'continuous'
    },
    liveStats() {
      const sent = this.samples.length
      const replies = this.samples.filter((s) => s.status === 'reply').length
      const timeouts = sent - replies
      const values = this.samples.filter((s) => s.status === 'reply' && s.timeMs != null).map((s) => s.timeMs)
      const avg = values.length ? values.reduce((a, b) => a + b, 0) / values.length : null
      const loss = sent > 0 ? ((timeouts * 100) / sent).toFixed(1) : '0.0'
      return {
        sent,
        replies,
        timeouts,
        packetLoss: `${loss}%`,
        minRtt: values.length ? `${Math.min(...values).toFixed(1)}ms` : null,
        avgRtt: avg != null ? `${avg.toFixed(1)}ms` : null,
        maxRtt: values.length ? `${Math.max(...values).toFixed(1)}ms` : null,
      }
    },
    chartData() {
      const windowed = this.samples.slice(-120)
      const width = Math.max(740, this.viewportWidth - 200)
      const height = 180
      const margin = { top: 10, right: 16, bottom: 28, left: 44 }
      const plotWidth = width - margin.left - margin.right
      const plotHeight = height - margin.top - margin.bottom
      if (!windowed.length) {
        return { width, height, margin, probeDots: [], linePolyline: '', yTicks: [], xTicks: [], baselineY: margin.top + plotHeight }
      }
      const replies = windowed.filter((s) => s.timeMs != null).map((s) => s.timeMs)
      const maxObserved = replies.length ? Math.max(...replies) : 10
      const yMax = Math.max(10, Math.ceil(maxObserved / 5) * 5)
      const baselineY = margin.top + plotHeight
      const xStep = windowed.length > 1 ? plotWidth / (windowed.length - 1) : 0
      const probeDots = windowed.map((s, i) => {
        const x = margin.left + i * xStep
        const y = s.timeMs == null ? baselineY : baselineY - (s.timeMs / yMax) * plotHeight
        return { x, y, status: s.status, seq: s.seq, timeMs: s.timeMs, from: s.from }
      })
      const linePolyline = probeDots.filter((_, idx) => windowed[idx].timeMs != null).map((p) => `${p.x},${p.y}`).join(' ')
      const yTicks = Array.from({ length: 5 }, (_, i) => {
        const value = (yMax / 4) * i
        const y = baselineY - (value / yMax) * plotHeight
        return { y, label: `${Math.round(value)} ms` }
      })
      const firstSeq = windowed[0].seq
      const midSeq = windowed[Math.floor((windowed.length - 1) / 2)].seq
      const lastSeq = windowed[windowed.length - 1].seq
      const xTicks = [
        { x: margin.left, label: `#${firstSeq}` },
        { x: margin.left + plotWidth / 2, label: `#${midSeq}` },
        { x: margin.left + plotWidth, label: `#${lastSeq}` },
      ]
      return { width, height, margin, probeDots, linePolyline, yTicks, xTicks, baselineY }
    },
    jsonView() {
      return {
        host: this.host,
        mode: this.mode,
        timeoutSeconds: this.timeoutSeconds,
        running: this.running,
        summary: this.result,
        pongs: this.samples.map((s) => ({
          seq: s.seq,
          status: s.status,
          from: s.from,
          timeMs: s.timeMs,
          message: s.message,
        })),
      }
    },
  },
  beforeUnmount() {
    this.stop(false)
    if (this.handleResize) {
      window.removeEventListener('resize', this.handleResize)
      this.handleResize = null
    }
  },
  mounted() {
    this.handleResize = () => {
      this.viewportWidth = window.innerWidth
    }
    window.addEventListener('resize', this.handleResize)
  },
  methods: {
    async copyCli(command, disabled) {
      if (disabled) return
      try {
        await navigator.clipboard.writeText(command)
        this.copied = true
        setTimeout(() => { this.copied = false }, 1500)
      } catch {
        this.copied = false
      }
    },
    parseSamplePayload(raw) {
      const obj = (raw && typeof raw === 'object') ? raw : {}
      const seqRaw = obj.seq
      const fromRaw = obj.from
      const statusRaw = obj.status
      const timeMsRaw = obj.timeMs
      const timeRaw = obj.time
      let timeMs = null
      if (typeof timeMsRaw === 'number' && Number.isFinite(timeMsRaw)) {
        timeMs = timeMsRaw
      } else if (typeof timeRaw === 'string') {
        const parsed = Number.parseFloat(timeRaw.replace('ms', '').trim())
        if (Number.isFinite(parsed)) timeMs = parsed
      }
      const status = statusRaw === 'reply' || (timeMs != null) ? 'reply' : 'timeout'
      const seq = typeof seqRaw === 'number' && Number.isFinite(seqRaw) ? seqRaw : (this.samples.length + 1)
      const from = typeof fromRaw === 'string' ? fromRaw : null
      return {
        seq,
        from,
        timeMs,
        status,
        message: typeof obj.message === 'string' ? obj.message : (status === 'reply' ? 'Reply received' : 'No response'),
      }
    },
    stop(updateSummary = true) {
      if (updateSummary && this.running && this.samples.length) {
        this.result = {
          host: this.host,
          packetsSent: this.liveStats.sent,
          packetsReceived: String(this.liveStats.replies),
          packetLoss: this.liveStats.packetLoss,
          minRtt: this.liveStats.minRtt,
          avgRtt: this.liveStats.avgRtt,
          maxRtt: this.liveStats.maxRtt,
          status: this.liveStats.replies > 0 ? 'Reachable' : 'Unreachable',
        }
      }
      if (this.pingSource) {
        this.pingSource.close()
        this.pingSource = null
      }
      this.running = false
    },
    runPing() {
      if (!this.host) return
      this.stop(false)
      this.activeTab = 'live'
      this.running = true
      this.error = ''
      this.result = null
      this.samples = []
      const qs = new URLSearchParams()
      qs.set('timeout', String(this.timeoutSeconds))
      if (this.mode === 'fixed') {
        qs.set('count', String(this.count))
      } else {
        qs.set('continuous', 'true')
      }
      const url = `/api/ping-stream/${encodeURIComponent(this.host)}?${qs.toString()}`
      this.pingSource = new EventSource(url)

      this.pingSource.addEventListener('sample', (ev) => {
        const payload = JSON.parse(ev.data)
        this.samples.push(this.parseSamplePayload(payload))
        if (this.samples.length > 500) this.samples.shift()
      })

      this.pingSource.addEventListener('summary', (ev) => {
        this.result = JSON.parse(ev.data)
      })

      this.pingSource.addEventListener('done', (ev) => {
        this.result = JSON.parse(ev.data)
        this.stop(false)
      })

      this.pingSource.addEventListener('error', (ev) => {
        try {
          const payload = JSON.parse(ev.data)
          this.error = payload.message || 'Ping failed'
        } catch {
          if (this.running) this.error = 'Ping stream disconnected'
        }
        this.stop(false)
      })

      this.pingSource.onerror = () => {
        if (!this.running) return
        this.error = 'Ping stream disconnected'
        this.stop(false)
      }
    },
  },
})
</script>

<style>
.ping-page { padding: 1rem 0; }
.ping-page h2 { margin-bottom: 0.25rem; }
.ping-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.ping-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 0.5rem; flex-wrap: wrap; }
.ping-page .input { flex: 1; min-width: 180px; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; }
.ping-page .input-sm { max-width: 80px; min-width: 60px; flex: 0; }
.ping-page .input-mode { max-width: 150px; min-width: 130px; flex: 0; }
.ping-page .btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; }
.ping-page .btn:hover { background: #2a2a4e; }
.ping-page .btn:disabled { opacity: 0.6; }
.ping-page .btn-stop { background: #991b1b; }
.ping-page .btn-stop:hover { background: #7f1d1d; }
.ping-page .error { color: #d32f2f; margin-bottom: 1rem; }
.ping-page .hint { color: #666; font-size: 0.8rem; margin-bottom: 1rem; }
.ping-page .tabs { display: flex; gap: 0; border-bottom: 2px solid #ddd; background: #f8f9fa; margin-bottom: 0.6rem; border-radius: 6px 6px 0 0; overflow: hidden; }
.ping-page .tab { padding: 8px 14px; cursor: pointer; font-size: 0.8rem; color: #6b7280; border: none; background: none; border-bottom: 2px solid transparent; margin-bottom: -2px; }
.ping-page .tab.active { color: #111827; border-bottom-color: #111827; font-weight: 600; }
.ping-page .results { background: white; border-radius: 8px; border: 1px solid #eee; margin-bottom: 1rem; overflow: hidden; }
.ping-page .stats { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 0; border-bottom: 1px solid #eee; }
.ping-page .stat { padding: 0.6rem 0.8rem; }
.ping-page .stat span { display: block; color: #666; font-size: 0.72rem; margin-bottom: 0.2rem; }
.ping-page .stat strong { color: #1a1a2e; font-size: 0.9rem; }
.ping-page .chart-wrap { padding: 0.6rem 0.8rem 0.2rem; border-bottom: 1px solid #eee; }
.ping-page .chart-title { font-size: 0.72rem; color: #666; margin-bottom: 0.35rem; }
.ping-page .chart { width: 100%; height: 170px; display: block; }
.ping-page .chart-grid { stroke: #eceff3; stroke-width: 1; }
.ping-page .chart-axis { stroke: #9ca3af; stroke-width: 1.2; }
.ping-page .chart-label { fill: #6b7280; font-size: 9px; user-select: none; }
.ping-page .chart-label-y { text-anchor: end; dominant-baseline: middle; }
.ping-page .chart-label-x { text-anchor: middle; dominant-baseline: middle; }
.ping-page .chart-line { fill: none; stroke: #1f6feb; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.ping-page .chart-probe-dot { stroke: #ffffff; stroke-width: 0.9; }
.ping-page .chart-probe-dot-reply { fill: #111827; }
.ping-page .chart-probe-dot-timeout { fill: #b91c1c; }
.ping-page .chart-probe-hitbox { fill: transparent; pointer-events: all; }
.ping-page .sample-list { max-height: 340px; overflow-y: auto; }
.ping-page .sample-row { display: grid; grid-template-columns: 52px 110px 1fr 90px; gap: 0.5rem; padding: 0.45rem 0.8rem; font-size: 0.82rem; border-top: 1px solid #f4f4f4; }
.ping-page .sample-row.reply .status { color: #0f766e; }
.ping-page .sample-row.timeout .status { color: #b91c1c; }
.ping-page .seq,
.ping-page .time { color: #555; }
.ping-page .from { color: #1a1a2e; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ping-page .result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; }
.ping-page .cli-copy { margin-top: 1.25rem; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 0.75rem 1rem; width: 100%; }
.ping-page .cli-label { font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; margin-bottom: 0.5rem; }
.ping-page .cli-row { display: flex; gap: 0.75rem; align-items: center; flex-wrap: wrap; }
.ping-page .cli-command { background: #0f172a; color: #e2e8f0; padding: 0.5rem 0.75rem; border-radius: 6px; font-size: 0.85rem; overflow-x: auto; flex: 1; min-width: 200px; }
.ping-page .cli-btn { padding: 0.45rem 0.9rem; background: #1a1a2e; color: white; border: none; border-radius: 6px; cursor: pointer; font-size: 0.85rem; white-space: nowrap; }
.ping-page .cli-btn:hover { background: #2a2a4e; }
.ping-page .cli-btn:disabled { opacity: 0.6; cursor: not-allowed; }
@media (max-width: 1100px) {
  .ping-page .stats { grid-template-columns: repeat(4, minmax(0, 1fr)); }
}
@media (max-width: 680px) {
  .ping-page .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
