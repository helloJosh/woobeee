import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { describe, expect, it, vi } from "vitest";
import { createMcpServer } from "../src/mcp/serverFactory.js";
import { buildTools, type ToolApi } from "../src/mcp/tools.js";
import { ToolApiError, createToolApi, loadToolEnv } from "../src/mcp/toolApi.js";

const ALLOWED = ["get_round_products", "list_my_projects", "list_my_rounds", "propose_round_products", "propose_task"];

function fakeApi(): ToolApi {
  return {
    listMyRounds: vi.fn(async () => ({ rounds: [] })),
    getRoundProducts: vi.fn(async () => []),
    listMyProjects: vi.fn(async () => ({ projects: [] })),
    proposeRoundProducts: vi.fn(async () => null),
    proposeTask: vi.fn(async () => null),
  };
}

const tool = (api: ToolApi, name: string) => buildTools(api).find((t) => t.name === name)!;
const text = (result: { content: { text: string }[] }) => result.content.map((c) => c.text).join("\n");

describe("buildTools", () => {
  it("AI_AC_17 exposes exactly the five allowed tools", () => {
    expect(buildTools(fakeApi()).map((t) => t.name).sort()).toEqual(ALLOWED);
  });

  it("AI_AC_17 no tool takes a member identity — the requester comes from the request, never from the model", () => {
    for (const t of buildTools(fakeApi())) {
      const keys = Object.keys(t.inputShape).map((k) => k.toLowerCase());
      expect(keys, t.name).not.toContain("memberid");
      expect(keys, t.name).not.toContain("email");
      expect(keys, t.name).not.toContain("loginid");
    }
  });

  it("AI_AC_17 propose_round_products rejects invalid input before calling the server", async () => {
    const api = fakeApi();
    const propose = tool(api, "propose_round_products");
    const item = { name: "GO패스 디럭스", price: 29, coins: 0 };
    const bad: unknown[] = [
      { roundId: 1, items: [] },
      { roundId: 1, items: [{ ...item, name: "" }] },
      { roundId: 1, items: [{ ...item, name: "가".repeat(201) }] },
      { roundId: 1, items: [{ ...item, price: 0 }] },
      { roundId: 1, items: [{ ...item, price: -5 }] },
      { roundId: 1, items: [{ ...item, price: 1.234 }] },
      { roundId: 1, items: [{ ...item, coins: -1 }] },
      { roundId: 1, items: [{ ...item, coins: 1.5 }] },
      { roundId: 1, items: Array.from({ length: 51 }, () => item) },
      { roundId: 0, items: [item] },
      { roundId: "x", items: [item] },
      { items: [item] },
    ];

    for (const args of bad) {
      const result = await propose.run(args);
      expect(result.isError, JSON.stringify(args).slice(0, 80)).toBe(true);
    }
    expect(api.proposeRoundProducts).not.toHaveBeenCalled();
  });

  it("AI_AC_17 propose_round_products accepts a valid list, including a price like ₹29.00 and 50 items", async () => {
    const api = fakeApi();
    const propose = tool(api, "propose_round_products");
    const item = { name: "GO패스 디럭스", price: 29.0, coins: 0 };

    expect((await propose.run({ roundId: 3, items: [item, { name: "500 코인", price: 149.5, coins: 500 }] })).isError).toBeFalsy();
    expect((await propose.run({ roundId: 3, items: Array.from({ length: 50 }, () => item) })).isError).toBeFalsy();
    expect(api.proposeRoundProducts).toHaveBeenCalledWith(3, [item, { name: "500 코인", price: 149.5, coins: 500 }]);
  });

  it("AI_AC_17 propose_task validates dates, times, reminders and colour", async () => {
    const api = fakeApi();
    const propose = tool(api, "propose_task");

    for (const args of [
      { name: "" },
      { name: "치과", startDate: "11/20/2026" },
      { name: "치과", startTime: "2pm" },
      { name: "치과", reminders: [15] },
      { name: "치과", color: "red" },
      { name: "치과", projectId: -1 },
    ]) {
      expect((await propose.run(args)).isError, JSON.stringify(args)).toBe(true);
    }
    expect(api.proposeTask).not.toHaveBeenCalled();

    const ok = { name: "치과 예약", startDate: "2026-11-20", startTime: "14:30", reminders: [10, 30], color: "#FF0000" };
    expect((await propose.run(ok)).isError).toBeFalsy();
    expect(api.proposeTask).toHaveBeenCalledWith(ok);
  });

  it("AI_AC_17 turns a server rejection into a readable tool error instead of crashing", async () => {
    const api = fakeApi();
    api.proposeRoundProducts = vi.fn(async () => { throw new ToolApiError(403, "ai_applyForbidden"); });
    api.listMyRounds = vi.fn(async () => { throw new TypeError("fetch failed"); });

    const rejected = await tool(api, "propose_round_products").run({ roundId: 9, items: [{ name: "x", price: 1, coins: 0 }] });
    const down = await tool(api, "list_my_rounds").run({});

    expect(rejected.isError).toBe(true);
    expect(text(rejected)).toContain("ai_applyForbidden");
    expect(down.isError).toBe(true);
    expect(text(down)).toContain("fetch failed");
  });

  it("returns the server's data as text for the read tools", async () => {
    const api = fakeApi();
    api.listMyRounds = vi.fn(async () => ({ rounds: [{ id: 5, title: "7차", currency: "INR" }] }));

    const result = await tool(api, "list_my_rounds").run({});

    expect(result.isError).toBeFalsy();
    expect(text(result)).toContain("7차");
  });
});

