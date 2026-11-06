import type { InternalApi } from "./internalApi.js";

export interface RunInput {
  requestId: string;
  attempt: number;
  text: string;
  imagePaths: string[];
  /** 이미지를 내려받은 임시 폴더. claude 의 작업 디렉터리이기도 하다. */
  dir: string;
}

export type RunResult = { ok: true; summary: string } | { ok: false; error: string };

export interface HandlerDeps {
  api: InternalApi;
  mkTmp(): Promise<string>;
  rm(dir: string): Promise<void>;
  fetchImages(keys: string[], dir: string): Promise<string[]>;
  run(input: RunInput): Promise<RunResult>;
}

export type Outcome = "done" | "skipped" | "failed";

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * 이벤트 하나를 끝까지 처리한다(AI-AC-16).
 *
 * <p>도메인 실패(이미지가 없다, claude 가 실패했다)는 예외로 새지 않고 {@code api.fail} 로 보고한다. app-mvc 에 닿지
 * 못해 claim·보고가 던지는 오류만 밖으로 나간다 — 호출자는 그때 오프셋을 커밋하지 않고 같은 이벤트를 다시 처리한다.
 * 임시 폴더는 어떤 경로로 끝나든 지운다.
 */
export async function handleEvent(deps: HandlerDeps, event: { requestId: string }): Promise<Outcome> {
  const claim = await deps.api.claim(event.requestId);
  if (claim.kind === "skip") {
    return "skipped";
  }

  const dir = await deps.mkTmp();
  try {
    let result: RunResult;
    try {
      const imagePaths = await deps.fetchImages(claim.imageKeys, dir);
      result = await deps.run({ requestId: event.requestId, attempt: claim.attempt, text: claim.text, imagePaths, dir });
    } catch (error) {
      result = { ok: false, error: describe(error) };
    }

    if (result.ok) {
      await deps.api.complete(event.requestId, claim.attempt, result.summary);
      return "done";
    }
    await deps.api.fail(event.requestId, claim.attempt, result.error);
    return "failed";
  } finally {
    await deps.rm(dir);
  }
}
