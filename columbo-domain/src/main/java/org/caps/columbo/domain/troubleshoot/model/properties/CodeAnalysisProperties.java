package org.caps.columbo.domain.troubleshoot.model.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志+代码联合排查-本地代码分析配置（root-path 指向本地代码工程根目录）
 */
@Data
@ConfigurationProperties(prefix = "troubleshoot.code-analysis", ignoreInvalidFields = true)
public class CodeAnalysisProperties {

    /** 本地代码工程根目录，工具只能访问该目录下的文件 */
    private String rootPath = "";

    /** 代码副本每日自动更新开关（时间见 refresh-cron；仅对当前分支 git pull --ff-only，不改分支） */
    private Boolean refreshEnabled = Boolean.TRUE;

    /** readCodeFile 单次返回的最大行数（控制会话上下文膨胀，需要更长可分段读） */
    private Integer maxReadLines = 150;

    /** searchCode/findFiles 单次返回的最大命中条数 */
    private Integer maxMatches = 50;

    /** 单文件读取大小上限（字节），超过则跳过 */
    private Integer maxFileBytes = 524288;

    /**
     * 工程注册表：key=根目录下的工程目录名。
     * description 供模型理解工程用途。
     */
    private Map<String, Project> projects = new LinkedHashMap<>();

    /** 话术路由词典：环境词/业务线词 → CLS 应用 + 本地代码位置 */
    private Routing routing = new Routing();

    @Data
    public static class Project {

        /** 工程说明，如: 订单中心后台管理接口系统 */
        private String description;
    }

    @Data
    public static class Routing {

        /** 环境词典：生产/预发/多区域等 */
        private List<Environment> environments = new ArrayList<>();

        /** 业务线词典：订单/支付/用户等 */
        private List<BusinessLine> businessLines = new ArrayList<>();
    }

    @Data
    public static class Environment {

        /** 环境名，如: 生产（xx业务） */
        private String name;

        /** 口语关键词，命中任意即视为该环境（忽略大小写，contains 匹配） */
        private List<String> keywords;

        /** 腾讯云CLS应用名前缀（完整应用名 = 前缀 + "." + 站点后缀，与 CLS TopicName 对应）；未接CLS的环境不配置 */
        private String clsAppPrefix;

        /** 该环境代码副本的根目录（工具传 environment 时读取这里） */
        private String codePath;

        /** 该环境代码副本跟踪的 git 分支名（说明用），如: master、release */
        private String codeBranch;

        /** 补充说明 */
        private String note;
    }

    @Data
    public static class BusinessLine {

        /** 业务线名，如: 订单交易 */
        private String name;

        /** 口语关键词，命中任意即视为该业务线 */
        private List<String> keywords;

        /** 业务说明 */
        private String description;

        /** 该业务线涉及的站点/服务列表 */
        private List<Station> stations = new ArrayList<>();
    }

    @Data
    public static class Station {

        /** 站点名，如: 订单服务、支付网关 */
        private String name;

        /** 该站点在本业务线中的职责 */
        private String role;

        /** CLS 应用名后缀，完整应用名 = 环境前缀 + "." + 后缀，如 order.web */
        private String appSuffix;

        /** 本地工程目录名（code-analysis.projects 的 key） */
        private String localProject;

        /** 建议搜索的代码位置/关键词提示 */
        private String searchHints;
    }

}
