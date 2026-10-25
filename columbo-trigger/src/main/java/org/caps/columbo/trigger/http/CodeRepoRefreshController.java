package org.caps.columbo.trigger.http;

import org.caps.columbo.api.response.Response;
import org.caps.columbo.domain.troubleshoot.service.CodeRepoRefreshService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 本地代码副本刷新：手动触发对全部环境根目录下的 git 工程执行 git pull --ff-only
 * （每日 07:30 由 CodeRepoRefreshService 定时自动执行，这里是应急补拉入口，
 * 例如排查前怀疑代码过期、或定时窗口机器没开机时手动触发一次）。
 */
@Slf4j
@Tag(name = "智能体服务", description = "本地代码副本刷新")
@RestController
@RequestMapping("/api/v1/")
public class CodeRepoRefreshController {

    @Resource
    private CodeRepoRefreshService codeRepoRefreshService;

    @Operation(summary = "刷新本地代码副本", description = "对各环境根目录下所有 git 工程执行 git pull --ff-only，返回逐工程结果")
    @PostMapping("code_repo_refresh")
    public Response<RefreshResponse> refresh() {
        log.info("手动刷新本地代码副本");
        CodeRepoRefreshService.RefreshSummary summary = codeRepoRefreshService.refreshAll("手动触发");
        return Response.success(new RefreshResponse(
                summary.trigger(),
                summary.results(),
                summary.updated(), summary.upToDate(), summary.failed(), summary.skipped(),
                summary.costMs()));
    }

    public record RefreshResponse(String trigger,
                                  List<CodeRepoRefreshService.RepoResult> results,
                                  long updated,
                                  long upToDate,
                                  long failed,
                                  long skipped,
                                  long costMs) {
    }
}
