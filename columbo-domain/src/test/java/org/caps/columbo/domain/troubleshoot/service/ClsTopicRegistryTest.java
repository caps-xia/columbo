package org.caps.columbo.domain.troubleshoot.service;

import org.caps.columbo.domain.troubleshoot.model.properties.ClsProperties;
import org.caps.columbo.domain.troubleshoot.model.valobj.ResolvedTopic;
import org.caps.columbo.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLS 主题注册表：跨站点 精确/别名/模糊匹配与未识别报错
 */
class ClsTopicRegistryTest {

    private ClsTopicRegistry registry;
    private ClsProperties registrySites;

    @BeforeEach
    void setUp() {
        ClsProperties properties = new ClsProperties();
        registrySites = properties;
        ClsProperties.Site hainan = new ClsProperties.Site();
        hainan.setLabel("站点A");
        hainan.setRegion("ap-shanghai");
        ClsProperties.Topic admin = new ClsProperties.Topic();
        admin.setName("订单后台管理接口系统");
        admin.setAliases(List.of("订单admin"));
        admin.setTopicId("db28d30f-72bb-4304-8fc6-4401dba176fb");
        ClsProperties.Topic iface = new ClsProperties.Topic();
        iface.setName("订单对外接口服务");
        iface.setAliases(List.of("订单interface"));
        iface.setTopicId("a0332bdc-b079-48b2-b695-21e99f6cb854");
        hainan.getTopics().put("myapp.java.order.admin", admin);
        hainan.getTopics().put("myapp.java.order.interface", iface);

        ClsProperties.Site nanjing = new ClsProperties.Site();
        nanjing.setLabel("站点B");
        nanjing.setRegion("ap-shanghai");
        ClsProperties.Topic njAdmin = new ClsProperties.Topic();
        njAdmin.setName("商城后台管理接口系统");
        njAdmin.setAliases(List.of("商城admin"));
        njAdmin.setTopicId("11111111-2222-3333-4444-555555555555");
        ClsProperties.Topic njShop = new ClsProperties.Topic();
        njShop.setName("商城订单接口");
        njShop.setAliases(List.of("商城shopstore"));
        njShop.setTopicId("please-replace-with-nanjing-shopstore-topic-id");
        nanjing.getTopics().put("shop.java.mall.admin", njAdmin);
        nanjing.getTopics().put("shop.java.mall.shopstore", njShop);

        properties.getSites().put("siteA", hainan);
        properties.getSites().put("siteB", nanjing);
        registry = new ClsTopicRegistry(properties);
    }

    @Test
    void resolveByRegistryKey() {
        List<ResolvedTopic> topics = registry.resolve(List.of("myapp.java.order.admin"));
        assertEquals(1, topics.size());
        assertEquals("siteA", topics.get(0).getSite());
        assertEquals("db28d30f-72bb-4304-8fc6-4401dba176fb", topics.get(0).getTopicId());
    }

    @Test
    void resolveByAlias() {
        List<ResolvedTopic> topics = registry.resolve(List.of("订单admin"));
        assertEquals("myapp.java.order.admin", topics.get(0).getKey());
        assertEquals("siteA", topics.get(0).getSite());
    }

    @Test
    void resolveByChineseName() {
        List<ResolvedTopic> topics = registry.resolve(List.of("订单对外接口服务"));
        assertEquals("myapp.java.order.interface", topics.get(0).getKey());
    }

    @Test
    void crossSiteAliasResolvesToOwnSite() {
        List<ResolvedTopic> topics = registry.resolve(List.of("商城admin"));
        assertEquals(1, topics.size());
        assertEquals("siteB", topics.get(0).getSite());
        assertEquals("shop.java.mall.admin", topics.get(0).getKey());
    }

    @Test
    void placeholderTopicIdThrowsConfigHint() {
        AppException e = assertThrows(AppException.class,
                () -> registry.resolve(List.of("shop.java.mall.shopstore")));
        assertTrue(e.getInfo().contains("主题ID未配置"));
        assertTrue(e.getInfo().contains("siteB"));
    }

