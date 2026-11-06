// 빌드된 MCP 서버를 실제 stdio 로 띄워 도구 목록을 확인한다: `npm run build && node scripts/smoke-mcp.mjs`
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const transport = new StdioClientTransport({
  command: "node",
  args: ["dist/mcp/server.js"],
  env: { AI_REQUEST_ID: "smoke", AI_ATTEMPT: "1", AI_INTERNAL_URL: "http://127.0.0.1:1", AI_WORKER_SECRET: "smoke" },
});
const client = new Client({ name: "smoke", version: "0" });
await client.connect(transport);
const { tools } = await client.listTools();
await client.close();

console.log(`${tools.length} tools: ${tools.map((t) => t.name).sort().join(", ")}`);
process.exit(tools.length === 5 ? 0 : 1);
