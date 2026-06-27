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
const zeroconfServiceReference = [
  { protocol: "mDNS", type: "_ipp._tcp.local", title: "IPP Printer", description: "Modern printer queue used by AirPrint, Mopria, CUPS, and many Windows/macOS flows.", observed: 0 },
  { protocol: "mDNS", type: "_ipps._tcp.local", title: "Secure IPP Printer", description: "TLS-protected IPP printer endpoint.", observed: 0 },
  { protocol: "mDNS", type: "_printer._tcp.local", title: "LPD Printer", description: "Older printer advertisement still seen on some devices.", observed: 0 },
  { protocol: "mDNS", type: "_scanner._tcp.local", title: "Scanner", description: "Scanner service, often emitted by multifunction printers.", observed: 0 },
  { protocol: "mDNS", type: "_http._tcp.local", title: "HTTP Admin", description: "Embedded web UI, admin page, or local application endpoint.", observed: 0 },
  { protocol: "mDNS", type: "_airplay._tcp.local", title: "AirPlay", description: "Apple media display or playback receiver.", observed: 0 },
  { protocol: "mDNS", type: "_raop._tcp.local", title: "AirPlay Audio", description: "Remote Audio Output Protocol speaker or receiver.", observed: 0 },
  { protocol: "mDNS", type: "_googlecast._tcp.local", title: "Google Cast", description: "Chromecast or Cast receiver.", observed: 0 },
  { protocol: "mDNS", type: "_hap._tcp.local", title: "HomeKit", description: "Apple HomeKit accessory endpoint.", observed: 0 },
  { protocol: "SSDP", type: "urn:schemas-upnp-org:device:MediaRenderer:1", title: "UPnP Media Renderer", description: "DLNA playback device such as a TV, receiver, or speaker.", observed: 0 },
  { protocol: "SSDP", type: "urn:schemas-upnp-org:device:MediaServer:1", title: "UPnP Media Server", description: "DLNA media library or content source.", observed: 0 },
  { protocol: "SSDP", type: "urn:schemas-upnp-org:device:InternetGatewayDevice:1", title: "Internet Gateway", description: "Router or gateway control service.", observed: 0 },
  { protocol: "WS-Discovery", type: "dn:NetworkVideoTransmitter", title: "ONVIF Camera", description: "Network camera or video transmitter discovered with SOAP-over-UDP.", observed: 0 },
  { protocol: "WS-Discovery", type: "wsdp:Device", title: "WSD Device", description: "Generic Web Services for Devices endpoint.", observed: 0 },
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
      return [...services].sort((a, b) => {
        const knownA = a.title !== a.type ? 1 : 0
        const knownB = b.title !== b.type ? 1 : 0
        if (knownA !== knownB) return knownB - knownA
        if ((b.observed || 0) !== (a.observed || 0)) return (b.observed || 0) - (a.observed || 0)
        return a.type.localeCompare(b.type)
      })
    },
    serviceGuide() {
      const merged = new Map()
      zeroconfServiceReference.forEach(service => merged.set(`${service.protocol}:${service.type}`, { ...service }))
      this.serviceCatalog.forEach(service => merged.set(`${service.protocol}:${service.type}`, { ...service }))
      return [...merged.values()].sort((a, b) => {
        if ((b.observed || 0) !== (a.observed || 0)) return (b.observed || 0) - (a.observed || 0)
        const knownA = a.title !== a.type ? 1 : 0
        const knownB = b.title !== b.type ? 1 : 0
        if (knownA !== knownB) return knownB - knownA
        return a.type.localeCompare(b.type)
      })
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
      if (this.mode === "query") return this.protocol === "wsd" || this.name.length > 0
      if (this.mode === "node-status") return this.protocol === "netbios" && this.host.length > 0
      return this.mode === "listen"
    },
    queryPlaceholder() {
      if (this.protocol === "mdns") return "_services._dns-sd._udp.local"
      if (this.protocol === "llmnr") return "printer.local"
      if (this.protocol === "ssdp") return "ssdp:all"
      if (this.protocol === "wsd") return "wsdp:Device or dn:NetworkVideoTransmitter"
      return "MYPC"
    },
    actionLabel() {
      if (this.mode === "listen") return "Start Listener"
      if (this.mode === "node-status") return "Read Node Status"
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
        const r = await fetch("/api/zeroconf/dashboard")
        if (!r.ok) throw new Error(`Dashboard error: ${r.status}`)
        this.snapshot = await r.json()
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
        const r = await fetch("/api/zeroconf/dashboard/refresh")
        if (!r.ok) throw new Error(`Refresh error: ${r.status}`)
        this.snapshot = await r.json()
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
      const hints = {
        A: "IPv4 address",
        AAAA: "IPv6 address",
        PTR: "points service type to instance",
        SRV: "target host and port",
        TXT: "capability metadata",
      }
      return hints[type] || "DNS record"
    },
    txtHint(key) {
      const normalized = String(key || "").toLowerCase()
      const hints = {
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
      return hints[normalized] || "service metadata"
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
        const r = await fetch(`/api/zeroconf/device/${encodeURIComponent(id)}/details${suffix}`)
        if (!r.ok) throw new Error(`Host detail error: ${r.status}`)
        const result = await r.json()
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
      const labels = [
        ["friendlyName", "Friendly name"],
        ["deviceType", "Device type"],
        ["manufacturer", "Manufacturer"],
        ["modelName", "Model"],
        ["modelDescription", "Model description"],
        ["modelNumber", "Model number"],
        ["serialNumber", "Serial"],
        ["UDN", "UDN"],
        ["presentationURL", "Presentation URL"],
      ]
      return labels
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
        const r = await fetch(`/api/zeroconf/description?url=${encodeURIComponent(location)}`)
        if (!r.ok) throw new Error(`Description error: ${r.status}`)
        const result = await r.json()
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
    async runManual() {
      this.manualLoading = true
      this.manualResult = null
      try {
        const params = new URLSearchParams()
        params.set("ipFamily", this.ipFamily)
        params.set("timeout", String(this.timeout || 5))
        let url = ""
        if (this.protocol === "mdns" && this.mode === "query") {
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
        const r = await fetch(url)
        if (!r.ok) throw new Error(`API error: ${r.status} ${r.statusText}`)
        this.manualResult = await r.json()
      } catch (e) {
        this.manualResult = { status: "error", error: e instanceof Error ? e.message : "Unexpected error" }
      } finally {
        this.manualLoading = false
      }
    },
  },
})
</script>

