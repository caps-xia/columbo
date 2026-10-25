# Columbo × COLA 架构对照

> 对照基准：COLA 4.0（Clean Object-Oriented and Layered Architecture，阿里张建飞），
> 参照《COLA架构：适合 AICoding 的 DDD 架构》一文的四层口径：Adapter / Application / Domain / Infrastructure，
> 单向依赖 + ArchUnit 自动校验。本文回答三个问题：Columbo 符不符合、哪里有意不同、改代码时怎么守住。

## 一、模块映射

| Columbo 模块 | COLA 对应层 | 职责 | 依赖 |
|---|---|---|---|
| `columbo-trigger` | **adapter** 适配层 | HTTP Controller、SSE 编排、全局异常、TraceIdFilter；只做协议转换与转发 | api、domain、types |
| `columbo-domain` | **application + domain**（合一） | 应用编排（ChatService/StreamingSessionAppService）+ 领域模型/领域服务/端口定义 | types |
| `columbo-infrastructure` | **infrastructure** 基础设施层 | 端口实现（SessionStore/Embedding/腾讯云CLS）、Mapper、Gateway | domain |
| `columbo-api` | **client** 对外契约 | `IAgentService`/`IAuthService` 接口 + DTO + `Response<T>` | types |
| `columbo-app` | **start** 启动模块 | `Application` 组合根、yml、静态资源 | trigger、infrastructure |
| `columbo-types` | **component** 公共组件 | ResponseCode/AppException、纯 JDK 工具（无任何模块依赖） | 无 |

命名不同（trigger≠adapter、api≠client），职责与依赖方向一一对应。**COLA 的精髓是依赖方向和组件位置，不是模块名**——不为对齐而改名。

## 二、已符合 COLA 的点

1. **单向依赖 + 依赖倒置**：domain 定义端口（`ISessionStorePort`/`IEmbeddingPort`），infrastructure 实现，domain 零反向依赖。
2. **对外契约纯净**：api 只有接口 + DTO，无实现细节。
3. **Adapter 是薄层**：Controller 最大 337 行/7 端点，无业务规则、无 DB 访问。
4. **ArchUnit 自动校验**（`columbo-app` 的 `ArchitectureTest`，见下）——正是文章说的"分层约束写进 CI，AI 打破立即红灯"。

## 三、有意偏离项（及不照搬的理由）

| 偏离 | COLA 要求 | Columbo 现状 | 不改的理由 |
|---|---|---|---|
| 无独立 application 模块 | app 层与 domain 层分模块 | 应用编排（ChatService 等）在 columbo-domain 内，靠分包（`service/chat`、`pipeline`、`armory`）组织 | 单智能体服务编排量有限；文章自述"忌过度分层、渐进式"。服务长大、编排逻辑明显膨胀时再拆 `columbo-application` |
| domain 容忍 Spring 注解与 SDK | "领域层纯 POJO" | domain 大量 `@Component`、Spring AI/Google ADK/rxjava | 本项目"领域"就是智能体编排本身；剥离 SDK 等于重写。守住底线即可：**HTTP/DB/MyBatis 不进 domain**（ArchUnit 已固化） |
| 本地文件 IO 在 domain | 技术细节归 infra | 知识库/代码副本的文件读写（KnowledgeTools 等）在 domain | 知识库文件即领域模型的一部分（sidecar `.vectors.json` 随库走）；仅"出网调用"（嵌入网关）强制走端口 |

## 四、改代码时的分层规则（ArchUnit 已固化，红灯即违规）

`columbo-app/src/test/java/org/caps/columbo/test/arch/ArchitectureTest.java` 共 9 条：

**依赖方向（5 条）**
1. types 不依赖任何其他模块
2. api 只依赖 types（实现细节不进契约）
3. domain 不依赖 infrastructure/trigger/api（六边形内核）
4. infrastructure 不依赖 trigger/api
5. trigger 不依赖 infrastructure（持久化经 domain 端口间接可达）

**组件位置（4 条，COLA 式"各归其位"）**
6. `@RestController/@Controller` 只能在 trigger
7. MyBatis `BaseMapper` 只能在 infrastructure
8. domain 不得依赖 `java.net.http`/`org.springframework.web`（网关调用走端口，如 `IEmbeddingPort`）
9. api/types 不得有 Spring 组件注解（`@Component/@Service/@Configuration`）

**新增代码的速记**：HTTP 进 trigger；出网调用/DB 落 infrastructure 并实现 domain 端口；接口与 DTO 进 api；纯工具进 types；其余进 domain。

## 五、本次对齐动作记录

- `VectorKnowledgeService` 的嵌入网关 HTTP 调用外移：domain 新增 `IEmbeddingPort`，infrastructure 新增 `EmbeddingPortImpl`——domain 从此零 `java.net.http` 依赖，并固化为规则 8。
- `ArchitectureTest` 新增组件位置规则 4 条（6~9）。
