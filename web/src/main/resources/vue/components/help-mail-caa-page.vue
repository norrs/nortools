<template id="help-mail-caa-page">
  <div class="mail-help">
    <section class="card block">
      <h2>CAA For Mail Server Help</h2>
      <p>
        <strong>CAA</strong> records define which certificate authorities are allowed to issue certificates for a host.
        Publishing CAA on MX hostnames reduces certificate mis-issuance risk.
      </p>
    </section>

    <section class="card block">
      <h3>Example Records</h3>
      <pre>mx1.example.com. IN CAA 0 issue "letsencrypt.org"
mx1.example.com. IN CAA 0 iodef "mailto:security@example.com"</pre>
      <p>
        Repeat for each MX hostname that presents a public certificate.
      </p>
    </section>

    <section class="card block">
      <h3>Practical Tips</h3>
      <ul>
        <li>Use host-level CAA records on each MX if possible</li>
        <li>Keep allowed CAs minimal</li>
        <li>Set an <code>iodef</code> contact for issuance incident reports</li>
      </ul>
    </section>
  </div>
</template>

<script>
app.component("help-mail-caa-page", { template: "#help-mail-caa-page" })
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
