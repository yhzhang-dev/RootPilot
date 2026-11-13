# RootPilot 架构

## 分层

```text
Compose 屏幕
   │ collectAsStateWithLifecycle()
ViewModel（每屏一个 StateFlow<UiState>）
   │
Repository（Device / App / History）
   │                    │
data source            domain/ActionExecutor ──► RootShell ──► su
(DeviceInfoSource、    （UI 与 HTTP API 共用）
 AppInfoSource、
 Room DAO、
 RootShell)
```

规则：

- Composable 只调用 ViewModel；不直接触碰 DAO、`RootShell` 或 `PackageManager`。
- 每个屏幕的状态是一个 `StateFlow<UiState>`，通过 `collectAsStateWithLifecycle()` 订阅。
- `ActionExecutor` 是冻结 / 解冻 / 强制停止的唯一执行入口，UI 与 Agent API 都走它。
- 手动依赖注入：`RootPilotApplication` 创建 `AppContainer`，ViewModel 通过伴生 `factory(container)`
  构造。没有 Hilt。

## 线程与数据流

- `PackageManager`、`StatFs`、`ActivityManager`、Keystore 解密、Room 写操作都在 `Dispatchers.IO`。
- 电池状态由动态 `BatteryReceiver` 推入 `BatteryMonitor` 的 `StateFlow`，界面实时刷新。
- 快照写入后发送应用内广播 `com.hangge.rootpilot.action.SNAPSHOT_SAVED`（带包名限定）。

## Room

数据库 `rootpilot.db` v1，`exportSchema=true`，schema 输出到 `app/schemas`。

| 表 | 用途 |
| --- | --- |
| `device_snapshots` | 采集的设备快照 |
| `action_records` | 操作审计（含被拒绝的尝试） |
| `api_requests` | Agent API 请求日志（方法/路径/状态/耗时） |

DAOH 暴露 `Flow<List<...>>`（最新在前）；导出时读取全量。Token 不在任何表或导出中。

## Provider

`com.hangge.rootpilot.provider`，`exported=false`，只读：

- `content://com.hangge.rootpilot.provider/snapshots`
- `content://com.hangge.rootpilot.provider/actions`

`query()` 必须在主线程同步返回，因此用 `runBlocking(Dispatchers.IO)` 查询 Room，再把行复制进
`MatrixCursor`；`insert/update/delete` 直接抛 `UnsupportedOperationException`。

## Service 与 API

`AgentService` 是 `specialUse` 类型的前台服务，通知带“停止”动作。`AgentServer`：

- `ServerSocket` 绑定 `127.0.0.1`；每个连接处理一个请求，读超时 5 s，JSON 请求体上限 8 KiB。
- `GET /v1/health` 免 Token；其余接口校验 `X-RootPilot-Token`（`MessageDigest.isEqual`）。
- 每个请求写一条 `api_requests`；日志里没有 Token、Header 或请求体。
- 响应 `application/json; charset=utf-8`，错误体 `{"error","code"}`。

## 无障碍桥与 UI 采集

`RootPilotAccessibilityService` 是可选的第二条 UI 采集路径，与 rooted `uiautomator dump` 并存：

- 输出**与 uiautomator 兼容的 XML**，额外带一个 `node-index`（先序序号），桌面端解析代码不用改。
- 选窗口时只取 `TYPE_APPLICATION`，聚焦优先、面积次之；这解决了 `rootInActiveWindow`
  返回状态栏或输入法窗口、以及部分应用返回**空根节点**（class 为空、bounds 全 0）的问题。
- 点击节点用 `ACTION_CLICK`，失败时向上找可点击祖先，再失败才退化成按 bounds 中心的手势点击。
- 文本写入用 `ACTION_SET_TEXT`，不合成按键，因此中文、emoji、空格都正确，也不需要 shell 转义。
- 服务未启用时 `AgentServer.dumpTree()` 自动回退到 `RootCommand.DumpUiHierarchy`，接口契约不变。

窗口几何由 `/v1/ui/windows` 暴露（类型、包名、bounds、焦点）。输入法是一个独立窗口，
盖在应用之上并吞掉落在其带内的触摸；`observe` 的 `occluders` 字段让调用方在点击前就能发现。

多开（ColorOS 应用分身）是**同包名的第二个 Android 用户**，窗口可以同时存在。因此窗口的
`userId` 只在「聚焦且包名等于前台包名」时填充，其余为 `null`，不做猜测。

## 安全模型

- `RootCommand` 是封闭的 sealed interface，只有固定模板；包名参数在构造时用
  `^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$` 校验。
- 每次 `su` 调用 8 s 超时、输出上限 32 KiB、超时后 `destroyForcibly`。
- `SafeMode` 默认开启，拦截冻结与强制停止（UI 与 API 同时生效）；解冻始终允许。
- 冻结/强制停止排除：系统应用、RootPilot 自身、当前桌面、已启用输入法。
- `Allow Agent Control` 默认关闭，写接口因此默认 403。
- 无 Root 时所有特权操作返回 `ROOT_UNAVAILABLE`，界面保持可用。
