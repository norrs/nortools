<template id="network-interfaces-page">
  <div class="tool-view network-interfaces-page">
    <h2>Interfaces & Routing</h2>
    <p class="desc">Local host identity, per-interface addressing, DHCP/DNS details, and routing table.</p>

    <div class="actions">
      <button @click="load" :disabled="loading">{{ loading ? "Refreshing..." : "Refresh" }}</button>
    </div>

    <div v-if="loading">Loading local network inventory...</div>
    <div v-else-if="error" class="error">{{ error }}</div>

    <div v-else-if="data" class="grid">
      <section class="card">
        <h3>Host</h3>
        <div class="kv"><span>Hostname</span><code>{{ data.host.hostname || 'unknown' }}</code></div>
        <div class="kv"><span>NetBIOS</span><code>{{ data.host.netbios || 'n/a' }}</code></div>
        <div class="kv"><span>Platform</span><code>{{ data.host.platform || 'unknown' }}</code></div>
        <div class="kv"><span>OS</span><code>{{ data.host.osName }} {{ data.host.osVersion }}</code></div>
        <div class="kv"><span>Arch</span><code>{{ data.host.osArch }}</code></div>
        <div class="kv"><span>Captured</span><code>{{ data.generatedAt }}</code></div>
      </section>

      <section class="card">
        <h3>IP & DNS</h3>
        <div class="kv">
          <span>Default DNS</span>
          <code>{{ hasItems(data.defaultDnsServers) ? data.defaultDnsServers.join(', ') : 'unknown' }}</code>
        </div>
        <div v-if="hasItems(addressedInterfaceSummaries)" class="routes-table-wrap list-block">
          <table class="routes-table">
            <thead>
              <tr>
                <th>Interface</th>
                <th>DNS</th>
                <th>IPv4</th>
                <th>IPv6</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="summary in addressedInterfaceSummaries" :key="summary.name" :class="[summary.isDefault ? 'route-default' : '']">
                <td>
                  <code>{{ summary.name || 'n/a' }}</code>
                  <span v-if="summary.isDefault" class="default-badge">Default</span>
                </td>
                <td><code>{{ hasItems(summary.dnsServers) ? summary.dnsServers.join(', ') : 'n/a' }}</code></td>
                <td>
                  <code v-if="hasItems(summary.ipv4)">
                    <span v-for="(addr, idx) in summary.ipv4" :key="addrKey(summary, addr, idx)">
                      <a :href="'#' + interfaceAnchorId(summary)">{{ addr.ip }}{{ formatPrefix(addr.prefixLength) }}</a><span v-if="idx < summary.ipv4.length - 1">, </span>
                    </span>
                  </code>
                  <code v-else>none</code>
                </td>
                <td>
                  <code v-if="hasItems(summary.ipv6)">
                    <span v-for="(addr, idx) in summary.ipv6" :key="addrKey(summary, addr, idx)">
                      <a :href="'#' + interfaceAnchorId(summary)">{{ addr.ip }}{{ formatPrefix(addr.prefixLength) }}</a><span v-if="idx < summary.ipv6.length - 1">, </span>
                    </span>
                  </code>
                  <code v-else>none</code>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="muted list-block">No interfaces with IP addresses found.</div>
      </section>

      <section class="card">
        <h3>Routes ({{ hasItems(data.routes) ? data.routes.length : 0 }})</h3>
        <div v-if="hasItems(data.routes)" class="routes-table-wrap">
          <table class="routes-table">
            <thead>
              <tr>
                <th>Destination</th>
                <th>Gateway</th>
                <th>Interface</th>
                <th>Metric</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(route, idx) in data.routes"
                :key="routeKey(route, idx)"
                :class="[
                  routeIsDefault(route) ? 'route-default' : '',
                  routeUsesDownInterface(route) ? 'route-down' : '',
                ]"
              >
                <td><code>{{ route.destination || 'n/a' }}</code></td>
                <td><code>{{ route.gateway || 'n/a' }}</code></td>
                <td>
                  <code>{{ route.interfaceName || 'n/a' }}</code>
                  <span v-if="routeIsDefault(route)" class="default-badge">Default</span>
                  <span v-if="routeUsesDownInterface(route)" class="down-badge">Interface down</span>
                </td>
                <td><code>{{ formatMetric(route.metric) }}</code></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="muted">No parsed route rows available.</div>
        <details v-if="data.routingTableRaw" class="raw-block">
          <summary>Raw routing table output</summary>
          <pre>{{ data.routingTableRaw }}</pre>
        </details>
      </section>

      <section class="card">
        <div class="interfaces-head">
          <h3>Interfaces ({{ hasItems(visibleInterfaces) ? visibleInterfaces.length : 0 }})</h3>
          <label class="toggle">
            <input type="checkbox" v-model="showDisabledInterfaces" />
            <span>Show disabled interfaces</span>
          </label>
        </div>
        <div v-if="!hasItems(visibleInterfaces)" class="muted">No interfaces reported.</div>
        <div v-else class="interface-list">
          <div
            v-for="iface in visibleInterfaces"
            :key="iface.name"
            :id="interfaceAnchorId(iface)"
            :class="['iface', isDefaultInterface(iface) ? 'iface-default' : '', iface.up ? '' : 'iface-down']"
          >
            <div class="iface-head">
              <strong>{{ iface.name }}</strong>
              <span class="muted">{{ iface.displayName }}</span>
              <span v-if="isDefaultInterface(iface)" class="default-badge">Default route</span>
              <span v-if="!iface.up" class="down-badge">Down</span>
            </div>
            <div class="kv"><span>State</span><code>{{ iface.up ? 'up' : 'down' }}</code></div>
            <div class="kv"><span>Type</span><code>{{ iface.loopback ? 'loopback' : (iface.virtual ? 'virtual' : 'physical') }}</code></div>
            <div class="kv"><span>MAC</span><code>{{ iface.macAddress || 'n/a' }}</code></div>
            <div class="kv"><span>MTU</span><code>{{ formatMtu(iface.mtu) }}</code></div>
            <div class="kv"><span>DHCP</span><code>{{ iface.dhcp || 'unknown' }}</code></div>
            <div class="kv"><span>DNS</span><code>{{ hasItems(iface.dnsServers) ? iface.dnsServers.join(', ') : 'n/a' }}</code></div>
            <div class="kv">
              <span>IPv4 Addresses</span>
              <code v-if="hasItems(addressesByFamily(iface, 'IPv4'))">
                <span v-for="(addr, idx) in addressesByFamily(iface, 'IPv4')" :key="addrKey(iface, addr, idx)">
                  {{ addr.ip }}{{ formatPrefix(addr.prefixLength) }}<span v-if="idx < addressesByFamily(iface, 'IPv4').length - 1">, </span>
                </span>
              </code>
              <code v-else>none</code>
            </div>
            <div class="kv">
              <span>IPv6 Addresses</span>
              <code v-if="hasItems(addressesByFamily(iface, 'IPv6'))">
                <span v-for="(addr, idx) in addressesByFamily(iface, 'IPv6')" :key="addrKey(iface, addr, idx)">
                  {{ addr.ip }}{{ formatPrefix(addr.prefixLength) }}<span v-if="idx < addressesByFamily(iface, 'IPv6').length - 1">, </span>
                </span>
              </code>
              <code v-else>none</code>
            </div>
          </div>
        </div>
      </section>

      <section v-if="hasItems(data.notes)" class="card">
        <h3>Notes</h3>
        <ul>
          <li v-for="(note, idx) in data.notes" :key="`note-${idx}`">{{ note }}</li>
        </ul>
      </section>
    </div>
  </div>
