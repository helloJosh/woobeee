import type { ProductInput, TaskInput, ToolApi } from "./tools.js";

/** 내부 API 가 도구 호출을 거절했다. {@code code} 는 응답의 에러 코드 키(`ai_staleAttempt` 등)다. */
export class ToolApiError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(`${status} ${code}`);
    this.name = "ToolApiError";
  }
}

export interface ToolEnv {
  requestId: string;
  attempt: number;
  internalUrl: string;
  workerSecret: string;
}

/**
 * MCP 서버 프로세스의 환경에서 요청 id·attempt·시크릿을 읽는다. 요청자는 모델이 정하는 것이 아니라 이 환경이 정한다 —
 * 도구 입력에는 회원 식별자가 없다(AI-AC-17). 빠지면 시작하지 않는다.
 */
export function loadToolEnv(env: Record<string, string | undefined>): ToolEnv {
  const names = ["AI_REQUEST_ID", "AI_ATTEMPT", "AI_INTERNAL_URL", "AI_WORKER_SECRET"] as const;
  const missing = names.filter((name) => !env[name]);
  if (missing.length > 0) {
    throw new Error(`the woobeee MCP server is missing environment variables: ${missing.join(", ")}`);
  }
  const attempt = Number(env.AI_ATTEMPT);
  if (!Number.isInteger(attempt) || attempt < 1) {
    throw new Error(`AI_ATTEMPT must be a positive integer, got "${env.AI_ATTEMPT}"`);
  }
  return {
    requestId: env.AI_REQUEST_ID as string,
    attempt,
    internalUrl: env.AI_INTERNAL_URL as string,
    workerSecret: env.AI_WORKER_SECRET as string,
  };
}

/** 도구가 app-mvc 내부 API 에 보내는 호출. 모든 호출에 요청 id, attempt, 워커 시크릿이 실린다(AI-AC-06·13). */
export function createToolApi(env: ToolEnv, fetchFn: typeof fetch = fetch): ToolApi {
  const root = `${env.internalUrl.replace(/\/+$/, "")}/internal/ai/requests/${env.requestId}`;
  const headers = { "X-Ai-Worker-Secret": env.workerSecret, "Content-Type": "application/json" };

  async function send(url: string, init: RequestInit): Promise<unknown> {
    const response = await fetchFn(url, { ...init, headers });
    if (!response.ok) {
      const body = (await response.json().catch(() => null)) as { header?: { message?: string } } | null;
      throw new ToolApiError(response.status, body?.header?.message ?? "unknown");
    }
    const body = (await response.json()) as { data?: unknown };
    return body.data ?? null;
  }

  const get = (path: string) => send(`${root}${path}${path.includes("?") ? "&" : "?"}attempt=${env.attempt}`, { method: "GET" });
  const post = (path: string, body: object) =>
    send(`${root}${path}`, { method: "POST", body: JSON.stringify({ attempt: env.attempt, ...body }) });

  return {
    listMyRounds: () => get("/pokemon/rounds"),
    getRoundProducts: (roundId: number) => get(`/pokemon/rounds/${roundId}/products`),
    listMyProjects: () => get("/schedule/tree"),
    proposeRoundProducts: (roundId: number, items: ProductInput[]) => post("/proposals/round-products", { roundId, items }),
    proposeTask: (task: TaskInput) => post("/proposals/tasks", { task }),
  };
}
