# Agent API

基础地址：`http://127.0.0.1:8765`（仅回环）。桌面端先执行 `adb forward tcp:8765 tcp:8765`。

鉴权：除 `/v1/health` 外都需要请求头 `X-RootPilot-Token: <token>`，Token 在应用 Agent 页复制。
所有响应为 JSON；错误体形状：

```json
{"error":"人类可读原因","code":"ROOT_UNAVAILABLE"}
```

`code` 取值：`ROOT_UNAVAILABLE`、`SAFE_MODE`、`AGENT_CONTROL_OFF`、`INVALID_PACKAGE`、
`SYSTEM_APP`、`NOT_FOUND`、`CLIPBOARD_UNAVAILABLE`、`COMMAND_FAILED`；另有鉴权专用的
`UNAUTHORIZED`（HTTP 401）。

`CLIPBOARD_UNAVAILABLE`（409）表示剪贴板写入不会被系统接受：Android 只允许**前台应用**写剪贴板，
后台写入会被静默丢弃，所以接口先查前台再写，写完再读回校验，两者任一不成立就返回该错误。

## 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/v1/health` | 免 Token，`{"ok":true,"version":"2"}` |
| GET | `/v1/status` | 型号、Android、Root、电池、内存/存储可用量、应用数 |
| GET | `/v1/capabilities` | API 能力、权限状态、SafeMode 与 Agent 控制状态 |
| GET | `/v1/network/status` | 网络传输类型、验证状态、带宽与接口地址 |
| GET | `/v1/sensors` | 传感器清单、厂商、功耗、分辨率与唤醒属性 |
| GET | `/v1/sensors/sample?type=1` | 采集指定类型传感器的一次最新读数 |
| GET | `/v1/location/current` | 最近一次系统定位、精度、来源和时间 |
| GET | `/v1/screen/screenshot` | PNG 截图，JSON 中以 Base64 返回 |
| GET | `/v1/ui/tree` | 当前界面的 UIAutomator XML 层级（无障碍可用时由其提供，响应带 `source`） |
| GET | `/v1/ui/windows` | 窗口几何：类型、包名、bounds、焦点，以及 `foregroundUserId` 和聚焦窗口的 `userId`（用于区分主应用与分身） |
| GET | `/v1/files?path=` | 查看 Agent 专用文件交换目录 |
| GET | `/v1/files/read?path=` | 读取交换目录内文件，Base64 返回 |
| GET | `/v1/notifications` | 当前活动通知（包名、标题、正文、时间、是否可清除） |
| GET | `/v1/events?after=0&timeoutMs=25000` | 增量长轮询事件；最长等待 30 秒 |
| GET | `/v1/subscriptions` | 连续定位和传感器订阅状态 |
| GET | `/v1/automation/observe` | 一次返回前台包名、PNG 截图 Base64 与 UI XML |
| GET | `/v1/ui/foreground` | 当前前台应用包名与 Android 用户（分身与主应用包名相同，靠 `userId` 区分） |
| GET | `/v1/apps?query=&limit=50` | 应用列表（包名、名称、版本、targetSdk、系统/启用、权限名） |
| GET | `/v1/history?limit=50` | 最近操作记录 |
| POST | `/v1/apps/{pkg}/freeze` | 冻结（需要 Agent 控制 + SafeMode 关闭 + Root） |
| POST | `/v1/apps/{pkg}/unfreeze` | 解冻（需要 Agent 控制 + Root；SafeMode 不拦截） |
| POST | `/v1/apps/{pkg}/force-stop` | 强制停止（同冻结） |
| POST | `/v1/apps/{pkg}/launch` | 启动应用（需要 Agent 控制）；`?user=999` 可启动分身，缺省为 user 0 |
| POST | `/v1/ui/tap` | 点击：`{"x":100,"y":200}` |
| POST | `/v1/ui/node/tap` | 按节点点击：`{"nodeIndex":17}`（无障碍树里的 `node-index`，无需 Root） |
| POST | `/v1/ui/swipe` | 滑动：`{"x1":100,"y1":800,"x2":100,"y2":200,"durationMs":300}` |
| POST | `/v1/ui/key` | Android keycode：`{"keyCode":3}` |
| POST | `/v1/ui/text` | 写入文本：`{"text":"hello world"}`；优先无障碍 `ACTION_SET_TEXT`（任意 Unicode），失败回退 `input text`（仅 ASCII） |
| POST | `/v1/files/write` | 写入交换目录：`{"path":"inbox/a.txt","base64":"..."}` |
| POST | `/v1/notifications/dismiss` | 清除通知：`{"key":"通知 key"}` |
| POST | `/v1/subscriptions/location/start` | 开始连续定位：`{"intervalMs":10000}` |
| POST | `/v1/subscriptions/location/stop` | 停止连续定位：`{}` |
| POST | `/v1/subscriptions/sensor/start` | 开始传感器订阅：`{"type":1,"intervalMs":1000}` |
| POST | `/v1/subscriptions/sensor/stop` | 停止传感器订阅：`{"type":1}` |
| POST | `/v1/automation/act` | 执行一次类型化动作并可返回新观察结果 |

