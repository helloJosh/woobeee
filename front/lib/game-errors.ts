/**
 * 게임 화면들이 공유하는 두 가지 작은 유틸 — 오류 문구 만들기와 유니온 소진 검사.
 *
 * gameAPI의 호출은 apiRequest(front/lib/api.ts)에 suppressAlert=true를 넘겨서 blocking
 * alert()를 켜지 않는다 — 게임 화면들은 인라인 배너로 안내하기 때문이다. 그래도 4xx/401은
 * 여전히 getFriendlyErrorMessage로 만든 친절한 메시지가 담긴 Error를 던지므로 그 메시지를
 * 그대로 쓰면 된다. 반면 백엔드 다운·오프라인·CORS 같은 네트워크 레벨 실패는 apiRequest에
 * 닿기도 전에 fetch가 던지는 TypeError("Failed to fetch" 등)가 그대로 올라오는데, 이 경로는
 * 친절한 메시지가 없다 — 그래서 이 경우만 별도로 안내 문구를 채운다.
 */
export const NETWORK_ERROR_MESSAGE =
    "서버에 연결할 수 없습니다. 네트워크 상태를 확인하고 다시 시도해 주세요."

export function describeGameApiError(error: unknown, fallback: string): string {
    if (error instanceof TypeError) {
        return NETWORK_ERROR_MESSAGE
    }
    if (error instanceof Error && error.message) {
        return error.message
    }
    return fallback
}

/**
 * switch(outcome.kind)에 default가 없으면 새 kind가 추가돼도 TS가 조용히 넘어간다.
 * default: assertNever(outcome) 으로 두면, 처리 안 된 분기가 outcome을 never로 좁히지
 * 못해 컴파일 타임에 잡힌다.
 */
export function assertNever(value: never): never {
    throw new Error(`Unhandled variant: ${JSON.stringify(value)}`)
}
