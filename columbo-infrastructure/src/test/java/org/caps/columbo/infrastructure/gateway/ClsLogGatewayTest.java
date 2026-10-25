package org.caps.columbo.infrastructure.gateway;

import org.caps.columbo.domain.troubleshoot.model.properties.ClsProperties;
import org.caps.columbo.types.exception.AppException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLS 查询语句拼装（envTagKey 按站点可选过滤）与占位符密钥/站点拦截
 */
class ClsLogGatewayTest {

    private static ClsProperties propertiesWithSite() {
        ClsProperties properties = new ClsProperties();
        ClsProperties.Site hainan = new ClsProperties.Site();
        hainan.setLabel("站点A");
        hainan.setRegion("ap-shanghai");
        properties.getSites().put("siteA", hainan);
        return properties;
    }

    @Test
    void buildQueryForcesEnvFilterWhenEnvTagConfigured() {
        assertEquals("__TAG__.env:\"product\"", ClsLogGateway.buildQuery("__TAG__.env", "product", null, null, null));
        assertEquals("__TAG__.env:\"qa\"", ClsLogGateway.buildQuery("__TAG__.env", "qa", null, null, null));
    }

    @Test
    void buildQuerySkipsEnvFilterWhenEnvTagBlank() {
        assertEquals("", ClsLogGateway.buildQuery("", "product", null, null, null));
        assertEquals("trace_id:\"abc123\"",
                ClsLogGateway.buildQuery(null, "product", "abc123", null, null));
    }

    @Test
    void buildQueryCombinesFieldConditions() {
        String query = ClsLogGateway.buildQuery("__TAG__.env", "product", " abc123 ", "error", "下单失败");
        assertEquals("__TAG__.env:\"product\" AND trace_id:\"abc123\" AND log_level:\"ERROR\" AND message:\"下单失败\"", query);
    }

    @Test
    void searchLogRejectedWhenSecretIsPlaceholder() {
        ClsLogGateway gateway = new ClsLogGateway(propertiesWithSite());
        AppException e = assertThrows(AppException.class,
                () -> gateway.searchLog("siteA", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                        0L, 1L, "__TAG__.env:\"product\"", 10L, "desc", null));
        assertTrue(e.getInfo().contains("密钥未配置"));
        assertTrue(e.getInfo().contains("siteA"));
    }

    @Test
    void searchLogRejectedWhenDisabled() {
        ClsProperties properties = propertiesWithSite();
        properties.setEnabled(false);
        ClsLogGateway gateway = new ClsLogGateway(properties);
        AppException e = assertThrows(AppException.class,
                () -> gateway.searchLog("siteA", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                        0L, 1L, "__TAG__.env:\"product\"", 10L, "desc", null));
        assertTrue(e.getInfo().contains("未启用"));
    }

    @Test
    void searchLogRejectedWhenSiteMissing() {
        ClsLogGateway gateway = new ClsLogGateway(propertiesWithSite());
        AppException e = assertThrows(AppException.class,
                () -> gateway.searchLog("siteB", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                        0L, 1L, "__TAG__.env:\"product\"", 10L, "desc", null));
        assertTrue(e.getInfo().contains("站点"));
    }

    @Test
    void searchLogRejectedWhenRegionMissing() {
        ClsProperties properties = new ClsProperties();
        properties.setSecretId("id");
        properties.setSecretKey("key");
        ClsProperties.Site site = new ClsProperties.Site();
        site.setLabel("站点B");
        properties.getSites().put("siteB", site);
        ClsLogGateway gateway = new ClsLogGateway(properties);
        AppException e = assertThrows(AppException.class,
                () -> gateway.searchLog("siteB", "some-topic", 0L, 1L, "*", 10L, "desc", null));
        assertTrue(e.getInfo().contains("region"));
    }
}