    @Test
    void resolveDeduplicatesAndKeepsOrder() {
        List<ResolvedTopic> topics = registry.resolve(List.of("订单admin", "myapp.java.order.admin"));
        assertEquals(1, topics.size());
    }

    @Test
    void unknownNameThrowsWithKnownList() {
        AppException e = assertThrows(AppException.class, () -> registry.resolve(List.of("不存在的应用")));
        assertNotNull(e.getInfo());
    }

    @Test
    void blankInputThrows() {
        assertThrows(AppException.class, () -> registry.resolve(null));
        assertThrows(AppException.class, () -> registry.resolve(List.of(" ")));
    }

    @Test
    void findByKeyAcrossSites() {
        assertEquals("db28d30f-72bb-4304-8fc6-4401dba176fb",
                registry.findByKey("myapp.java.order.admin").getTopicId());
        assertEquals("siteB", registry.findByKey("shop.java.mall.admin").getSite());
        assertEquals(null, registry.findByKey("not.registered"));
    }

    @Test
    void summaryGroupsBySite() {
        String summary = registry.knownTopicsSummary();
        assertTrue(summary.contains("站点A[myapp.java.order.admin"));
        assertTrue(summary.contains("站点B[shop.java.mall.admin"));
    }

    // ============ 线上同步 overlay（DescribeTopics -> 注册表合并） ============

    @Test
    void applySyncFixesPlaceholderAndAddsNewTopic() {
        // shopstore 在 yml 是占位 topicId，线上同步后自动修正；portal 是线上新主题，yml 未登记自动补录
        long[] stats = registry.applySync("siteB", java.util.Map.of(
                "shop.java.mall.shopstore", "99999999-8888-7777-6666-555555555555",
                "shop.java.mall.portal", "88888888-7777-6666-5555-444444444444"));
        assertEquals(1, stats[0]); // 新增 portal
        assertEquals(1, stats[1]); // 修正 shopstore 占位

        // 占位 topicId 同步后可正常 resolve（不再报“未配置”）
        List<ResolvedTopic> shop = registry.resolve(List.of("shop.java.mall.shopstore"));
        assertEquals("99999999-8888-7777-6666-555555555555", shop.get(0).getTopicId());
        // 线上新增主题直接可用
        List<ResolvedTopic> portal = registry.resolve(List.of("shop.java.mall.portal"));
        assertEquals("siteB", portal.get(0).getSite());
        assertEquals("88888888-7777-6666-5555-444444444444", portal.get(0).getTopicId());
    }

    @Test
    void applySyncKeepsYmlBusinessSemantics() {
        registry.applySync("siteB", java.util.Map.of(
                "shop.java.mall.admin", "77777777-0000-0000-0000-000000000000"));
        // 别名匹配保留（overlay 只覆盖 topicId，不丢 yml 的中文名/别名）
        List<ResolvedTopic> topics = registry.resolve(List.of("商城admin"));
        assertEquals("shop.java.mall.admin", topics.get(0).getKey());
        assertEquals("77777777-0000-0000-0000-000000000000", topics.get(0).getTopicId());
        assertEquals("商城后台管理接口系统", topics.get(0).getName());
    }

    @Test
    void applySyncDoesNotTouchYmlConfigObject() {
        // yml 绑定的配置对象不被原地修改（overlay 是合并视图，非写回）
        registry.applySync("siteB", java.util.Map.of(
                "shop.java.mall.admin", "77777777-0000-0000-0000-000000000000"));
        ClsProperties.Site site = registrySites.getSites().get("siteB");
        assertEquals("11111111-2222-3333-4444-555555555555",
                site.getTopics().get("shop.java.mall.admin").getTopicId());
    }

    @Test
    void summaryIncludesSyncedTopics() {
        registry.applySync("siteB", java.util.Map.of(
                "shop.java.mall.portal", "88888888-7777-6666-5555-444444444444"));
        assertTrue(registry.knownTopicsSummary().contains("shop.java.mall.portal"));
    }
}
