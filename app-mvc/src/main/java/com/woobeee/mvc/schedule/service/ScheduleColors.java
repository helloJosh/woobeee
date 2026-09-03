package com.woobeee.mvc.schedule.service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 할 일 고유색. 값 목록은 front/lib/schedule.ts 의 SCHEDULE_COLORS 와 동일해야 한다
 * (스펙 2026-09-01 §4 가 단일 출처). 24색 — 앞 17색은 tailwind 500 계열 색상환 전체,
 * 뒤 7색은 갈색·슬레이트와 짙은 톤.
 */
public final class ScheduleColors {
    public static final List<String> PALETTE = List.of(
            "#ef4444", "#f97316", "#f59e0b", "#eab308", "#84cc16", "#22c55e",
            "#10b981", "#14b8a6", "#06b6d4", "#0ea5e9", "#3b82f6", "#6366f1",
            "#8b5cf6", "#a855f7", "#d946ef", "#ec4899", "#f43f5e", "#92400e",
            "#64748b", "#991b1b", "#166534", "#115e59", "#1e40af", "#6b21a8");

    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private ScheduleColors() {
    }

    public static String randomColor() {
        return PALETTE.get(ThreadLocalRandom.current().nextInt(PALETTE.size()));
    }

    public static boolean isValidHex(String color) {
        return color != null && HEX.matcher(color).matches();
    }
}
