import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { writeMcpConfig } from "../src/mcpConfig.js";

describe("writeMcpConfig", () => {
  it("AI_AC_17 writes a stdio server entry that carries the request, attempt and secret only to the MCP child", async () => {
    const dir = await mkdtemp(join(tmpdir(), "ai-worker-mcp-"));
    try {
      const path = await writeMcpConfig(dir, {
        requestId: "r1", attempt: 2, internalUrl: "http://app-mvc:8000", workerSecret: "s3cret", serverPath: "/app/dist/mcp/server.js",
      });

      const config = JSON.parse(await readFile(path, "utf8"));
      expect(path).toBe(join(dir, "mcp.json"));
      expect(config.mcpServers.woobeee).toEqual({
        command: "node",
        args: ["/app/dist/mcp/server.js"],
        env: { AI_REQUEST_ID: "r1", AI_ATTEMPT: "2", AI_INTERNAL_URL: "http://app-mvc:8000", AI_WORKER_SECRET: "s3cret" },
      });
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });
});
