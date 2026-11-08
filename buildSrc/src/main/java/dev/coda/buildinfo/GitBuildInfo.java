package dev.coda.buildinfo;

/** Build metadata also works in source archives and on machines without git. */
public final class GitBuildInfo {
    public interface Git { String run(String... args) throws Exception; }
    public final String commit, describe, tag, count;
    public GitBuildInfo(Git git) {
        commit = read(git, "rev-parse", "--short=12", "HEAD");
        describe = read(git, "describe", "--tags", "--always", "--dirty");
        tag = read(git, "describe", "--tags", "--exact-match", "HEAD");
        count = read(git, "rev-list", "--count", "HEAD");
    }
    private static String read(Git git, String... args) {
        try { String value = git.run(args).trim(); return value.isEmpty() ? "unknown" : value; }
        catch (Exception unavailable) { return "unknown"; }
    }
    public int debugVersionCode(int fallback) {
        try { int n = Integer.parseInt(count); return n > 0 ? n : fallback; }
        catch (NumberFormatException invalid) { return fallback; }
    }
}
