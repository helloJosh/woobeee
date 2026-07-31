import { errorMessageMap } from "./error-messages"

export const getFriendlyErrorMessage = (
    messageKey?: string,
    locale: "ko" | "en" = "ko"
): string => {
    if (!messageKey) return locale === "en" ? "An error occurred." : "오류가 발생했습니다."

    const messages = errorMessageMap[locale]
    // @ts-ignore
    return messages?.[messageKey] || (locale === "en" ? "Unexpected error." : "예기치 못한 오류가 발생했습니다.")
}