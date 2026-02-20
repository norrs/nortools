<template id="help-dns-record-types-page">
  <div class="dns-help">
    <section class="card block">
      <h2>DNS Record Types Help</h2>
      <p>
        This page documents commonly known and operationally relevant DNS record types you may query in DNS tooling.
      </p>
      <p>
        RFC references are linked per type. Some obsolete or experimental types may have no dedicated RFC assignment.
      </p>
    </section>

    <section class="card block">
      <h3>Core Records</h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>Type</th><th>Description</th><th>Typical Use</th><th>RFC</th></tr></thead>
          <tbody>
            <tr v-for="row in coreTypes" :key="`core-${row.type}`">
              <td><code>{{ row.type }}</code></td>
              <td>{{ row.description }}</td>
              <td>{{ row.use }}</td>
              <td>
                <span v-if="rfcLinks(row.type).length" class="rfc-links">
                  <a v-for="link in rfcLinks(row.type)" :key="`${row.type}-${link.code}`" :href="link.url" target="_blank" rel="noopener noreferrer">{{ link.label }}</a>
                </span>
                <span v-else class="rfc-none">No RFC mapping</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="card block">
      <h3>Email And Service Discovery</h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>Type</th><th>Description</th><th>Typical Use</th><th>RFC</th></tr></thead>
          <tbody>
            <tr v-for="row in serviceTypes" :key="`svc-${row.type}`">
              <td><code>{{ row.type }}</code></td>
              <td>{{ row.description }}</td>
              <td>{{ row.use }}</td>
              <td>
                <span v-if="rfcLinks(row.type).length" class="rfc-links">
                  <a v-for="link in rfcLinks(row.type)" :key="`${row.type}-${link.code}`" :href="link.url" target="_blank" rel="noopener noreferrer">{{ link.label }}</a>
                </span>
                <span v-else class="rfc-none">No RFC mapping</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="card block">
      <h3>DNSSEC And Security Records</h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>Type</th><th>Description</th><th>Typical Use</th><th>RFC</th></tr></thead>
          <tbody>
            <tr v-for="row in securityTypes" :key="`sec-${row.type}`">
              <td><code>{{ row.type }}</code></td>
              <td>{{ row.description }}</td>
              <td>{{ row.use }}</td>
              <td>
                <span v-if="rfcLinks(row.type).length" class="rfc-links">
                  <a v-for="link in rfcLinks(row.type)" :key="`${row.type}-${link.code}`" :href="link.url" target="_blank" rel="noopener noreferrer">{{ link.label }}</a>
                </span>
                <span v-else class="rfc-none">No RFC mapping</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="card block">
      <h3>Additional Standardized Types</h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>Type</th><th>Description</th><th>Typical Use</th><th>RFC</th></tr></thead>
          <tbody>
            <tr v-for="row in additionalTypes" :key="`add-${row.type}`">
              <td><code>{{ row.type }}</code></td>
              <td>{{ row.description }}</td>
              <td>{{ row.use }}</td>
              <td>
                <span v-if="rfcLinks(row.type).length" class="rfc-links">
                  <a v-for="link in rfcLinks(row.type)" :key="`${row.type}-${link.code}`" :href="link.url" target="_blank" rel="noopener noreferrer">{{ link.label }}</a>
                </span>
                <span v-else class="rfc-none">No RFC mapping</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="card block">
      <h3>Query / Transfer Special Types</h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>Type</th><th>Description</th><th>Typical Use</th><th>RFC</th></tr></thead>
          <tbody>
            <tr v-for="row in specialTypes" :key="`special-${row.type}`">
              <td><code>{{ row.type }}</code></td>
              <td>{{ row.description }}</td>
              <td>{{ row.use }}</td>
              <td>
                <span v-if="rfcLinks(row.type).length" class="rfc-links">
                  <a v-for="link in rfcLinks(row.type)" :key="`${row.type}-${link.code}`" :href="link.url" target="_blank" rel="noopener noreferrer">{{ link.label }}</a>
                </span>
                <span v-else class="rfc-none">No RFC mapping</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p>
        Some of these are protocol/meta records and may not behave like normal zone-data lookups.
      </p>
    </section>
  </div>
