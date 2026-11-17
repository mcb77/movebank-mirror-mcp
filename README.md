# movebank-mirror-mcp

An [MCP (Model Context Protocol)](https://modelcontextprotocol.io) server
that exposes a local Movebank mirror, written by
[`movebank-mirror`](https://github.com/mcb77/movebank-mirror), as
LLM-callable tools. Plug it into Claude Code, Claude Desktop, or any other
MCP-compatible client and ask natural-language questions about your
mirrored studies.

**Status:** v0.0.1, unreleased. The 8 tools work against a real mirror.

---

## What you can do with it

A researcher with a local Movebank mirror plus Claude Code:

> "Show me studies about albatrosses in the Galápagos."  
> *(Claude calls `search("albatross Galapagos")` → returns study 2911040)*

> "What sensors does that study have?"  
> *(Claude calls `get_study(2911040)` → reports GPS + acceleration, 28 individuals)*

> "Download just the GPS data to /tmp/albatross-gps.csv."  
> *(Claude calls `summarize_events(...)` to check size, then
> `download_events_to_file(...)` and tells you where the file is)*

The LLM never sees millions of raw rows; it sees summaries and produces
files for you to analyze in polars / pandas / R.

---

## The 8 tools

| Tool                          | Purpose                                                            |
|-------------------------------|--------------------------------------------------------------------|
| `list_studies(filter?)`       | One-line summaries of studies in the mirror                        |
| `get_study(study_id)`         | Full metadata for one study                                        |
| `list_entities(study_id, type)`| individuals / tags / deployments / sensors                        |
| `list_sensor_types()`         | Global sensor-type catalog                                         |
| `search(query)`               | Fuzzy free-text over studies / taxa / PIs                          |
| `summarize_events(...)`       | Row count, columns, extent, head/tail preview — *call before download* |
| `query_events(..., aggregation)` | Bounded aggregations returning scalars / small tables          |
| `download_events_to_file(...)`| CSV file on disk; the LLM never sees raw rows                      |

Two design rules: summaries by default, files on request (most Movebank
data is too big for an LLM's context); and no admin tools such as
accepting licenses or starting a sync, which stay outside the
conversation.

---

## Architecture

```
   Claude Code / Claude Desktop / MCP Inspector
                    │
                    │ JSON-RPC over stdio   OR   HTTP/SSE
                    ▼
           movebank-mirror-mcp    ◄── this repo
                    │            ( subprocess per session
                    │              OR long-lived daemon
                    │              on 127.0.0.1:8090 )
                    │
                    │ in-process: movebank-mirror's MirrorReader
                    ▼
            movebank-mirror/      (file/folder structure)
```

`movebank-mirror-mcp` is a Spring Boot app using
[`spring-ai-starter-mcp-server`](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
for the MCP plumbing (JSON-RPC framing, tool registration, schema
emission). It reads the mirror directory directly with the `reader`
package of `movebank-mirror`, the same code
[`movebank-mirror-api`](https://github.com/mcb77/movebank-mirror-api)
serves its REST API from. So events come in Movebank's order with
Movebank's deployment assignment, and `download_events_to_file` writes the
same bytes the API would send. No server has to be running.

## Requirements

- JDK 21
- A mirror directory written by `movebank-mirror`, given in the
  `MOVEBANK_MIRROR_DIR` environment variable. Without it, every tool
  answers with an error that says so.
- An MCP client (Claude Code, Claude Desktop, or MCP Inspector for
  development testing)

---

## Installing

Download `movebank-mirror-mcp-boot-<version>.tar` (or `.zip`) from the
[GitHub releases](https://github.com/mcb77/movebank-mirror-mcp/releases),
unpack it, and run `bin/movebank-mirror-mcp` (needs Java 21):

```bash
tar -xf movebank-mirror-mcp-boot-0.0.1.tar
./movebank-mirror-mcp-boot-0.0.1/bin/movebank-mirror-mcp --help
```

Register that `bin/movebank-mirror-mcp` with your MCP client as shown under
[Run modes](#run-modes).

## Building

```bash
./gradlew installDist
./build/install/movebank-mirror-mcp/bin/movebank-mirror-mcp --help
```

The dist archive is produced by `./gradlew bootDistTar bootDistZip`
under `build/distributions/`.

To build against a sibling `../movebank-mirror` checkout instead of the
published library, pass `-PlocalMovebankMirror` (or uncomment it in
`gradle.properties`).

## Testing the server during development

Two paths, increasing integration:

**1. `scripts/mcp-call.sh` — fastest inner loop.** Spawns a fresh
server, does the MCP handshake, calls one tool, prints the result.
Each call is ~3 seconds.

```bash
./gradlew installDist
export MOVEBANK_MIRROR_DIR=/var/lib/mvb
./scripts/mcp-call.sh list_studies
./scripts/mcp-call.sh list_studies '{"filter":"albatross"}'
./scripts/mcp-call.sh get_study   '{"study_id":2911040}'
```

**2. [MCP Inspector](https://github.com/modelcontextprotocol/inspector)
— browser UI for click-around testing.** Useful for exploring tool
schemas visually, less useful for repeated calls.

```bash
npx @modelcontextprotocol/inspector ./build/install/movebank-mirror-mcp/bin/movebank-mirror-mcp
```

## Run modes

The same binary supports two transports. Pick by use case — both expose
identical tool surfaces.

### stdio — subprocess per client session

The standard MCP-client model. Claude Code (or Claude Desktop, or MCP
Inspector) spawns the server as a subprocess on session start and
shuts it down when you exit. Nothing to manage; nothing left running.

```bash
./build/install/movebank-mirror-mcp/bin/movebank-mirror-mcp --stdio
# blocks reading JSON-RPC from stdin
```

`MOVEBANK_MIRROR_MCP_STDIO=true` env var has the same effect.

Register with Claude Code:

```bash
claude mcp add --scope user -e MOVEBANK_MIRROR_DIR=/var/lib/mvb movebank-mirror-mcp -- /absolute/path/to/movebank-mirror-mcp/build/install/movebank-mirror-mcp/bin/movebank-mirror-mcp --stdio
```

Trade-off: a code change requires a Claude Code session restart for
the new binary to be spawned — see [`scripts/mcp-call.sh`](scripts/mcp-call.sh)
for a faster inner-loop test path that re-spawns per call.

### HTTP / SSE — long-lived daemon

Run movebank-mirror-mcp once; many MCP clients connect to it over the SSE
transport. One process, one JVM warm-up; code changes pick up after a
single server restart instead of one per client session.

This is what you get if you launch the binary with no flag:

```bash
MOVEBANK_MIRROR_DIR=/var/lib/mvb ./build/install/movebank-mirror-mcp/bin/movebank-mirror-mcp
# listening on 127.0.0.1:8090, SSE endpoint at /sse
```

Bound to 127.0.0.1 by default — there's no authentication on the MCP
surface, so don't expose externally without a reverse proxy that adds
it.

Register with Claude Code:

```bash
claude mcp add --transport sse --scope user movebank-mirror-mcp http://127.0.0.1:8090/sse
```

When to use which: **stdio** for the typical single-user setup (one
researcher, one Claude Code session); **HTTP/SSE** when you want
multiple clients to share a single warm server, when iteration on the
server code is frequent, or when the daemon model fits your
deployment (e.g. systemd unit, container).

In either mode, the 8 tools appear in Claude's tool list as
`mcp__movebank-mirror-mcp__list_studies`, etc.

---

## Pairs with the rest of the family

- [`movebank-api-client`](https://github.com/mcb77/movebank-api-client) — Java client for the Movebank REST API
- [`movebank-mirror`](https://github.com/mcb77/movebank-mirror) — sync studies to a local file/folder structure; its reader is this server's data access
- [`movebank-mirror-api`](https://github.com/mcb77/movebank-mirror-api) — the same mirror as a Movebank-compatible REST API, for R and other clients
- [`movebank-rocker`](https://github.com/mcb77/movebank-rocker) — pinned R environment for parallel R-based workflows

---

## License

LGPL-2.1, matching the rest of the family. See [LICENSE](LICENSE).

This is a community tool, not an official Movebank product. Built
independently against the public Movebank API surface.
