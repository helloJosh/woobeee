import type React from "react"
import type { Metadata } from "next"
import { Inter } from "next/font/google"
import "./globals.css"
import { ThemeProvider } from "@/components/theme-provider"
import { AuthProvider } from "@/hooks/use-auth"

const inter = Inter({ subsets: ["latin"] })

export const metadata: Metadata = {
    title: "Woobeee Blog",
    description: "Backend developer의 개벌 배포의 기록을 남기는 기술 블로그",
    verification: {
        google: "2RCEa6sNCl3hSwoaqa3-kYV3B0z179VjCqtKOADZB0A",
        other: {
            "naver-site-verification":
                "a779dbe8d6f1f31e69e41bdac8670f8ffaaf473a",
        },
    },
    openGraph: {
        title: "Woobeee Blog",
        description: "개발과 기록을 남기는 기술 블로그",
        url: "https://woobeee.com",
        siteName: "Woobeee",
        images: [
            {
                url: "https://woobeee.com/og-image.png",
                width: 1200,
                height: 630,
                alt: "Woobeee Blog",
            },
        ],
        locale: "ko_KR",
        type: "website",
    },
}

export default function RootLayout({
                                     children,
                                   }: {
  children: React.ReactNode
}) {
  return (
      <html lang="ko">
      <body className={inter.className}>
      <ThemeProvider attribute="class" defaultTheme="light" enableSystem>
        <AuthProvider>{children}</AuthProvider>
      </ThemeProvider>
      </body>
      </html>
  )
}
