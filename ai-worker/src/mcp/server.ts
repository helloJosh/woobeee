import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { createMcpServer } from "./serverFactory.js";
import { createToolApi, loadToolEnv } from "./toolApi.js";

/**
 * claude 가 {@code --mcp-config} 로 자식 프로세스로 띄우는 진입점. stdout 은 MCP 프로토콜 전용이라 로그는
 * 반드시 stderr 로만 쓴다.
 */
try {
  const server = createMcpServer(createToolApi(loadToolEnv(process.env)));
  await server.connect(new StdioServerTransport());
} catch (error) {
  console.error("[woobeee-mcp]", error instanceof Error ? error.message : error);
  process.exit(1);
}
