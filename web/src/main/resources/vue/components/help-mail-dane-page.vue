<template id="help-mail-dane-page">
  <div class="mail-help">
    <section class="card block">
      <h2>Mail DANE Help</h2>
      <p>
        <strong>DANE for SMTP</strong> uses DNSSEC-signed TLSA records to bind MX hostnames to expected TLS certificates.
        This protects against STARTTLS downgrade and rogue certificates.
      </p>
    </section>

    <section class="card block">
      <h3>Prerequisites</h3>
      <ul>
        <li>DNSSEC must be enabled on MX host zones</li>
        <li>Each MX should publish TLSA at <code>_25._tcp.&lt;mx-host&gt;</code></li>
        <li>TLSA data must match the certificate served over STARTTLS</li>
      </ul>
    </section>

    <section class="card block">
      <h3>Common TLSA Pattern</h3>
      <pre>_25._tcp.mx1.example.com. IN TLSA 3 1 1 &lt;SPKI-SHA256-HEX&gt;</pre>
      <p>
        This is usage <code>3</code> (DANE-EE), selector <code>1</code> (SPKI), matching <code>1</code> (SHA-256).
      </p>
    </section>

    <section class="card block">
      <h3>Rollover Guidance</h3>
      <ul>
        <li>Publish at least two TLSA records during certificate rotation</li>
        <li>Keep old and new certificate associations active during overlap</li>
        <li>Remove old records only after all caches have expired</li>
      </ul>
    </section>
  </div>
</template>

<script>
app.component("help-mail-dane-page", { template: "#help-mail-dane-page" })
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