describe("tool api (what each call sends to app-mvc)", () => {
  const ok = (data: unknown) => new Response(JSON.stringify({ header: { isSuccessful: true }, data }), { status: 200 });
  const env = { requestId: "req-9", attempt: 3, internalUrl: "http://app-mvc:8000/", workerSecret: "s3cret" };

  it("AI_AC_17 every call carries the request id, the attempt and the worker secret from the environment", async () => {
    const fetchFn = vi.fn(async () => ok([]));
    const api = createToolApi(env, fetchFn as unknown as typeof fetch);

    await api.listMyRounds();
    await api.getRoundProducts(5);
    await api.listMyProjects();
    await api.proposeRoundProducts(5, [{ name: "x", price: 1, coins: 0 }]);
    await api.proposeTask({ name: "치과" });

    const calls = fetchFn.mock.calls as unknown as [string, RequestInit][];
    expect(calls.map(([url]) => url)).toEqual([
      "http://app-mvc:8000/internal/ai/requests/req-9/pokemon/rounds?attempt=3",
      "http://app-mvc:8000/internal/ai/requests/req-9/pokemon/rounds/5/products?attempt=3",
      "http://app-mvc:8000/internal/ai/requests/req-9/schedule/tree?attempt=3",
      "http://app-mvc:8000/internal/ai/requests/req-9/proposals/round-products",
      "http://app-mvc:8000/internal/ai/requests/req-9/proposals/tasks",
    ]);
    for (const [, init] of calls) {
      expect((init.headers as Record<string, string>)["X-Ai-Worker-Secret"]).toBe("s3cret");
    }
    expect(JSON.parse(calls[3]![1].body as string)).toEqual({ attempt: 3, roundId: 5, items: [{ name: "x", price: 1, coins: 0 }] });
    expect(JSON.parse(calls[4]![1].body as string)).toEqual({ attempt: 3, task: { name: "치과" } });
  });

  it("raises a ToolApiError carrying the status and the server's code key on a rejection", async () => {
    const fetchFn = vi.fn(async () => new Response(JSON.stringify({ header: { isSuccessful: false, message: "ai_staleAttempt", resultCode: 409 } }), { status: 409 }));
    const api = createToolApi(env, fetchFn as unknown as typeof fetch);

    await expect(api.listMyRounds()).rejects.toMatchObject({ status: 409, code: "ai_staleAttempt" });
  });

  it("loadToolEnv reads the four variables and refuses to start without them", () => {
    expect(loadToolEnv({ AI_REQUEST_ID: "r", AI_ATTEMPT: "2", AI_INTERNAL_URL: "http://x", AI_WORKER_SECRET: "s" }))
      .toEqual({ requestId: "r", attempt: 2, internalUrl: "http://x", workerSecret: "s" });
    expect(() => loadToolEnv({})).toThrowError(/AI_REQUEST_ID.*AI_ATTEMPT.*AI_INTERNAL_URL.*AI_WORKER_SECRET/s);
    expect(() => loadToolEnv({ AI_REQUEST_ID: "r", AI_ATTEMPT: "two", AI_INTERNAL_URL: "http://x", AI_WORKER_SECRET: "s" })).toThrow();
  });
});

describe("createMcpServer (over the real MCP protocol)", () => {
  async function connect(api: ToolApi) {
    const server = createMcpServer(api);
    const client = new Client({ name: "test", version: "0" });
    const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
    await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);
    return client;
  }

  it("AI_AC_17 lists exactly the five allowed tools to a client", async () => {
    const client = await connect(fakeApi());

    const { tools } = await client.listTools();

    expect(tools.map((t) => t.name).sort()).toEqual(ALLOWED);
  });

  it("AI_AC_17 a tool call round-trips, and a server rejection comes back as isError without breaking the connection", async () => {
    const api = fakeApi();
    api.proposeTask = vi.fn(async () => { throw new ToolApiError(409, "ai_staleAttempt"); });
    const client = await connect(api);

    const rejected = await client.callTool({ name: "propose_task", arguments: { name: "치과" } });
    const afterwards = await client.callTool({ name: "list_my_rounds", arguments: {} });

    expect(rejected.isError).toBe(true);
    expect(afterwards.isError).toBeFalsy();
  });

  it("AI_AC_17 invalid arguments are rejected by the protocol layer or the tool, never reaching the server", async () => {
    const api = fakeApi();
    const client = await connect(api);

    const result = await client.callTool({ name: "propose_round_products", arguments: { roundId: 1, items: [] } }).catch(() => ({ isError: true }));

    expect(result.isError).toBe(true);
    expect(api.proposeRoundProducts).not.toHaveBeenCalled();
  });
});
