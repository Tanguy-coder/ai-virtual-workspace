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
                - Make every task as small as possible. Each one is done by one agent in a single run of at most %d tokens \
                of model calls (about %d tool steps), possibly on a small, cheap model with a short context. If a piece of work \
                might not fit, split it.
                - One task = one focused change: a few files (aim for 4 or fewer), its own tests, 1 to 4 acceptance criteria. \
                A feature becomes several tasks (model, endpoint, UI, ...) linked by dependsOn.
                - Keep texts terse: description at most 2 sentences, brief at most 1 sentence.""".formatted(budget, steps(budget));
    }
}