</template>

<script>
app.component("network-interfaces-page", {
  template: "#network-interfaces-page",
  data() {
    return {
      loading: false,
      error: "",
      data: null,
      showDisabledInterfaces: false,
    }
  },
  computed: {
    defaultRouteInterfaceNames() {
      const names = []
      const routes = (this.data && Array.isArray(this.data.routes)) ? this.data.routes : []
      for (const route of routes) {
        if (!route) continue
        const destination = String(route.destination || "").toLowerCase()
        const iface = String(route.interfaceName || "").trim()
        if (!iface) continue
        if (destination === "default" || destination === "0.0.0.0" || destination === "::/0") {
          if (names.indexOf(iface) < 0) names.push(iface)
        }
      }
      return names
    },
    sortedInterfaces() {
      const interfaces = (this.data && Array.isArray(this.data.interfaces)) ? this.data.interfaces.slice() : []
      interfaces.sort((a, b) => {
        const aDefault = this.isDefaultInterface(a) ? 0 : 1
        const bDefault = this.isDefaultInterface(b) ? 0 : 1
        if (aDefault !== bDefault) return aDefault - bDefault
        return String(a && a.name ? a.name : "").localeCompare(String(b && b.name ? b.name : ""))
      })
      return interfaces
    },
    visibleInterfaces() {
      if (this.showDisabledInterfaces) return this.sortedInterfaces
      return this.sortedInterfaces.filter((iface) => Boolean(iface && iface.up))
    },
    addressedInterfaceSummaries() {
      const interfaces = (this.data && Array.isArray(this.data.interfaces)) ? this.data.interfaces : []
      const rows = []
      for (const iface of interfaces) {
        if (!iface) continue
        if (!iface.up) continue
        const ipv4 = this.addressesByFamily(iface, 'IPv4')
        const ipv6 = this.addressesByFamily(iface, 'IPv6')
        if (!ipv4.length && !ipv6.length) continue
        const dnsServers = Array.isArray(iface.dnsServers) ? iface.dnsServers : []
        rows.push({
          name: String(iface.name || ''),
          displayName: String(iface.displayName || ''),
          up: Boolean(iface.up),
          isDefault: this.isDefaultInterface(iface),
          dnsServers,
          ipv4,
          ipv6,
        })
      }
      rows.sort((a, b) => {
        const defaultOrder = (a.isDefault ? 0 : 1) - (b.isDefault ? 0 : 1)
        if (defaultOrder !== 0) return defaultOrder
        return String(a.name).localeCompare(String(b.name))
      })
      return rows
    },
  },
  mounted() {
    this.load()
  },
  methods: {
    hasItems(value) {
      return Array.isArray(value) && value.length > 0
    },
    routeKey(route, idx) {
      return String(idx) + "-" + String(route.destination || "") + "-" + String(route.gateway || "")
    },
    interfaceAnchorId(iface) {
      const name = String(iface && iface.name ? iface.name : "").trim()
      const displayName = String(iface && iface.displayName ? iface.displayName : "").trim()
      const raw = name || displayName || "iface"
      const safe = raw.toLowerCase().replace(/[^a-z0-9_-]+/g, "-").replace(/^-+|-+$/g, "")
      return "iface-" + (safe || "unknown")
    },
    isDefaultInterface(iface) {
      if (!iface) return false
      const name = String(iface.name || "").trim()
      const displayName = String(iface.displayName || "").trim()
      return this.defaultRouteInterfaceNames.indexOf(name) >= 0 || this.defaultRouteInterfaceNames.indexOf(displayName) >= 0
    },
    routeIsDefault(route) {
      if (!route) return false
      const destination = String(route.destination || "").toLowerCase()
      return destination === "default" || destination === "0.0.0.0" || destination === "::/0"
    },
    routeUsesDownInterface(route) {
      if (!route) return false
      const routeIface = String(route.interfaceName || "").trim()
      if (!routeIface) return false
      const interfaces = (this.data && Array.isArray(this.data.interfaces)) ? this.data.interfaces : []
      for (const iface of interfaces) {
        const name = String(iface && iface.name ? iface.name : "").trim()
        const displayName = String(iface && iface.displayName ? iface.displayName : "").trim()
        if (routeIface === name || routeIface === displayName) {
          return !Boolean(iface.up)
        }
      }
      return false
    },
    addrKey(iface, addr, idx) {
      return String(iface && iface.name ? iface.name : "") + "-" + String(addr && addr.ip ? addr.ip : "") + "-" + String(idx)
    },
    addressesByFamily(iface, family) {
      const addresses = (iface && Array.isArray(iface.addresses)) ? iface.addresses : []
      return addresses.filter((addr) => String(addr && addr.family ? addr.family : "") === family)
    },
    formatMtu(value) {
      return value == null ? 'n/a' : String(value)
    },
    formatMetric(value) {
      return value == null ? "n/a" : String(value)
    },
    formatPrefix(value) {
      return value == null ? "" : "/" + String(value)
    },
    async load() {
      this.loading = true
      this.error = ""
      try {
        const res = await fetch("/api/network-interfaces")
        if (!res.ok) throw new Error(`API error: ${res.status} ${res.statusText}`)
        this.data = await res.json()
      } catch (e) {
        this.error = e instanceof Error ? e.message : "Failed to load network interface info"
      } finally {
        this.loading = false
      }
    },
  },
})
</script>

