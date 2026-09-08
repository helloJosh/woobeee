package com.woobeee.mvc.schedule.entity;

/**
 * 세 층(프로젝트·마일스톤·할 일)이 공유하는 상태. 프론트 lib/schedule.ts 의 ScheduleStatus 와
 * 세 테이블의 status CHECK 제약(V12)과 같아야 한다.
 * <p>
 * {@code ON_HOLD}(보류)·{@code ERROR}(오류)는 사용자가 일부러 세워 둔 상태다 — 기한이 지나도
 * 자동 완료(SCHEDULE-AC-21)와 다이제스트의 기한 경과 목록이 건드리지 않는다 (SCHEDULE-AC-39).
 */
public enum ScheduleStatus {
    NOT_STARTED,
    IN_PROGRESS,
    DONE,
    ON_HOLD,
    ERROR
}
