import { describe, expect, it, vi } from "vitest";
import { consumerOptions, runConsumer, type ConsumerLike } from "../src/consumer.js";

type EachMessage = (payload: {
  topic: string;
  partition: number;
  message: { offset: string; value: Buffer | null };
  heartbeat: () => Promise<void>;
}) => Promise<void>;

function fakeConsumer() {
  let each: EachMessage | undefined;
  const calls: string[] = [];
  const consumer: ConsumerLike & { runOptions?: Record<string, unknown> } = {
    connect: vi.fn(async () => {}),
    subscribe: vi.fn(async () => {}),
    disconnect: vi.fn(async () => {}),
    commitOffsets: vi.fn(async () => { calls.push("commit"); }),
    run: vi.fn(async (options) => {
      consumer.runOptions = options as unknown as Record<string, unknown>;
      each = options.eachMessage as EachMessage;
    }),
  };
  return { consumer, calls, each: () => each! };
}

const msg = (offset: string, body: unknown = { requestId: `r-${offset}` }) => ({
  topic: "ai.request.v1",
  partition: 0,
  message: { offset, value: Buffer.from(typeof body === "string" ? body : JSON.stringify(body)) },
  heartbeat: vi.fn(async () => {}),
});

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

describe("runConsumer", () => {
  it("AI_AC_19 subscribes with manual commits and a single concurrent partition", async () => {
    const f = fakeConsumer();
    await runConsumer(f.consumer, "ai.request.v1", async () => "done");

    // 오프셋이 없는 새 그룹은 처음부터 읽는다 — 워커가 늦게 떠도 그 전에 쌓인 요청을 건너뛰지 않는다(옛 이벤트는 claim 의 409 가 걸러낸다)
    expect(f.consumer.subscribe).toHaveBeenCalledWith({ topic: "ai.request.v1", fromBeginning: true });
    expect(f.consumer.runOptions).toMatchObject({ autoCommit: false, partitionsConsumedConcurrently: 1 });
  });

  it("AI_AC_16 commits the offset only after the handler finished (offset + 1)", async () => {
    const f = fakeConsumer();
    const order: string[] = [];
    await runConsumer(f.consumer, "ai.request.v1", async () => { await sleep(10); order.push("handled"); return "done" as const; },
      { retryDelayMs: 1 });
    f.consumer.commitOffsets = vi.fn(async () => { order.push("commit"); });

    await f.each()(msg("41"));

    expect(order).toEqual(["handled", "commit"]);
    expect(f.consumer.commitOffsets).toHaveBeenCalledWith([{ topic: "ai.request.v1", partition: 0, offset: "42" }]);
  });

  it("AI_AC_16 commits a skipped event too (a duplicate delivery is safely ignored)", async () => {
    const f = fakeConsumer();
    await runConsumer(f.consumer, "ai.request.v1", async () => "skipped");

    await f.each()(msg("7"));

    expect(f.consumer.commitOffsets).toHaveBeenCalledTimes(1);
  });

  it("AI_AC_16 does not commit while the handler throws, retries the same event and commits once it succeeds", async () => {
    const f = fakeConsumer();
    const commitsSeenByEachAttempt: number[] = [];
    let attempts = 0;
    const handle = vi.fn(async (event: { requestId: string }) => {
      commitsSeenByEachAttempt.push((f.consumer.commitOffsets as ReturnType<typeof vi.fn>).mock.calls.length);
      attempts += 1;
      if (attempts < 3) throw new Error("app-mvc down");
      expect(event.requestId).toBe("r-5");
      return "done" as const;
    });
    await runConsumer(f.consumer, "ai.request.v1", handle, { retryDelayMs: 1 });

    await f.each()(msg("5"));

    expect(handle).toHaveBeenCalledTimes(3);
    // 세 번 시도하는 동안 한 번도 커밋되지 않았고, 성공한 뒤에 정확히 한 번 커밋된다
    expect(commitsSeenByEachAttempt).toEqual([0, 0, 0]);
    expect(f.consumer.commitOffsets).toHaveBeenCalledTimes(1);
  });

  it("drops (and commits) a message that is not a valid event instead of looping on it forever", async () => {
    const f = fakeConsumer();
    const handle = vi.fn(async () => "done" as const);
    await runConsumer(f.consumer, "ai.request.v1", handle);

    await f.each()(msg("1", "this is not json"));
    await f.each()(msg("2", { no: "requestId" }));

    expect(handle).not.toHaveBeenCalled();
    expect(f.consumer.commitOffsets).toHaveBeenCalledTimes(2);
  });

  it("AI_AC_19 never runs two events at the same time, even if delivered concurrently", async () => {
    const f = fakeConsumer();
    const timeline: string[] = [];
    await runConsumer(f.consumer, "ai.request.v1", async (event) => {
      timeline.push(`start ${event.requestId}`);
      await sleep(25);
      timeline.push(`end ${event.requestId}`);
      return "done";
    });

    await Promise.all([f.each()(msg("1")), f.each()(msg("2"))]);

    expect(timeline).toEqual(["start r-1", "end r-1", "start r-2", "end r-2"]);
  });

  it("AI_AC_19 heartbeats while a long event is handled and stops afterwards", async () => {
    const f = fakeConsumer();
    await runConsumer(f.consumer, "ai.request.v1", async () => { await sleep(60); return "done"; },
      { heartbeatIntervalMs: 5 });
    const payload = msg("1");

    await f.each()(payload);
    const during = payload.heartbeat.mock.calls.length;
    await sleep(40);

    expect(during).toBeGreaterThanOrEqual(3);
    expect(payload.heartbeat.mock.calls.length).toBe(during);
  });
});

describe("consumerOptions", () => {
  it("AI_AC_19 sets a session timeout longer than the claude timeout so a long run does not trigger a rebalance", () => {
    const options = consumerOptions({ groupId: "ai-worker", claudeTimeoutMs: 120_000 });

    expect(options.groupId).toBe("ai-worker");
    expect(options.sessionTimeout).toBeGreaterThanOrEqual(5 * 60_000);
    expect(options.rebalanceTimeout).toBeGreaterThanOrEqual(options.sessionTimeout!);
  });
});
