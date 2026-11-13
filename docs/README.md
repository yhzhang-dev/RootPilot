# RootPilot 文档

本目录存放 RootPilot 的设计、接口、决策与验证文档。根目录 [README](../README.md) 是项目总览。

## 文档索引

| 文档 | 内容 |
|---|---|
| [ROOTPILOT_BRIEF.md](ROOTPILOT_BRIEF.md) | 项目简报：目标、范围、约束、课程映射、构建顺序、验收标准 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 分层结构、线程与数据流、Room 表 |
| [API.md](API.md) | 回环 HTTP API、Token 鉴权与 MCP 工具 |
| [DECISIONS.md](DECISIONS.md) | 15 条设计决策（D1–D15）及取舍理由 |
| [VERIFICATION.md](VERIFICATION.md) | 构建、单元测试、PHK110 手工验收记录与已知限制 |
| [开发日志.md](开发日志.md) | 按里程碑整理的开发记录与待办 |

## 截图

界面截图在 [screenshots/](screenshots/)，包括总览、应用、历史、Agent、设置、导出与深色主题。

## 约定

- 接口与安全边界以 `DECISIONS.md` 与代码为准；改动接口时同步更新 `API.md`。
- 验证结果以 `VERIFICATION.md` 为准，不声称未运行的仪器测试。
- 开发日志追加式记录，最新在最上。
