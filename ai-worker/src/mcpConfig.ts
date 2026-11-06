import { writeFile } from "node:fs/promises";
import { join } from "node:path";

export interface McpConfigInput {
  requestId: string;
  attempt: number;
  internalUrl: string;
  workerSecret: string;
  serverPath: string;
}

/**
 * claude 의 {@code --mcp-config} 파일. MCP 서버(woobeee)를 stdio 자식 프로세스로 띄우고, 요청 id·attempt·시크릿은
 * 이 서버의 환경으로만 전달한다 — claude 프로세스 자신의 환경에는 시크릿이 없다(AI-AC-17).
 */
export async function writeMcpConfig(dir: string, input: McpConfigInput): Promise<string> {
  const path = join(dir, "mcp.json");
  const config = {
    mcpServers: {
      woobeee: {
        command: "node",
        args: [input.serverPath],
        env: {
          AI_REQUEST_ID: input.requestId,
          AI_ATTEMPT: String(input.attempt),
          AI_INTERNAL_URL: input.internalUrl,
          AI_WORKER_SECRET: input.workerSecret,
        },
      },
    },
  };
  await writeFile(path, JSON.stringify(config, null, 2), { mode: 0o600 });
  return path;
}
