export interface ToolLink {
  name: string;
  path: string;
  icon?: string;
  description?: string;
}

export interface ToolGroup {
  name: string;
  tools: ToolLink[];
}

export const toolGroups: ToolGroup[] = [
  {
    name: 'DNS',
    tools: [
      { name: 'DNS Lookup', path: '/dns', icon: '🔍', description: 'Query DNS records for any domain' },
      { name: 'DNSSEC Lookup', path: '/dnssec', icon: '🔐', description: 'Query DNSSEC-specific records' },
      { name: 'Reverse DNS', path: '/reverse-dns', icon: '🔄', description: 'Find hostname for an IP address' },
    ],
  },
  {
    name: 'Email Auth',
    tools: [
      { name: 'SPF Lookup', path: '/spf', icon: '📧', description: 'Check SPF records for a domain' },
      { name: 'DKIM Lookup', path: '/dkim', icon: '🔑', description: 'Look up DKIM public key records' },
      { name: 'DMARC Lookup', path: '/dmarc', icon: '🛡️', description: 'Check DMARC policy for a domain' },
    ],
  },
  {
    name: 'Network',
    tools: [
      { name: 'TCP Port Check', path: '/tcp', icon: '🔌', description: 'Test TCP connectivity to a host' },
      { name: 'HTTP Check', path: '/http', icon: '🌐', description: 'Check HTTP response from a URL' },
      { name: 'HTTPS / SSL', path: '/https', icon: '🔒', description: 'Check HTTPS and SSL/TLS details' },
      { name: 'Ping', path: '/ping', icon: '📡', description: 'Ping a host to check reachability' },
      { name: 'Traceroute', path: '/traceroute', icon: '🗺️', description: 'Visual network path tracing' },
    ],
  },
  {
    name: 'WHOIS',
    tools: [
      { name: 'WHOIS Lookup', path: '/whois', icon: '📋', description: 'Domain or IP registration info' },
    ],
  },
  {
    name: 'Blocklist',
    tools: [
      { name: 'Blacklist Check', path: '/blacklist', icon: '🚫', description: 'Check if an IP is blacklisted' },
    ],
  },
  {
    name: 'Utility',
    tools: [
      { name: 'What Is My IP', path: '/whatismyip', icon: '🏠', description: 'Detect your public IP address' },
      { name: 'Subnet Calculator', path: '/subnet', icon: '🧮', description: 'Calculate subnet details from CIDR' },
      { name: 'Password Generator', path: '/password', icon: '🔐', description: 'Generate secure random passwords' },
      { name: 'Email Extractor', path: '/email-extract', icon: '✉️', description: 'Extract email addresses from text' },
    ],
  },
  {
    name: 'Generators',
    tools: [
      { name: 'SPF Generator', path: '/spf-generator', icon: '⚙️', description: 'Build an SPF record from components' },
      { name: 'DMARC Generator', path: '/dmarc-generator', icon: '⚙️', description: 'Build a DMARC record from options' },
    ],
  },
  {
    name: 'Composite',
    tools: [
      { name: 'DNS Health', path: '/dns-health', icon: '🏥', description: 'Comprehensive DNS health analysis' },
      { name: 'Domain Health', path: '/domain-health', icon: '💊', description: 'Full domain health check' },
    ],
  },
  {
    name: 'Info',
    tools: [
      { name: 'About', path: '/about', description: 'About this project and architecture details' },
    ],
  },
];
