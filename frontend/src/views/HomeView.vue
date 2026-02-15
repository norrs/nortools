<script setup lang="ts">
</script>

<template>
  <div class="home">
    <h1>NorTools</h1>
    <p class="subtitle">Network diagnostic and DNS tools</p>

    <div class="tool-cards">
      <RouterLink to="/dns" class="card">
        <h3>🔍 DNS Lookup</h3>
        <p>Query DNS records for any domain</p>
      </RouterLink>
      <RouterLink to="/dnssec" class="card">
        <h3>🔐 DNSSEC Lookup</h3>
        <p>Query DNSSEC-specific records</p>
      </RouterLink>
      <RouterLink to="/reverse-dns" class="card">
        <h3>🔄 Reverse DNS</h3>
        <p>Find hostname for an IP address</p>
      </RouterLink>
      <RouterLink to="/spf" class="card">
        <h3>📧 SPF Lookup</h3>
        <p>Check SPF records for a domain</p>
      </RouterLink>
      <RouterLink to="/dkim" class="card">
        <h3>🔑 DKIM Lookup</h3>
        <p>Look up DKIM public key records</p>
      </RouterLink>
      <RouterLink to="/dmarc" class="card">
        <h3>🛡️ DMARC Lookup</h3>
        <p>Check DMARC policy for a domain</p>
      </RouterLink>
      <RouterLink to="/tcp" class="card">
        <h3>🔌 TCP Port Check</h3>
        <p>Test TCP connectivity to a host</p>
      </RouterLink>
      <RouterLink to="/http" class="card">
        <h3>🌐 HTTP Check</h3>
        <p>Check HTTP response from a URL</p>
      </RouterLink>
      <RouterLink to="/https" class="card">
        <h3>🔒 HTTPS / SSL</h3>
        <p>Check HTTPS and SSL/TLS details</p>
      </RouterLink>
      <RouterLink to="/ping" class="card">
        <h3>📡 Ping</h3>
        <p>Ping a host to check reachability</p>
      </RouterLink>
      <RouterLink to="/traceroute" class="card">
        <h3>🗺️ Traceroute</h3>
        <p>Visual network path tracing</p>
      </RouterLink>
      <RouterLink to="/whois" class="card">
        <h3>📋 WHOIS Lookup</h3>
        <p>Domain or IP registration info</p>
      </RouterLink>
      <RouterLink to="/blacklist" class="card">
        <h3>🚫 Blacklist Check</h3>
        <p>Check if an IP is blacklisted</p>
      </RouterLink>
      <RouterLink to="/whatismyip" class="card">
        <h3>🏠 What Is My IP</h3>
        <p>Detect your public IP address</p>
      </RouterLink>
      <RouterLink to="/subnet" class="card">
        <h3>🧮 Subnet Calculator</h3>
        <p>Calculate subnet details from CIDR</p>
      </RouterLink>
      <RouterLink to="/password" class="card">
        <h3>🔐 Password Generator</h3>
        <p>Generate secure random passwords</p>
      </RouterLink>
      <RouterLink to="/email-extract" class="card">
        <h3>✉️ Email Extractor</h3>
        <p>Extract email addresses from text</p>
      </RouterLink>
      <RouterLink to="/spf-generator" class="card">
        <h3>⚙️ SPF Generator</h3>
        <p>Build an SPF record from components</p>
      </RouterLink>
      <RouterLink to="/dmarc-generator" class="card">
        <h3>⚙️ DMARC Generator</h3>
        <p>Build a DMARC record from options</p>
      </RouterLink>
      <RouterLink to="/dns-health" class="card">
        <h3>🏥 DNS Health</h3>
        <p>Comprehensive DNS health analysis</p>
      </RouterLink>
      <RouterLink to="/domain-health" class="card">
        <h3>💊 Domain Health</h3>
        <p>Full domain health check</p>
      </RouterLink>
    </div>
  </div>
</template>

<style scoped>
.home {
  padding: 2rem 0;
}

h1 {
  font-size: 2rem;
  color: #1a1a2e;
}

.subtitle {
  color: #666;
  margin-bottom: 2rem;
}

.tool-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1rem;
}

.card {
  background: white;
  border-radius: 8px;
  padding: 1.5rem;
  text-decoration: none;
  color: inherit;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
  transition: box-shadow 0.2s;
}

.card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.card h3 {
  color: #1a1a2e;
  margin-bottom: 0.5rem;
}

.card p {
  color: #666;
  font-size: 0.9rem;
}
</style>

