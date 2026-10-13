package io.github.houseelves.desk.thread;

import java.util.List;

/**
 * The agent's fix for an issue, to open as a PR: one commit on the repo's default branch, pushed to a new branch on
 * your fork.
 *
 * @param repo     the issue's repo, where the PR opens
 * @param base     its default branch, the PR's base
 * @param headRepo your fork (owner/name), worked out when you preview
 * @param headRef  the new branch on it, worked out when you preview
 * @param from     the default branch commit the fix is on
 * @param to       the fix, one commit on top of {@code from}
 */
public record PrOut(String repo, String base, String headRepo, String headRef, String title, String body, String from,
        String to, List<PushOut.Commit> commits, String diffstat) {

    public PrOut withTarget(String newHeadRepo, String newHeadRef) {
        return new PrOut(repo, base, newHeadRepo, newHeadRef, title, body, from, to, commits, diffstat);
    }

    public PrOut withText(String newTitle, String newBody) {
        return new PrOut(repo, base, headRepo, headRef, newTitle, newBody, from, to, commits, diffstat);
    }
}
