package org.caps.columbo.domain.troubleshoot.service;

import org.caps.columbo.domain.troubleshoot.model.properties.CodeAnalysisProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 本地代码副本每日自动更新：对全部环境根目录（root-path + routing 各环境 code-path）下的
 * git 工程执行 git pull --ff-only——只更新当前分支，不切分支、不合并本地改动（失败只告警）。
 * 保证排查助手 searchCode/readCodeFile 读到的代码与远端分支一致，避免按过期代码下结论。
 *
 * @author caps
 */
@Slf4j
@Service
public class CodeRepoRefreshService {

    /** 单个工程 pull 超时（秒）：大仓库慢网络也够用 */
    private static final int PULL_TIMEOUT_SECONDS = 120;

    private final CodeAnalysisProperties properties;

    public CodeRepoRefreshService(CodeAnalysisProperties properties) {
        this.properties = properties;
    }

    /** 每日定时更新（默认 07:30，troubleshoot.code-analysis.refresh-cron 可调） */
    @Scheduled(cron = "${troubleshoot.code-analysis.refresh-cron:0 30 7 * * ?}")
    public void scheduledRefresh() {
        if (!Boolean.TRUE.equals(properties.getRefreshEnabled())) {
            log.info("代码副本自动更新已关闭（troubleshoot.code-analysis.refresh-enabled=false），跳过");
            return;
        }
        refreshAll("每日定时");
    }

    /** 手动触发（供接口/运维调用），返回逐工程结果与汇总 */
    public RefreshSummary refreshAll(String trigger) {
        long start = System.currentTimeMillis();
        List<RepoResult> results = new ArrayList<>();
        for (String envRoot : collectEnvRoots()) {
            File root = new File(envRoot);
            File[] projects = root.listFiles(File::isDirectory);
            if (projects == null) {
                results.add(new RepoResult(envRoot, "-", "-", "SKIPPED", "根目录不存在: " + envRoot, 0));
                continue;
            }
            Arrays.sort(projects);
            for (File project : projects) {
                // 跳过隐藏目录（.idea/.git 等）
                if (project.getName().startsWith(".")) {
                    continue;
                }
                results.add(pullRepo(envRoot, project));
            }
        }
        long cost = System.currentTimeMillis() - start;
        RefreshSummary summary = new RefreshSummary(trigger, results, cost);
        log.info("代码副本更新完成 trigger={} 工程数={} 更新={} 已最新={} 失败={} 跳过={} 耗时={}ms",
                trigger, results.size(), summary.updated(), summary.upToDate(),
                summary.failed(), summary.skipped(), cost);
        return summary;
    }

    /** 汇总环境根目录：默认 root-path + 路由词典各环境 code-path（去重保持顺序） */
    private Set<String> collectEnvRoots() {
        Set<String> roots = new LinkedHashSet<>();
        if (properties.getRootPath() != null && !properties.getRootPath().isBlank()) {
            roots.add(properties.getRootPath().trim());
        }
        if (properties.getRouting() != null && properties.getRouting().getEnvironments() != null) {
            for (org.caps.columbo.domain.troubleshoot.model.properties.CodeAnalysisProperties.Environment env : properties.getRouting().getEnvironments()) {
                String path = env == null ? null : env.getCodePath();
                if (path != null && !path.isBlank()) {
                    roots.add(path.trim());
                }
            }
        }
        return roots;
    }

    private RepoResult pullRepo(String envRoot, File project) {
        long start = System.currentTimeMillis();
        if (!new File(project, ".git").exists()) {
            return new RepoResult(envRoot, project.getName(), "-", "SKIPPED", "非 git 目录", 0);
        }
        // 分支信息尽量保留（pull 失败时结果里也能看到是哪个分支）
        String branch = "-";
        try {
            branch = git(project, "rev-parse", "--abbrev-ref", "HEAD").trim();
            String output = git(project, "pull", "--ff-only");
            boolean upToDate = output.contains("Already up to date") || output.contains("已经是最新的");
            String detail = upToDate ? "当前分支已最新" : summarize(output);
            return new RepoResult(envRoot, project.getName(), branch,
                    upToDate ? "UP_TO_DATE" : "UPDATED", detail, System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 失败不阻断其他工程：本地改动未提交/无 upstream/网络不通等都会走到这里
            log.warn("代码副本更新失败 {}{}: {}", envRoot, File.separator, project.getName(), e);
            return new RepoResult(envRoot, project.getName(), branch, "FAILED",
                    abbreviate(e.getMessage()), System.currentTimeMillis() - start);
        }
    }

    /** 在工程目录执行 git 命令，返回合并后的标准输出（失败抛异常，异常消息含 git 输出尾巴） */
    private String git(File workDir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        boolean finished = process.waitFor(PULL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IllegalStateException("git " + args[0] + " 超时（>" + PULL_TIMEOUT_SECONDS + "s）");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("git " + args[0] + " 退出码 " + process.exitValue()
                    + ": " + abbreviate(output));
        }
        return output;
    }

    /** 多行 git 输出压成一行摘要（取前 160 字符，够定位问题即可） */
    private static String summarize(String output) {
        String line = output == null ? "" : output.trim().replaceAll("\\s+", " ");
        return line.length() <= 160 ? line : line.substring(0, 160) + "…";
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(trimmed.length() - 200);
    }

    /** 单工程结果 */
    public record RepoResult(String envRoot, String project, String branch,
                             String status, String detail, long costMs) {
    }

    /** 一次刷新的汇总 */
    public record RefreshSummary(String trigger, List<RepoResult> results, long costMs) {

        public long updated() {
            return count("UPDATED");
        }

        public long upToDate() {
            return count("UP_TO_DATE");
        }

        public long failed() {
            return count("FAILED");
        }

        public long skipped() {
            return count("SKIPPED");
        }

        private long count(String status) {
            return results.stream().filter(r -> status.equals(r.status())).count();
        }
    }
}
