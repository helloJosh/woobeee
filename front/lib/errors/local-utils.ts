export const getBrowserLocale = (): "ko" | "en" => {
    if (typeof window === "undefined") return "en" // SSR-safe

    const lang = navigator.language || navigator.languages?.[0] || "en"
    return lang.startsWith("ko") ? "ko" : "en"
}