# nortools — Architecture

## Overview

nortools is a suite of 49 network/DNS CLI tools, a web portal, and a desktop application — all written in Kotlin, built entirely by Bazel, with a Vue 3 SPA frontend.

```
┌─────────────────────────────────────────────────────────┐
│                      Bazel (8.5.1)                      │
│              Single build system for everything         │
│                                                         │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────┐  │
│  │ 49 CLI   │  │ Web      │  │ Desktop  │  │ Vue    │  │
│  │ tools    │  │ Portal   │  │ App      │  │ SPA    │  │
│  │ (Kotlin) │  │ (Javalin)│  │ (Krema)  │  │ (Vite) │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └───┬────┘  │
│       │              │              │             │      │
│       └──────┬───────┘              │             │      │
│              │                      │             │      │
│       ┌──────┴──────┐               │             │      │
│       │ Shared libs │               │             │      │
│       │ dns,network │               │             │      │
│       │ cli,output  │               │             │      │
│       └─────────────┘               │             │      │
│                                     │             │      │
│              ┌──────────────────────┘             │      │
│              │ depends on web_lib + krema-core    │      │
│              │ + frontend:build (data dep)        │      │
│              └───────────────────────────────────-┘      │
└─────────────────────────────────────────────────────────┘
```

## Bazel Build System

Bazel builds **everything** — Kotlin, JavaScript, npm dependencies. There are no separate `npm build` or `pnpm build` commands outside of Bazel.

### Module Dependencies (`MODULE.bazel`)

| Bazel Module | Version | Purpose |
|---|---|---|
| `rules_java` | 9.5.0 | Java 25 toolchain |
| `rules_kotlin` | 2.1.10 | Kotlin compilation (`kt_jvm_library`, `kt_jvm_binary`, `kt_jvm_test`) |
| `rules_jvm_external` | 6.10 | Maven dependency resolution |
| `aspect_rules_js` | 2.9.2 | JavaScript/npm/pnpm integration |
| `rules_nodejs` | 6.7.3 | Node.js 22.22.0 toolchain |
| `bazel_skylib` | 1.9.0 | Bazel utility rules |

### How the Frontend Build Works

pnpm manages npm packages. The lockfile (`pnpm-lock.yaml`) is checked in and Bazel's `npm_translate_lock` reads it to create a hermetic npm dependency graph. Vite is invoked as a Bazel action:

```
pnpm-lock.yaml ──► npm_translate_lock ──► npm_link_all_packages
                                                  │
frontend/src/**/*.vue,ts ──► copy_to_bin ─────────┤
                                                  ▼
                                          vite_bin.vite("build")
                                                  │
                                                  ▼
                                          frontend/dist/  (output)
```

The `//frontend:build` target produces a `dist/` directory containing the compiled Vue SPA. Both `//web:web` and `//desktop:desktop` declare this as a `data` dependency, making it available in their Bazel runfiles at runtime.

### Key Bazel Targets

| Target | Type | Description |
|---|---|---|
| `//lib/dns` | `kt_jvm_library` | DNS resolver library (dnsjava wrapper) |
| `//lib/network` | `kt_jvm_library` | HTTP/TCP client library |
| `//lib/cli` | `kt_jvm_library` | CLI base command (Clikt) |
| `//lib/output` | `kt_jvm_library` | Output formatting (table, JSON, detail) |
| `//frontend:build` | `vite_bin.vite` | Vue SPA → `dist/` |
| `//web:web_lib` | `kt_jvm_library` | Javalin server + API handlers |
| `//web:web` | `kt_jvm_binary` | Standalone web server |
| `//desktop:desktop` | `kt_jvm_binary` | Desktop app (Krema + embedded Javalin) |
| `//tools/dns/a` | `kt_jvm_binary` | Example: A record lookup CLI tool |

### Toolchain

- **Java 25** (Azul Zulu) — configured in `.bazelrc` via `--java_language_version=25` and `--java_runtime_version=remotejdk_25`
- **Node.js 22.22.0** — Bazel-managed toolchain, no system Node required
- **pnpm 10.29.3** — managed locally via mise, lockfile consumed by Bazel
- **Bazel 8.5.1** — pinned in `.bazelversion`, run via Bazelisk (also managed by mise)

---

## Javalin Web Server