</template>

<script>
app.component("help-dns-record-types-page", {
  template: "#help-dns-record-types-page",
  data() {
    return {
      coreTypes: [
        { type: 'A', description: 'Maps a name to an IPv4 address.', use: 'example.com -> 93.184.216.34' },
        { type: 'AAAA', description: 'Maps a name to an IPv6 address.', use: 'example.com -> 2606:2800:220:1:248:1893:25c8:1946' },
        { type: 'CNAME', description: 'Alias from one name to another canonical name.', use: 'www -> example.com' },
        { type: 'NS', description: 'Authoritative nameservers for a zone/delegation.', use: 'Zone delegation' },
        { type: 'SOA', description: 'Start of authority metadata for a DNS zone.', use: 'Serial, refresh, retry, expire' },
        { type: 'PTR', description: 'Reverse mapping from IP to hostname.', use: 'Mail/network reputation checks' },
        { type: 'TXT', description: 'Arbitrary text data attached to a name.', use: 'SPF/verification tokens/policies' },
        { type: 'CAA', description: 'Which certificate authorities may issue certs for a domain.', use: 'Public CA issuance control' },
        { type: 'DNAME', description: 'Alias an entire subtree to another domain subtree.', use: 'Namespace redirection' },
      ],
      serviceTypes: [
        { type: 'MX', description: 'Mail exchange servers for domain email delivery.', use: 'Inbound SMTP routing' },
        { type: 'SRV', description: 'Service locator with priority/weight/port/target.', use: '_service._proto lookups' },
        { type: 'NAPTR', description: 'Rule-based service discovery and URI rewriting.', use: 'SIP/ENUM discovery' },
        { type: 'SVCB', description: 'General service binding and endpoint parameters.', use: 'Modern service bootstrapping' },
        { type: 'HTTPS', description: 'Service binding profile for HTTPS endpoints.', use: 'HTTP/3, alt endpoints, hints' },
        { type: 'URI', description: 'Maps owner name to URI with priority and weight.', use: 'URI publication in DNS' },
        { type: 'KX', description: 'Key exchange host record.', use: 'Legacy key exchange scenarios' },
        { type: 'RT', description: 'Route-through host for X.400 gatewaying.', use: 'Legacy routing metadata' },
        { type: 'PX', description: 'RFC822 to X.400 mapping information.', use: 'Legacy mail interop' },
      ],
      securityTypes: [
        { type: 'DNSKEY', description: 'Public keys for DNSSEC-signed zones.', use: 'Zone signing trust anchors' },
        { type: 'DS', description: 'Delegation signer digest linking child to parent.', use: 'DNSSEC chain of trust' },
        { type: 'RRSIG', description: 'Cryptographic signature over RRsets.', use: 'DNSSEC validation' },
        { type: 'NSEC', description: 'Authenticated denial of existence (DNSSEC).', use: 'Proves missing names/types' },
        { type: 'NSEC3', description: 'Hashed authenticated denial of existence.', use: 'DNSSEC denial with hashing' },
        { type: 'NSEC3PARAM', description: 'NSEC3 hashing parameters for zone.', use: 'NSEC3 operation metadata' },
        { type: 'CDS', description: 'Child DS suggestion published by child zone.', use: 'Automated DS updates' },
        { type: 'CDNSKEY', description: 'Child DNSKEY publication for parent sync.', use: 'Automated DNSSEC rollover flow' },
        { type: 'CSYNC', description: 'Child-to-parent synchronization hints.', use: 'Delegation data sync automation' },
        { type: 'TLSA', description: 'TLS certificate association record (DANE).', use: 'Bind TLS certs/keys to names' },
        { type: 'SMIMEA', description: 'S/MIME cert association (DANE profile).', use: 'Email cert binding' },
        { type: 'OPENPGPKEY', description: 'OpenPGP public key in DNS.', use: 'Publish PGP keys by email hash' },
        { type: 'SSHFP', description: 'SSH host key fingerprints.', use: 'SSH host key verification' },
        { type: 'IPSECKEY', description: 'IPsec keying material pointer/data.', use: 'IPsec gateway key discovery' },
        { type: 'CERT', description: 'Certificate or CRL storage in DNS.', use: 'Legacy certificate publication' },
        { type: 'DHCID', description: 'DHCP identity association data.', use: 'DHCP/DNS conflict resolution' },
        { type: 'ZONEMD', description: 'Digest for entire DNS zone content.', use: 'Zone integrity verification' },
      ],
      additionalTypes: [
        { type: 'HINFO', description: 'Host CPU/OS information.', use: 'Rare legacy host metadata' },
        { type: 'LOC', description: 'Geographic location of a host/domain.', use: 'Latitude/longitude publication' },
        { type: 'RP', description: 'Responsible person contact for a domain.', use: 'Administrative contact pointer' },
        { type: 'AFSDB', description: 'Andrew File System / DCE server location.', use: 'Legacy directory services' },
        { type: 'X25', description: 'X.25 PSDN address.', use: 'Legacy networking environments' },
        { type: 'ISDN', description: 'ISDN address and subaddress.', use: 'Legacy telephony integration' },
        { type: 'NSAP', description: 'NSAP network address.', use: 'OSI networking legacy' },
        { type: 'NSAP-PTR', description: 'Pointer for NSAP addresses.', use: 'OSI reverse mappings' },
        { type: 'GPOS', description: 'Geographical position (older LOC alternative).', use: 'Deprecated location format' },
        { type: 'APL', description: 'Address prefix list.', use: 'Prefix matching policy data' },
        { type: 'EUI48', description: '48-bit MAC-like identifier.', use: 'Hardware identifier publication' },
        { type: 'EUI64', description: '64-bit identifier.', use: 'Interface/device identifiers' },
        { type: 'NID', description: 'Node identifier for ILNP.', use: 'ILNP experiments' },
        { type: 'L32', description: 'Locator 32-bit for ILNP.', use: 'ILNP experiments' },
        { type: 'L64', description: 'Locator 64-bit for ILNP.', use: 'ILNP experiments' },
        { type: 'LP', description: 'Locator pointer for ILNP.', use: 'ILNP experiments' },
        { type: 'TALINK', description: 'Trust anchor link (historic DNSSEC draft).', use: 'Rare/obsolete deployments' },
        { type: 'SPF', description: 'Legacy SPF RR type (superseded by TXT).', use: 'Historical SPF publication' },
        { type: 'KEY', description: 'Legacy key record (older DNSSEC/PKIX usage).', use: 'Obsolete in modern DNSSEC' },
        { type: 'SIG', description: 'Legacy signature record (pre-RRSIG).', use: 'Obsolete DNSSEC format' },
        { type: 'NXT', description: 'Legacy authenticated denial record.', use: 'Obsolete, replaced by NSEC/NSEC3' },
        { type: 'A6', description: 'Experimental IPv6 address record.', use: 'Obsolete IPv6 scheme' },
        { type: 'WKS', description: 'Well-known service bitmap at an address.', use: 'Legacy service advertisement' },
        { type: 'MB', description: 'Mailbox domain name.', use: 'Historic mail records' },
        { type: 'MG', description: 'Mail group member.', use: 'Historic mail records' },
        { type: 'MR', description: 'Mail rename domain.', use: 'Historic mail records' },
        { type: 'MINFO', description: 'Mailbox or mail list information.', use: 'Historic mail metadata' },
        { type: 'NULL', description: 'Experimental null RR for arbitrary data.', use: 'Testing/experimental only' },
        { type: 'MD', description: 'Mail destination host (obsolete).', use: 'Historic only' },
        { type: 'MF', description: 'Mail forwarder host (obsolete).', use: 'Historic only' },
      ],
      specialTypes: [
        { type: 'AXFR', description: 'Full zone transfer operation.', use: 'Authoritative server replication' },
        { type: 'IXFR', description: 'Incremental zone transfer operation.', use: 'Efficient authoritative replication' },
        { type: 'ANY', description: 'Meta-query for multiple record types (often limited).', use: 'Diagnostics/troubleshooting' },
        { type: 'OPT', description: 'EDNS pseudo-record for extended DNS signaling.', use: 'Larger UDP payloads and options' },
        { type: 'TSIG', description: 'Transaction signatures for DNS messages.', use: 'Authenticated updates/transfers' },
        { type: 'TKEY', description: 'Key establishment for DNS transaction security.', use: 'Negotiating shared keys' },
      ],
      rfcByType: {
        'A': ['1035'], 'NS': ['1035'], 'SOA': ['1035'], 'PTR': ['1035'], 'MX': ['1035'], 'TXT': ['1035'], 'HINFO': ['1035'], 'NULL': ['1035'], 'WKS': ['1035'], 'MB': ['1035'], 'MG': ['1035'], 'MR': ['1035'], 'MINFO': ['1035'], 'MD': ['1035'], 'MF': ['1035'],
        'AAAA': ['3596'], 'CNAME': ['1034', '1035'], 'DNAME': ['6672'], 'CAA': ['8659'],
        'SRV': ['2782'], 'NAPTR': ['3403'], 'SVCB': ['9460'], 'HTTPS': ['9460'], 'URI': ['7553'], 'KX': ['2230'], 'RT': ['1183'], 'PX': ['2163'],
        'DNSKEY': ['4034'], 'DS': ['4034'], 'RRSIG': ['4034'], 'NSEC': ['4034'], 'NSEC3': ['5155'], 'NSEC3PARAM': ['5155'], 'CDS': ['7344'], 'CDNSKEY': ['7344'], 'CSYNC': ['7477'], 'TLSA': ['6698'], 'SMIMEA': ['8162'], 'OPENPGPKEY': ['7929'], 'SSHFP': ['4255'], 'IPSECKEY': ['4025'], 'CERT': ['4398'], 'DHCID': ['4701'], 'ZONEMD': ['8976'],
        'LOC': ['1876'], 'RP': ['1183'], 'AFSDB': ['1183'], 'X25': ['1183'], 'ISDN': ['1183'], 'NSAP': ['1706'], 'NSAP-PTR': ['1348'], 'GPOS': ['1712'], 'APL': ['3123'], 'EUI48': ['7043'], 'EUI64': ['7043'], 'NID': ['6742'], 'L32': ['6742'], 'L64': ['6742'], 'LP': ['6742'], 'SPF': ['4408'], 'KEY': ['2535'], 'SIG': ['2535'], 'NXT': ['2535'], 'A6': ['2874'],
        'AXFR': ['1035'], 'IXFR': ['1995'], 'ANY': ['1035', '8482'], 'OPT': ['6891'], 'TSIG': ['2845'], 'TKEY': ['2930'],
      },
    }
  },
  methods: {
    rfcLinks(type) {
      const refs = this.rfcByType[type] || []
      return refs.map((code) => ({
        code,
        label: `RFC ${code}`,
        url: `https://www.rfc-editor.org/rfc/rfc${code}.html`,
      }))
    },
  },
})
</script>

<style>
.dns-help { display: grid; gap: 0.8rem; }
.dns-help .block { margin-bottom: 0; }
.dns-help h2 { margin-top: 0; margin-bottom: 0.5rem; }
.dns-help h3 { margin-top: 0; margin-bottom: 0.45rem; }
.dns-help p { margin: 0.4rem 0; color: #334155; }
.dns-help .table-wrap { overflow-x: auto; border: 1px solid #e2e8f0; border-radius: 8px; }
.dns-help table { width: 100%; border-collapse: collapse; font-size: 0.84rem; }
.dns-help th, .dns-help td { text-align: left; padding: 0.52rem 0.58rem; border-bottom: 1px solid #f1f5f9; vertical-align: top; }
.dns-help th { background: #f8fafc; color: #334155; font-weight: 600; }
.dns-help code { background: #f8fafc; border-radius: 4px; padding: 0.08rem 0.28rem; }
.dns-help .rfc-links { display: inline-flex; gap: 0.4rem; flex-wrap: wrap; }
.dns-help .rfc-links a { color: #1d4ed8; text-decoration: none; white-space: nowrap; }
.dns-help .rfc-links a:hover { text-decoration: underline; }
.dns-help .rfc-none { color: #64748b; font-size: 0.8rem; }
</style>
