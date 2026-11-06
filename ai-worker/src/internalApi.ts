/** 워커가 app-mvc 내부 API(`/internal/ai/**`)와 이야기하는 쪽. 시크릿은 모든 호출에 실린다(AI-AC-13). */

export type ClaimResult =
  | { kind: "claimed"; attempt: number; text: string; imageKeys: string[] }
  | { kind: "skip" };

export interface InternalApi {
  claim(requestId: string): Promise<ClaimResult>;
  complete(requestId: string, attempt: number, summary: string): Promise<void>;
  fail(requestId: string, attempt: number, error: string): Promise<void>;
}

/** app-mvc 에 닿지 못했거나 예상 밖 응답. 이벤트를 잃지 않으려면 처리를 다시 시도해야 한다는 신호다. */
export class InternalApiError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message);
    this.name = "InternalApiError";
  }
}

export function createInternalApi(baseUrl: string, secret: string, fetchFn: typeof fetch = fetch): InternalApi {
  const root = baseUrl.replace(/\/+$/, "");

  async function post(path: string, body?: unknown): Promise<Response> {
    return fetchFn(`${root}/internal/ai${path}`, {
      method: "POST",
      headers: { "X-Ai-Worker-Secret": secret, "Content-Type": "application/json" },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
  }

  /** 409 는 "이미 끝났거나 다른 시도가 이어받았다" 라 보고할 것이 남지 않는다. 그 밖의 비정상은 던진다. */
  async function report(path: string, body: unknown): Promise<void> {
    const response = await post(path, body);
    if (response.ok || response.status === 409) {
      return;
    }
    throw new InternalApiError(`app-mvc rejected ${path} with ${response.status}`, response.status);
  }

  return {
    async claim(requestId) {
      const response = await post(`/requests/${requestId}/claim`);
      if (response.status === 409) {
        return { kind: "skip" };
      }
      if (!response.ok) {
        throw new InternalApiError(`app-mvc rejected claim with ${response.status}`, response.status);
      }
      const { data } = (await response.json()) as { data: { attempt: number; text: string | null; imageKeys: string[] } };
      return { kind: "claimed", attempt: data.attempt, text: data.text ?? "", imageKeys: data.imageKeys ?? [] };
    },
    complete: (requestId, attempt, summary) => report(`/requests/${requestId}/complete`, { attempt, summary }),
    fail: (requestId, attempt, error) => report(`/requests/${requestId}/fail`, { attempt, error }),
  };
}