[Javalin](https://javalin.io/) 6.7.0 is the HTTP server for both the web portal and the desktop app. A single reusable `startServer()` function in `WebPortal.kt` configures everything:

```kotlin
fun startServer(frontendMode: FrontendMode, port: Int = 7070): Javalin
```

**What it does:**
1. Locates the Vue SPA `dist/` directory from Bazel runfiles (`findDistDir()`)
2. Configures Javalin to serve static files from that directory
3. Sets up SPA fallback so vue-router handles client-side routing
4. Registers all `/api/*` routes (23 endpoints covering DNS, email, network, WHOIS, blocklist, utility, composite, and generator tools)
5. Returns the started Javalin instance

**Static file serving:** The Vue `dist/` directory is served as external static files. Bazel runfiles use symlinks, so `findDistDir()` resolves them via `.canonicalFile` to prevent Jetty (Javalin's embedded server) from rejecting alias references.

### API Architecture

All tool logic lives in `ApiHandlers.kt` as top-level handler functions. Each handler:
- Receives a Javalin `Context` and `Gson` instance
- Extracts path/query parameters
- Uses the shared `//lib/dns` and `//lib/network` libraries
- Returns JSON via `ctx.json()`

The Vue SPA calls these endpoints through `frontend/src/api/client.ts`, which provides typed wrapper functions for every API route.

---

## Krema Desktop App

[Krema](https://github.com/krema-build/krema) (v0.3.0) is "Tauri for Java" — it wraps the system's native webview (WebKit on Linux/macOS, WebView2 on Windows) into a desktop window, avoiding bundling a full browser engine.

### How It Works

The desktop app does **not** use Krema's IPC command system. Instead, it starts an embedded Javalin server and points the Krema webview at localhost:

```
┌──────────────────────────────────────────┐
│              Desktop App Process          │
│                                          │
│  ┌────────────────────────────────────┐  │
│  │         Krema Webview Window       │  │
│  │                                    │  │
│  │   Points at http://localhost:PORT  │  │
│  │                                    │  │
│  │   ┌────────────────────────────┐   │  │
│  │   │        Vue SPA             │   │  │
│  │   │  (same as web version)     │   │  │
│  │   └────────────┬───────────────┘   │  │
│  └────────────────┼───────────────────┘  │
│                   │ HTTP (fetch)          │
│  ┌────────────────▼───────────────────┐  │
│  │     Embedded Javalin Server        │  │
│  │     (port 0 = random port)         │  │
│  │                                    │  │
│  │     startServer(port = 0)          │  │
│  │     reuses WebPortal.kt logic      │  │
│  └────────────────────────────────────┘  │
└──────────────────────────────────────────┘
```

### Desktop Entry Point (`KremaApp.kt`)

```kotlin
fun main(args: Array<String>) {
    val server = startServer(port = 0)          // random port
    val serverUrl = "http://localhost:${server.port()}"

    val app = Krema.app()
        .title("NorTools")
        .size(1200, 800)
        .devUrl(serverUrl)                      // point webview here

    app.run()                                   // blocks until window closes
    server.stop()                               // clean shutdown
}
```

### Why Embedded Server Instead of Krema IPC

- **One codebase** — the Vue SPA uses the same HTTP `fetch()` calls in both web and desktop mode. No dual-mode client code needed.
- **Full API parity** — every tool available on the web is automatically available on desktop.
- **Simpler architecture** — no need to register Krema IPC commands for each tool, no serialization layer between frontend and backend.

### Bazel Integration

The `//desktop:desktop` target depends on:
- `//web:web_lib` — the Javalin server and all API handlers (transitively pulls in `//lib/dns`, `//lib/network`, dnsjava, Gson, etc.)
- `@maven//:build_krema_krema_core` — Krema's webview wrapper
- `//frontend:build` (as `data`) — the compiled Vue SPA dist files

JVM flags include `--enable-native-access=ALL-UNNAMED` for Krema's native webview FFI.

### Running

```bash
# Web portal (standalone server on port 7070)
bazelisk run //web:web

# Desktop app (embedded server on random port + native window)
bazelisk run //desktop:desktop

# Desktop app with devtools
bazelisk run //desktop:desktop -- --dev
```

---

## Project Structure

```
nortools/
├── MODULE.bazel              # Bazel module: all deps (Kotlin, Maven, JS, Node)
├── .bazelrc                  # Java 25 toolchain config
├── .bazelversion             # Bazel 8.5.1
├── krema.toml                # Krema desktop app config
├── pnpm-lock.yaml            # npm lockfile (consumed by Bazel)
├── pnpm-workspace.yaml       # pnpm workspace: [frontend]
│
├── lib/                      # Shared Kotlin libraries
│   ├── dns/                  #   DNS resolver (dnsjava wrapper)
│   ├── network/              #   HTTP/TCP clients
│   ├── cli/                  #   Clikt base command
│   └── output/               #   Output formatting
│
├── tools/                    # 49 CLI tools (each a kt_jvm_binary)
│   ├── dns/                  #   a, aaaa, cname, mx, ns, ptr, soa, srv, txt
│   ├── dnssec/               #   dnskey, ds, rrsig, nsec, nsec3param
│   ├── email/                #   spf, dkim, dmarc, bimi, mta-sts, tlsrpt, smtp,
│   │                         #   header-analyzer, spf-generator, dmarc-generator
│   ├── network/              #   tcp, http, https, ping, trace
│   ├── whois/                #   whois, arin, asn
│   ├── blocklist/            #   blacklist, blocklist, cert, loc, ipseckey
│   ├── util/                 #   whatismyip, subnet-calc, password-gen,
│   │                         #   email-extract, dns-propagation, dns-health
│   └── composite/            #   domain-health, deliverability, compliance,
│                             #   dmarc-report, bulk, mailflow
│
├── web/                      # Javalin web portal
│   └── src/main/kotlin/
│       └── WebPortal.kt      #   startServer(), findDistDir(), route registration
│       └── ApiHandlers.kt    #   All 23 API handler functions
│
├── desktop/                  # Krema desktop app
│   └── src/main/kotlin/
│       └── KremaApp.kt       #   Starts embedded Javalin + Krema webview
│
└── frontend/                 # Vue 3 SPA (built by Vite via Bazel)
    ├── BUILD.bazel           #   vite_bin.vite("build") target
    ├── vite.config.ts
    ├── package.json
    └── src/
        ├── api/client.ts     #   Typed HTTP client for all API endpoints
        ├── components/       #   Sidebar.vue
        ├── router/           #   vue-router with 21 routes
        └── views/            #   22 Vue views (Home + 21 tools)
```
