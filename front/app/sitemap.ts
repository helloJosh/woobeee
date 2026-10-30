import type { MetadataRoute } from "next"
import { fetchPosts } from "@/lib/server-blog"

// robots.ts 가 가리키는 /sitemap.xml. 빌드 때 백엔드가 없을 수 있으니 요청 시점에 만든다.
export const dynamic = "force-dynamic"

const SITE = "https://woobeee.com"
const PAGE_SIZE = 50
const MAX_PAGES = 100 // 폭주 방지 — 5,000 글

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
    const entries: MetadataRoute.Sitemap = [
        { url: `${SITE}/blog`, changeFrequency: "daily", priority: 1 },
    ]

    for (let page = 0; page < MAX_PAGES; page++) {
        const res = await fetchPosts({ page, size: PAGE_SIZE })
        if (!res) break
        for (const post of res.contents) {
            entries.push({
                url: `${SITE}/blog/${post.id}`,
                lastModified: post.createdAt ? new Date(post.createdAt) : undefined,
                changeFrequency: "weekly",
                priority: 0.8,
            })
        }
        if (!res.hasNext) break
    }
    return entries
}