<style>
.network-interfaces-page { padding: 1rem 0; }
.network-interfaces-page .desc { color: #666; font-size: 0.92rem; margin-bottom: 0.8rem; }
.network-interfaces-page .actions { margin-bottom: 0.85rem; }
.network-interfaces-page button { border: 1px solid #0ea5e9; background: #0ea5e9; color: #fff; border-radius: 6px; padding: 0.42rem 0.7rem; cursor: pointer; }
.network-interfaces-page a { color: #0369a1; text-decoration: underline; text-underline-offset: 1px; }
.network-interfaces-page .error { color: #d32f2f; }
.network-interfaces-page .grid { display: grid; gap: 1rem; }
.network-interfaces-page .card { background: #fff; border-radius: 8px; padding: 1rem; box-shadow: 0 1px 2px rgba(0,0,0,0.06); }
.network-interfaces-page .kv { display: grid; grid-template-columns: 160px 1fr; gap: 0.75rem; margin: 0.3rem 0; }
.network-interfaces-page .kv span { color: #64748b; }
.network-interfaces-page code { background: #f8fafc; border-radius: 4px; padding: 0.08rem 0.3rem; }
.network-interfaces-page .list-block { margin-top: 0.4rem; }
.network-interfaces-page .muted { color: #64748b; }
.network-interfaces-page .interfaces-head { display: flex; justify-content: space-between; gap: 1rem; align-items: center; flex-wrap: wrap; margin-bottom: 0.4rem; }
.network-interfaces-page .interfaces-head h3 { margin: 0; }
.network-interfaces-page .toggle { display: inline-flex; align-items: center; gap: 0.4rem; color: #334155; font-size: 0.9rem; }
.network-interfaces-page .interface-list { display: grid; gap: 0.8rem; }
.network-interfaces-page .iface { border: 1px solid #e2e8f0; border-radius: 8px; padding: 0.7rem; }
.network-interfaces-page .iface.iface-default { border-color: #0ea5e9; background: #f0f9ff; }
.network-interfaces-page .iface.iface-down { border-color: #ef4444; background: #fff5f5; }
.network-interfaces-page .iface-head { margin-bottom: 0.35rem; display: flex; gap: 0.45rem; align-items: baseline; flex-wrap: wrap; }
.network-interfaces-page .default-badge { background: #0ea5e9; color: #fff; font-size: 0.74rem; border-radius: 999px; padding: 0.08rem 0.45rem; }
.network-interfaces-page .down-badge { background: #ef4444; color: #fff; font-size: 0.74rem; border-radius: 999px; padding: 0.08rem 0.45rem; }
.network-interfaces-page .routes-table-wrap { overflow-x: auto; }
.network-interfaces-page .routes-table { width: 100%; border-collapse: collapse; font-size: 0.87rem; }
.network-interfaces-page .routes-table th,
.network-interfaces-page .routes-table td { border: 1px solid #cbd5e1; padding: 0.42rem 0.5rem; text-align: left; vertical-align: top; }
.network-interfaces-page .routes-table th { background: #e2e8f0; color: #1e293b; }
.network-interfaces-page .routes-table tr.route-default td { background: #f0f9ff; }
.network-interfaces-page .routes-table tr.route-down td { background: #fff5f5; }
.network-interfaces-page .raw-block { margin-top: 0.7rem; }
.network-interfaces-page .raw-block pre { background: #0f172a; color: #e2e8f0; border-radius: 6px; padding: 0.75rem; overflow-x: auto; white-space: pre-wrap; }
</style>
