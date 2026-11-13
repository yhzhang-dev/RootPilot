# 决策记录

## D1. 写接口的 SafeMode 语义

简报 §8 要求“写接口需要 `AllowAgentControl` 且 `SafeMode=false`”，而 §2/§7 要求
“SafeMode 拦截冻结与强制停止，解冻始终可用”。

**决定：** 所有写接口都要求 `AllowAgentControl=true`；`SafeMode` 只拦截冻结与强制停止，
解冻不受 `SafeMode` 限制。这样 UI 与 API 的行为一致，也保留下“出问题后仍能解冻”的恢复路径。

## D2. 鉴权错误码

简报的错误码枚举没有鉴权失败项。401 响应使用 `{"error":"Token 无效","code":"UNAUTHORIZED"}`，
其余错误码严格遵循简报枚举。

## D3. 操作审计的行数

简报要求“执行前后都记录”。实现为：校验通过后先插入一条 `success=false, message="执行中…"`
的占位记录，命令结束后用 `@Update` 把同一行改写为最终结果（成功/失败、消息）。
拒绝（未授权 Root、SafeMode、系统应用等）只写一条失败记录。历史里不会出现重复行。

## D4. Provider 的同步查询

`ContentProvider.query()` 必须同步返回 `Cursor`。实现在 `Dispatchers.IO` 上执行 Room 查询，
把结果复制进 `MatrixCursor` 后关闭数据库游标，避免 Room 的“主线程禁止查询”检查。
Provider 不导出（`exported=false`），外部访问只走 HTTP API。

## D5. 快照与操作记录的归属

`ActionExecutor` 负责特权操作（冻结/解冻/强制停止）与开机记录；`DeviceRepository.captureSnapshot()`
负责快照写入并额外写一条 `SNAPSHOT` 记录。快照不是 Root 操作，因此不经过 `ActionExecutor`。

## D6. 依赖注入

手动 `AppContainer`，不引入 Hilt；两周课程作业的复杂度不需要注解处理器与组件图。

## D7. 图标

为遵守简报的允许依赖清单（未列 `material-icons-core`），底部标签与顶栏图标用
`ImageVector.Builder` 手绘，放在 `ui/components/RootPilotIcons.kt`。

## D8. 端口修改

端口在 Agent 页保存，写入 SharedPreferences；正在运行的服务不会热切换端口，需重启服务生效。
界面会明确提示这一点。

## D9. 开机广播的 OEM 重放

ColorOS 在安装/更新后会向新包重放 `ACTION_BOOT_COMPLETED`，导致 History 出现假的“开机记录”。
`BootReceiver` 只在 `SystemClock.elapsedRealtime() < 15 分钟` 时记录，其余视为重放并忽略。

## D10. 仪器测试

本机没有 AVD，且简报禁止在 PHK110 上运行 `connectedDebugAndroidTest`。本期以 JVM 单元测试 +
设备手工验收代替；报告里如实说明，不声称仪器测试通过。

## D11. 课程主线采用纵向链路演示

总览页增加“一键课程链路”，按固定顺序实际执行：Room 快照与 SharedPreferences 读取、内部 JSON
文件写入、ContentResolver 查询只读 Provider、启动 Agent 前台 Service、访问回环 HTTP API。链路只调用
既有仓库和安全边界，不增加任意 Shell、外网访问或新的底部页面。验证报告不包含 Agent Token。

## D12. 桌面 Agent v2 能力边界

桌面端通过能力发现调用定位、传感器采样、截图、UI 层级和文件交换。截图与 UI dump 使用固定输出
路径的类型化 RootCommand；坐标、按键和文本均验证参数，不提供任意 Shell。文件读写限制在
`files/agent-exchange`，单文件 4 MiB。桌面控制仍需 Token，写操作还需显式开启 Agent 控制。

## D13. 实时事件采用长轮询

