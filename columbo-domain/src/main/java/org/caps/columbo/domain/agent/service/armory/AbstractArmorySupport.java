package org.caps.columbo.domain.agent.service.armory;

import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import org.caps.columbo.domain.agent.model.entity.ArmoryCommandEntity;
import org.caps.columbo.domain.agent.model.valobj.AiAgentRegisterVO;
import org.caps.columbo.domain.agent.service.armory.factory.DefaultArmoryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;

import jakarta.annotation.Resource;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public abstract class AbstractArmorySupport extends AbstractMultiThreadStrategyRouter<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> {

    protected final Logger log = LoggerFactory.getLogger(AbstractArmorySupport.class);

    @Resource
    protected ApplicationContext applicationContext;

    @Override
    protected void multiThread(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }

    /**
     * 通用的Bean注册方法
     *
     * @param beanName  Bean名称
     * @param beanClass Bean类型
     * @param <T>       Bean类型
     */
    protected synchronized <T> void registerBean(String beanName, Class<T> beanClass, T beanInstance) {
        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();

        // 注册Bean
        BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(beanClass, () -> beanInstance);
        BeanDefinition beanDefinition = beanDefinitionBuilder.getRawBeanDefinition();
        beanDefinition.setScope(BeanDefinition.SCOPE_SINGLETON);

        // 覆盖式重注册（模型热切换用）：Boot 默认禁止同名 BeanDefinition 覆盖，临时放开——
        // 先覆盖定义再销毁旧单例（顺序不能反），期间定义始终存在，并发 getBean 不会出现
        // "智能体ID不存在"的空窗；随后按新定义预热重建。原 overriding 标志在 finally 恢复。
        boolean overriding = beanFactory.isAllowBeanDefinitionOverriding();
        beanFactory.setAllowBeanDefinitionOverriding(true);
        try {
            beanFactory.registerBeanDefinition(beanName, beanDefinition);
            beanFactory.destroySingleton(beanName);
        } finally {
            beanFactory.setAllowBeanDefinitionOverriding(overriding);
        }
        beanFactory.getBean(beanName);

        log.info("成功注册Bean: {}", beanName);
    }

    protected <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }

}