<style>
.zeroconf-page { padding: 1rem 0; color: #111827; }
.zeroconf-page .page-head { display: flex; justify-content: space-between; gap: 1rem; align-items: flex-start; margin-bottom: 1rem; }
.zeroconf-page .desc { color: #64748b; font-size: 0.9rem; margin: 0.25rem 0 0; }
.zeroconf-page .head-actions { display: flex; gap: 0.5rem; flex-wrap: wrap; justify-content: flex-end; }
.zeroconf-page .toggle-chip { display: inline-flex; align-items: center; gap: 0.45rem; border: 1px solid #d1d5db; border-radius: 999px; background: #fff; padding: 0.45rem 0.7rem; font-size: 0.78rem; color: #334155; }
.zeroconf-page .toggle-chip input { margin: 0; }
.zeroconf-page .btn { border: 0; background: #0f172a; color: #fff; border-radius: 6px; padding: 0.55rem 0.95rem; cursor: pointer; }
.zeroconf-page .btn-secondary { background: #e5e7eb; color: #111827; }
.zeroconf-page .btn:disabled { opacity: .6; cursor: default; }
.zeroconf-page .summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 0.6rem; margin-bottom: 0.8rem; }
.zeroconf-page .summary-card { border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.65rem 0.75rem; display: flex; justify-content: space-between; align-items: baseline; gap: 0.6rem; background: #fff; }
.zeroconf-page .summary-card span { font-size: 0.74rem; color: #64748b; font-weight: 600; }
.zeroconf-page .summary-card strong { font-size: 1rem; color: #0f172a; }
.zeroconf-page .ok { color: #166534; }
.zeroconf-page .warn { color: #92400e; }
.zeroconf-page .protocol-strip { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 0.5rem; margin-bottom: 0.8rem; }
.zeroconf-page .protocol-pill { border: 1px solid #e5e7eb; border-left: 4px solid #94a3b8; border-radius: 8px; padding: 0.55rem 0.65rem; background: #fff; display: flex; flex-direction: column; gap: 0.15rem; min-width: 0; }
.zeroconf-page .protocol-pill.ok { border-left-color: #16a34a; }
.zeroconf-page .protocol-pill.warning { border-left-color: #f59e0b; }
.zeroconf-page .protocol-pill strong { font-size: 0.82rem; color: #111827; }
.zeroconf-page .protocol-pill span { font-size: 0.74rem; color: #64748b; }
.zeroconf-page .resolution-map { border: 1px solid #e5e7eb; border-radius: 8px; background: #fff; padding: 0.85rem; margin-bottom: 0.8rem; }
.zeroconf-page .resolution-flow { display: flex; gap: 0.45rem; align-items: stretch; }
.zeroconf-page .resolution-flow.compact { flex-wrap: wrap; }
.zeroconf-page .flow-step { border: 1px solid #dbeafe; border-left: 4px solid #2563eb; background: #eff6ff; border-radius: 8px; padding: 0.55rem; min-width: 130px; flex: 1 1 130px; }
.zeroconf-page .flow-step strong { display: block; font-size: 0.78rem; color: #1e3a8a; margin-bottom: 0.22rem; }
.zeroconf-page .flow-step span { display: block; color: #334155; font-size: 0.78rem; overflow-wrap: anywhere; }
.zeroconf-page .flow-arrow { align-self: center; justify-self: center; color: #64748b; font-weight: 700; }
.zeroconf-page .explainer-grid { display: grid; grid-template-columns: minmax(320px, 1fr) minmax(320px, 1fr); gap: 0.8rem; margin-bottom: 0.8rem; }
.zeroconf-page .explainer-panel { border: 1px solid #e5e7eb; border-radius: 8px; background: #fff; padding: 0.85rem; min-width: 0; }
.zeroconf-page .panel-title span { color: #64748b; font-size: 0.78rem; }
.zeroconf-page .service-type-list { display: grid; gap: 0.5rem; }
.zeroconf-page .service-type-row { border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.55rem; display: grid; grid-template-columns: minmax(160px, 0.8fr) minmax(180px, 1.4fr) auto; gap: 0.55rem; align-items: start; }
.zeroconf-page .service-type-row.observed { border-left: 4px solid #16a34a; }
.zeroconf-page .service-type-row strong { display: block; font-size: 0.84rem; margin-bottom: 0.25rem; }
.zeroconf-page .service-type-row code { padding: 0.2rem 0.35rem; font-size: 0.74rem; }
.zeroconf-page .service-type-row p { margin: 0; color: #475569; font-size: 0.78rem; line-height: 1.35; }
.zeroconf-page .service-type-row span { color: #64748b; font-size: 0.74rem; white-space: nowrap; }
.zeroconf-page .filter-band { display: grid; grid-template-columns: minmax(220px, 1fr) 180px 180px; gap: 0.55rem; margin-bottom: 0.8rem; }
.zeroconf-page .input { width: 100%; padding: 0.55rem 0.65rem; border: 1px solid #d1d5db; border-radius: 6px; background: #fff; }
.zeroconf-page .inventory-layout { display: grid; grid-template-columns: minmax(320px, 0.95fr) minmax(360px, 1.2fr); gap: 0.8rem; align-items: start; }
.zeroconf-page .device-list { display: flex; flex-direction: column; gap: 0.45rem; }
.zeroconf-page .device-row { border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.65rem; background: #fff; text-align: left; cursor: pointer; display: grid; grid-template-columns: 1fr auto; gap: 0.45rem 0.7rem; }
.zeroconf-page .device-row.active { border-color: #0f172a; box-shadow: inset 3px 0 0 #0f172a; }
.zeroconf-page .device-main { display: flex; flex-direction: column; gap: 0.15rem; min-width: 0; }
.zeroconf-page .device-main strong { font-size: 0.92rem; overflow-wrap: anywhere; }
.zeroconf-page .device-main span, .zeroconf-page .device-meta { color: #64748b; font-size: 0.78rem; }
.zeroconf-page .device-meta { grid-column: 1 / -1; display: flex; justify-content: space-between; gap: 0.6rem; overflow-wrap: anywhere; }
.zeroconf-page .device-services { grid-column: 1 / -1; display: grid; gap: 0.22rem; margin-top: 0.1rem; }
.zeroconf-page .device-services span { color: #334155; font-size: 0.74rem; background: #f8fafc; border: 1px solid #e5e7eb; border-radius: 6px; padding: 0.22rem 0.38rem; overflow-wrap: anywhere; }
.zeroconf-page .confidence { font-size: 0.7rem; text-transform: uppercase; letter-spacing: 0; border-radius: 999px; padding: 0.18rem 0.45rem; background: #f1f5f9; color: #475569; height: fit-content; }
.zeroconf-page .confidence.high { background: #dcfce7; color: #166534; }
.zeroconf-page .confidence.medium { background: #fef3c7; color: #92400e; }
.zeroconf-page .detail-panel, .zeroconf-page .events-panel, .zeroconf-page .advanced-panel { border: 1px solid #e5e7eb; border-radius: 8px; background: #fff; padding: 0.85rem; }
.zeroconf-page .detail-head { display: flex; justify-content: space-between; gap: 0.75rem; align-items: baseline; margin-bottom: 0.6rem; }
.zeroconf-page h3, .zeroconf-page h4 { margin: 0; }
.zeroconf-page .detail-head h3 { font-size: 1rem; overflow-wrap: anywhere; }
.zeroconf-page .detail-head span, .zeroconf-page .muted { color: #64748b; font-size: 0.82rem; }
.zeroconf-page .tag-row { display: flex; flex-wrap: wrap; gap: 0.35rem; margin-bottom: 0.75rem; }
.zeroconf-page .tag { background: #eef2ff; color: #3730a3; border-radius: 999px; padding: 0.2rem 0.5rem; font-size: 0.74rem; }
.zeroconf-page .detail-section { border-top: 1px solid #e5e7eb; padding-top: 0.7rem; margin-top: 0.7rem; display: flex; flex-direction: column; gap: 0.35rem; }
.zeroconf-page .section-head { display: flex; justify-content: space-between; gap: 0.75rem; align-items: center; }
.zeroconf-page code { display: block; background: #f8fafc; border: 1px solid #e5e7eb; border-radius: 6px; padding: 0.35rem 0.45rem; overflow-wrap: anywhere; }
.zeroconf-page .kv { display: flex; justify-content: space-between; gap: 0.7rem; font-size: 0.82rem; }
.zeroconf-page .kv span { color: #64748b; }
.zeroconf-page .location-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 0.45rem; align-items: center; }
.zeroconf-page .location-row a { color: #1d4ed8; background: #f8fafc; border: 1px solid #e5e7eb; border-radius: 6px; padding: 0.35rem 0.45rem; overflow-wrap: anywhere; }
.zeroconf-page .description-card { border: 1px solid #dbeafe; border-radius: 8px; background: #eff6ff; padding: 0.55rem; display: grid; gap: 0.35rem; }
.zeroconf-page .description-card .kv strong { text-align: right; overflow-wrap: anywhere; }
.zeroconf-page .description-services { border-top: 1px solid #bfdbfe; padding-top: 0.45rem; display: grid; gap: 0.3rem; }
.zeroconf-page .description-services > strong { font-size: 0.78rem; color: #1e3a8a; }
.zeroconf-page .meaning-row { margin: 0; display: grid; grid-template-columns: minmax(120px, 0.45fr) minmax(160px, 1fr); gap: 0.55rem; font-size: 0.82rem; color: #475569; }
.zeroconf-page .meaning-row strong { color: #111827; overflow-wrap: anywhere; }
.zeroconf-page .inspection-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0.7rem; }
.zeroconf-page .inspection-panel { border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.65rem; background: #f8fafc; }
.zeroconf-page .inspection-list { display: grid; gap: 0.45rem; }
.zeroconf-page .inspection-row { border: 1px solid #e5e7eb; border-radius: 6px; background: #fff; padding: 0.5rem; display: grid; gap: 0.3rem; }
.zeroconf-page .inspection-row strong { display: block; font-size: 0.82rem; }
.zeroconf-page .inspection-row a { color: #1d4ed8; overflow-wrap: anywhere; }
.zeroconf-page .inspection-meta { display: flex; flex-wrap: wrap; gap: 0.45rem; color: #64748b; font-size: 0.74rem; }
.zeroconf-page .mini-table, .zeroconf-page .result-table { width: 100%; border-collapse: collapse; font-size: 0.8rem; }
.zeroconf-page .mini-table th, .zeroconf-page .mini-table td, .zeroconf-page .result-table th, .zeroconf-page .result-table td { border-bottom: 1px solid #e5e7eb; padding: 0.45rem; text-align: left; vertical-align: top; overflow-wrap: anywhere; }
.zeroconf-page .mini-table th, .zeroconf-page .result-table th { color: #475569; background: #f8fafc; font-weight: 600; }
.zeroconf-page .events-panel, .zeroconf-page .advanced-panel { margin-top: 0.9rem; }
.zeroconf-page .panel-title { display: flex; justify-content: space-between; gap: 1rem; align-items: center; margin-bottom: 0.65rem; }
.zeroconf-page .link-button { border: 0; background: transparent; color: #1d4ed8; cursor: pointer; padding: 0.25rem; }
.zeroconf-page .callout { margin-bottom: 0.75rem; border-radius: 6px; padding: 0.55rem 0.65rem; font-size: 0.83rem; background: #fff7ed; color: #92400e; border: 1px solid #fed7aa; }
.zeroconf-page .bad-callout { background: #fef2f2; color: #991b1b; border-color: #fecaca; }
.zeroconf-page .empty-state { color: #64748b; font-size: 0.86rem; padding: 0.7rem 0; }
.zeroconf-page .control-band { display: grid; grid-template-columns: repeat(4, minmax(120px, 1fr)); gap: 0.65rem; margin-bottom: 0.8rem; }
.zeroconf-page .field { display: flex; flex-direction: column; gap: 0.25rem; }
.zeroconf-page label { font-size: 0.74rem; color: #64748b; font-weight: 600; }
.zeroconf-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; flex-wrap: wrap; }
.zeroconf-page .target-input { flex: 1; min-width: 220px; }
.zeroconf-page .input-sm { flex: 0 0 110px; }
.zeroconf-page .json-pre { margin: 0; background: #0f172a; color: #e2e8f0; border-radius: 6px; padding: 0.8rem; overflow-x: auto; font-size: 0.8rem; }
@media (max-width: 1000px) {
  .zeroconf-page .summary-grid, .zeroconf-page .protocol-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .zeroconf-page .inventory-layout, .zeroconf-page .filter-band, .zeroconf-page .explainer-grid, .zeroconf-page .inspection-grid { grid-template-columns: 1fr; }
  .zeroconf-page .resolution-flow { flex-direction: column; }
  .zeroconf-page .flow-arrow { display: none; }
  .zeroconf-page .service-type-row, .zeroconf-page .meaning-row { grid-template-columns: 1fr; }
  .zeroconf-page .location-row { grid-template-columns: 1fr; }
  .zeroconf-page .page-head { flex-direction: column; }
  .zeroconf-page .head-actions { justify-content: flex-start; }
}
</style>
