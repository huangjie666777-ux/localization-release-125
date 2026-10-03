# 多语言消息发布后端（Localization Release）

纯后端服务：整批提交基准语言及各语言的 ICU MessageFormat 文案与显式回退关系，
在发布前做模板与参数一致性校验，帮助研发在上线前发现参数漏译。发布后版本递增、
不可变、持久化，可按版本/语言/键渲染，也可下载回退后的完整 JSON 语言包。

技术栈：Java 17、Javalin 6.5.0、ICU4J 76.1、Jackson、JUnit 5。

## 范围

- **提交校验**：整批原子校验通过后才发布。语言标签按 BCP 47（RFC 5646 正则 + ICU 规范化）校验；
  键必须非空；Jackson 开启严格重复键检测，重复语言标签/消息键拒绝；文案必须为字符串，
  空字符串是合法译文。基准语言必须覆盖全部键，其他语言不得新增键。
- **ICU 模板**：用 ICU MessagePattern 递归解析，支持命名参数、select、plural
  （含 selectordinal）、任意嵌套、引号转义、#、offset:N 与 =精确值 分支。
  递归收集所有分支中的参数；禁止位置参数；禁止其他格式类型
  （number/date/time/spellout/choice 等，构造 MessageFormat 时一并拦截）。
  每个译文的参数集合与类型必须与基准一致，同一参数不能兼作 plural 与 select；
  各译文允许有不同的复数分支（如 ru 的 one/few/many/other）。
- **回退**：显式声明每门语言的回退目标；拒绝未知目标、环、无法到达基准的链。
  逐键沿链找到第一个携带该键的语言（必然终止于基准），渲染与语言包均返回实际来源语言。
- **版本**：全部校验通过后递增发布不可变版本，提交需带 expectedVersion（乐观锁）。
  冲突（HTTP 409）或任何校验失败都不消耗版本号、不留半成品（先写版本文件再原子切换
  current 指针）。重启从磁盘恢复；旧版本不随新版本改变；读路径基于不可变快照，
  并发查询不会混用两版。
- **渲染**：按版本/语言/键渲染。plural 参数只接受有限数值（拒绝 NaN/Infinity），
  select 参数只接受字符串；缺失或多余参数一律 400。按译文来源语言的 ICU 复数规则选择分支。
- **语言包**：下载回退后的完整语言包，保留原始模板与逐键来源。

### 限制（Limits.java）

| 项 | 上限 |
| --- | --- |
| 请求体 | 1 MiB |
| 基准键数 | 1,000 |
| 语言数 | 100 |
| 全部键值条数 | 20,000 |
| 单模板长度 | 10,000 字符 |
| 复数/select 嵌套深度 | 32 |

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /health | 存活与当前版本 |
| POST | /v1/versions | 整批提交发布（带 expectedVersion） |
| GET | /v1/versions | 当前版本号 |
| POST | /v1/render | 按版本/语言/键/参数渲染 |
| GET | /v1/versions/{version 或 latest}/languages/{lang}/pack | 回退后的完整语言包 |

错误统一为 400 + JSON error，版本冲突为 409，超过体积限制为 413。

提交示例（完整样例见 scripts/v1.json）：

```json
{
  "expectedVersion": 0,
  "base": "en",
  "fallback": { "zh-Hant": "zh", "zh": "en" },
  "messages": {
    "en": { "hello": "Hello {name}!" },
    "zh": { "hello": "你好，{name}！" },
    "zh-Hant": {}
  }
}
```

渲染请求：{ "version": "latest", "lang": "zh-Hant", "key": "hello", "params": { "name": "Ada" } }，
响应含渲染文本 text 与实际来源 sourceLang。

## 构建与测试

```bash
mvn -o test          # 离线单测（本地 .m2 已含依赖）；去掉 -o 可联网
mvn -o package -DskipTests
mvn -o dependency:copy-dependencies -DoutputDirectory=target/dependency
```

## 启动

```bash
PORT=8080 DATA_DIR=data/versions \
  java -cp 'target/classes:target/dependency/*' dev.localization.release.Main
```

启动后用 curl 演示发布、乐观冲突、回退渲染、语言包下载、新版本发布与旧版不可变：

```bash
bash scripts/demo.sh
```

## 代码结构

- LanguageTags：BCP 47 标签正则校验与 ICU 规范化。
- TemplateAnalyzer：ICU MessagePattern 递归模板分析与参数模式提取。
- FallbackResolver：回退链校验与逐键来源解析。
- Publisher：整批目录/模板/参数一致性校验（失败不留痕）。
- VersionStore：目录存储、原子写、乐观版本号、重启恢复与历史版本读取（多文件协作）。
- Renderer：强类型参数检查与按来源语言 ICU 复数规则渲染。
- ApiServer / Main：Javalin 路由、请求体与重复键限制、入口。
