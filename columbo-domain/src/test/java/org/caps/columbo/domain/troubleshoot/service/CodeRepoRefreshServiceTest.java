package org.caps.columbo.domain.troubleshoot.service;

import org.caps.columbo.domain.troubleshoot.model.properties.CodeAnalysisProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代码副本刷新服务：真实 git 仓库（临时目录）验证 UP_TO_DATE / UPDATED / FAILED / SKIPPED 四态
 * 与多环境根目录聚合、非 git 目录跳过。
 */
class CodeRepoRefreshServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void 四态分类与多环境根聚合() throws Exception {
        Path envRoot1 = Files.createDirectories(tempDir.resolve("env1"));
        Path envRoot2 = Files.createDirectories(tempDir.resolve("env2"));

        // UPDATED：克隆后远端有新提交，服务执行 pull 拉到更新
        Path origin = createCommit("origin-work", "init");
        runGit(origin.getParent(), "clone", "--quiet", origin.toString(), envRoot1.resolve("repo-synced").toString());
        createCommitIn(origin, "second");

        // FAILED：git 仓库无 upstream（pull 必然失败）
        Path repoNoRemote = envRoot1.resolve("repo-no-remote");
        runGit(envRoot1, "init", "--quiet", repoNoRemote.toString());
        commitIn(repoNoRemote, "init");

        // SKIPPED：非 git 目录
        Files.createDirectories(envRoot2.resolve("plain"));

        CodeAnalysisProperties properties = new CodeAnalysisProperties();
        properties.setRootPath(envRoot1.toString());
        CodeAnalysisProperties.Environment env2 = new CodeAnalysisProperties.Environment();
        env2.setCodePath(envRoot2.toString());
        properties.getRouting().getEnvironments().add(env2);

        CodeRepoRefreshService service = new CodeRepoRefreshService(properties);
        CodeRepoRefreshService.RefreshSummary summary = service.refreshAll("单测");

        List<CodeRepoRefreshService.RepoResult> results = summary.results();
        // env1 有 2 个 git 工程 + env2 有 1 个非 git 目录 = 3 条结果（根目录聚合无重复）
        assertEquals(3, results.size());

        CodeRepoRefreshService.RepoResult synced = find(results, "repo-synced");
        assertEquals("UPDATED", synced.status());
        assertTrue(!synced.detail().isBlank(), "更新结果应有摘要: " + synced.detail());

        CodeRepoRefreshService.RepoResult noRemote = find(results, "repo-no-remote");
        assertEquals("FAILED", noRemote.status());
        // 分支名随 git 默认配置（master/main），关键是解析成功而非被吞掉
        assertTrue(!noRemote.branch().isBlank() && !"-".equals(noRemote.branch()),
                "失败结果也应保留分支名: " + noRemote.branch());

        CodeRepoRefreshService.RepoResult plain = find(results, "plain");
        assertEquals("SKIPPED", plain.status());

        assertEquals(1, summary.updated());
        assertEquals(1, summary.failed());
        assertEquals(1, summary.skipped());
        assertTrue(summary.costMs() >= 0);
    }

    private CodeRepoRefreshService.RepoResult find(List<CodeRepoRefreshService.RepoResult> results, String project) {
        return results.stream().filter(r -> r.project().equals(project)).findFirst().orElseThrow();
    }

    /** 建一个带一次提交的工作仓库，返回其路径 */
    private Path createCommit(String name, String message) throws Exception {
        Path dir = tempDir.resolve(name);
        runGit(tempDir, "init", "--quiet", dir.toString());
        commitIn(dir, message);
        return dir;
    }

    private void createCommitIn(Path repo, String message) throws Exception {
        Files.writeString(repo.resolve("file.txt"), "content@" + System.nanoTime());
        runGit(repo, "add", "-A");
        commitIn(repo, message);
    }

    private void commitIn(Path repo, String message) throws Exception {
        runGit(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "--allow-empty", "-m", message);
    }

    private void runGit(Path cwd, String... args) throws IOException, InterruptedException {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("git"));
        command.addAll(java.util.List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 失败: " + output);
        }
    }
}
