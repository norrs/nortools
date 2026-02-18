# nortools Architecture

## Overview

nortools is a Kotlin/Bazel project with:

- 49 CLI tools
- a Javalin web server
- a Krema desktop app that embeds the same Javalin server

Frontend assets/templates are now owned by `web`:

- `web/src/main/resources/vue` (JavalinVue layout/components)

## Runtime Model

- Web target: `//web:web` serves API routes and UI.
- Desktop target: `//desktop:desktop` starts the same server on a random localhost port and opens it in a native webview.
- Native targets package the same resources into the deploy jar/native binary.

## Frontend Routing

- JavalinVue is the only frontend mode.
- Tool and help pages are mapped directly in `WebPortal.kt` to Vue components.

## Key Paths

- `web/src/main/kotlin/no/norrs/nortools/web/WebPortal.kt` server/bootstrap/routing
- `web/src/main/kotlin/no/norrs/nortools/web/*Handlers.kt` API handlers
- `web/src/main/resources/vue/*` JavalinVue resources
- `desktop/src/main/kotlin/no/norrs/nortools/desktop/KremaApp.kt` desktop entrypoint
- `desktop/graal/resource-config.json` native resource includes
