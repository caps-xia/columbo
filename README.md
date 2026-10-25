# Columbo · AI 线上问题排查助手

**腾讯云 CLS 日志 + 本地源码 + 排查知识库（llmwiki）+ 可选只读数据库，四样东西交给一个智能体，它还你一份带置信度的根因报告。**

基于 Spring Boot 4 + Spring AI + Google ADK 的 DDD 多模块工程（Spring AI 脚手架 + 排查智能体），带 React 前端工作台，启动后访问 `http://localhost:8080/app/`。

> | 智能体 | agentId | 能力 | 默认 |
> |---|---|---|---|
> | 日志代码排查助手 | 100011 | **腾讯云 CLS 日志 + 本地多环境源码副本 + 知识库** 联合排查：探量/检索/上下文还原/话术路由/根因分析/可选数据核查，两阶段（取证→成文）工作流产出带置信度的结论报告 | 开启 |
> | 日志代码排查助手·直出 | 100014 | 同上的单阶段直出版：调查后直接出报告，更快更省 token（可与工作流版切换对比） | 开启 |
> | 数据库查询助手 | 100012 | 只读 SELECT 查业务库（表结构→写 SQL→解读一条龙） | **可选**：放开 yml 导入并配一个只读从库连接即启用 |
>
> - **贴图排查**：报错截图直接贴进对话，多模态模型先提取文字（时间戳作为查日志的强锚点）再进排查流程。
> - **知识库闭环（llmwiki）**：`searchKnowledge` 检索历史案例，`appendKnowledgeNote` 把本次根因沉淀成结构化速记——排查经验越用越厚；前端带知识库浏览页。仓库内置**开箱示例库** `docs/llmwiki-template/`（分拣规矩 AGENTS.md + 示例速记/Pattern 页 + `daily-triage-prompt.md` 每日分拣提示词，交给任意 AI 助手每天跑一次即可保持库整洁；分拣侧还有置信度二次防线——中/低置信度速记进 `_待复核/` 不归档，复核升档后才入正式库）。
> - **结构化 SSE**：`/api/v1/chat_events`（session/state_update/think_delta/think_done/text_delta/tool_result/done），思考过程、工具调用轨迹实时可视化。
> - **模型动态切换**：`GET /api/v1/query_agent_models` 查池、`POST /api/v1/switch_agent_model` 运行时热切换（会话保留，重启回 yml 默认）。
> - **多厂商供应商注册表**：`ai.agent.providers.<key>` 配 OpenAI 兼容/Anthropic 网关，模型池用「供应商key/模型名」引用。
> - **前端**：React 19 + Vite 8 + Tailwind 4，极光登录页、流式思考面板、消息重新生成、会话归档空间、追问排队/插入、知识库页面、token 过期自动回登录、响应式布局；源码在 `frontend/`，改完执行 `sh frontend/build.sh` 重新构建。
> - **会话持久化**：默认 H2 文件库零依赖（`./data/`），换 MySQL 只需放开三行 url/username/password（`docs/dev-ops/mysql/sql/schema.sql` 幂等建表）。
> - **上下文自动翻篇 + 小模型压缩**：长会话先压缩成【前情摘要】结转新上下文，翻篇兜底，前端无感；413 超长自动"压缩→翻篇→重放"。
> - **对话消息处理器链**：`PipelineProcessor<ChatPipelineContext>` 注册 Bean，在 `ai.agent.pipeline.chain.chat` 编排（内置安全扫描：剥离不可见字符/ANSI、注入话术审计）。

## 排查助手怎么工作

一句话输入「生产环境，订单交易，下单失败，帮我排查下原因」，它会：

