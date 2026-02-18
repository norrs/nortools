<template id="help-mta-sts-dns-page">
  <div class="mta-help">
    <section class="card block">
      <h2>MTA-STS DNS Help</h2>
      <p>
        <strong>MTA-STS</strong> (<em>Mail Transfer Agent Strict Transport Security</em>) is a standard that tells sending
        mail servers they should use encrypted SMTP transport (TLS) and only deliver to approved MX servers.
      </p>
      <p>
        In plain terms: it helps prevent attackers from downgrading email transport to insecure delivery,
        or redirecting mail to unauthorized servers.
      </p>
    </section>

    <section class="card block">
      <h3>Why You Create It</h3>
      <ul>
        <li>Protect inbound email against STARTTLS downgrade attacks</li>
        <li>Ensure mail is delivered only to expected MX destinations</li>
        <li>Give other mail systems a machine-readable transport security policy</li>
        <li>Work with TLS-RPT reporting so you can monitor failures safely</li>
      </ul>
    </section>

    <section class="card block">
      <h3>Required DNS Record</h3>
      <pre>_mta-sts.example.com. IN TXT "v=STSv1; id=20260218T000000"</pre>
      <p>
        Update the <code>id</code> every time your MTA-STS policy file changes. Sending servers use this to detect updates.
      </p>
    </section>

    <section class="card block">
      <h3>Policy File Requirement</h3>
      <p>
        DNS alone is not enough. You must also host the policy file over HTTPS at:
      </p>
      <pre>https://mta-sts.&lt;your-domain&gt;/.well-known/mta-sts.txt</pre>
      <p>A typical starting policy:</p>
      <pre>version: STSv1
mode: testing
mx: mail.example.com
max_age: 86400</pre>
    </section>

    <section class="card block">
      <h3>Checks To Pass Before Enabling Enforce</h3>
      <ul>
        <li>All MX hosts support STARTTLS on port 25</li>
        <li>MX certificates are valid and trusted by standard public CAs</li>
        <li>Certificate names match MX hostnames used in policy</li>
        <li>Policy file is reachable via HTTPS with a valid web certificate</li>
        <li>TLS-RPT is configured so you can observe failures during rollout</li>
      </ul>
    </section>

    <section class="card block">
      <h3>Recommended Rollout</h3>
      <ol>
        <li>Publish DNS TXT + HTTPS policy in <code>mode: testing</code></li>
        <li>Monitor TLS-RPT reports and fix delivery/cert issues</li>
        <li>Move to <code>mode: enforce</code> when reports are clean and stable</li>
      </ol>
    </section>
  </div>
</template>

<script>
app.component("help-mta-sts-dns-page", { template: "#help-mta-sts-dns-page" })
</script>

<style>
.mta-help { display: grid; gap: 0.8rem; }
.mta-help .block { margin-bottom: 0; }
.mta-help h2 { margin-top: 0; margin-bottom: 0.5rem; }
.mta-help h3 { margin-top: 0; margin-bottom: 0.45rem; }
.mta-help p { margin: 0.4rem 0; color: #334155; }
.mta-help ul,
.mta-help ol { margin: 0.35rem 0 0.2rem 1.1rem; color: #334155; }
.mta-help li { margin: 0.25rem 0; }
.mta-help pre { margin: 0.45rem 0; background: #f8fafc; border: 1px solid #e2e8f0; color: #0f172a; }
</style>
