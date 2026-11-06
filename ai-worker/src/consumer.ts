import { Kafka, type ConsumerConfig } from "kafkajs";
import type { WorkerConfig } from "./config.js";
import type { Outcome } from "./handler.js";

export interface AiEvent {
  requestId: string;
}

export type Handle = (event: AiEvent) => Promise<Outcome>;

export interface EachMessagePayloadLike {
  topic: string;
  partition: number;
  message: { offset: string; value: Buffer | null };
  heartbeat(): Promise<void>;
}

/** kafkajs Consumer 에서 우리가 쓰는 부분만. 가짜로 갈아 끼워 소비 규칙을 테스트하려고 좁혔다. */
export interface ConsumerLike {
  connect(): Promise<void>;
  subscribe(args: { topic: string; fromBeginning?: boolean }): Promise<void>;
  run(args: {
    autoCommit: boolean;
    partitionsConsumedConcurrently: number;
    eachMessage: (payload: EachMessagePayloadLike) => Promise<void>;
  }): Promise<void>;
  commitOffsets(offsets: { topic: string; partition: number; offset: string }[]): Promise<void>;
  disconnect(): Promise<void>;
}

export interface RunConsumerOptions {
  /** app-mvc 에 닿지 못했을 때 같은 이벤트를 다시 시도하기 전에 쉬는 시간. */
  retryDelayMs?: number;
  /** 긴 처리 중 브로커에 살아 있음을 알리는 간격. */
  heartbeatIntervalMs?: number;
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * 직렬 소비(AI-AC-19, AI-AC-16).
 *
 * <ul>
 *   <li>이벤트는 한 번에 하나씩만 처리한다 — kafkajs 가 알아서 직렬화하더라도 여기서 한 번 더 줄 세운다.
 *   <li>오프셋은 처리가 <b>끝난 뒤에</b> 커밋한다. 처리 중에 죽으면 이벤트가 다시 오고 서버의 claim 이 가려낸다.
 *   <li>app-mvc 에 닿지 못해 handler 가 던지면 커밋하지 않고 쉬었다가 같은 이벤트를 다시 처리한다.
 *   <li>처리가 긴 동안 heartbeat 를 보내 세션이 끊겨 리밸런스가 나지 않게 한다.
 *   <li>JSON 이 아니거나 requestId 가 없는 메시지는 영영 처리할 수 없으므로 버리고 커밋한다.
 * </ul>
 */
export async function runConsumer(
  consumer: ConsumerLike,
  topic: string,
  handle: Handle,
  options: RunConsumerOptions = {},
): Promise<void> {
  const retryDelayMs = options.retryDelayMs ?? 5_000;
  const heartbeatIntervalMs = options.heartbeatIntervalMs ?? 10_000;
  let queue: Promise<unknown> = Promise.resolve();

  async function process(payload: EachMessagePayloadLike): Promise<void> {
    const event = parseEvent(payload.message.value);
    if (event) {
      const heartbeat = setInterval(() => { payload.heartbeat().catch(() => {}); }, heartbeatIntervalMs);
      try {
        for (;;) {
          try {
            await handle(event);
            break;
          } catch (error) {
            console.error(`[ai-worker] ${event.requestId}: app-mvc unreachable, will retry:`, error instanceof Error ? error.message : error);
            await sleep(retryDelayMs);
          }
        }
      } finally {
        clearInterval(heartbeat);
      }
    } else {
      console.warn(`[ai-worker] dropping an unreadable message at offset ${payload.message.offset}`);
    }
    await consumer.commitOffsets([
      { topic: payload.topic, partition: payload.partition, offset: (BigInt(payload.message.offset) + 1n).toString() },
    ]);
  }

  await consumer.connect();
  // 오프셋이 없는 새 그룹은 처음부터 읽는다. latest 에서 시작하면 워커가 늦게 떴을 때 그 전에 쌓인 요청이 영영
  // 읽히지 않고 RECEIVED 로 남아 사용자가 잠긴다. 옛 이벤트를 다시 받아도 claim 의 409 가 걸러낸다.
  await consumer.subscribe({ topic, fromBeginning: true });
  await consumer.run({
    autoCommit: false,
    partitionsConsumedConcurrently: 1,
    eachMessage: (payload) => {
      const run = queue.then(() => process(payload));
      queue = run.catch(() => {});
      return run;
    },
  });
}

function parseEvent(value: Buffer | null): AiEvent | null {
  if (!value) return null;
  try {
    const parsed = JSON.parse(value.toString()) as { requestId?: unknown };
    return typeof parsed.requestId === "string" && parsed.requestId.length > 0 ? { requestId: parsed.requestId } : null;
  } catch {
    return null;
  }
}

/**
 * 세션·리밸런스 타임아웃은 claude 의 최대 실행 시간보다 넉넉해야 한다. 짧으면 긴 처리 중에 브로커가 소비자를 죽은
 * 것으로 보고 리밸런스를 일으켜 같은 이벤트가 두 번 돈다(AI-AC-19).
 */
export function consumerOptions(args: { groupId: string; claudeTimeoutMs: number }): ConsumerConfig {
  const timeout = Math.max(5 * 60_000, args.claudeTimeoutMs * 2);
  return { groupId: args.groupId, sessionTimeout: timeout, rebalanceTimeout: timeout, heartbeatInterval: 10_000 };
}

export function createKafkaConsumer(cfg: WorkerConfig): ConsumerLike {
  const kafka = new Kafka({
    clientId: "ai-worker",
    brokers: cfg.kafkaBrokers,
    ...(cfg.kafkaSasl ? { sasl: cfg.kafkaSasl } : {}),
  });
  return kafka.consumer(consumerOptions({ groupId: cfg.groupId, claudeTimeoutMs: cfg.claudeTimeoutMs })) as unknown as ConsumerLike;
}
