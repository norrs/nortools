import { createRouter, createWebHistory } from 'vue-router';
import HomeView from '../views/HomeView.vue';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    // DNS
    { path: '/dns', name: 'dns', component: () => import('../views/DnsLookup.vue') },
    { path: '/dnssec', name: 'dnssec', component: () => import('../views/DnssecLookup.vue') },
    { path: '/reverse-dns', name: 'reverse-dns', component: () => import('../views/ReverseDns.vue') },
    // Email Auth
    { path: '/spf', name: 'spf', component: () => import('../views/SpfLookup.vue') },
    { path: '/dkim', name: 'dkim', component: () => import('../views/DkimLookup.vue') },
    { path: '/dmarc', name: 'dmarc', component: () => import('../views/DmarcLookup.vue') },
    // Network
    { path: '/tcp', name: 'tcp', component: () => import('../views/TcpCheck.vue') },
    { path: '/http', name: 'http', component: () => import('../views/HttpCheck.vue') },
    { path: '/https', name: 'https', component: () => import('../views/HttpsCheck.vue') },
    { path: '/ping', name: 'ping', component: () => import('../views/PingCheck.vue') },
    { path: '/traceroute', name: 'traceroute', component: () => import('../views/Traceroute.vue') },
    // WHOIS
    { path: '/whois', name: 'whois', component: () => import('../views/WhoisLookup.vue') },
    // Blocklist
    { path: '/blacklist', name: 'blacklist', component: () => import('../views/BlacklistCheck.vue') },
    // Utility
    { path: '/whatismyip', name: 'whatismyip', component: () => import('../views/WhatIsMyIp.vue') },
    { path: '/subnet', name: 'subnet', component: () => import('../views/SubnetCalc.vue') },
    { path: '/password', name: 'password', component: () => import('../views/PasswordGen.vue') },
    { path: '/email-extract', name: 'email-extract', component: () => import('../views/EmailExtract.vue') },
    // Generators
    { path: '/spf-generator', name: 'spf-generator', component: () => import('../views/SpfGenerator.vue') },
    { path: '/dmarc-generator', name: 'dmarc-generator', component: () => import('../views/DmarcGenerator.vue') },
    // Composite
    { path: '/dns-health', name: 'dns-health', component: () => import('../views/DnsHealth.vue') },
    { path: '/domain-health', name: 'domain-health', component: () => import('../views/DomainHealth.vue') },
  ],
});

export default router;