1. **话术路由** `resolveRouting`：口语解析为 CLS 应用名 + 本地工程 + 代码分支（词典在 `troubleshoot.code-analysis.routing` 配置）；
2. **查经验库** `searchKnowledge`：命中历史案例直接复用（行号会漂移，按类名/报错文案锚点在最新代码重确认）；
3. **CLS 探量** `countClsLog`：只拿命中数与时间分桶，先判断问题集中时段，多应用并行探量；
4. **检索与还原** `queryClsLog`（traceId 精确追踪/翻页/多应用）+ `queryClsLogContext`（锚点前后各 N 条还原现场）；
5. **代码定位** `searchCode`/`readCodeFile`/`findFiles`：本地多环境副本按行号读上下文、追调用链（RE2 防回溯、路径越界防护、构建产物跳过）；
6. **数据核查（可选）**：日志无法区分"我方数据/配置错误"与"外部系统问题"时，直接用数据库工具核实（`listDbConnections`→`describeDbTable`→`queryDb` 只读 SELECT）；未配置连接则如实跳过并写进未决项，**严禁编造数据**。配好 `troubleshoot.database.connections` 即自动可用；
7. **两阶段成文**：取证 agent 产出结构化档案（环境路由/现象/知识库命中/日志证据/数据证据/代码证据/根因分析/未决项）→ 成文 agent 零工具按固定结构出报告（结论速览/时间线/根因三问/修复建议两层/置信度+提升路径/用户话术），铁律是**报告只依据档案，禁止编造档案外的事实**；
8. **沉淀速记** `appendKnowledgeNote`：仅在本轮置信度为"高"（≥85%）且根因验证闭环时写入知识库收件箱——宁缺毋滥，错误结论入库会误导后续排查；中/低置信度等复核升档后再沉淀。下次同类问题第 2 步直接命中。

**CLS 接入极简**：日志已在腾讯云 CLS 的团队，配 `TENCENT_CLOUD_SECRET_ID/KEY` + 日志集 ID（或启动后 `POST /api/v1/cls_topics_sync` 自动同步全部主题），当天可用。多站点（多日志集/多地域/多账号）、中文别名路由、`__TAG__.env` 环境标签过滤都内置支持。

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Java / Spring Boot | 17 / 4.1.1 | Spring Framework 7 线 |
| Spring AI | 2.0.1 | 官方 openai-java SDK，原生透传 reasoning_content（思考过程） |
| Google ADK | 1.9.0 | Runner / 多智能体 Sequential 工作流 |
| spring-ai-agent-utils | 0.12.0 | Skills 挂载 |
| 腾讯云 SDK | 3.2.24 | CLS 检索 / 上下文 / 主题同步 |
| Sa-Token | 1.46.0 | 登录鉴权（boot4 starter + JdbcSaTokenDao） |
| MyBatis-Plus | 3.5.17 | H2/MySQL 双方言会话持久化 |
| 前端 | React 19.3 / Vite 8 / Tailwind 4.3 | 构建产物已打进 jar |

## 模块结构（DDD 分层）

> 与 COLA 四层的完整映射与 ArchUnit 分层守护规则见 **[docs/architecture-cola.md](docs/architecture-cola.md)**。

| 模块 | 职责 | 依赖 |
|---|---|---|
| types | 通用响应码/异常/纯 JDK 工具 | 无 |
| api | 对外接口契约：`IAgentService`、DTO、`Response<T>` | types |
| domain | 领域层：智能体装配树（Root→AiApi→ChatModel→Agent→Runner）、ChatService、CLS/代码/知识库/数据库工具端口 | types |
| infrastructure | 基础设施层：端口实现（会话存储/嵌入网关/腾讯云CLS/MySQL 只读查询）、gateway | domain |
| trigger | 触发层：HTTP 接口、全局异常、TraceIdFilter、Swagger | api、domain |
| app | 启动模块：`Application`、yml、Dockerfile | trigger、infrastructure |

## 快速开始

