<template id="zeroconf-discovery-page">
  <div class="tool-view zeroconf-page">
    <div class="page-head">
      <div>
        <h2>ZeroConf Discovery</h2>
        <p class="desc">Live local discovery inventory from mDNS, LLMNR, NetBIOS, SSDP, and WS-Discovery.</p>
      </div>
      <div class="head-actions">
        <label class="toggle-chip">
          <input v-model="autoDescriptions" type="checkbox" />
          <span>Auto-fetch SSDP descriptions</span>
        </label>
        <label class="toggle-chip">
          <input v-model="smbIntrospection" type="checkbox" />
          <span>SMB introspection</span>
        </label>
        <button class="btn btn-secondary" @click="loadSnapshot" :disabled="loading">Refresh View</button>
        <button class="btn" @click="forceRefresh" :disabled="refreshing">{{ refreshing ? 'Scanning...' : 'Scan Now' }}</button>
      </div>
    </div>
    <section class="summary-grid">
      <div class="summary-card"><span>Devices</span><strong>{{ snapshot?.deviceCount || 0 }}</strong></div>
      <div class="summary-card"><span>Services</span><strong>{{ snapshot?.serviceCount || 0 }}</strong></div>
      <div class="summary-card"><span>Hostnames</span><strong>{{ hostnames.length }}</strong></div>
      <div class="summary-card"><span>Status</span><strong :class="snapshot?.scanning ? 'warn' : 'ok'">{{ snapshot?.scanning ? 'Scanning' : 'Listening' }}</strong></div>
    </section>
    <section class="protocol-strip">
      <div v-for="stat in protocolStats" :key="stat.protocol" class="protocol-pill" :class="stat.status">
        <strong>{{ stat.protocol }}</strong>
        <span>{{ stat.observations }} total / {{ stat.lastObservations || 0 }} last scan</span>
      </div>
    </section>
    <div v-if="error" class="callout bad-callout">{{ error }}</div>
    <div v-for="warning in visibleWarnings" :key="warning" class="callout">{{ warning }}</div>
    <section class="resolution-map">
      <div class="panel-title">
        <h3>DNS-SD Resolution Map</h3>
        <span>PTR -> SRV -> A/AAAA -> TXT</span>
      </div>
      <div class="resolution-flow">
        <div class="flow-step">
          <strong>Browse</strong>
          <span>_services._dns-sd._udp.local</span>
        </div>
        <div class="flow-arrow">></div>
        <div class="flow-step">
          <strong>Service Type</strong>
          <span>_ipp._tcp.local</span>
        </div>
        <div class="flow-arrow">></div>
        <div class="flow-step">
          <strong>Instance</strong>
          <span>Office Printer._ipp._tcp.local</span>
        </div>
        <div class="flow-arrow">></div>
        <div class="flow-step">
          <strong>Endpoint</strong>
          <span>printer.local:631</span>
        </div>
        <div class="flow-arrow">></div>
        <div class="flow-step">
          <strong>Capabilities</strong>
          <span>TXT keys like ty, pdl, URF</span>
        </div>
      </div>
    </section>
    <section class="explainer-grid">
      <div class="explainer-panel">
        <div class="panel-title">
          <h3>Common Services</h3>
          <span>{{ serviceGuide.length }} types</span>
        </div>
        <div v-if="serviceGuide.length" class="service-type-list">
          <div v-for="service in serviceGuide.slice(0, 12)" :key="service.protocol + service.type" class="service-type-row" :class="{ observed: service.observed > 0 }">
            <div>
              <strong>{{ service.title }}</strong>
              <code>{{ service.type }}</code>
            </div>
            <p>{{ service.description }}</p>
            <span>{{ service.observed > 0 ? `${service.observed} seen` : 'reference' }}</span>
          </div>
        </div>
        <div v-else class="empty-state">Service explanations appear as DNS-SD, SSDP, and WSD types are observed.</div>
      </div>
      <div class="explainer-panel">
        <div class="panel-title">
          <h3>Hostname Resolution</h3>
          <span>{{ hostnames.length }} names</span>
        </div>
        <table v-if="hostnames.length" class="mini-table">
          <thead><tr><th>Name</th><th>Addresses</th><th>Source</th></tr></thead>
          <tbody>
            <tr v-for="host in hostnames.slice(0, 12)" :key="host.hostname">
              <td>{{ host.hostname }}</td>
              <td>{{ host.addresses.join(', ') || '-' }}</td>
              <td>{{ host.protocols.join(', ') }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-state">A/AAAA name bindings will appear here.</div>
      </div>
    </section>
    <section class="filter-band">
      <input v-model.trim="filter" class="input search-input" placeholder="Filter devices, services, addresses, protocols" />
      <select v-model="categoryFilter" class="input select-input">
        <option value="">All categories</option>
        <option v-for="category in categories" :key="category" :value="category">{{ category }}</option>
      </select>
      <select v-model="protocolFilter" class="input select-input">
        <option value="">All protocols</option>
        <option v-for="protocol in protocols" :key="protocol" :value="protocol">{{ protocol }}</option>
      </select>
    </section>
    <section class="inventory-layout">
      <div class="device-list">
        <button
          v-for="device in filteredDevices"
          :key="device.id"
          class="device-row"
          :class="{ active: selectedDevice?.id === device.id }"
          @click="selectedId = device.id"
        >
          <div class="device-main">
            <strong>{{ deviceLabel(device) }}</strong>
            <span>{{ device.category }}</span>
          </div>
          <div class="device-meta">
            <span>{{ device.protocols.join(', ') }}</span>
            <span>{{ device.addresses[0] || device.locations[0] || 'No address yet' }}</span>
          </div>
          <div v-if="deviceServiceLines(device).length" class="device-services">
            <span v-for="line in deviceServiceLines(device)" :key="line">{{ line }}</span>
          </div>
          <div class="confidence" :class="device.confidence">{{ device.confidence }}</div>
        </button>
        <div v-if="!filteredDevices.length" class="empty-state">No devices discovered yet. Leave the page open or run Scan Now.</div>
      </div>
      <aside class="detail-panel">
        <template v-if="selectedDevice">
          <div class="detail-head">
            <h3>{{ deviceLabel(selectedDevice) }}</h3>
            <span>{{ selectedDevice.category }}</span>
          </div>
          <div class="tag-row">
            <span v-for="protocol in selectedDevice.protocols" :key="protocol" class="tag">{{ protocol }}</span>
          </div>
          <div class="detail-section">
            <h4>Addresses</h4>
            <p v-if="!selectedDevice.addresses.length" class="muted">No address evidence yet.</p>
            <code v-for="address in selectedDevice.addresses" :key="address">{{ address }}</code>
          </div>
          <div class="detail-section">
            <h4>Hostnames</h4>
            <p v-if="!selectedDevice.hostnames?.length" class="muted">No hostname binding observed.</p>
            <code v-for="hostname in selectedDevice.hostnames || []" :key="hostname">{{ hostname }}</code>
          </div>
          <div class="detail-section">
            <h4>Resolution Path</h4>
            <div v-if="selectedResolutionSteps.length" class="resolution-flow compact">
              <template v-for="(step, idx) in selectedResolutionSteps" :key="step.label">
                <div class="flow-step">
                  <strong>{{ step.label }}</strong>
                  <span>{{ step.value }}</span>
                </div>
                <div v-if="idx < selectedResolutionSteps.length - 1" class="flow-arrow">></div>
              </template>
            </div>
            <p v-else class="muted">No decoded service chain yet.</p>
          </div>
          <div class="detail-section">
            <h4>Services</h4>
            <table v-if="selectedDevice.services.length" class="mini-table">
              <thead><tr><th>Protocol</th><th>Type</th><th>Name</th><th>Target</th><th>Port</th><th>Action</th></tr></thead>
              <tbody>
                <tr v-for="(service, idx) in selectedDevice.services" :key="idx">
                  <td>{{ service.protocol }}</td>
                  <td>{{ service.type }}</td>
                  <td>{{ service.name }}</td>
                  <td>{{ service.target || service.location }}</td>
                  <td>{{ service.port || '' }}</td>
                  <td>
                    <a
                      v-if="serviceActionHref(service)"
                      :href="serviceActionHref(service)"
                      target="_blank"
                      rel="noopener noreferrer"
                      @click.prevent="openExternalUrl(serviceActionHref(service))"
                    >Open</a>
                    <span v-else class="muted">-</span>
                  </td>
                </tr>
              </tbody>
            </table>
            <p v-else class="muted">No decoded services yet.</p>
          </div>

          <div class="detail-section">
            <div class="section-head">
              <h4>Host Inspection</h4>
              <button class="link-button" @click="loadSelectedDetail(true)" :disabled="detailLoading || !selectedDeviceId">
                {{ detailLoading ? 'Inspecting...' : 'Refresh host details' }}
              </button>
            </div>
            <p v-if="detailError" class="muted">{{ detailError }}</p>
            <p v-else-if="detailLoading && !selectedDetail" class="muted">Inspecting advertised services and reachable endpoints.</p>
            <template v-else-if="selectedDetail">
              <div v-for="warning in selectedDetail.warnings || []" :key="warning" class="callout">{{ warning }}</div>

              <div class="inspection-grid">
                <div class="inspection-panel">
                  <div class="panel-title">
                    <h4>Web Interfaces</h4>
                    <span>{{ (selectedDetail.webLinks || []).length }}</span>
                  </div>
                  <div v-if="selectedDetail.webLinks?.length" class="inspection-list">
                    <div v-for="link in selectedDetail.webLinks" :key="link.url" class="inspection-row">
                      <div>
                        <strong>{{ link.label }}</strong>
                        <a :href="link.url" target="_blank" rel="noopener noreferrer" @click.prevent="openExternalUrl(link.url)">{{ link.url }}</a>
                      </div>
                      <div class="inspection-meta">
                        <span>{{ link.source }}</span>
                        <span v-if="link.httpStatus">HTTP {{ link.httpStatus }}</span>
                        <span v-if="link.server">{{ link.server }}</span>
                        <span v-if="link.error">{{ link.error }}</span>
                      </div>
                    </div>
                  </div>
                  <p v-else class="muted">No HTTP or HTTPS interfaces inferred from discovery data.</p>
                </div>

                <div class="inspection-panel">
                  <div class="panel-title">
                    <h4>Reachable Ports</h4>
                    <span>{{ (selectedDetail.portChecks || []).length }}</span>
                  </div>
                  <table v-if="selectedDetail.portChecks?.length" class="mini-table">
                    <thead><tr><th>Host</th><th>Port</th><th>Service</th><th>Status</th><th>Info</th></tr></thead>
                    <tbody>
                      <tr v-for="probe in selectedDetail.portChecks" :key="`${probe.host}:${probe.port}`">
                        <td>{{ probe.host }}</td>
                        <td>{{ probe.port }}</td>
                        <td>{{ probe.label }}</td>
                        <td>{{ probe.connected ? 'Open' : 'Closed' }}</td>
                        <td>{{ probe.banner || probe.error || `${probe.responseTimeMs} ms` }}</td>
                      </tr>
                    </tbody>
                  </table>
                  <p v-else class="muted">No follow-up ports selected for this host.</p>
                </div>
              </div>

              <div v-if="selectedDetail.netbios" class="inspection-panel">
                <div class="panel-title">
                  <h4>NetBIOS Node Status</h4>
                  <span>{{ selectedDetail.netbios.host }}</span>
                </div>
                <table v-if="selectedDetail.netbios.names?.length" class="mini-table">
                  <thead><tr><th>Name</th><th>Suffix</th><th>Group</th><th>Flags</th></tr></thead>
                  <tbody>
                    <tr v-for="entry in selectedDetail.netbios.names" :key="`${entry.name}:${entry.suffix}:${entry.flags}`">
                      <td>{{ entry.name }}</td>
                      <td>{{ formatHex(entry.suffix) }}</td>
                      <td>{{ entry.group ? 'yes' : 'no' }}</td>
                      <td>{{ formatHex(entry.flags) }}</td>
                    </tr>
                  </tbody>
                </table>
                <p v-else class="muted">No NetBIOS node status names returned.</p>
                <p v-if="selectedDetail.netbios.addresses?.length" class="muted">Addresses: {{ selectedDetail.netbios.addresses.join(', ') }}</p>
                <p v-if="selectedDetail.netbios.errors?.length" class="muted">{{ selectedDetail.netbios.errors.join(' | ') }}</p>
              </div>

              <div v-if="selectedDetail.smb || smbIntrospection" class="inspection-panel">
                <div class="panel-title">
                  <h4>SMB Introspection</h4>
                  <span>{{ selectedDevice.addresses?.[0] || selectedDevice.hostnames?.[0] || '-' }}</span>
                </div>
                <p v-if="!smbIntrospection" class="muted">Enable the SMB introspection toggle to attempt anonymous or guest SMB share enumeration.</p>
                <template v-else-if="selectedDetail.smb">
                  <div class="inspection-meta">
                    <span v-if="selectedDetail.smb.dialect">{{ selectedDetail.smb.dialect }}</span>
                    <span v-if="selectedDetail.smb.authenticationStatus">{{ selectedDetail.smb.authenticationStatus }}</span>
                    <span v-if="selectedDetail.smb.signingRequired">signing required</span>
                    <span v-else-if="selectedDetail.smb.signingEnabled">signing enabled</span>
                    <span v-if="selectedDetail.smb.encryptionSupported">encryption supported</span>
                  </div>
                  <table v-if="selectedDetail.smb.shares?.length" class="mini-table">
                    <thead><tr><th>Name</th><th>Type</th><th>Comment</th></tr></thead>
                    <tbody>
                      <tr v-for="share in selectedDetail.smb.shares" :key="`${share.name}:${share.type}`">
                        <td>{{ share.name }}</td>
                        <td>{{ share.type }}</td>
                        <td>{{ share.comment }}</td>
                      </tr>
                    </tbody>
                  </table>
                  <p v-else class="muted">{{ selectedDetail.smb.note || selectedDetail.smb.error || 'No SMB shares were returned.' }}</p>
                </template>
              </div>
            </template>
          </div>

          <div class="detail-section">
            <h4>Service Meaning</h4>
            <p v-for="(service, idx) in selectedDevice.services.filter(s => s.description)" :key="idx" class="meaning-row">
              <strong>{{ service.type }}</strong>
              <span>{{ service.description }}</span>
            </p>
            <p v-if="!selectedDevice.services.some(s => s.description)" class="muted">No service explanation available yet.</p>
          </div>

          <div class="detail-section">
            <h4>DNS Records</h4>
            <table v-if="selectedDevice.dnsRecords?.length" class="mini-table">
              <thead><tr><th>Name</th><th>Type</th><th>Meaning</th><th>Value</th><th>TTL</th></tr></thead>
              <tbody>
                <tr v-for="(record, idx) in selectedDevice.dnsRecords" :key="idx">
                  <td>{{ record.hostname }}</td>
                  <td>{{ record.type }}</td>
                  <td>{{ recordTypeHint(record.type) }}</td>
                  <td>{{ record.value }}</td>
                  <td>{{ record.ttl }}</td>
                </tr>
              </tbody>
            </table>
            <p v-else class="muted">No A/AAAA/SRV records attached to this device yet.</p>
          </div>

          <div class="detail-section">
            <h4>TXT Metadata</h4>
            <table v-if="selectedDevice.txtRecords?.length" class="mini-table">
              <thead><tr><th>Service</th><th>Key</th><th>Meaning</th><th>Value</th></tr></thead>
              <tbody>
                <tr v-for="(record, idx) in selectedDevice.txtRecords" :key="idx">
                  <td>{{ record.service }}</td>
                  <td>{{ record.key }}</td>
                  <td>{{ txtHint(record.key) }}</td>
                  <td>{{ record.value }}</td>
                </tr>
              </tbody>
            </table>
            <p v-else class="muted">No TXT metadata decoded for this device.</p>
          </div>

          <div class="detail-section">
            <h4>Locations</h4>
            <div v-for="location in selectedDevice.locations" :key="location" class="location-row">
              <a
                v-if="isExternalUrl(location)"
                :href="location"
                target="_blank"
                rel="noopener noreferrer"
                @click.prevent="openExternalUrl(location)"
              >{{ location }}</a>
              <span v-else>{{ location }}</span>
              <button v-if="isDescriptionUrl(location)" class="link-button" @click="loadDescription(location)" :disabled="descriptionLoadingUrl === location">
                {{ descriptionLoadingUrl === location ? 'Inspecting...' : 'Inspect description' }}
              </button>
            </div>
            <p v-if="!selectedDevice.locations.length" class="muted">No metadata URL observed.</p>
            <p v-if="descriptionError" class="muted">{{ descriptionError }}</p>
            <div v-for="location in selectedDevice.locations" :key="`${location}-description`">
              <div v-if="descriptions[location]?.description" class="description-card">
                <div class="kv" v-for="row in descriptionRows(descriptions[location].description)" :key="row.label">
                  <span>{{ row.label }}</span>
                  <strong v-if="!isExternalUrl(row.value)">{{ row.value }}</strong>
                  <a
                    v-else
                    :href="row.value"
                    target="_blank"
                    rel="noopener noreferrer"
                    @click.prevent="openExternalUrl(row.value)"
                  >{{ row.value }}</a>
                </div>
                <div v-if="descriptions[location].description.services?.length" class="description-services">
                  <strong>Services</strong>
                  <code v-for="service in descriptions[location].description.services" :key="service.serviceId || service.serviceType">
                    {{ service.serviceType || service.serviceId }}
                  </code>
                </div>
              </div>
            </div>
          </div>

          <div class="detail-section">
            <h4>Discovery Documents</h4>
            <div v-for="document in selectedDevice.documents || []" :key="document.index" class="location-row">
              <a
                :href="discoveryDocumentUrl(document)"
                target="_blank"
                rel="noopener noreferrer"
                @click.prevent="openExternalUrl(discoveryDocumentUrl(document))"
              >{{ document.label }}</a>
              <span class="muted">{{ document.protocol }} / {{ document.contentType }} / {{ formatBytes(document.sizeBytes) }}</span>
            </div>
            <p v-if="!(selectedDevice.documents || []).length" class="muted">No raw discovery XML captured for this device.</p>
          </div>

          <div class="detail-section">
            <h4>Evidence</h4>
            <div class="kv"><span>First seen</span><strong>{{ shortTime(selectedDevice.firstSeen) }}</strong></div>
            <div class="kv"><span>Last seen</span><strong>{{ shortTime(selectedDevice.lastSeen) }}</strong></div>
            <div class="kv"><span>Evidence count</span><strong>{{ selectedDevice.evidenceCount }}</strong></div>
          </div>
        </template>
        <div v-else class="empty-state">Select a discovered device to inspect protocol evidence.</div>
      </aside>
    </section>

    <section class="events-panel">
      <div class="panel-title">
        <h3>Recent Discovery Events</h3>
        <button class="link-button" @click="showAdvanced = !showAdvanced">{{ showAdvanced ? 'Hide' : 'Show' }} advanced tools</button>
      </div>
      <table v-if="events.length" class="result-table">
        <thead><tr><th>Time</th><th>Protocol</th><th>Observation</th></tr></thead>
        <tbody>
          <tr v-for="event in events" :key="event.seenAt + event.summary">
            <td>{{ shortTime(event.seenAt) }}</td>
            <td>{{ event.protocol }}</td>
            <td>{{ event.summary }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-state">No events recorded yet.</div>
    </section>

    <section v-if="showAdvanced" class="advanced-panel">
      <div class="panel-title"><h3>Manual Protocol Probe</h3></div>
      <div class="control-band">
        <div class="field">
          <label>Protocol</label>
          <select v-model="protocol" class="input">
            <option value="netbios">NetBIOS Name Service</option>
            <option value="mdns">mDNS / DNS-SD</option>
            <option value="llmnr">LLMNR</option>
            <option value="ssdp">SSDP / UPnP</option>
            <option value="wsd">WS-Discovery</option>
          </select>
        </div>
        <div class="field">
          <label>Mode</label>
          <select v-model="mode" class="input">
            <option value="query">Query</option>
            <option value="node-status" :disabled="protocol !== 'netbios'">Node Status</option>
            <option value="listen">Listen</option>
          </select>
        </div>
        <div class="field">
          <label>IP Family</label>
          <select v-model="ipFamily" class="input">
            <option value="ipv4">IPv4</option>
            <option value="ipv6">IPv6</option>
            <option value="both">Both</option>
          </select>
        </div>
        <div class="field field-small">
          <label>Timeout</label>
          <input v-model.number="timeout" class="input" type="number" min="1" max="60" />
        </div>
      </div>

      <form @submit.prevent="runManual" class="lookup-form">
        <select v-if="mode === 'query' && protocol === 'mdns'" v-model="mdnsPreset" class="input target-input" @change="applyMdnsPreset">
          <option value="">Custom mDNS name</option>
          <option value="__known_services">All known service types</option>
          <option v-for="service in mdnsKnownServices" :key="service.type" :value="service.type">{{ service.title }} - {{ service.type }}</option>
        </select>
        <input v-if="mode === 'query'" v-model.trim="name" class="input target-input" :placeholder="queryPlaceholder" />
        <input v-if="mode === 'query' && protocol === 'netbios'" v-model.trim="target" class="input target-input" placeholder="255.255.255.255" />
        <input v-if="mode === 'query' && protocol === 'netbios'" v-model.number="suffix" class="input input-sm" type="number" min="0" max="255" />
        <input v-if="mode === 'query' && (protocol === 'mdns' || protocol === 'llmnr')" v-model.trim="recordType" class="input input-sm" :placeholder="protocol === 'llmnr' ? 'A' : 'PTR'" />
        <input v-if="mode === 'query' && protocol === 'wsd'" v-model.trim="scopeFilter" class="input target-input" placeholder="Scopes (optional)" />
        <input v-if="mode === 'node-status' && protocol === 'netbios'" v-model.trim="host" class="input target-input" placeholder="192.168.1.25" />
        <button class="btn" :disabled="manualLoading || !canRun">{{ manualLoading ? 'Running...' : actionLabel }}</button>
      </form>

      <pre v-if="manualResult" class="json-pre">{{ JSON.stringify(manualResult, null, 2) }}</pre>
    </section>
  </div>
</template>

<script>
const zeroconfServiceReference = window.zeroconfServiceReference || []
const serviceSort = (a, b) => {
  if ((b.observed || 0) !== (a.observed || 0)) return (b.observed || 0) - (a.observed || 0)
  const knownDelta = (b.title !== b.type ? 1 : 0) - (a.title !== a.type ? 1 : 0)
  return knownDelta || a.type.localeCompare(b.type)
}
const fetchJson = async (url, label) => {
  const r = await fetch(url)
  if (!r.ok) throw new Error(`${label}: ${r.status}`)
  return r.json()
}
const queryPlaceholders = {
  mdns: "_services._dns-sd._udp.local",
  llmnr: "printer.local",
  ssdp: "ssdp:all",
  wsd: "wsdp:Device or dn:NetworkVideoTransmitter",
}
const recordTypeHints = { A: "IPv4 address", AAAA: "IPv6 address", PTR: "points service type to instance", SRV: "target host and port", TXT: "capability metadata" }
const txtHints = {
  txtvers: "TXT schema version",
  ty: "human-readable model",
  product: "device product string",
  rp: "resource path",
  pdl: "printer languages",
  urf: "AirPrint capabilities",
  qtotal: "queue count",
  adminurl: "admin web page",
  uuid: "stable device id",
  note: "location note",
  color: "color support",
  duplex: "duplex support",
  tls: "TLS support",
  scan: "scan support",
}
const descriptionLabels = [
  ["friendlyName", "Friendly name"], ["deviceType", "Device type"], ["manufacturer", "Manufacturer"], ["modelName", "Model"],
  ["modelDescription", "Model description"], ["modelNumber", "Model number"], ["serialNumber", "Serial"], ["UDN", "UDN"],
  ["presentationURL", "Presentation URL"],
]

app.component("zeroconf-discovery-page", {
  template: "#zeroconf-discovery-page",
  data() {
    return {
      snapshot: null,
      loading: false,
      refreshing: false,
      error: "",
      poller: null,
      selectedId: "",
      filter: "",
      categoryFilter: "",
      protocolFilter: "",
      showAdvanced: false,
      protocol: "mdns",
      mode: "query",
      ipFamily: "ipv4",
      timeout: 5,
      maxPackets: 25,
      bindAddress: "0.0.0.0",
      name: "",
      recordType: "PTR",
      scopeFilter: "",
      mdnsPreset: "",
      target: "255.255.255.255",
      suffix: 32,
      host: "",
      manualLoading: false,
      manualResult: null,
      descriptionLoadingUrl: "",
      descriptionError: "",
      descriptions: {},
      descriptionRequests: {},
      autoDescriptions: false,
      autoDescriptionPrimed: false,
      smbIntrospection: false,
      detailLoading: false,
      detailError: "",
      detailCache: {},
    }
  },
  mounted() {
    this.autoDescriptions = sessionStorage.getItem("zeroconf-auto-descriptions") === "1"
    this.smbIntrospection = sessionStorage.getItem("zeroconf-smb-introspection") === "1"
    this.loadSnapshot()
    this.poller = setInterval(this.loadSnapshot, 5000)
  },
  unmounted() {
    if (this.poller) clearInterval(this.poller)
  },
  computed: {
    devices() {
      return Array.isArray(this.snapshot?.devices) ? this.snapshot.devices : []
    },
    hostnames() {
      return Array.isArray(this.snapshot?.hostnames) ? this.snapshot.hostnames : []
    },
    serviceCatalog() {
      const services = Array.isArray(this.snapshot?.serviceCatalog) ? this.snapshot.serviceCatalog : []
      return [...services].sort(serviceSort)
    },
    serviceGuide() {
      const merged = new Map()
      zeroconfServiceReference.forEach(service => merged.set(`${service.protocol}:${service.type}`, { ...service }))
      this.serviceCatalog.forEach(service => merged.set(`${service.protocol}:${service.type}`, { ...service }))
      return [...merged.values()].sort(serviceSort)
    },
    mdnsKnownServices() {
      return zeroconfServiceReference
        .filter(service => service.protocol === "mDNS")
        .sort((a, b) => a.title.localeCompare(b.title))
    },
    events() {
      return Array.isArray(this.snapshot?.events) ? this.snapshot.events : []
    },
    protocolStats() {
      const stats = Array.isArray(this.snapshot?.protocolStats) ? this.snapshot.protocolStats : []
      const known = ["mDNS", "LLMNR", "NetBIOS", "SSDP", "WS-Discovery"]
      return known.map(protocol => stats.find(s => s.protocol === protocol) || { protocol, status: "idle", observations: 0 })
    },
    visibleWarnings() {
      return (this.snapshot?.warnings || []).slice(0, 3)
    },
    categories() {
      return [...new Set(this.devices.map(d => d.category).filter(Boolean))].sort()
    },
    protocols() {
      return [...new Set(this.devices.flatMap(d => d.protocols || []))].sort()
    },
    filteredDevices() {
      const term = this.filter.toLowerCase()
      return this.devices.filter(device => {
        const blob = `${this.deviceLabel(device)} ${JSON.stringify(device)}`.toLowerCase()
        return (!term || blob.includes(term)) &&
          (!this.categoryFilter || device.category === this.categoryFilter) &&
          (!this.protocolFilter || (device.protocols || []).includes(this.protocolFilter))
      })
    },
    selectedDevice() {
      if (!this.filteredDevices.length) return null
      return this.filteredDevices.find(d => d.id === this.selectedId) || this.filteredDevices[0]
    },
    selectedDeviceId() {
      return this.selectedDevice?.id || ""
    },
    selectedDetail() {
      if (!this.selectedDeviceId) return null
      const cacheKey = `${this.selectedDeviceId}|smb=${this.smbIntrospection ? '1' : '0'}`
      return this.detailCache[cacheKey] || null
    },
    selectedResolutionSteps() {
      const device = this.selectedDevice
      if (!device) return []
      const services = device.services || []
      const service = services.find(s => s.protocol === "mDNS" && s.type) || services[0]
      if (!service) return []
      const endpoint = service.port ? `${service.target || service.location || "-"}:${service.port}` : (service.target || service.location || "-")
      const steps = [
        { label: service.protocol === "mDNS" ? "Service Type" : "Protocol Type", value: service.type || service.protocol },
        { label: "Instance", value: service.name || device.displayName },
        { label: service.protocol === "mDNS" ? "SRV Target" : "Endpoint", value: endpoint },
      ]
      if ((device.addresses || []).length) steps.push({ label: "A / AAAA", value: device.addresses.join(", ") })
      if ((device.txtRecords || []).length) steps.push({ label: "TXT", value: `${device.txtRecords.length} metadata entries` })
      return steps
    },
    canRun() {
      if (this.mode === "query" && this.protocol === "mdns" && this.mdnsPreset === "__known_services") return true
      if (this.mode === "query") return this.protocol === "wsd" || this.name.length > 0
      if (this.mode === "node-status") return this.protocol === "netbios" && this.host.length > 0
      return this.mode === "listen"
    },
    queryPlaceholder() {
      return queryPlaceholders[this.protocol] || "MYPC"
    },
    actionLabel() {
      if (this.mode === "listen") return "Start Listener"
      if (this.mode === "node-status") return "Read Node Status"
      if (this.protocol === "mdns" && this.mdnsPreset === "__known_services") return "Sweep Service Types"
      if (this.protocol === "ssdp") return "Search Services"
      if (this.protocol === "wsd") return "Probe Devices"
      return "Query Name"
    },
  },
  watch: {
    autoDescriptions(value) {
      sessionStorage.setItem("zeroconf-auto-descriptions", value ? "1" : "0")
      if (value) {
        this.hydrateKnownDescriptions()
      }
    },
    smbIntrospection(value) {
      sessionStorage.setItem("zeroconf-smb-introspection", value ? "1" : "0")
      if (this.selectedDeviceId) this.loadSelectedDetail(true)
    },
    selectedDeviceId: {
      immediate: true,
      handler(id) {
        if (id) this.loadSelectedDetail(false)
      },
    },
  },
  methods: {
    hasKremaBridge() {
      return !!(window.krema && typeof window.krema.invoke === "function")
    },
    async loadSnapshot() {
      this.loading = true
      try {
        this.snapshot = await fetchJson("/api/zeroconf/dashboard", "Dashboard error")
        this.error = ""
        if (this.autoDescriptions) this.hydrateKnownDescriptions()
      } catch (e) {
        this.error = e instanceof Error ? e.message : "Could not load discovery dashboard"
      } finally {
        this.loading = false
      }
    },
    async forceRefresh() {
      this.refreshing = true
      try {
        this.snapshot = await fetchJson("/api/zeroconf/dashboard/refresh", "Refresh error")
        if (this.autoDescriptions) this.hydrateKnownDescriptions()
        setTimeout(this.loadSnapshot, 2500)
      } catch (e) {
        this.error = e instanceof Error ? e.message : "Could not start discovery scan"
      } finally {
        this.refreshing = false
      }
    },
    shortTime(value) {
      if (!value) return "-"
      try { return new Date(value).toLocaleTimeString() } catch (_) { return value }
    },
    formatHex(value) {
      if (value === undefined || value === null || Number.isNaN(Number(value))) return "-"
      return `0x${Number(value).toString(16)}`
    },
    formatBytes(value) {
      const bytes = Number(value || 0)
      if (bytes < 1024) return `${bytes} B`
      return `${(bytes / 1024).toFixed(1)} KB`
    },
    async openExternalUrl(url) {
      const target = String(url || "").trim()
      if (!target) return
      if (this.hasKremaBridge()) {
        try {
          await window.krema.invoke("shell:openUrl", { url: target })
          return
        } catch (_) {
          // Fall through for browser/dev mode and older desktop bridge builds.
        }
      }
      window.open(target, "_blank", "noopener,noreferrer")
    },
    isExternalUrl(value) {
      return /^https?:\/\//i.test(String(value || "").trim())
    },
    discoveryDocumentUrl(document) {
      const path = `/api/zeroconf/device/${encodeURIComponent(this.selectedDeviceId)}/documents/${encodeURIComponent(document.index)}`
      return new URL(path, window.location.origin).toString()
    },
    deviceLabel(device) {
      const description = this.deviceDescription(device)
      return description?.friendlyName || description?.modelName || device?.displayName || "Unknown device"
    },
    deviceDescription(device) {
      if (!device) return null
      const location = (device.locations || []).find(url => this.descriptions[url]?.description)
      return location ? this.descriptions[location]?.description || null : null
    },
    recordTypeHint(type) {
      return recordTypeHints[type] || "DNS record"
    },
    txtHint(key) {
      return txtHints[String(key || "").toLowerCase()] || "service metadata"
    },
    deviceServiceLines(device) {
      const services = Array.isArray(device?.services) ? device.services : []
      return services.map(service => {
        const type = service.type || service.name || service.protocol
        const endpoint = service.port
          ? `${service.target || service.location || "-"}:${service.port}`
          : (service.target || service.location || "")
        return endpoint ? `${service.protocol}: ${type} -> ${endpoint}` : `${service.protocol}: ${type}`
      })
    },
    async hydrateKnownDescriptions() {
      const shouldPrime = !this.autoDescriptionPrimed
      this.autoDescriptionPrimed = true
      const candidates = this.devices
        .filter(device => (device.protocols || []).includes("SSDP"))
        .flatMap(device => device.locations || [])
        .filter(location => this.isDescriptionUrl(location))
        .filter(location => shouldPrime || !this.descriptions[location])
        .filter(location => !this.descriptionRequests[location])
      for (const location of [...new Set(candidates)]) {
        await this.loadDescription(location, { silent: true })
      }
    },
    serviceActionHref(service) {
      if (!service) return ""
      if (service.location && /^https?:\/\//i.test(service.location)) return service.location
      const host = this.normalizeServiceHost(service.target) || this.selectedDevice?.addresses?.[0] || this.selectedDevice?.hostnames?.[0]
      const port = service.port
      if (!host || !port) return ""
      const type = String(service.type || "").toLowerCase()
      const scheme = (type.includes("_ipps") || type.includes("https") || port === 443 || port === 8443) ? "https"
        : (type.includes("_http") || type.includes("_ipp") || [80, 81, 631, 8008, 8080].includes(port)) ? "http"
        : ""
      if (!scheme) return ""
      const defaultPort = (scheme === "http" && port === 80) || (scheme === "https" && port === 443)
      return defaultPort ? `${scheme}://${host}/` : `${scheme}://${host}:${port}/`
    },
    normalizeServiceHost(value) {
      const raw = String(value || "").trim()
      if (!raw || raw.startsWith("uuid:") || raw.startsWith("urn:")) return ""
      return raw.replace(/^https?:\/\//i, "").split("/")[0].split(":")[0]
    },
    async loadSelectedDetail(force) {
      const id = this.selectedDeviceId
      if (!id) return
      const cacheKey = `${id}|smb=${this.smbIntrospection ? '1' : '0'}`
      if (!force && this.detailCache[cacheKey]) {
        this.detailError = ""
        return
      }
      this.detailLoading = true
      this.detailError = ""
      try {
        const params = new URLSearchParams()
        if (this.smbIntrospection) params.set("includeSmb", "true")
        const suffix = params.toString() ? `?${params.toString()}` : ""
        const result = await fetchJson(`/api/zeroconf/device/${encodeURIComponent(id)}/details${suffix}`, "Host detail error")
        if (result.status === "error") throw new Error(result.error || "Could not inspect host")
        this.detailCache = { ...this.detailCache, [cacheKey]: result }
      } catch (e) {
        this.detailError = e instanceof Error ? e.message : "Could not inspect host"
      } finally {
        this.detailLoading = false
      }
    },
    isDescriptionUrl(location) {
      return this.isExternalUrl(location)
    },
    descriptionRows(description) {
      return descriptionLabels
        .map(([key, label]) => ({ label, value: description?.[key] }))
        .filter(row => row.value)
    },
    async loadDescription(location, options = {}) {
      const silent = !!options.silent
      if (this.descriptions[location] || this.descriptionRequests[location]) return
      this.descriptionRequests = { ...this.descriptionRequests, [location]: true }
      if (!silent) {
        this.descriptionLoadingUrl = location
        this.descriptionError = ""
      }
      try {
        const result = await fetchJson(`/api/zeroconf/description?url=${encodeURIComponent(location)}`, "Description error")
        if (result.status !== "ok") throw new Error(result.error || "Could not read device description")
        this.descriptions = { ...this.descriptions, [location]: result }
      } catch (e) {
        if (!silent) {
          this.descriptionError = e instanceof Error ? e.message : "Could not read device description"
        }
      } finally {
        const nextRequests = { ...this.descriptionRequests }
        delete nextRequests[location]
        this.descriptionRequests = nextRequests
        if (!silent) this.descriptionLoadingUrl = ""
      }
    },
    applyMdnsPreset() {
      if (this.protocol !== "mdns" || this.mode !== "query") return
      if (this.mdnsPreset && this.mdnsPreset !== "__known_services") {
        this.name = this.mdnsPreset
        this.recordType = "PTR"
      }
    },
    async runMdnsKnownServiceSweep(params) {
      const serviceTypes = [...new Set(this.mdnsKnownServices.map(service => service.type))]
      const queries = []
      const records = []
      const rows = []
      for (const serviceType of serviceTypes) {
        const queryParams = new URLSearchParams(params)
        queryParams.set("type", "PTR")
        const result = await fetchJson(`/api/zeroconf/mdns/query/${encodeURIComponent(serviceType)}?${queryParams}`, `mDNS ${serviceType} error`)
        queries.push({ serviceType, status: result.status, responseCount: result.responseCount || 0 })
        ;(result.records || []).forEach(record => records.push(record))
        ;(result.rows || []).forEach(row => rows.push({ serviceType, ...row }))
      }
      return {
        protocol: "mDNS",
        mode: "service-type-sweep",
        status: records.length ? "ok" : "no-responses",
        queryCount: serviceTypes.length,
        responseCount: records.length,
        queries,
        rows,
        records,
      }
    },
    async runManual() {
      this.manualLoading = true
      this.manualResult = null
      try {
        const params = new URLSearchParams()
        params.set("ipFamily", this.ipFamily)
        params.set("timeout", String(this.timeout || 5))
        let url = ""
        if (this.protocol === "mdns" && this.mode === "query") {
          if (this.mdnsPreset === "__known_services") {
            this.manualResult = await this.runMdnsKnownServiceSweep(params)
            return
          }
          params.set("type", this.recordType || "PTR")
          url = `/api/zeroconf/mdns/query/${encodeURIComponent(this.name)}?${params}`
        } else if (this.protocol === "llmnr" && this.mode === "query") {
          params.set("type", this.recordType || "A")
          url = `/api/zeroconf/llmnr/query/${encodeURIComponent(this.name)}?${params}`
        } else if (this.protocol === "llmnr" && this.mode === "listen") {
          url = `/api/zeroconf/llmnr/listen?${params}`
        } else if (this.protocol === "ssdp" && this.mode === "query") {
          params.set("searchTarget", this.name || "ssdp:all")
          url = `/api/zeroconf/ssdp/search?${params}`
        } else if (this.protocol === "ssdp" && this.mode === "listen") {
          url = `/api/zeroconf/ssdp/listen?${params}`
        } else if (this.protocol === "wsd" && this.mode === "query") {
          if (this.name) params.set("types", this.name)
          if (this.scopeFilter) params.set("scopes", this.scopeFilter)
          url = `/api/zeroconf/wsd/probe?${params}`
        } else if (this.protocol === "wsd" && this.mode === "listen") {
          url = `/api/zeroconf/wsd/listen?${params}`
        } else if (this.protocol === "mdns" && this.mode === "listen") {
          url = `/api/zeroconf/mdns/listen?${params}`
        } else if (this.mode === "query") {
          params.set("target", this.target || "255.255.255.255")
          params.set("suffix", String(this.suffix ?? 32))
          url = `/api/zeroconf/netbios/query/${encodeURIComponent(this.name)}?${params}`
        } else if (this.mode === "node-status") {
          url = `/api/zeroconf/netbios/node-status/${encodeURIComponent(this.host)}?${params}`
        } else {
          url = `/api/zeroconf/netbios/listen?${params}`
        }
        this.manualResult = await fetchJson(url, "API error")
      } catch (e) {
        this.manualResult = { status: "error", error: e instanceof Error ? e.message : "Unexpected error" }
      } finally {
        this.manualLoading = false
      }
    },
  },
})
</script>
