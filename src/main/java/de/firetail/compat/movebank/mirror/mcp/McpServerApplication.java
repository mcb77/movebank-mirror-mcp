package de.firetail.compat.movebank.mirror.mcp;

import de.firetail.compat.movebank.mirror.mcp.tools.MovebankTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;
import java.util.List;

/**
 * Entry point for the Movebank MCP server.
 *
 * Two transports in one binary:
 *
 *   default (no flag) — HTTP/SSE on 127.0.0.1:8090. Long-lived daemon
 *                       that multiple MCP clients can connect to over
 *                       Spring AI's SSE transport.
 *
 *   --stdio           — JSON-RPC over stdin/stdout. The Claude Code /
 *                       Claude Desktop / MCP Inspector subprocess
 *                       model. Same UX as the original v0.0.1.
 *                       MOVEBANK_MIRROR_MCP_STDIO=true env var has the
 *                       same effect.
 *
 * Transport selection works by activating Spring profile 'stdio'
 * when requested; profile-specific config lives in
 * application-stdio.yml. The 8 tool methods on {@link MovebankTools}
 * are auto-registered for either transport.
 *
 * In stdio mode, logging is redirected to stderr (see
 * logback-spring.xml) so the stdout JSON-RPC channel stays clean.
 */
@SpringBootApplication
public class McpServerApplication {

    static final String USAGE = """
            Usage: movebank-mirror-mcp [--stdio] [--help] [--version]

            MCP server over a local Movebank mirror (written by movebank-mirror).

              (no option)  HTTP/SSE on 127.0.0.1:8090, endpoint /sse
              --stdio      JSON-RPC over stdin/stdout, for Claude Code / Claude Desktop
                           (same as MOVEBANK_MIRROR_MCP_STDIO=true)
              --help       print this and exit
              --version    print the version and exit

            Environment:
              MOVEBANK_MIRROR_DIR   the mirror directory (required)
            """;

    public static void main(String[] args) {
        List<String> a = Arrays.asList(args);
        if (a.contains("--help") || a.contains("-h")) {
            System.out.print(USAGE);
            return;
        }
        if (a.contains("--version") || a.contains("-V")) {
            String v = McpServerApplication.class.getPackage().getImplementationVersion();
            System.out.println("movebank-mirror-mcp " + (v != null ? v : "(development build)"));
            return;
        }
        SpringApplication app = new SpringApplication(McpServerApplication.class);
        if (wantsStdio(args)) {
            app.setAdditionalProfiles("stdio");
        }
        app.run(args);
    }

    private static boolean wantsStdio(String[] args) {
        if (Arrays.asList(args).contains("--stdio")) return true;
        String env = System.getenv("MOVEBANK_MIRROR_MCP_STDIO");
        return env != null && env.equalsIgnoreCase("true");
    }

    @Bean
    public ToolCallbackProvider movebankToolProvider(MovebankTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
