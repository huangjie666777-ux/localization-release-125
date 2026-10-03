# 多语言消息发布（纯后端）

面向研发上线前自查“参数漏译”的多语言消息发布服务。Java 17 + Javalin 6.5.0 + ICU4J 76.1，
无数据库：每个发布版本是一个不可变 JSON 文件，原子落盘、启动恢复。

## 功能范围

- **整批提交**：一次提交基准语言、各语言消息键值与显式回退关系；语言必须是合法 BCP 47 标签，
  键非空、不可重复（JSON 解析即拒绝重复键），文案必须是字符串；**空字符串是合法译文**。
- **ICU MessageFormat 校验**：仅允许命名参数、`select`、`plural` 及其任意嵌套；
  正确处理引号 `''`、`#`、`offset`、精确数值分支 `=N`；
  拒绝位置参数 `{0}`、`number/date/time/choice/selectordinal` 等其他格式类型及未知格式。
  递归提取所有分支参数（嵌套在任何分支内都算）。
- **参数一致性（漏译检测核心）**：每条译文的参数集合必须与基准完全一致；同名参数不得在
  基准/译文中兼作 `plural` 与 `select`。译文允许使用不同的复数分支集合（例如中文只写 `other`）。
- **键覆盖**：基准定义全部键，其他语言只能缺译（回退）或同键翻译，不得新增键。
- **回退图**：逐键沿配置回退到基准，渲染与语言包均返回实际来源语言；
  拒绝未知回退目标、循环、到不了基准的链、基准自身声明回退。
- **版本模型**：整批校验通过后才发布递增不可变版本；提交携带 `expectedVersion` 乐观锁。
  冲突（HTTP 409）或任何校验失败（HTTP 400）不写文件、不消耗版本号、不留半批；
  重启后从数据目录恢复；旧版内容不随后续发布改变；并发查询使用不可变快照，绝不混用两版。
- **渲染**：按版本、语言、键及参数渲染；按**实际来源语言**的 ICU 复数规则选分支；
  `plural` 参数只接受有限数值（拒绝 `NaN/Infinity`），`select` 只接受字符串，
  缺参数、多参数、参数类型错误均报 400。
- **语言包下载**：返回回退补齐后的完整语言包，`messages` 保留 ICU 模板原文，
  `sources` 给出每个键的实际来源语言。
- **限制**：请求体大小、消息键数、语言条数、模板嵌套深度均可通过环境变量调整。

## 配置（环境变量）

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `LOC_PORT` | `8080` | HTTP 端口 |
| `LOC_DATA_DIR` | `data` | 版本文件目录（`v1.json`、`v2.json` …） |
| `LOC_MAX_BODY_BYTES` | `1048576` | 请求体上限（1 MiB） |
| `LOC_MAX_KEYS` | `2000` | 基准消息键上限 |
| `LOC_MAX_LANGUAGES` | `100` | 语言条数上限 |
| `LOC_MAX_TEMPLATE_DEPTH` | `10` | ICU 参数嵌套深度上限 |

## 构建与测试

```bash
mvn -o test                    # 离线 JUnit：标签/分析器/回退/校验/存储恢复/HTTP 共 15 例
mvn -o package -DskipTests
```

## 启动

```bash
mvn -o dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
LOC_PORT=8091 LOC_DATA_DIR=/tmp/loc-demo \
  java -cp "target/classes:$(cat /tmp/cp.txt)" dev.localization.Main
# 或: mvn -o exec:java -Dexec.mainClass=dev.localization.Main
```

## HTTP API

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/releases` | 整批校验并发布新版本 |
| `GET /api/versions` | 版本号列表与最新版本 |
| `GET /api/versions/latest` | 最新版本元数据 |
| `POST /api/versions/{v}/render` | 指定版本渲染（`{v}` 可写 `latest`） |
| `GET /api/versions/{v}/languages/{lang}/pack` | 下载回退补齐语言包（`{v}` 可写 `latest`） |

发布体示例：

```json
{
  "expectedVersion": 0,
  "baseLanguage": "en",
  "fallbacks": {"zh-Hant-TW": "zh-Hans-CN", "zh-Hans-CN": "en"},
  "languages": {
    "en": {
      "items": "{count,plural,offset:1 =0{no items} =1{just one} one{# item} other{# items}}",
      "actor": "{gender,select,female{her} male{his} other{their}}"
    },
    "zh-Hans-CN": {"items": "{count,plural,other{共 # 个}}", "actor": "{gender,select,other{TA}}"},
    "zh-Hant-TW": {}
  }
}
```

渲染请求：`{"language":"zh-Hant-TW","key":"items","parameters":{"count":3}}`，
响应包含 `rendered` 与 `sourceLanguage`（缺译时为沿回退链命中的语言）。

## curl 演示

```bash
# 1. 发布（首次 expectedVersion=0）
curl -s -X POST localhost:8091/api/releases -H 'Content-Type: application/json' \
  --data @release1.json

# 2. 乐观锁冲突 -> 409，版本号不增加
curl -i -X POST localhost:8091/api/releases -H 'Content-Type: application/json' \
  --data @release1.json

# 3. 渲染：exact 分支 / offset / plural 复数规则按来源语言
curl -s -X POST localhost:8091/api/versions/1/render -H 'Content-Type: application/json' \
  -d '{"language":"en","key":"items","parameters":{"count":5}}'

# 4. 回退：zh-Hant-TW 缺译 -> 命中 zh-Hans-CN，sourceLanguage 标明来源
curl -s -X POST localhost:8091/api/versions/latest/render -H 'Content-Type: application/json' \
  -d '{"language":"zh-Hant-TW","key":"items","parameters":{"count":3}}'

# 5. 下载补齐语言包（保留模板 + 每键来源）
curl -s localhost:8091/api/versions/1/languages/zh-Hant-TW/pack
```

## 模块协作

- `LangTags` — BCP 47 目录校验（RFC 5646 文法 + JDK 往返校验）。
- `TemplateAnalyzer` / `TemplateInfo` — 基于 ICU `MessagePattern` 的模板分析与参数提取。
- `ReleaseValidator` — JSON 结构、键覆盖、参数一致性（漏译检测）聚合校验。
- `FallbackResolver` — 回退图解析（环/断链/未知目标）。
- `LocalizationService` / `Renderer` — 逐键回退解析、按来源语言复数规则渲染、语言包组装。
- `VersionStore` — 原子文件存储、启动恢复、发布锁与不可变版本快照；`HttpApi`/`Main` 负责 HTTP 与多版本协作。

## 明确不在范围内

认证鉴权、前端界面、分布式多节点协调、消息键语义与翻译质量的人工评审。
