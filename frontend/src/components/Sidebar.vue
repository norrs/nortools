<script setup lang="ts">
import { RouterLink } from 'vue-router';

const toolGroups = [
  {
    name: 'DNS',
    tools: [
      { name: 'DNS Lookup', path: '/dns' },
      { name: 'DNSSEC Lookup', path: '/dnssec' },
      { name: 'Reverse DNS', path: '/reverse-dns' },
    ],
  },
  {
    name: 'Email Auth',
    tools: [
      { name: 'SPF Lookup', path: '/spf' },
      { name: 'DKIM Lookup', path: '/dkim' },
      { name: 'DMARC Lookup', path: '/dmarc' },
    ],
  },
  {
    name: 'Network',
    tools: [
      { name: 'TCP Port Check', path: '/tcp' },
      { name: 'HTTP Check', path: '/http' },
      { name: 'HTTPS / SSL', path: '/https' },
      { name: 'Ping', path: '/ping' },
      { name: 'Traceroute', path: '/traceroute' },
    ],
  },
  {
    name: 'WHOIS',
    tools: [
      { name: 'WHOIS Lookup', path: '/whois' },
    ],
  },
  {
    name: 'Blocklist',
    tools: [
      { name: 'Blacklist Check', path: '/blacklist' },
    ],
  },
  {
    name: 'Utility',
    tools: [
      { name: 'What Is My IP', path: '/whatismyip' },
      { name: 'Subnet Calculator', path: '/subnet' },
      { name: 'Password Generator', path: '/password' },
      { name: 'Email Extractor', path: '/email-extract' },
    ],
  },
  {
    name: 'Generators',
    tools: [
      { name: 'SPF Generator', path: '/spf-generator' },
      { name: 'DMARC Generator', path: '/dmarc-generator' },
    ],
  },
  {
    name: 'Composite',
    tools: [
      { name: 'DNS Health', path: '/dns-health' },
      { name: 'Domain Health', path: '/domain-health' },
    ],
  },
  {
    name: 'Info',
    tools: [
      { name: 'About', path: '/about' },
    ],
  },
];
</script>

<template>
  <nav class="sidebar">
    <RouterLink to="/" class="logo">
      <h1>NorTools</h1>
    </RouterLink>
    <div v-for="group in toolGroups" :key="group.name" class="tool-group">
      <h3>{{ group.name }}</h3>
      <ul>
        <li v-for="tool in group.tools" :key="tool.path">
          <RouterLink :to="tool.path">{{ tool.name }}</RouterLink>
        </li>
      </ul>
    </div>
  </nav>
</template>

<style scoped>
.sidebar {
  width: 240px;
  background: #1a1a2e;
  color: #eee;
  padding: 1rem;
  min-height: 100vh;
}

.logo {
  text-decoration: none;
  color: #4fc3f7;
}

.logo h1 {
  font-size: 1.4rem;
  margin-bottom: 1.5rem;
}

.tool-group {
  margin-bottom: 1.5rem;
}

.tool-group h3 {
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: #888;
  margin-bottom: 0.5rem;
}

.tool-group ul {
  list-style: none;
}

.tool-group li a {
  display: block;
  padding: 0.4rem 0.5rem;
  color: #ccc;
  text-decoration: none;
  border-radius: 4px;
  font-size: 0.9rem;
}

.tool-group li a:hover,
.tool-group li a.router-link-active {
  background: rgba(255, 255, 255, 0.1);
  color: #4fc3f7;
}
</style>

