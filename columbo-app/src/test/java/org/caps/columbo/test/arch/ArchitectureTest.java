package org.caps.columbo.test.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构守护（ArchUnit）：把六边形架构的依赖方向固化成测试。
 * 谁在 domain 里 import 了 infrastructure 的实现类、或在 infrastructure 里偷懒依赖 trigger/api，
 * 构建期立即红灯，而不是靠 code review 肉眼把关。
 *
 * <p>规则与模块依赖方向一一对应：app → trigger + infrastructure；trigger → api/domain/types；
 * infrastructure → domain；domain → types。app 模块（org.caps.columbo.Application 与 config 包）
 * 是组合根，允许依赖一切，不做限制。
 *
 * <p>纯静态分析，不依赖外网/密钥；由 surefire architecture-guard 执行保证始终运行
 * （app 其余测试依赖真实网关，默认跳过）。
 *
 * @author caps
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.caps.columbo");
    }

    @Test
    void typesMustNotDependOnAnyOtherModule() {
        ArchRule rule = noClasses().that().resideInAPackage("..columbo.types..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..columbo.domain..", "..columbo.infrastructure..", "..columbo.trigger..", "..columbo.api..");
        rule.check(classes);
    }

    @Test
    void apiMustNotDependOnImplementationModules() {
        // api 是对外契约（接口+DTO）：只允许依赖 types，实现细节不得泄漏进契约
        ArchRule rule = noClasses().that().resideInAPackage("..columbo.api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..columbo.domain..", "..columbo.infrastructure..", "..columbo.trigger..");
        rule.check(classes);
    }

    @Test
    void domainMustNotDependOnAdapters() {
        // 六边形内核：domain 定义端口（ISessionStorePort 等），不得反向依赖适配器/触发层/组合根
        ArchRule rule = noClasses().that().resideInAPackage("..columbo.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..columbo.infrastructure..", "..columbo.trigger..", "..columbo.api..", "org.caps.columbo.config..");
        rule.check(classes);
    }

    @Test
    void infrastructureMustNotDependOnTriggerOrApi() {
        // infrastructure 只实现 domain 的端口，不感知 HTTP 触发层与对外契约
        ArchRule rule = noClasses().that().resideInAPackage("..columbo.infrastructure..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..columbo.trigger..", "..columbo.api..", "org.caps.columbo.config..");
        rule.check(classes);
    }

    @Test
    void triggerMustNotDependOnInfrastructure() {
        // 触发层编排 domain 服务，持久化/网关细节只能经 domain 端口间接可达
        ArchRule rule = noClasses().that().resideInAPackage("..columbo.trigger..")
                .should().dependOnClassesThat().resideInAnyPackage("..columbo.infrastructure..");
        rule.check(classes);
    }

    // ============ 以下为 COLA 组件位置守护（技术组件各归其位，AI 改代码时防走样） ============

    @Test
    void webControllersMustLiveInTriggerOnly() {
        // Controller 属于 Adapter 层：业务编排进 domain，其他模块出现 Controller 即走样
        ArchRule rule = noClasses().that().resideOutsideOfPackage("..columbo.trigger..")
                .should().beAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .orShould().beAnnotatedWith("org.springframework.web.bind.annotation.Controller");
        rule.check(classes);
    }

    @Test
    void mybatisMappersMustLiveInInfrastructureOnly() {
        // Mapper/DAO 是持久化适配器，只允许出现在 infrastructure；domain 经端口（ISessionStorePort）间接访问
        ArchRule rule = noClasses().that().resideOutsideOfPackage("..columbo.infrastructure..")
                .should().beAssignableTo(com.baomidou.mybatisplus.core.mapper.BaseMapper.class);
        rule.check(classes);
    }

    @Test
    void domainMustStayFreeOfHttpAndWebStack() {
        // 领域内核零技术依赖（COLA 最核心的约束）：HTTP 客户端/Servlet 技术栈不得进 domain，
        // 网关调用一律走端口（如 IEmbeddingPort），实现放 infrastructure
        ArchRule rule = noClasses().that().resideInAPackage("..columbo.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("java.net.http..", "org.springframework.web..");
        rule.check(classes);
    }

    @Test
    void apiAndTypesMustStaySpringFree() {
        // 对外契约（api）与公共类型（types）不沾 Spring 组件注解：api 只有接口+DTO，types 只有纯 JDK 工具
        ArchRule rule = noClasses().that().resideInAnyPackage("..columbo.api..", "..columbo.types..")
                .should().beAnnotatedWith("org.springframework.stereotype.Component")
                .orShould().beAnnotatedWith("org.springframework.stereotype.Service")
                .orShould().beAnnotatedWith("org.springframework.context.annotation.Configuration");
        rule.check(classes);
    }
}
