<template id="help-mail-starttls-page">
  <div class="mail-help">
    <section class="card block">
      <h2>Mail STARTTLS Help</h2>
      <p>
        <strong>STARTTLS</strong> upgrades an SMTP connection on port 25 from plain text to TLS encryption.
        Without STARTTLS, inbound email can be read or modified in transit.
      </p>
    </section>

    <section class="card block">
      <h3>What To Configure</h3>
      <ul>
        <li>Enable STARTTLS on every public MX host on TCP/25</li>
        <li>Install a valid certificate chain for each MX host</li>
        <li>Offer modern TLS versions and ciphers</li>
        <li>Avoid fallback to cleartext-only mail paths</li>
      </ul>
    </section>

    <section class="card block">
      <h3>Quick Validation</h3>
      <pre>openssl s_client -starttls smtp -connect mail.example.com:25 -servername mail.example.com</pre>
      <p>
        Check that the server responds with <code>220</code> to <code>STARTTLS</code> and completes a TLS handshake.
      </p>
    </section>

    <section class="card block">
      <h3>Recommended Next Step</h3>
      <p>
        After STARTTLS works, publish DANE and/or MTA-STS so sending servers can enforce encrypted delivery.
      </p>
    </section>
  </div>
</template>

<script>
app.component("help-mail-starttls-page", { template: "#help-mail-starttls-page" })
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
