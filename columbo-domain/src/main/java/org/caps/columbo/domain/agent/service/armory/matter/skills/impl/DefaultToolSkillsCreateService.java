package org.caps.columbo.domain.agent.service.armory.matter.skills.impl;

import org.caps.columbo.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.caps.columbo.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI Community 构建skills <a href="https://github.com/spring-ai-community/spring-ai-agent-utils">spring-ai-agent-utils</a>
 * <p>
 * type=directory：直接用文件系统路径（生产部署推荐，skills 外置）；
 * type=resource：classpath 路径，IDE/解压运行时直接读取；打成 fat jar 后 classpath 资源没有文件系统路径，
 * 这里会自动把资源目录解压到临时目录再挂载，保证 jar 部署可用。
 *
 * @author caps
 * 2026/2/6 08:04
 */
@Slf4j
@Service
public class DefaultToolSkillsCreateService implements ToolSkillsCreateService {

    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ToolSkills toolSkills) throws Exception {

        String type = toolSkills.getType();
        String path = toolSkills.getPath();

        List<ToolCallback> toolCallbackList = new ArrayList<>();

        if ("directory".equals(type)) {
            ToolCallback toolCallback = SkillsTool.builder()
                    .addSkillsDirectory(path)
                    .build();
            toolCallbackList.add(toolCallback);
        }

        if ("resource".equals(type)) {
            ToolCallback toolCallback = SkillsTool.builder()
                    .addSkillsResource(resolveReadableResource(path))
                    .build();
            toolCallbackList.add(toolCallback);
        }

        return toolCallbackList.toArray(new ToolCallback[0]);
    }

    /**
     * classpath 资源优先按文件系统路径返回（IDE / 解压部署）；
     * fat jar 内没有文件系统路径，解压到临时目录后返回该目录资源
     */
    private Resource resolveReadableResource(String path) throws IOException {
        Resource resource = new ClassPathResource(path);
        if (resource.exists()) {
            try {
                resource.getFile();
                return resource;
            } catch (FileNotFoundException ignored) {
                // 嵌套 jar 资源，走解压
            }
        }
        return new FileSystemResource(extractToTempDirectory(path).toFile());
    }

    /** 把 classpath 目录递归解压到临时目录，返回目标目录 */
    private Path extractToTempDirectory(String path) throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:" + path + "/**");

        Path targetDir = Files.createTempDirectory("scaffold-skills-");
        int baseIndex = path.length();
        int copied = 0;
        for (Resource resource : resources) {
            String uri = resource.getURI().toString();
            int idx = uri.indexOf(path);
            if (idx < 0 || !resource.isReadable() || uri.endsWith("/")) {
                continue; // 跳过目录项与无法定位相对路径的条目
            }
            Path target = targetDir.resolve(uri.substring(idx + baseIndex).replaceFirst("^/", ""));
            Files.createDirectories(target.getParent());
            try (var input = resource.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }
        log.info("skills 从 classpath[{}] 解压到临时目录[{}]，共 {} 个文件", path, targetDir, copied);
        return targetDir;
    }

}