写接口返回 `{"ok":true,"packageName":"..."}` 或错误体。

UI 自动化接口需要 Agent 控制开启、SafeMode 关闭和 Root；`/v1/ui/node/tap` 走无障碍服务，不要求 Root，
但同样受 Agent 控制与 SafeMode 约束。文本输入仅接受受控 ASCII 字符，拒绝
Shell 元字符；所有自动化尝试都会写入操作审计。

文件接口只能访问应用私有目录下的 `files/agent-exchange`，规范化路径后仍会校验父目录，拒绝
`../` 等越界路径。单文件上限 4 MiB。截图和 UI 层级通过固定 Root 命令生成，不接受调用方路径。

`/v1/events` 是适合桌面 Agent 的 HTTP 长轮询流。客户端保存 `lastEventId`，下一次作为 `after`
传入；当前事件包括通知新增/移除、定位样本、传感器样本/错误和自动化动作。事件缓冲有界，断线
重连后可读取近期事件，但它不是永久消息队列。

### 观察结果的字段

`/v1/automation/observe` 除截图与 UI XML 外还返回：

| 字段 | 含义 |
| --- | --- |
| `foregroundPackage` / `foregroundUserId` | 前台包名与所在 Android 用户（分身与主应用同包名） |
| `uiSource` | `accessibility` 或 `uiautomator`，标明这棵树来自哪条路径 |
| `occluders` | 非应用窗口（输入法、系统栏）的 bounds；落在其中的点击会被它们消费 |

`/v1/ui/windows` 的每项还带 `userId`，但只对「聚焦且包名等于前台包名」的窗口填充，其余为 `null`。

`/v1/automation/act` 的 `action` 支持 `tap`、`swipe`、`key`、`text`、`launch`、`node`、`clipboard`、`paste`，
参数与原有类型化
端点一致（`node` 传 `nodeIndex`，`clipboard` 传 `text`）。`observe` 默认 `true`，成功后等待 `settleMs`（0–3000 ms）并附加新的
observation，形成“观察 → 单步操作 → 再观察”闭环。

## 状态码

- 400：包名无效
- 401：缺少/错误 Token
- 403：`AGENT_CONTROL_OFF`、`SAFE_MODE`、`SYSTEM_APP`
- 404：应用不存在或路径未知
- 409：Root 不可用
- 500：命令执行失败

## 示例

```powershell
curl http://127.0.0.1:8765/v1/health

curl -H "X-RootPilot-Token: <token>" "http://127.0.0.1:8765/v1/apps?query=wechat&limit=10"

curl -X POST -H "X-RootPilot-Token: <token>" ^
  http://127.0.0.1:8765/v1/apps/com.example.app/freeze
```

## 审计与隐私

- 每个请求（包括 401）写一条 `api_requests`：方法、路径、状态码、耗时。
- Token、请求头、请求体不写日志；导出文件也不包含 Token。
