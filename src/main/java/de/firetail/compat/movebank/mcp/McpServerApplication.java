package de.firetail.compat.movebank.mcp;

import de.firetail.compat.movebank.mcp.tools.MovebankTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Entry point for the Movebank MCP server.
 *
 * Runs as a stdio JSON-RPC subprocess: launched by Claude Code / Claude
 * Desktop / MCP Inspector and communicates via stdin/stdout. The 8 tool
 * methods on {@link MovebankTools} are auto-registered and exposed.
 *
 * Logging is redirected to stderr (see logback-spring.xml) so the
 * stdout JSON-RPC channel stays clean.
 */
@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider movebankToolProvider(MovebankTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
