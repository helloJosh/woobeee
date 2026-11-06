import { z } from "zod";
import { ToolApiError } from "./toolApi.js";

export interface ProductInput {
  name: string;
  price: number;
  coins: number;
}

export interface TaskInput {
  name: string;
  projectId?: number | undefined;
  milestoneId?: number | undefined;
  startDate?: string | undefined;
  endDate?: string | undefined;
  startTime?: string | undefined;
  endTime?: string | undefined;
  reminders?: (10 | 30)[] | undefined;
  color?: string | undefined;
}

/** 도구가 서버에 하는 일. 읽기는 요청자 범위로만 돌려받고, 쓰기는 제안을 기록할 뿐이다. */
export interface ToolApi {
  listMyRounds(): Promise<unknown>;
  getRoundProducts(roundId: number): Promise<unknown>;
  listMyProjects(): Promise<unknown>;
  proposeRoundProducts(roundId: number, items: ProductInput[]): Promise<unknown>;
  proposeTask(task: TaskInput): Promise<unknown>;
}

export interface ToolResult {
  [key: string]: unknown;
  content: { type: "text"; text: string }[];
  isError?: boolean;
}

export interface ToolDef {
  name: string;
  description: string;
  /** MCP 서버가 클라이언트에 알리는 입력 모양. */
  inputShape: z.ZodRawShape;
  /** 입력을 검증하고 서버를 부른다. 어떤 실패도 예외가 아니라 isError 결과로 돌려준다. */
  run(args: unknown): Promise<ToolResult>;
}

const text = (value: string): ToolResult => ({ content: [{ type: "text", text: value }] });
const failure = (value: string): ToolResult => ({ content: [{ type: "text", text: value }], isError: true });

function describeError(error: unknown): string {
  if (error instanceof ToolApiError) {
    return `서버가 요청을 거절했습니다 (${error.status} ${error.code}). 같은 호출을 반복하지 말고 이유를 요약에 적으세요.`;
  }
  return `서버에 연결하지 못했습니다: ${error instanceof Error ? error.message : String(error)}`;
}

/**
 * 도구 하나를 만든다. 입력은 zod 로 검증하며, 스키마에 없는 키(예: 모델이 지어낸 memberId)는 버려져 서버에
 * 전달되지 않는다. 검증을 통과하지 못하면 서버를 부르지 않는다(AI-AC-17).
 */
function define<Shape extends z.ZodRawShape>(
  name: string,
  description: string,
  shape: Shape,
  call: (args: z.infer<z.ZodObject<Shape>>) => Promise<unknown>,
): ToolDef {
  const schema = z.object(shape);
  return {
    name,
    description,
    inputShape: shape,
    async run(raw) {
      const parsed = schema.safeParse(raw ?? {});
      if (!parsed.success) {
        const issues = parsed.error.issues.map((issue) => `${issue.path.join(".") || "(입력)"}: ${issue.message}`);
        return failure(`입력이 올바르지 않습니다 — ${issues.join("; ")}`);
      }
      try {
        const data = await call(parsed.data);
        return text(data === null || data === undefined ? "제안을 기록했습니다." : JSON.stringify(data));
      } catch (error) {
        return failure(describeError(error));
      }
    },
  };
}

const id = z.number().int().positive();
/** 스토어 가격은 {@code ₹29.00} 꼴이다 — 소수 둘째 자리까지. */
const price = z.number().positive().refine((v) => Math.abs(v * 100 - Math.round(v * 100)) < 1e-6, "소수 둘째 자리까지만 쓸 수 있습니다");
const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "YYYY-MM-DD 형식이어야 합니다");
const time = z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/, "HH:mm 형식이어야 합니다");

export function buildTools(api: ToolApi): ToolDef[] {
  return [
    define("list_my_rounds", "내가 연 공동구매 차수 목록(id, 제목, 통화, 상태). 상품을 제안할 차수를 고를 때 먼저 본다.", {}, () => api.listMyRounds()),

    define("get_round_products", "한 차수의 현재 상품표. 이미 있는 상품을 다시 제안하지 않도록 확인한다.", { roundId: id }, ({ roundId }) =>
      api.getRoundProducts(roundId)),

    define("list_my_projects", "내 프로젝트·마일스톤·할 일 트리. 할 일을 어느 프로젝트에 넣을지 정할 때 본다.", {}, () => api.listMyProjects()),

    define(
      "propose_round_products",
      "한 차수에 넣을 상품을 제안한다(등록은 사용자가 승인해야 일어난다). price 는 스토어에 보이는 숫자 그대로, coins 는 포켓코인 수(패스·티켓은 0).",
      {
        roundId: id,
        items: z
          .array(z.object({ name: z.string().trim().min(1).max(200), price, coins: z.number().int().min(0) }))
          .min(1)
          .max(50),
      },
      ({ roundId, items }) => api.proposeRoundProducts(roundId, items),
    ),

    define(
      "propose_task",
      "할 일 하나를 제안한다(등록은 사용자가 승인해야 일어난다). 날짜는 YYYY-MM-DD, 시간은 HH:mm, reminders 는 시작 전 알림(분)으로 10 또는 30.",
      {
        name: z.string().trim().min(1).max(200),
        projectId: id.optional(),
        milestoneId: id.optional(),
        startDate: date.optional(),
        endDate: date.optional(),
        startTime: time.optional(),
        endTime: time.optional(),
        reminders: z.array(z.union([z.literal(10), z.literal(30)])).optional(),
        color: z.string().regex(/^#[0-9A-Fa-f]{6}$/, "#RRGGBB 형식이어야 합니다").optional(),
      },
      (task) => api.proposeTask(task),
    ),
  ];
}
