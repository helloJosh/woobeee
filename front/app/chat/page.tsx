"use client";

import { useEffect, useRef, useState, ComponentPropsWithoutRef } from "react";
import ReactMarkdown from "react-markdown";
import type { Components } from "react-markdown";
import remarkGfm from "remark-gfm";

const CHAT_URL = "https://woobeee.com/api/chat/stream";

type Msg = { role: "user" | "assistant" | "system"; content: string };

// code 렌더러에서 사용할 안전한 타입(ReactMarkdown이 inline을 명시하지 않아서 확장)
type CodeRendererProps = ComponentPropsWithoutRef<"code"> & {
    inline?: boolean;
    node?: any;
};

export default function ChatWidget() {
    const [open, setOpen] = useState(false);
    const [input, setInput] = useState("");
    const [msgs, setMsgs] = useState<Msg[]>([]);
    const [loading, setLoading] = useState(false);
    const abortRef = useRef<AbortController | null>(null);

    // ESC로 닫기
    useEffect(() => {
        const onKey = (e: KeyboardEvent) => {
            if (e.key === "Escape") setOpen(false);
        };
        window.addEventListener("keydown", onKey);
        return () => window.removeEventListener("keydown", onKey);
    }, []);

    async function send() {
        const text = input; // 공백 유지 위해 trim() 제거
        if (!text || loading) return;

        setInput("");
        const history = [...msgs, { role: "user", content: text } as Msg];
        setMsgs(history);
        setLoading(true);

        // 기존 스트림 취소
        if (abortRef.current) abortRef.current.abort();
        const ac = new AbortController();
        abortRef.current = ac;

        // 화면에 어시스턴트 자리 만들기
        const ansIndex = history.length;
        setMsgs((prev) => [...prev, { role: "assistant", content: "" } as Msg]);

        try {
            const res = await fetch(CHAT_URL, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    Accept: "text/event-stream",
                },
                body: JSON.stringify({ messages: history, maxTokens: 512 }),
                signal: ac.signal,
            });

            if (!res.ok || !res.body) throw new Error("Network error");
            const reader = res.body.getReader();
            const dec = new TextDecoder();

            let buf = "";
            let acc = "";
            let doneAll = false;

            while (!doneAll) {
                const { done, value } = await reader.read();
                if (done) break;

                buf += dec.decode(value, { stream: true });

                // SSE 이벤트 분리 (\n\n)
                let idx: number;
                while ((idx = buf.indexOf("\n\n")) !== -1) {
                    // trim() 제거하여 앞뒤 공백/개행 보존
                    const chunk = buf.slice(0, idx);
                    buf = buf.slice(idx + 2);

                    if (!chunk) continue;

                    // 여러 줄 중 data: 라인만 처리
                    const dataLine = chunk
                        .split(/\r?\n/)
                        .find((l) => l.startsWith("data:"));

                    if (!dataLine) continue;

                    // 'data:' 이후 원문 그대로(공백 포함)
                    const data = dataLine.slice(5);

                    // 스트림 종료 신호
                    if (data === "[DONE]") {
                        doneAll = true;
                        break;
                    }

                    // JSON/문자/숫자 모두 유연 처리
                    let piece: unknown = data;
                    try {
                        const j = JSON.parse(data);
                        piece =
                            j?.choices?.[0]?.delta?.content ??
                            j?.delta?.content ??
                            j?.content ??
                            j;
                    } catch {
                        piece = data;
                    }

                    if (piece !== null && piece !== undefined) {
                        const token = String(piece); // 0 같은 숫자 보존

                        acc += token;
                        const urlPattern = /(https:\/\/[^\s]*?)(\d+)(?=[^\w]*$|[\s])/g;

                        acc = acc.replace(urlPattern, (match, baseUrl, num) => {
                            return ` \n  - http://woobeee.com/blog/${num}\n`;
                        });

                        setMsgs((prev) => {
                            const copy = [...prev];
                            copy[ansIndex] = { role: "assistant", content: acc };
                            return copy;
                        });
                    }
                }
            }
        } catch (e) {
            setMsgs((prev) => {
                const copy = [...prev];
                copy[ansIndex] = {
                    role: "assistant",
                    content: "⚠️ 네트워크 오류가 발생했어요. 잠시 후 다시 시도해주세요.",
                };
                return copy;
            });
            // eslint-disable-next-line no-console
            console.error(e);
        } finally {
            setLoading(false);
            abortRef.current = null;
        }
    }

    // ReactMarkdown 컴포넌트 맵(타입 안전)
    const mdComponents: Components = {
        p: ({ node, ...props }) => <p {...props} className="whitespace-pre-wrap" />,
        code: (props) => {
            const { inline, ...rest } = props as CodeRendererProps; // inline 안전 사용
            return inline ? (
                <code
                    {...rest}
                    className="rounded bg-black/10 px-1 py-0.5 text-[0.9em] dark:bg-white/10"
                />
            ) : (
                <pre className="overflow-auto rounded-lg p-3 bg-neutral-100 dark:bg-neutral-900">
          <code {...rest} />
        </pre>
            );
        },
        a: ({ node, ...props }) => (
            <a
                {...props}
                className="underline underline-offset-2 hover:opacity-80"
                target="_blank"
                rel="noreferrer"
            />
        ),
        ul: ({ node, ...props }) => <ul {...props} className="list-disc pl-5" />,
        ol: ({ node, ...props }) => <ol {...props} className="list-decimal pl-5" />,
        blockquote: ({ node, ...props }) => (
            <blockquote
                {...props}
                className="border-l-4 pl-3 italic opacity-80"
            />
        ),
    };

    return (
        <>
            {/* 떠있는 버튼 */}
            <button
                onClick={() => setOpen((v) => !v)}
                className="fixed right-6 bottom-6 z-[1000] h-14 w-14 rounded-full bg-black text-white shadow-xl hover:opacity-90"
                aria-label="AI Chat"
                title="AI Chat"
            >
                💬
            </button>

            {/* 모달 */}
            {open && (
                <div className="fixed right-6 bottom-24 z-[1000] w-[360px] max-h-[70vh] overflow-hidden rounded-xl border border-neutral-200 bg-white shadow-2xl dark:border-neutral-800 dark:bg-neutral-900">
                    <div className="flex items-center justify-between border-b px-4 py-3 text-sm font-semibold dark:border-neutral-800">
                        블로그 글에 대한 것을 문의해주세요
                        <br />
                        (예: Redis 관련 글 있어?)
                        <button
                            onClick={() => setOpen(false)}
                            className="text-neutral-500 hover:text-neutral-700 dark:hover:text-neutral-300"
                        >
                            ✕
                        </button>
                    </div>

                    <div className="h-[48vh] overflow-auto p-3 text-sm leading-relaxed">
                        {msgs.map((m, i) => (
                            <div
                                key={i}
                                className={`mb-2 ${m.role === "user" ? "text-right" : "text-left"}`}
                            >
                                <div
                                    className={`inline-block max-w-[85%] whitespace-pre-wrap rounded-lg px-3 py-2 ${
                                        m.role === "user"
                                            ? "bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900"
                                            : "bg-neutral-100 text-neutral-900 dark:bg-neutral-800 dark:text-neutral-100"
                                    }`}
                                >
                                    <ReactMarkdown remarkPlugins={[remarkGfm]} components={mdComponents}>
                                        {m.content}
                                    </ReactMarkdown>
                                </div>
                            </div>
                        ))}
                        {loading && (
                            <div className="text-left text-xs text-neutral-500">생각 중…</div>
                        )}
                    </div>

                    <div className="flex items-center gap-2 border-t px-3 py-3 dark:border-neutral-800">
                        <input
                            value={input}
                            onChange={(e) => setInput(e.target.value)}
                            onKeyDown={(e) => {
                                if (e.key === "Enter" && !e.shiftKey) send();
                            }}
                            placeholder="메시지를 입력하세요…"
                            className="flex-1 rounded-md border border-neutral-300 bg-white px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-neutral-400 dark:border-neutral-700 dark:bg-neutral-900"
                        />
                        <button
                            onClick={send}
                            disabled={loading}
                            className="rounded-md bg-black px-3 py-2 text-sm text-white hover:opacity-90 disabled:opacity-50"
                        >
                            보내기
                        </button>
                    </div>
                </div>
            )}
        </>
    );
}