事件接口使用带游标的 HTTP 长轮询，而不是在课程项目中再引入 WebSocket/SSE 依赖。服务保存有界
近期事件，客户端通过 `after` 和返回的 `lastEventId` 增量消费；单次等待最多 30 秒。这样继续复用
回环 Socket、Token 鉴权、Room 请求审计和 `adb forward`，桌面端也容易断线重连。

## D14. 自动化采用观察—动作—观察闭环

组合观察同时返回截图、UIAutomator XML 和前台包名。动作接口只接受已有的类型化 tap/swipe/key/
text/launch，不接受 Shell；动作成功后默认再次采集观察结果。桌面 Agent 应优先从 UI XML bounds
定位控件，用截图补充视觉语义，并在每个动作后验证结果，避免固定坐标脚本失控。

## D15. MCP 是桌面适配层

`scripts/rootpilot_mcp.py` 是零第三方依赖的 stdio MCP Server，只把 MCP tool call 映射到回环 HTTP
API；截图作为 MCP image 返回，UI XML 作为 text 返回。它不在手机或桌面新增网络监听面，安全边界
仍由 ADB 转发、API Token、Agent 控制开关、SafeMode 和类型化命令共同提供。

## D16. 无障碍树优先、uiautomator 兜底

`uiautomator dump` 在 PHK110 上约 2.9 秒，且与截图同请求时会超时丢数据。无障碍服务读同一棵树
只要几十到几百毫秒。但微信等应用会屏蔽无障碍子树，直接改用无障碍会让这些应用彻底读不到。

**决定：** 两条路径都保留，`/v1/ui/tree` 与观察接口优先用无障碍，返回 429 个字符以上的树才采用，
否则回退 uiautomator；响应用 `source` / `uiSource` 标明实际来源。调用方不需要区分。

## D17. 窗口几何单独成接口

坐标点击失败的第一大原因是**输入法遮挡**：键盘是一个独立窗口，盖在应用之上，落在其带内的触摸
被它消费。仅凭截图无法知道键盘是否弹起、弹到多高。

**决定：** 增加 `/v1/ui/windows` 返回窗口类型、包名、bounds 与焦点，并在观察结果里附 `occluders`。
点击前先确认目标点不在 `input-method` 的 bounds 内，而不是靠记忆里的固定坐标。

## D18. 分身靠 userId 而不是包名

ColorOS「应用分身」是同一个 APK 跑在第二个 Android 用户里，包名、Activity、界面完全相同；
两个实例的窗口可以同时存在于 WindowManager。按包名归属窗口必然把主号和分身认成同一个人。

**决定：** 前台用户来自 `topResumedActivity` 的 `u<id>`，可信且唯一；窗口的 `userId` 只在
「聚焦且包名匹配前台」时填充，其余留 `null`。启动分身必须 `am start --user <id>`，
`context.startActivity` 永远落在 user 0。

## D19. 文本输入的三档降级

`input text` 注入的是按键事件，会被输入法当成拼音组合：实测 `rootpilot 2050` 变成 `root'pilot`，
空格丢失、连字符变减号。而无障碍的 `ACTION_SET_TEXT` 在屏蔽节点树的应用（微信）里没有目标节点。

**决定：** 按能力降级——先 `ACTION_SET_TEXT`（任意 Unicode，不走输入法）；失败再 `input text`
（仅 ASCII，且依赖输入法处于英文模式）；中文兜底走「写剪贴板 + 粘贴」。剪贴板写入必须让
RootPilot 处于前台，Android 13+ 会静默丢弃后台应用的写操作。

## D20. 长按用分离的 DOWN/UP 事件

`input tap` 把按下与抬起合成一次注入，`input swipe x y x y <ms>` 在部分界面不足以触发长按判定
（例如朋友圈的「发表文字」）。**决定：** `tap` 增加 `holdMs` 参数，大于 0 时改发
`input motionevent DOWN` → 等待 → `UP` 两条独立事件。实测 1200 ms 稳定触发长按分支。

