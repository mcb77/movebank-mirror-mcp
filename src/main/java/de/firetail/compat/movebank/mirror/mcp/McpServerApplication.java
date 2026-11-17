package de.firetail.compat.movebank.mirror.mcp;

import de.firetail.compat.movebank.mirror.mcp.tools.MovebankTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;

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

    public static void main(String[] args) {
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
