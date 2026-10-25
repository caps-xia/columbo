package org.caps.columbo.domain.agent.service.armory.matter.tools;

import org.caps.columbo.domain.troubleshoot.model.properties.ClsProperties;
import org.caps.columbo.domain.troubleshoot.model.properties.CodeAnalysisProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地代码分析工具单元测试（@TempDir 构造迷你代码工程）
 */
class LocalCodeToolsTest {

    @TempDir
    Path tempDir;

    /** 环境代码副本专用目录（与默认根目录隔离，模拟另一套环境副本） */
    @TempDir
    Path envDir;

    private LocalCodeTools tools;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tempDir.resolve("projA/src/main/java/demo"));
        Files.writeString(tempDir.resolve("projA/src/main/java/demo/OrderController.java"), """
                package demo;

                public class OrderController {

                    public String createOrder(String orderId) {
                        if (orderId == null) {
                            throw new IllegalArgumentException("订单号为空 ORDER_EMPTY");
                        }
                        return "ok:" + orderId;
                    }
                }
                """);
        // target 构建产物与 node_modules 应被跳过
        Files.createDirectories(tempDir.resolve("projA/target/classes/demo"));
        Files.writeString(tempDir.resolve("projA/target/classes/demo/OrderController.java"), "ORDER_EMPTY in target");
        Files.createDirectories(tempDir.resolve("projB/web/node_modules"));
        Files.writeString(tempDir.resolve("projB/web/node_modules/lib.js"), "ORDER_EMPTY in node_modules");
        Files.writeString(tempDir.resolve("projB/web/app.js"), "function pay() { throw new Error('ORDER_EMPTY'); }");
        // 二进制文件（含 NUL 字节）应被跳过
        Files.write(tempDir.resolve("projA/src/main/java/demo/blob.bin"),
                new byte[]{'O', 'R', 'D', 'E', 'R', 0, 'E', 'M', 'P', 'T', 'Y'});

        // 生产环境代码副本（分支不同、内容有差异），放在独立目录避免污染默认根
        Files.createDirectories(envDir.resolve("env-prod/projA/src/main/java/demo"));
        Files.writeString(envDir.resolve("env-prod/projA/src/main/java/demo/OrderController.java"), """
                package demo;

                public class OrderController {

                    public String createOrder(String orderId) {
                        if (orderId == null) {
                            throw new IllegalArgumentException("PROD_ONLY_MARKER 生产分支订单异常");
                        }
                        return "ok-prod:" + orderId;
                    }
                }
                """);
        // 测试环境副本（仅此环境有的类，覆盖"文件在其他环境副本找到"提示）
        Files.createDirectories(envDir.resolve("env-qa/projA/src/main/java/demo"));
        Files.writeString(envDir.resolve("env-qa/projA/src/main/java/demo/QaOnlyService.java"), """
                package demo;

                public class QaOnlyService {
                    public String qa() { return "QA_ONLY_MARKER"; }
                }
                """);

        tools = newTools(300);
        ReflectionTestUtils.setField(tools, "clsProperties", clsProperties());
    }

    /** CLS 注册表：demo 站点只登记订单服务主题（用户服务故意不登记，覆盖 clsRegistered=false 分支） */
    private ClsProperties clsProperties() {
        ClsProperties properties = new ClsProperties();
        ClsProperties.Site demo = new ClsProperties.Site();
        demo.setLabel("演示站点");
        demo.getTopics().put("myapp.java.order", topicOf("11111111-2222-3333-4444-555555555555"));
        properties.getSites().put("demo", demo);
        return properties;
    }

    private static ClsProperties.Topic topicOf(String topicId) {
        ClsProperties.Topic topic = new ClsProperties.Topic();
        topic.setName("订单服务");
        topic.setTopicId(topicId);
        return topic;
    }

    private CodeAnalysisProperties.Routing routing() {
        CodeAnalysisProperties.Routing routing = new CodeAnalysisProperties.Routing();

        CodeAnalysisProperties.Environment prod = new CodeAnalysisProperties.Environment();
        prod.setName("生产环境");
        prod.setKeywords(List.of("生产", "prod", "线上"));
        prod.setClsAppPrefix("myapp.java");
        prod.setCodePath(envDir.resolve("env-prod").toString());
        prod.setCodeBranch("master");
        routing.getEnvironments().add(prod);

        CodeAnalysisProperties.Environment qa = new CodeAnalysisProperties.Environment();
        qa.setName("测试环境");
        qa.setKeywords(List.of("测试", "qa", "预发"));
        qa.setClsAppPrefix("myapp.java");
        qa.setCodePath(envDir.resolve("env-qa").toString());
        qa.setCodeBranch("qa");
        routing.getEnvironments().add(qa);

        CodeAnalysisProperties.Station orderStation = new CodeAnalysisProperties.Station();
        orderStation.setName("订单服务");
        orderStation.setRole("下单/订单状态/履约");
        orderStation.setAppSuffix("order");
        orderStation.setLocalProject("projA");
        orderStation.setSearchHints("OrderController、placeOrder");

        CodeAnalysisProperties.Station userStation = new CodeAnalysisProperties.Station();
        userStation.setName("用户服务");
        userStation.setRole("注册登录/会员信息");
        userStation.setAppSuffix("user");
        userStation.setLocalProject("projB");
        userStation.setSearchHints("UserController、login");

        CodeAnalysisProperties.BusinessLine order = new CodeAnalysisProperties.BusinessLine();
        order.setName("订单交易");
        order.setKeywords(List.of("订单", "下单", "支付", "退款"));
        order.setStations(List.of(orderStation, userStation));
        routing.getBusinessLines().add(order);
        return routing;
    }

    private LocalCodeTools newTools(int maxReadLines) {
        return newTools(maxReadLines, routing());
    }

    private LocalCodeTools newTools(int maxReadLines, CodeAnalysisProperties.Routing routing) {
        CodeAnalysisProperties properties = new CodeAnalysisProperties();
        properties.setRootPath(tempDir.toString());
        properties.setMaxReadLines(maxReadLines);
        properties.setMaxMatches(50);
        properties.setMaxFileBytes(524288);
        CodeAnalysisProperties.Project projA = new CodeAnalysisProperties.Project();
        projA.setDescription("工程A");
        properties.getProjects().put("projA", projA);
        properties.setRouting(routing);

        LocalCodeTools localCodeTools = new LocalCodeTools();
        ReflectionTestUtils.setField(localCodeTools, "properties", properties);
        return localCodeTools;
    }

    @Test
    void 未配置根目录时返回错误() {
        LocalCodeTools localCodeTools = new LocalCodeTools();
        CodeAnalysisProperties empty = new CodeAnalysisProperties();
        ReflectionTestUtils.setField(localCodeTools, "properties", empty);
        LocalCodeTools.ProjectListResult result = localCodeTools.listLocalProjects();
        assertFalse(result.getSuccess());
        assertNotNull(result.getErrorMsg());
    }

    @Test
    void 列出工程清单带说明() {
        LocalCodeTools.ProjectListResult result = tools.listLocalProjects();
        assertTrue(result.getSuccess());
        assertEquals(2, result.getProjects().size());
        LocalCodeTools.ProjectInfo projA = result.getProjects().stream()
                .filter(p -> "projA".equals(p.getDirName())).findFirst().orElseThrow();
        assertEquals("工程A", projA.getDescription());
    }

    @Test
    void 搜索命中源码并跳过构建产物与二进制() {
        LocalCodeTools.CodeSearchRequest request = new LocalCodeTools.CodeSearchRequest();
        request.setKeyword("ORDER_EMPTY");
        LocalCodeTools.CodeSearchResult result = tools.searchCode(request);
        assertTrue(result.getSuccess());
        assertEquals(2, result.getReturned());
        assertTrue(result.getMatches().stream().anyMatch(m ->
                m.getFile().endsWith("demo/OrderController.java") && m.getLineNo() == 7));
        assertTrue(result.getMatches().stream().anyMatch(m -> m.getFile().endsWith("web/app.js")));
        // target / node_modules / *.bin 均不出现
        result.getMatches().forEach(m -> {
            assertFalse(m.getFile().contains("target"));
            assertFalse(m.getFile().contains("node_modules"));
            assertFalse(m.getFile().endsWith(".bin"));
        });
    }

    @Test
    void 搜索限定工程与扩展名() {
        LocalCodeTools.CodeSearchRequest request = new LocalCodeTools.CodeSearchRequest();
        request.setKeyword("ORDER_EMPTY");
        request.setProjectName("projB");
        request.setFileExt("js");
        LocalCodeTools.CodeSearchResult result = tools.searchCode(request);
        assertTrue(result.getSuccess());
        assertEquals(1, result.getReturned());
        assertTrue(result.getMatches().get(0).getFile().endsWith("web/app.js"));
    }

    @Test
    void 搜索支持正则且非法正则报错() {
        LocalCodeTools.CodeSearchRequest request = new LocalCodeTools.CodeSearchRequest();
        request.setKeyword("Order.*Controller");
        request.setUseRegex(true);
        request.setFileExt("java");
        LocalCodeTools.CodeSearchResult result = tools.searchCode(request);
        assertTrue(result.getSuccess());
        assertTrue(result.getMatches().stream().anyMatch(m -> m.getLineNo() == 3));

        request.setKeyword("([invalid");
        LocalCodeTools.CodeSearchResult bad = tools.searchCode(request);
        assertFalse(bad.getSuccess());
        assertTrue(bad.getErrorMsg().contains("正则"));
    }

    @Test
    void 按行号区间读取文件() {
        LocalCodeTools.CodeFileReadRequest request = new LocalCodeTools.CodeFileReadRequest();
        request.setFilePath("projA/src/main/java/demo/OrderController.java");
        request.setStartLine(3);
        request.setEndLine(5);
        LocalCodeTools.CodeFileReadResult result = tools.readCodeFile(request);
        assertTrue(result.getSuccess());
        assertEquals(12, result.getTotalLines());
        String[] lines = result.getContent().stripTrailing().split("\n");
        assertEquals(3, lines.length);
        assertTrue(lines[0].startsWith("3\tpublic class OrderController"));
        assertTrue(result.getTruncated());
    }

    @Test
    void 读取行数超上限自动截断() {
        LocalCodeTools localCodeTools = newTools(4);
        LocalCodeTools.CodeFileReadRequest request = new LocalCodeTools.CodeFileReadRequest();
        request.setFilePath("projA/src/main/java/demo/OrderController.java");
        LocalCodeTools.CodeFileReadResult result = localCodeTools.readCodeFile(request);
        assertTrue(result.getSuccess());
        assertEquals(1, result.getStartLine());
        assertEquals(4, result.getEndLine());
        assertTrue(result.getTruncated());
    }

    @Test
    void 路径穿越被拒绝() {
        LocalCodeTools.CodeFileReadRequest request = new LocalCodeTools.CodeFileReadRequest();
        request.setFilePath("../outside.txt");
        LocalCodeTools.CodeFileReadResult result = tools.readCodeFile(request);
        assertFalse(result.getSuccess());
        assertTrue(result.getErrorMsg().contains("非法路径"));
    }

    @Test
    void 读取不存在文件返回错误() {
        LocalCodeTools.CodeFileReadRequest request = new LocalCodeTools.CodeFileReadRequest();
        request.setFilePath("projA/src/not-exist.java");
        LocalCodeTools.CodeFileReadResult result = tools.readCodeFile(request);
        assertFalse(result.getSuccess());
        assertTrue(result.getErrorMsg().contains("不存在"));
    }

    @Test
    void 按文件名正则定位文件() {
        LocalCodeTools.FileFindRequest request = new LocalCodeTools.FileFindRequest();
        request.setFileNamePattern(".*OrderController.*");
        LocalCodeTools.FileFindResult result = tools.findFiles(request);
        assertTrue(result.getSuccess());
        assertEquals(1, result.getReturned());
        assertEquals("projA/src/main/java/demo/OrderController.java", result.getFiles().get(0).getFile());
    }

    @Test
    void 话术路由解析环境加业务线为CLS应用与本地工程() {
        LocalCodeTools.RoutingRequest request = new LocalCodeTools.RoutingRequest();
        request.setEnvironment("生产环境");
        request.setBusinessLine("订单交易");
        LocalCodeTools.RoutingResult result = tools.resolveRouting(request);
        assertTrue(result.getSuccess());
        assertEquals(1, result.getEnvironments().size());
        assertEquals(2, result.getCandidates().size());

        LocalCodeTools.RoutingCandidate order = result.getCandidates().stream()
                .filter(c -> "订单服务".equals(c.getStation())).findFirst().orElseThrow();
        assertEquals("myapp.java.order", order.getClsAppName());
        assertTrue(order.getClsRegistered());
        assertEquals("projA", order.getLocalProject());
        assertEquals("master", order.getCodeBranch());

        // 用户服务未在 CLS 注册表登记主题，标记为不可查
        LocalCodeTools.RoutingCandidate user = result.getCandidates().stream()
                .filter(c -> "用户服务".equals(c.getStation())).findFirst().orElseThrow();
        assertNull(user.getClsAppName());
        assertFalse(user.getClsRegistered());
    }

    @Test
    void 话术路由未识别环境列出全部候选() {
        LocalCodeTools.RoutingRequest request = new LocalCodeTools.RoutingRequest();
        request.setEnvironment("边缘节点");
        request.setBusinessLine("订单交易");
        LocalCodeTools.RoutingResult result = tools.resolveRouting(request);
        assertTrue(result.getSuccess());
        assertEquals(2, result.getEnvironments().size());
        assertTrue(result.getNotes().stream().anyMatch(n -> n.contains("未识别环境")));
    }

    @Test
    void 话术路由现象兜底识别业务线() {
        LocalCodeTools.RoutingRequest request = new LocalCodeTools.RoutingRequest();
        request.setEnvironment("生产");
        request.setSymptom("用户下单失败");
        LocalCodeTools.RoutingResult result = tools.resolveRouting(request);
        assertTrue(result.getSuccess());
        assertEquals(2, result.getCandidates().size());
        result.getCandidates().forEach(c -> assertEquals("订单交易", c.getBusinessLine()));
    }

    @Test
    void 话术路由未注入CLS注册表不报错() {
        LocalCodeTools bare = newTools(300);
        LocalCodeTools.RoutingRequest request = new LocalCodeTools.RoutingRequest();
        request.setEnvironment("生产");
        request.setBusinessLine("订单交易");
        LocalCodeTools.RoutingResult result = bare.resolveRouting(request);
        assertTrue(result.getSuccess());
        result.getCandidates().forEach(c -> assertFalse(c.getClsRegistered()));
    }

    @Test
    void 话术路由返回环境代码分支() {
        LocalCodeTools.RoutingRequest request = new LocalCodeTools.RoutingRequest();
        request.setEnvironment("生产");
        request.setBusinessLine("订单交易");
        LocalCodeTools.RoutingResult result = tools.resolveRouting(request);
        assertTrue(result.getSuccess());
        assertEquals("master", result.getEnvironments().get(0).getCodeBranch());
        result.getCandidates().forEach(c -> assertEquals("master", c.getCodeBranch()));
    }

    @Test
    void 传环境参数搜索对应代码副本() {
        LocalCodeTools.CodeSearchRequest request = new LocalCodeTools.CodeSearchRequest();
        request.setEnvironment("生产");
        request.setKeyword("PROD_ONLY_MARKER");
        LocalCodeTools.CodeSearchResult result = tools.searchCode(request);
        assertTrue(result.getSuccess());
        assertEquals(1, result.getReturned());
        assertEquals("master", result.getBranch());
        assertTrue(result.getMatches().get(0).getFile().endsWith("demo/OrderController.java"));
        assertTrue(result.getMatches().get(0).getLineText().contains("PROD_ONLY_MARKER"));

        // 同一关键词在默认目录（root-path）搜不到
        LocalCodeTools.CodeSearchRequest noEnv = new LocalCodeTools.CodeSearchRequest();
        noEnv.setKeyword("PROD_ONLY_MARKER");
        assertEquals(0, tools.searchCode(noEnv).getReturned().intValue());
    }

    @Test
    void 按环境读取文件() {
        LocalCodeTools.CodeFileReadRequest request = new LocalCodeTools.CodeFileReadRequest();
        request.setEnvironment("生产");
        request.setFilePath("projA/src/main/java/demo/OrderController.java");
        LocalCodeTools.CodeFileReadResult result = tools.readCodeFile(request);
        assertTrue(result.getSuccess());
        assertEquals("master", result.getBranch());
        assertTrue(result.getContent().contains("PROD_ONLY_MARKER"));
        assertTrue(result.getContent().contains("ok-prod"));
    }

    @Test
    void 文件在其他环境副本找到时提示核对() {
        LocalCodeTools.CodeFileReadRequest request = new LocalCodeTools.CodeFileReadRequest();
        request.setEnvironment("生产");
        request.setFilePath("projA/src/main/java/demo/QaOnlyService.java");
        LocalCodeTools.CodeFileReadResult result = tools.readCodeFile(request);
        assertTrue(result.getSuccess());
        assertTrue(result.getContent().contains("QA_ONLY_MARKER"));
        assertNotNull(result.getNotes());
        assertTrue(result.getNotes().stream().anyMatch(n -> n.contains("测试环境") && n.contains("实际读取")));
    }

    @Test
    void 未识别环境回退默认目录并提示() {
        LocalCodeTools.CodeSearchRequest request = new LocalCodeTools.CodeSearchRequest();
        request.setEnvironment("边缘节点");
        request.setKeyword("ORDER_EMPTY");
        LocalCodeTools.CodeSearchResult result = tools.searchCode(request);
        assertTrue(result.getSuccess());
        assertNull(result.getEnvironment());
        assertEquals(2, result.getReturned());
        assertNotNull(result.getNotes());
        assertTrue(result.getNotes().stream().anyMatch(n -> n.contains("未识别环境")));
    }

    @Test
    void 环境目录缺失时降级默认目录() {
        CodeAnalysisProperties properties = (CodeAnalysisProperties)
                ReflectionTestUtils.getField(tools, "properties");
        properties.getRouting().getEnvironments().get(0)
                .setCodePath(tempDir.resolve("env-not-exist").toString());
        LocalCodeTools.CodeSearchRequest request = new LocalCodeTools.CodeSearchRequest();
        request.setEnvironment("生产");
        request.setKeyword("ORDER_EMPTY");
        LocalCodeTools.CodeSearchResult result = tools.searchCode(request);
        assertTrue(result.getSuccess());
        assertEquals(2, result.getReturned());
        assertTrue(result.getNotes().stream().anyMatch(n -> n.contains("代码目录不存在")));
    }

}
