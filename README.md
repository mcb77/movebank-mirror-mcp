# movebank-mcp

An [MCP (Model Context Protocol)](https://modelcontextprotocol.io) server
that exposes a [`movebank-mirror-api`](https://github.com/mcb77/movebank-mirror-api)
instance as LLM-callable tools. Plug it into Claude Code, Claude Desktop,
or any other MCP-compatible client and ask natural-language questions
about your local Movebank mirror.

**Status:** v0.0.1 scaffold. The 8 tools are registered and visible to
MCP clients; their implementations are stubs that return placeholder
responses. Implementations land next.

---

## What you can do with it (when implementations are done)

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
                    │ JSON-RPC over stdio
                    ▼
              movebank-mcp        ◄── this repo
                    │
                    │ HTTP
                    ▼
          movebank-mirror-api      (separate process on :8080)
                    │
                    │ on-disk read
                    ▼
            movebank-mirror/      (file/folder structure)
```

`movebank-mcp` is a Spring Boot app using
[`spring-ai-starter-mcp-server`](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
for the MCP plumbing (JSON-RPC framing, tool registration, schema
emission). It talks to `movebank-mirror-api` over HTTP — same loose
coupling as anything else that speaks the Movebank API.

## Requirements

- JDK 21
- A running [`movebank-mirror-api`](https://github.com/mcb77/movebank-mirror-api)
  pointed at a populated mirror directory (defaults to
  `http://localhost:8080/movebank`; override with
  `MOVEBANK_MIRROR_API_BASE_URL`)
- An MCP client (Claude Code, Claude Desktop, or MCP Inspector for
  development testing)

---

## Building

```bash
./gradlew installDist
./build/install/movebank-mcp/bin/movebank-mcp --help
```

The dist archive is produced by `./gradlew bootDistTar bootDistZip`
under `build/distributions/`.

## Testing the server during development

Two paths, increasing integration:

**1. `scripts/mcp-call.sh` — fastest inner loop.** Spawns a fresh
server, does the MCP handshake, calls one tool, prints the result.
Each call is ~3 seconds.

```bash
./gradlew installDist
./scripts/mcp-call.sh list_studies
./scripts/mcp-call.sh list_studies '{"filter":"albatross"}'
./scripts/mcp-call.sh get_study   '{"study_id":2911040}'
```

**2. [MCP Inspector](https://github.com/modelcontextprotocol/inspector)
— browser UI for click-around testing.** Useful for exploring tool
schemas visually, less useful for repeated calls.

```bash
npx @modelcontextprotocol/inspector ./build/install/movebank-mcp/bin/movebank-mcp
```

## Registering with Claude Code

After `./gradlew installDist`:

```bash
claude mcp add movebank-mcp -- \
    /absolute/path/to/movebank-mcp/build/install/movebank-mcp/bin/movebank-mcp
```

Restart your Claude Code session. The 8 tools then appear in Claude's
tool list as `mcp__movebank-mcp__list_studies`, etc.

---

## Pairs with the rest of the family

- [`movebank-api-client`](https://github.com/mcb77/movebank-api-client) — Java client for the Movebank REST API
- [`movebank-mirror`](https://github.com/mcb77/movebank-mirror) — sync studies to a local file/folder structure
- [`movebank-mirror-api`](https://github.com/mcb77/movebank-mirror-api) — local replay server for the mirror (this MCP server's runtime dependency)
- [`movebank-rocker`](https://github.com/mcb77/movebank-rocker) — pinned R environment for parallel R-based workflows

---

## License

LGPL-2.1, matching the rest of the family. See [LICENSE](LICENSE).

This is a community tool, not an official Movebank product. Built
independently against the public Movebank API surface.
