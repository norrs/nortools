<template id="help-mail-certificate-page">
  <div class="mail-help">
    <section class="card block">
      <h2>Mail Certificate Help</h2>
      <p>
        This article covers certificate trust, key strength, signature algorithm, and certificate name matching
        for SMTP STARTTLS on MX hosts.
      </p>
    </section>

    <section class="card block">
      <h3>Certificate Requirements</h3>
      <ul>
        <li>Serve a complete certificate chain trusted by common mail senders</li>
        <li>Use RSA 2048+ or modern EC/EdDSA key types</li>
        <li>Avoid SHA-1/MD5 signatures</li>
        <li>Include MX hostnames in SAN entries</li>
      </ul>
    </section>

    <section class="card block">
      <h3>MX Hostname Matching</h3>
      <p>
        The certificate should match each MX hostname (for example <code>mx1.example.com</code>).
        Wildcards can help, but explicit SAN names are clearer and safer.
      </p>
    </section>

    <section class="card block">
      <h3>Validation Command</h3>
      <pre>openssl s_client -starttls smtp -connect mx1.example.com:25 -servername mx1.example.com -showcerts</pre>
      <p>
        Confirm chain completeness, signature algorithm, key type, and SAN coverage.
      </p>
    </section>
  </div>
</template>

<script>
app.component("help-mail-certificate-page", { template: "#help-mail-certificate-page" })
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
