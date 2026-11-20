package dev.aiw.orchestration;

/**
 * How small a task must be (CTO requirement, 2026-11-10): one task fits in one run's token budget, even on a small,
 * cheap model, so any provider can carry a big project one small task at a time. The rules go into the Lead's
 * planning and splitting prompts; the budget is the one each run actually gets.
 */
final class TaskSizing {
    /** Rough cost of one step of a run: the prompt grows with the history, so this is an average, not a minimum. */
    static final long TOKENS_PER_STEP = 8_000;

    private TaskSizing() {}

    static long steps(long budget) {
        return Math.max(5, budget / TOKENS_PER_STEP);
    }

    static String rules(long budget) {
        return """
                - Hard limit: a task never uses more than %d tokens (about %d steps); it is never raised. Plan each task \
                to need at most HALF of it (about %d tool steps on a small, cheap model with a very short context), so \
                retries and fixes still fit. A task that reaches the limit is cut into smaller tasks.
                - Make every task the smallest that still delivers something testable: when a task could be cut into two \
                tasks that are each testable, cut it.
                - One task = one behavior: at most 2 source files plus their tests, about 100 changed lines, 1 to 3 \
                acceptance criteria that a test proves (never more than 3). A feature becomes several tasks (data, logic, \
                UI, wiring) linked by dependsOn; a task that changes an existing file names it in its description.
                - Keep texts terse: description at most 2 sentences, brief at most 1 sentence.""".formatted(budget, steps(budget), steps(budget / 2));
    }
}
