<template id="help-mail-tls-page">
  <div class="mail-help">
    <section class="card block">
      <h2>Mail TLS Configuration Help</h2>
      <p>
        This guide covers TLS settings behind checks such as TLS version, cipher quality, key exchange,
        TLS compression, renegotiation, and 0-RTT behavior.
      </p>
    </section>

    <section class="card block">
      <h3>Baseline TLS Policy</h3>
      <ul>
        <li>Prefer TLS 1.3 and keep TLS 1.2 enabled for compatibility</li>
        <li>Disable SSLv2/SSLv3/TLSv1.0/TLSv1.1 where possible</li>
        <li>Use AEAD ciphers (AES-GCM, CHACHA20-POLY1305)</li>
        <li>Prefer ECDHE for forward secrecy</li>
      </ul>
    </section>

    <section class="card block">
      <h3>Hardening Settings</h3>
      <ul>
        <li>Disable TLS-level compression</li>
        <li>Disable client-initiated renegotiation</li>
        <li>Disable weak ciphers and legacy hash algorithms</li>
        <li>Review 0-RTT support carefully before enabling TLS 1.3 early data</li>
      </ul>
    </section>

    <section class="card block">
      <h3>Quick Checks</h3>
      <pre>openssl s_client -starttls smtp -connect mail.example.com:25 -tls1_2
openssl s_client -starttls smtp -connect mail.example.com:25 -tls1_3</pre>
      <p>
        Test which TLS versions and ciphers are negotiated from an external sender perspective.
      </p>
    </section>
  </div>
</template>

<script>
app.component("help-mail-tls-page", { template: "#help-mail-tls-page" })
</script>

<style>
.mail-help { display: grid; gap: 0.8rem; }
.mail-help .block { margin-bottom: 0; }
.mail-help h2 { margin-top: 0; margin-bottom: 0.5rem; }
.mail-help h3 { margin-top: 0; margin-bottom: 0.45rem; }
.mail-help p { margin: 0.4rem 0; color: #334155; }
.mail-help ul { margin: 0.35rem 0 0.2rem 1.1rem; color: #334155; }
.mail-help li { margin: 0.25rem 0; }
.mail-help pre { margin: 0.45rem 0; background: #f8fafc; border: 1px solid #e2e8f0; color: #0f172a; }
</style>