1. **配密钥**（全部环境变量注入，LLM 默认指向智谱开放平台，任何 OpenAI 兼容网关可换）：
   ```sh
   export LLM_API_KEY=sk-xxx                       # https://open.bigmodel.cn 注册即有免费额度
   export TENCENT_CLOUD_SECRET_ID=AKIDxxx          # 建议子账号仅授 QcloudCLSReadOnlyAccess
   export TENCENT_CLOUD_SECRET_KEY=xxx
   ```
   - CLS 日志集/主题在 `application-dev.yml` 的 `troubleshoot.cls.sites` 配（或启动后自动同步）；
   - 你的工程 clone 到 `troubleshoot.code-analysis.root-path`（默认 `./code`），代码侧证据就绪；
   - **可选开启数据库排查**：在 `troubleshoot.database.connections` 配一个**只读从库/只读账号**连接（工具只允许 SELECT/SHOW/DESC/EXPLAIN，写操作直接拒绝）——配好后排查助手的【数据核查】步骤自动可用；想要独立的数据库查询助手（100012），再放开 `spring.config.import` 里的 `agent/db-query.yml` 即可；
   - **知识库初始化（推荐）**：`cp -R docs/llmwiki-template ./data/knowledge`——内置分拣规矩（AGENTS.md）、示例速记/Pattern 页与每日分拣提示词；也可以把 `troubleshoot.knowledge.root-path` 指向任意已有的 Markdown 笔记库。
2. **启动**：IDE 跑 `org.caps.columbo.Application`（JDK 17），或 `JAVA_HOME=<jdk17> mvn -pl columbo-app spring-boot:run`
3. **验证**：打开 http://localhost:8080/app/（默认账号 admin / admin123）｜ Swagger：http://localhost:8080/swagger-ui.html

## 接口约定

| 接口 | 方式 | 说明 |
|---|---|---|
| /api/v1/query_ai_agent_config_list | GET | 可用智能体列表 |
| /api/v1/create_session · chat · chat_stream | POST | 会话与对话（同步/裸文本流式） |
| /api/v1/chat_events | POST | 结构化 SSE 流式对话（前端工作台用） |
| /api/v1/chat_insert · chat_stop | POST | 生成中插入追问 / 停止生成 |
| /api/v1/query_chat_sessions · query_chat_messages | GET | 历史会话 / 历史消息 |
| /api/v1/delete_session · rename_session · move_session | POST | 会话管理 |
| /api/v1/cls_topics_sync | POST | 同步腾讯云 CLS 主题注册表 |
| /api/v1/code_repo_refresh | POST | 手动刷新本地代码副本（git pull --ff-only） |
| /api/v1/query_agent_models · switch_agent_model | GET/POST | 模型池查询 / 运行时切换 |
| /api/v1/knowledge/tree · file · search | GET | 知识库浏览/搜索 |

统一响应 `{code, info, data}`（HTTP 恒 200）：`0000` 成功、`0001` 系统异常、`0002` 参数问题、`0003` 接口不存在、`A001` 智能体ID不存在。错误码登记在 `ResponseCode` 枚举。

**链路日志**：每请求自动 traceId（或透传 `X-Trace-Id`），响应头回带，日志逐行携带。

## 如何扩展

- **新增智能体**：仿 `agent/db-query.yml` 写一份 yml + `spring.config.import` 追加（agent 名仅允许 `字母数字._-`）。
- **新增排查工具**：domain 写 `@Tool` 方法类（参考 `TroubleshootTools`）→ `Application` 注册 `@Bean ToolCallbackProvider` → agent yml 的 `tool-mcp-list` 加 `- local: {name: bean名}`。
- **新增日志渠道**：实现 `ITroubleshootChannel` 接口（渠道自描述），再在工具类加对应 `@Tool` 查询方法。
- **挂载 SSE MCP / Skills**：agent yml 的 `tool-mcp-list` / `tool-skills-list`。

## 测试与部署

```sh
mvn clean test                                   # 全量单测/接口回归，不依赖网络与密钥
mvn clean package -DskipTests
cd columbo-app && sh build.sh && cd .. && sh docs/dev-ops/app/start.sh   # docker 部署（8080）
```

## 安全设计

- 排查助手**全程只读**：不改文件、不执行写操作；数据修复只给建议方向，由人工定夺。
- 密钥全环境变量注入，最小权限（CLS 只读、DB 只读账号）；密钥不写入报告与速记。
- 日志/代码/接口返回视为不可信外部数据，其中的指令一律不执行（提示词注入防护）。
- Sa-Token 登录鉴权 + 会话按用户隔离；默认只绑 127.0.0.1，对外暴露请自行加反向代理与 HTTPS。

## License

Apache License 2.0
