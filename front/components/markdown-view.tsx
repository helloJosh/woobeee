import ReactMarkdown from "react-markdown"
import remarkGfm from "remark-gfm"
import remarkBreaks from "remark-breaks"
import rehypeHighlight from "rehype-highlight"

/**
 * 게시 화면(post-detail)과 편집기 미리보기가 같은 모습을 갖도록
 * 마크다운 렌더러와 본문 스타일을 한 곳에 둔다.
 */
export default function MarkdownView({ content }: { content: string }) {
    return (
        <div
            className="
              max-w-none font-sans text-base leading-7 break-words
              [&_h1]:font-bold [&_h1]:leading-tight [&_h1]:mt-6 [&_h1]:mb-3 [&_h1]:text-3xl
              [&_h2]:font-bold [&_h2]:leading-tight [&_h2]:mt-6 [&_h2]:mb-3 [&_h2]:text-2xl
              [&_h3]:font-bold [&_h3]:leading-tight [&_h3]:mt-5 [&_h3]:mb-2 [&_h3]:text-xl
              [&_h4]:font-semibold [&_h4]:mt-5 [&_h4]:mb-2
              [&_p]:my-3
              [&_ul]:list-disc [&_ul]:pl-4 [&_ul]:ml-6 [&_ul]:my-3
              [&_ol]:list-decimal [&_ol]:pl-4 [&_ol]:ml-6 [&_ol]:my-3
              [&_li]:my-1
              [&_li>ul]:mt-1 [&_li>ol]:mt-1
              [&_code]:bg-gray-100 [&_code]:rounded [&_code]:px-1.5 [&_code]:py-0.5
              [&_code]:font-mono [&_code]:text-sm
              [&_pre]:bg-gray-100 [&_pre]:p-4 [&_pre]:rounded-lg [&_pre]:overflow-x-auto [&_pre]:my-4
              [&_pre_code]:bg-transparent [&_pre_code]:p-0
              [&_blockquote]:my-4 [&_blockquote]:pl-4 [&_blockquote]:border-l-4
              [&_blockquote]:border-slate-200 [&_blockquote]:bg-slate-50 [&_blockquote]:text-slate-700
              [&_hr]:my-6 [&_hr]:border-0 [&_hr]:border-t [&_hr]:border-slate-200
              [&_img]:max-w-full [&_img]:h-auto
              [&_table]:w-full [&_table]:border-collapse [&_table]:my-4
              [&_th]:border [&_th]:border-slate-200 [&_th]:px-3 [&_th]:py-2 [&_th]:text-left
              [&_td]:border [&_td]:border-slate-200 [&_td]:px-3 [&_td]:py-2
              text-slate-800
              dark:text-slate-200
              dark:[&_pre]:bg-slate-900
              dark:[&_code]:bg-slate-900
              dark:[&_blockquote]:bg-slate-900 dark:[&_blockquote]:border-slate-600 dark:[&_blockquote]:text-slate-300
              dark:[&_hr]:border-slate-600
              dark:[&_th]:border-slate-600 dark:[&_td]:border-slate-600
            "
        >
            <ReactMarkdown
                remarkPlugins={[remarkGfm, remarkBreaks]}
                // detect:false -- 언어를 안 적은 펜스는 추측하지 않고 평문으로 둔다.
                // 추측을 켜면 짧은 조각이 엉뚱한 언어로 칠해진다.
                // ignoreMissing:true -- 모르는 언어여도 던지지 않고 평문으로 넘긴다.
                rehypePlugins={[[rehypeHighlight, { detect: false, ignoreMissing: true }]]}
            >
                {content}
            </ReactMarkdown>
        </div>
    )
}
