import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { buildTools, type ToolApi } from "./tools.js";

/** woobeee MCP 서버. 노출하는 도구는 buildTools 가 만든 다섯 개뿐이다(AI-AC-17). */
export function createMcpServer(api: ToolApi): McpServer {
  const server = new McpServer({ name: "woobeee", version: "0.0.1" });
  for (const tool of buildTools(api)) {
    server.registerTool(tool.name, { description: tool.description, inputSchema: tool.inputShape }, (args: unknown) => tool.run(args));
  }
  return server;
}
