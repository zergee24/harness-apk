package com.harnessapk.workbrief

/** 简报状态（设计 §8.1）。 */
enum class WorkBriefStatus {
    DRAFT,
    CAPTURING,
    PROCESSING,
    READY,
    RECOVERABLE,
    DELETING,
    DELETED,
    CORRUPTED,
}

/** 记录场次状态（设计 §8.2）。 */
enum class CaptureSessionStatus {
    PREPARING,
    ACTIVE,
    PAUSED,
    STOPPING,
    SEALED,
    RECOVERABLE,
    FAILED,
}

/** 用户标记四型（设计 §5.4）。 */
enum class UserMarkerType {
    DECISION,
    QUESTION,
    TODO,
    BOOKMARK,
}

/** 简报/场次状态机（纯逻辑，JVM 可测）。 */
object BriefStateMachine {

    val briefTransitions: Map<WorkBriefStatus, Set<WorkBriefStatus>> = mapOf(
        WorkBriefStatus.DRAFT to setOf(WorkBriefStatus.CAPTURING, WorkBriefStatus.DELETING),
        WorkBriefStatus.CAPTURING to setOf(
            WorkBriefStatus.PROCESSING,
            WorkBriefStatus.RECOVERABLE,
            WorkBriefStatus.DELETING,
        ),
        WorkBriefStatus.PROCESSING to setOf(
            WorkBriefStatus.READY,
            WorkBriefStatus.RECOVERABLE,
            WorkBriefStatus.DELETING,
        ),
        // READY -> PROCESSING：生成新修订或纪要候选
        WorkBriefStatus.READY to setOf(WorkBriefStatus.PROCESSING, WorkBriefStatus.DELETING),
        WorkBriefStatus.RECOVERABLE to setOf(
            WorkBriefStatus.CAPTURING,
            WorkBriefStatus.PROCESSING,
            WorkBriefStatus.READY,
            WorkBriefStatus.DELETING,
        ),
        WorkBriefStatus.DELETING to setOf(WorkBriefStatus.DELETED),
        WorkBriefStatus.CORRUPTED to emptySet(),
        WorkBriefStatus.DELETED to emptySet(),
    )

    val sessionTransitions: Map<CaptureSessionStatus, Set<CaptureSessionStatus>> = mapOf(
        CaptureSessionStatus.PREPARING to setOf(
            CaptureSessionStatus.ACTIVE,
            CaptureSessionStatus.RECOVERABLE,
            CaptureSessionStatus.FAILED,
        ),
        CaptureSessionStatus.ACTIVE to setOf(
            CaptureSessionStatus.PAUSED,
            CaptureSessionStatus.STOPPING,
            CaptureSessionStatus.RECOVERABLE,
            CaptureSessionStatus.FAILED,
        ),
        CaptureSessionStatus.PAUSED to setOf(
            CaptureSessionStatus.ACTIVE,
            CaptureSessionStatus.STOPPING,
            CaptureSessionStatus.RECOVERABLE,
            CaptureSessionStatus.FAILED,
        ),
        CaptureSessionStatus.STOPPING to setOf(CaptureSessionStatus.SEALED),
        CaptureSessionStatus.SEALED to emptySet(),
        CaptureSessionStatus.RECOVERABLE to setOf(CaptureSessionStatus.ACTIVE, CaptureSessionStatus.FAILED),
        CaptureSessionStatus.FAILED to emptySet(),
    )

    fun canTransitionBrief(from: WorkBriefStatus, to: WorkBriefStatus): Boolean =
        briefTransitions.getValue(from).contains(to)

    fun canTransitionSession(from: CaptureSessionStatus, to: CaptureSessionStatus): Boolean =
        sessionTransitions.getValue(from).contains(to)

    fun requireBriefTransition(from: WorkBriefStatus, to: WorkBriefStatus) {
        check(canTransitionBrief(from, to)) { "简报状态不允许 $from -> $to" }
    }

    fun requireSessionTransition(from: CaptureSessionStatus, to: CaptureSessionStatus) {
        check(canTransitionSession(from, to)) { "场次状态不允许 $from -> $to" }
    }
}
