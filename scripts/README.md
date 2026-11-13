# 脚本

桌面端与手机 Agent 交互用的辅助脚本。手机侧先启动 Agent 服务并复制 Token，再使用这里的脚本。

| 脚本 | 用途 |
|---|---|
| [connect-agent.ps1](connect-agent.ps1) | 定位 ADB、选择已授权设备、建立 `adb forward tcp:8765`，并验证 `/v1/health` |
| [rootpilot-agent.ps1](rootpilot-agent.ps1) | 桌面 CLI：能力发现、网络/传感器、截图、UI 树、应用启动、点击与文件读写 |
| [rootpilot_mcp.py](rootpilot_mcp.py) | 零第三方依赖的 stdio MCP Server，把 MCP tool call 映射到回环 HTTP API |
| [mcp-config.example.json](mcp-config.example.json) | 桌面 Agent 的 MCP 配置示例 |

## 快速开始

```powershell
.\connect-agent.ps1
$env:ROOTPILOT_TOKEN = "<应用内复制的 Token>"
.\rootpilot-agent.ps1 capabilities
py -3 .\rootpilot_mcp.py
```

## 约定

- 所有脚本只通过 ADB 转发访问回环 API，不新增网络监听面。
- 写操作仍需应用内打开 `Allow Agent Control`，冻结/强制停止还需关闭 `SafeMode`。
- 不要记录或提交 Token；脚本从 `ROOTPILOT_TOKEN` 环境变量读取。
