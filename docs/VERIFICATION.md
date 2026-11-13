# 验证记录

设备：OnePlus PHK110（Android 16 / SDK 36，KernelSU 已授权 Root）。安装方式 `adb install -r`。

## 构建与测试

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| Debug 构建 | `.\gradlew.bat :app:assembleDebug` | 通过 |
| 单元测试 | `.\gradlew.bat :app:testDebugUnitTest` | 通过 |
| Lint | `.\gradlew.bat :app:lintDebug` | 见 `app/build/reports/lint-results-debug.html` |
| 课程链路功能（代码级） | `assembleDebug + testDebugUnitTest + lintDebug` | 通过；待设备手工验收 |

单元测试覆盖：设置默认值与持久化、端口校验、Token 加密/复用/重新生成/掩码、包名校验、
Root 命令模板、CSV 导出转义。

仪器测试未运行：本机没有可用 AVD，PHK110 不运行 `connectedDebugAndroidTest`
（Gradle 的 connected 任务会卸载应用并清空私有数据）。

## 手工验收（PHK110）

| 场景 | 结果 |
| --- | --- |
| 冷启动进入总览 | 通过；显示 PHK110 / OnePlus / Android 16 / 494 个应用 / Root 已授权 |
| 采集快照 | 通过；`device_snapshots` 增加 1 行，并写入 `SNAPSHOT` 审计记录 |
| 应用列表与搜索 | 通过；按名称/包名即时过滤，用户/系统/已停用筛选可用 |
| 应用详情 | 通过；版本、targetSdk、minSdk、安装时间、权限（危险权限标记）、启用状态 |
| SafeMode 拦截冻结 | 通过；详情页显示“SafeMode 已开启：冻结与强制停止不可用，解冻仍可用” |
| 关闭 SafeMode 后冻结 `com.hangge.myapplication` | 通过；`pm list packages -d` 出现该包，界面显示“已停用” |
| 解冻同一应用 | 通过；包恢复启用，`UNFREEZE` 记录写入 |
| 审计记录 | `FREEZE`、`UNFREEZE`、`SNAPSHOT`、`BOOT` 均在 `action_records` 中 |
| Agent 前台服务 | 通过；`dumpsys activity services` 显示 `isForeground=true`，端口 8765 |
| `GET /v1/health` | 200 `{"ok":true,"version":"2"}` |
| `GET /v1/status` 无 Token | 401 |
| 应用内自检 | health 200 / status 带 Token 200（51ms）/ status 无 Token 401 |
| 请求日志 | `api_requests` 记录方法、路径、状态码、耗时 |
| Provider 自检 | `snapshots` / `actions` 行数正确，无异常 |
| JSON 导出（SAF） | 通过；外部文件 1364 字节，结构 `schemaVersion/exportedAt/device/snapshots/actions`，不含 Token |
| 内部报告副本 | `files/reports/report-<ts>.json` 生成 |
| Token 存储 | SharedPreferences 仅含 `token_iv` / `token_cipher`，无明文 |
| 通知监听授权 | `enabled_notification_listeners` 包含 RootPilot listener |
| `GET /v1/notifications` | 200 |
| 定位/传感器订阅 start/status/stop | 均为 200 |
| `GET /v1/events` | 200，可读取订阅产生的增量事件 |
| 独立截图 / UI 树 | 均为 200；PHK110 上分别约 0.6s / 2.6s |
| 桌面 MCP 初始化 / tools/list / health tool | 通过；health 返回 API version 2 |

## 已知限制

- 组合观察依赖 OEM 的 UIAutomator dump，PHK110 上可能超过 3 秒；桌面调用建议使用至少 15 秒超时。
- 系统应用在部分 ROM 上 `firstInstallTime` 为 1970 年代，界面显示“系统预装”。
- OEM 会在安装后重放 `BOOT_COMPLETED`；接收器用 `SystemClock.elapsedRealtime()` 过滤 15 分钟以外的重放。
- 端口修改后需重启 Agent 服务生效。
