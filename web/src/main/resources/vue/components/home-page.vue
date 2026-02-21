<template id="home-page">
  <div class="home-page">
    <h1>NorTools</h1>
    <p class="subtitle">Network diagnostic and DNS tools</p>

    <div v-for="group in toolGroups" :key="group.name" class="tool-group">
      <h2>{{ group.name }}</h2>
      <div class="tool-cards">
        <a v-for="tool in group.tools" :key="tool.path" :href="tool.path" class="card">
          <h3>{{ tool.icon ? `${tool.icon} ${tool.name}` : tool.name }}</h3>
          <p>{{ tool.description || 'Open tool' }}</p>
        </a>
      </div>
    </div>
  </div>
</template>

<script>
app.component("home-page", {
  template: "#home-page",
  data() {
    return {
      toolGroups: [
        {
          name: "DNS",
          tools: [
            { name: "DNS Lookup", path: "/dns", icon: "🔍", description: "Query DNS records for any domain" },
            { name: "DNSSEC Lookup", path: "/dnssec", icon: "🔐", description: "Query DNSSEC-specific records" },
            { name: "Reverse DNS", path: "/reverse-dns", icon: "🔄", description: "Find hostname for an IP address" },
          ],
        },
        {
          name: "Email Auth",
          tools: [
            { name: "SPF Lookup", path: "/spf", icon: "📧", description: "Check SPF records for a domain" },
            { name: "DKIM Lookup", path: "/dkim", icon: "🔑", description: "Look up DKIM public key records" },
            { name: "DMARC Lookup", path: "/dmarc", icon: "🛡️", description: "Check DMARC policy for a domain" },
          ],
        },
        {
          name: "Network",
          tools: [
            { name: "TCP Port Check", path: "/tcp", icon: "🔌", description: "Test TCP connectivity to a host" },
            { name: "HTTP Check", path: "/http", icon: "🌐", description: "Check HTTP response from a URL" },
            { name: "HTTPS / SSL", path: "/https", icon: "🔒", description: "Check HTTPS and SSL/TLS details" },
            { name: "Interfaces & Routing", path: "/network-interfaces", icon: "🧭", description: "Inspect interfaces, routes, and DNS settings" },
            { name: "Ping", path: "/ping", icon: "📡", description: "Ping a host to check reachability" },
            { name: "Traceroute", path: "/traceroute", icon: "🗺️", description: "Visual network path tracing" },
          ],
        },
        {
          name: "WHOIS",
          tools: [
            { name: "WHOIS Lookup", path: "/whois", icon: "📋", description: "Domain or IP registration info" },
            { name: "RPKI Route Validation", path: "/rpki-route", icon: "🛣️", description: "Validate route origin with Routinator" },
          ],
        },
        {
          name: "Blocklist",
          tools: [
            { name: "Blacklist Check", path: "/blacklist", icon: "🚫", description: "Check if an IP is blacklisted" },
          ],
        },
        {
          name: "Utility",
          tools: [
            { name: "What Is My IP", path: "/whatismyip", icon: "🏠", description: "Detect your public IP address" },
            { name: "Subnet Calculator", path: "/subnet", icon: "🧮", description: "Calculate subnet details from CIDR" },
            { name: "Password Generator", path: "/password", icon: "🔐", description: "Generate secure random passwords" },
            { name: "Email Extractor", path: "/email-extract", icon: "✉️", description: "Extract email addresses from text" },
          ],
        },
        {
          name: "Generators",
          tools: [
            { name: "SPF Generator", path: "/spf-generator", icon: "⚙️", description: "Build an SPF record from components" },
            { name: "DMARC Generator", path: "/dmarc-generator", icon: "⚙️", description: "Build a DMARC record from options" },
          ],
        },
        {
          name: "Composite",
          tools: [
            { name: "DNS Health", path: "/dns-health", icon: "🏥", description: "Comprehensive DNS health analysis" },
            { name: "Domain Health", path: "/domain-health", icon: "💊", description: "Full domain health check" },
          ],
        },
        {
          name: "Help",
          tools: [
            { name: "MTA-STS DNS Help", path: "/help/mta-sts-dns", description: "How to configure _mta-sts TXT record and policy file" },
            { name: "Mail STARTTLS Help", path: "/help/mail-starttls", description: "Enable STARTTLS safely on inbound MX servers" },
            { name: "Mail TLS Help", path: "/help/mail-tls", description: "Tune TLS versions, ciphers, renegotiation, and related settings" },
            { name: "Mail Certificate Help", path: "/help/mail-certificate", description: "Fix trust chain, key, signature, and hostname issues" },
            { name: "Mail CAA Help", path: "/help/mail-caa", description: "Publish CAA records for MX hostnames" },
            { name: "Mail DANE Help", path: "/help/mail-dane", description: "Publish and validate DNSSEC-backed SMTP TLSA records" },
          ],
        },
        {
          name: "Info",
          tools: [
            { name: "About", path: "/about", description: "About this project and architecture details" },
          ],
        },
      ],
    }
  },
})
</script>

<style>
.home-page {
  padding: 2rem 0;
}

.home-page h1 {
  font-size: 2rem;
  color: #1a1a2e;
}

.home-page .subtitle {
  color: #666;
  margin-bottom: 2rem;
}

.home-page .tool-group {
  margin-bottom: 2rem;
}

.home-page .tool-group h2 {
  font-size: 1rem;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: #475569;
  margin-bottom: 0.75rem;
}

.home-page .tool-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1rem;
}

.home-page .card {
  background: white;
  border-radius: 8px;
  padding: 1.5rem;
  text-decoration: none;
  color: inherit;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
  transition: box-shadow 0.2s;
}

.home-page .card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.home-page .card h3 {
  color: #1a1a2e;
  margin-bottom: 0.5rem;
}

.home-page .card p {
  color: #666;
  font-size: 0.9rem;
}
</style>
