# RootPilot — Agent Entry

RootPilot is a local-first Android device gateway for personal desktop Agents and a course assignment. Read [`docs/ROOTPILOT_BRIEF.md`](docs/ROOTPILOT_BRIEF.md) before writing code. The v1 brief remains the course baseline; this file authorizes v2 Agent capabilities that extend it.

## Hard constraints

- Work only inside `E:\RootPilot`. `E:\Companion` and `E:\MyApplication` are read-only references.
- Use Kotlin, Jetpack Compose, Material 3, one `MainActivity`, and bottom-tab navigation.
- Root is optional at runtime: every screen opens without root; privileged actions render disabled with a visible reason.
- `RootShell` accepts only the typed commands listed in the brief. No free-form shell command reaches `su`.
- The Agent server binds `127.0.0.1` only, requires a token, and keeps write endpoints off by default. `SafeMode` is on by default.
- Local-first product: no analytics or crash reporting. Device data may be exposed to the owner's desktop Agent through loopback + ADB forwarding. Outbound calls require an explicit feature and visible setting.
- Use an emulator for instrumented tests. Never run `connectedDebugAndroidTest` on PHK110.
- Keep `E:\Companion` unchanged. RootPilot may add opt-in sensors, location, notification access, screen capture, accessibility/UI automation, file exchange, network diagnostics and other device-Agent capabilities. Do not add WeChat-specific or cloud-model code.
- Development efficiency is preferred, but root safety still applies: use typed commands, parameter validation, timeouts, audit logs and recovery paths. Do not expose unrestricted shell execution over HTTP.

## First task

1. Read `docs/ROOTPILOT_BRIEF.md` fully.
2. Scaffold the project as described in brief §5.
3. Run `.\gradlew.bat assembleDebug`.

**Done when:** the app installs and opens on the Dashboard with five destinations (four bottom tabs: Dashboard, Apps, History, Agent; plus Settings from the top bar), and does not crash when root is unavailable.

## Commands

```powershell
# in E:\RootPilot
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug

# emulator only, never PHK110
.\gradlew.bat connectedDebugAndroidTest
```

## Working agreement

- Build after each vertical slice; keep the app runnable.
- Put new code under `app/src/main/java/com/hangge/rootpilot/`; keep UI, repository, root, and system layers separate.
- Every root action goes through `ActionExecutor`; UI and Agent API call the same executor.
- Record material decisions in `docs/` as the project grows.
- Report honestly: a feature is done only when its stated acceptance check passes on a device or emulator.

## v2 direction

- Treat the phone as a capability-driven execution node for a desktop Agent.
- Add `/v1/capabilities` before capability-specific endpoints.
- Prefer structured JSON tools, job IDs for long work, and event streaming over arbitrary commands.
- Keep loopback binding and ADB forwarding as the default transport.
- Permissions may be requested because this is an owner-controlled device, but the app must show whether each capability is granted and operational.

## Current state

v1 implemented (2026-11-10). Build order slices 1–8 are done and manually verified on PHK110:
five destinations, Room + SharedPreferences + encrypted token, typed root layer, freeze/unfreeze,
read-only provider, JSON/CSV export, foreground Agent service with loopback API, battery/boot
receivers. `assembleDebug`, `testDebugUnitTest`, `lintDebug` pass; no emulator is installed, so
instrumented tests were not run (PHK110 is manual-only per this file). Remaining: course report,
5-minute demo, optional screenshots polish. Evidence: `docs/VERIFICATION.md`.
