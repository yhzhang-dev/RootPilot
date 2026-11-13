# RootPilot — Project Brief

- **Status:** greenfield; no code yet.
- **Workspace:** `E:\RootPilot`
- **Audience:** coding agent starting the project; read this file fully before implementation.
- **Authority:** this brief wins over ad-hoc preferences; record material changes here or in a linked ADR.
- **Working target:** 2026-11-24 (two weeks from 2026-11-10). Deliverables: APK + course report + demo.

## 1. Mission

RootPilot is a local-first Android root control console. It gives the user one place to inspect device state, audit installed apps, perform a small set of reversible root actions, and expose the same actions to a future local Agent through a loopback HTTP API.

It is also a course assignment. The class reference app at `E:\MyApplication` demonstrates the chapters the course cares about: file storage, SharedPreferences, SQLite, ContentProvider, Service, BroadcastReceiver, and network programming. RootPilot must exercise those same platform components in a coherent personal tool, not as disconnected demo tabs.

**Positioning:** a utility, not a companion app. No character, no WeChat, no camera, no location, no cloud model.

## 2. Product scope (v1)

Five destinations, one `MainActivity`, bottom navigation. The Settings destination opens from the top bar and is not a bottom tab.

| Screen | Must have | Acceptance |
| --- | --- | --- |
| **Dashboard** | Device model, Android version, root status, battery/charging, memory, storage, Agent service state, last action; refresh + snapshot | Opens and renders without root; snapshot writes one Room row; refresh updates values |
| **Apps** | App list with icon/name/package/version/targetSdk/install time; search; filter user/system/disabled; detail; permission list; freeze/unfreeze/force-stop | Lists all installed apps with `QUERY_ALL_PACKAGES`; search filters instantly; root actions disabled without root; user-app freeze/unfreeze works with root and is logged |
| **History** | Room-backed snapshot/action/API log; filter by type; export JSON and CSV; clear | Records survive restart; export writes a readable file via SAF; clear asks for confirmation |
| **Agent** | Start/stop foreground Agent service; port; token display/regenerate; endpoint list; in-app API self-test; request log | Service starts from foreground with a notification; self-test shows HTTP status and JSON; requests are logged; token is required |
| **Settings** | Root check; SafeMode; Allow Agent Control; theme; data cleanup; about | Toggles persist; SafeMode blocks freeze/force-stop in both UI and API; unfreeze remains available |

UI language: Chinese. Code identifiers and package names: English. Use Material 3 with a compact, dark-friendly tool aesthetic; no Companion character or playful copy.

## 3. Non-negotiable constraints

- **Package:** `com.hangge.rootpilot`; app label `RootPilot`.
- **SDK:** minSdk 29, targetSdk 37, compileSdk 37; versionCode 1, versionName `1.0`.
- **Single Activity:** `MainActivity` with Compose tabs, matching the reference app's structure.
- **Root optional:** root detection is a runtime fact, never a startup requirement. Without root, the app is a read-only device/app inspector with history and API GET endpoints.
- **Local only:** bind the Agent server to loopback only. No cloud, analytics, crash reporting, or outbound API calls. `INTERNET` exists for the loopback server and its in-app self-test.
- **Typed root input:** package names are validated; commands are built from a fixed enum/template. No arbitrary user string reaches `su`.
- **Safe by default:** `SafeMode = true`; `Allow Agent Control = false`; write endpoints require both token and an explicit toggle.
- **No destructive system work:** no deleting data, remounting partitions, editing `/system`, changing display density, or batch-revoking permissions.
- **No legacy Companion features:** no notification listener, WeChat parsing, camera, location, DeepSeek, or cloud key. Those stay in `E:\Companion`.

## 4. Course mapping

The reference app's tabs are effectively the rubric map:

| Course chapter / component | Reference app | RootPilot implementation |
| --- | --- | --- |
| 6.2 File storage | `FileStorageHelper` + `FileStorageScreen` | Internal report file + SAF JSON/CSV export |
| 6.4 SharedPreferences | `PreferencesHelper` + `PreferencesScreen` | Settings, port, SafeMode, encrypted Agent token |
| SQLite | `UserDatabaseHelper` + `UserLibraryScreen` | Room: snapshots, actions, API requests |
| 7 ContentProvider | `ContactsHelper` + `ContactsScreen` | Custom read-only `RootPilotProvider`; in-app ContentResolver self-test |
| 8 Service | `TimerService` + `TimerScreen` | `AgentService` foreground service hosting the loopback API |
| 8 Broadcast | static + dynamic receivers | Dynamic battery receiver + static boot receiver + snapshot-saved broadcast |
| 9 Network | `NetworkImageLoader`, `LocalSocketServer` | Loopback HTTP API + in-app `HttpURLConnection` self-test + JSON |
| Compose UI | tabbed Compose app | Five destinations, Material 3, ViewModel state |

If the rubric literally requires reading the system contacts provider, add a small read-only contacts screen as a stretch item; the custom provider is the primary implementation.

## 5. Tech stack and structure

Use the versions already proven in `E:\Companion` where practical: Gradle 9.6, AGP 9.4.1, Kotlin 2.2.10, Compose BOM 2026.02.01, Room 2.8.4, KSP 2.3.11.

Allowed dependencies:

- Compose BOM, `activity-compose`, `material3`, `ui`, `ui-tooling-preview`
- `lifecycle-runtime-ktx`, `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose`
- `navigation-compose`
- `room-runtime`, `room-ktx`, KSP `room-compiler`
- `core-ktx`
- Test: JUnit, AndroidX test, Espresso, `room-testing`, Compose UI test
- Debug: Compose tooling and test manifest

Use platform APIs for the rest: `HttpURLConnection`, `ServerSocket`, `org.json`, `PackageManager`, `BatteryManager`, `StatFs`, `ActivityManager`, `ProcessBuilder`. No Hilt, Retrofit, Gson, WorkManager, DataStore, or image-loading library.

```text
app/src/main/java/com/hangge/rootpilot/
├── RootPilotApplication.kt        // manual AppContainer
├── MainActivity.kt
├── data/
│   ├── local/                     // Room database, entities, DAOs
│   ├── prefs/                     // SettingsStore, TokenStore
│   ├── root/                      // RootShell, RootCommand, RootResult
│   ├── system/                    // DeviceInfoSource, AppInfoSource
│   └── repository/                // DeviceRepository, AppRepository, HistoryRepository
├── domain/
│   ├── model/                     // DeviceSnapshot, AppInfo, ActionRecord, AgentAction
│   └── ActionExecutor.kt          // single execution path for UI + API
├── provider/RootPilotProvider.kt
├── service/AgentService.kt
├── service/AgentServer.kt
├── receiver/BatteryReceiver.kt
├── receiver/BootReceiver.kt
└── ui/
    ├── dashboard/  apps/  history/  agent/  settings/
    ├── components/  theme/
    └── RootPilotNavHost.kt
```

Layering rule: Composable → ViewModel → Repository → data source. Composables never call DAO, `RootShell`, or `PackageManager` directly. ViewModels expose one `StateFlow<UiState>` per screen through `collectAsStateWithLifecycle()`.

`ActionExecutor` is the only path that performs freeze/unfreeze/force-stop. UI and Agent API call the same executor; the executor writes an `ActionRecordEntity` for every attempt.

Manual dependency injection: `RootPilotApplication` creates the database, repositories, `RootShell`, and `ActionExecutor`; ViewModels use a small factory. This is deliberate for a two-week assignment; do not add Hilt.

## 6. Data model

Room database `rootpilot.db`, version 1, `exportSchema = true`, schema directory `app/schemas`.

```kotlin
@Entity(tableName = "device_snapshots")
data class DeviceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val capturedAt: Long,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val batteryTemperatureC: Float?,
    val memoryFreeBytes: Long,
    val storageFreeBytes: Long,
    val rootAvailable: Boolean,
    val appCount: Int
)

@Entity(tableName = "action_records")
data class ActionRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val packageName: String?,
    val action: String,          // SNAPSHOT, FREEZE, UNFREEZE, FORCE_STOP, BOOT
    val success: Boolean,
    val message: String
)

@Entity(tableName = "api_requests")
data class ApiRequestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val method: String,
    val path: String,
    val statusCode: Int,
    val durationMs: Long
)
```

DAOs expose `Flow<List<...>>` ordered newest-first, plus insert/delete-all. Export uses JSON and CSV; never serialize the Agent token.

SharedPreferences (course chapter 6.4):

- `agent_port: Int = 8765`
- `safe_mode: Boolean = true`
- `agent_control: Boolean = false`
- `theme: String = "system"`
- `token_iv: String`, `token_cipher: String` (Base64, AES/GCM via Android Keystore; alias `rootpilot_agent_token`)
- Regenerating the token replaces both token fields. Never write the token to logcat, Room, exports, or UI beyond a masked display.

`ContentProvider`:

- Authority: `com.hangge.rootpilot.provider`; `exported="false"`; read-only.
- Paths: `snapshots` → all device snapshots, `actions` → all action records.
- Implement `query()` and `getType()`; `insert/update/delete` throw `UnsupportedOperationException`.
- The Agent screen includes a “Provider self-test” that queries both paths through `ContentResolver` and shows counts. Future external Agents use the HTTP API, not an exported provider.

Export file shape:

```json
{"schemaVersion":1,"exportedAt":1700000000000,"device":{...},"snapshots":[...],"actions":[...]}
```

Write a copy to `filesDir/reports/report-<timestamp>.json`, then let the user save it through `ActivityResultContracts.CreateDocument("application/json")`. No storage permission.

## 7. Root layer

`RootShell.run(command: RootCommand): RootResult` runs on `Dispatchers.IO`:

- `ProcessBuilder("su", "-c", commandLine)`, merged stderr, 8 s timeout, output capped at 32 KiB, process destroyed on timeout.
- `RootResult(success, exitCode, output)`; failures become records, not crashes.
- Root detection: `su -c id` and parse `uid=0`; cache only for the current process refresh.
- Package names match `^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$`; commands are fixed templates.

Allowlist:

| Command | Purpose |
| --- | --- |
| `id` | Root check |
| `getprop ro.product.model` | Device model |
| `getprop ro.build.version.release` | Android release |
| `dumpsys battery` | Battery details |
| `pm list packages -3` | User packages (root fallback) |
| `pm disable-user --user 0 <pkg>` | Freeze a user app |
| `pm enable <pkg>` | Unfreeze |
| `am force-stop <pkg>` | Force stop |
| `cat /sys/class/thermal/thermal_zone0/temp` | Optional CPU/thermal temperature |

Freeze rules:

- Only packages with `ApplicationInfo.FLAG_SYSTEM == 0`.
- Exclude RootPilot itself, the current home app, and enabled input-method packages. If detection is uncertain, require an explicit confirmation and show the package name.
- Freeze and force-stop require `SafeMode == false` and a confirmation dialog. Unfreeze is always allowed.
- Record the action before and after execution; show success/failure in the Apps detail and History.
- Provide “Unfreeze all RootPilot-frozen apps” in Settings, built from recorded actions.

If `su` is missing, denied, times out, or returns non-zero, set `rootAvailable = false`, show “未授权 Root”, and keep the screen usable.

## 8. Agent API

`AgentService` is a foreground service. It starts when the user taps “Start Agent” on the Agent screen, shows a persistent notification with a Stop action, and stops the server on destroy.

`AgentServer`:

- `ServerSocket` bound to `InetAddress.getLoopbackAddress()` and the configured port (default 8765).
- One request per connection; HTTP/1.1 request line and headers; JSON body limit 8 KiB; 5 s socket read timeout.
- JSON via `org.json`; response `Content-Type: application/json; charset=utf-8`.
- Token header: `X-RootPilot-Token`. Compare with `MessageDigest.isEqual`; missing/invalid → 401.
- `GET /v1/health` is the only tokenless endpoint and returns `{"ok":true,"version":"1"}`.
- Write endpoints require `AllowAgentControl == true` and `SafeMode == false`; otherwise 403.
- Every request writes an `ApiRequestEntity` (method, path, status, duration); never log the token or body.
- Bind loopback only. Document `adb forward tcp:8765 tcp:8765` for a desktop Agent.

Endpoints:

| Method | Path | Response |
| --- | --- | --- |
| GET | `/v1/health` | `{"ok":true,"version":"1"}` |
| GET | `/v1/status` | device, Android, root, battery, charging, memory/storage free, app count |
| GET | `/v1/apps?query=&limit=50` | `{items:[{packageName,label,versionName,targetSdk,isSystem,isEnabled,permissions}]}` |
| GET | `/v1/history?limit=50` | `{items:[{createdAt,packageName,action,success,message}]}` |
| POST | `/v1/apps/{pkg}/freeze` | `{ok:true, packageName}` or typed error |
| POST | `/v1/apps/{pkg}/unfreeze` | `{ok:true, packageName}` or typed error |
| POST | `/v1/apps/{pkg}/force-stop` | `{ok:true, packageName}` or typed error |

Error shape: `{"error":"human-readable reason","code":"ROOT_UNAVAILABLE|SAFE_MODE|AGENT_CONTROL_OFF|INVALID_PACKAGE|SYSTEM_APP|NOT_FOUND|COMMAND_FAILED"}`.

All write endpoints go through `ActionExecutor`, exactly like the UI. The API is the Agent seam; do not add an LLM, cloud call, or MCP server in this phase.

## 9. Screen behavior details

- **Dashboard:** cards for device, root, battery, memory/storage, Agent service. “Capture snapshot” inserts a row and refreshes. Show root-only values as “需要 Root” rather than blank.
- **Apps:** load app metadata on `Dispatchers.IO`; cache icons; avoid decoding all icons on the main thread. Search by label/package. Filters: all/user/system/disabled. Detail shows dangerous/requested permissions and action buttons. Without root, buttons disabled with reason.
- **History:** tabs or chips for snapshots/actions/API; newest first; empty state; export current type or all; clear with confirmation.
- **Agent:** service switch; port; masked token + copy/regenerate; endpoint list with method/path; “测试本地 API” button; request log; explanation that the server is loopback-only.
- **Settings:** root check, SafeMode, Allow Agent Control, theme, unfreeze-all, clear history, app version, course/assignment note.

Error states use specific text: “未授权 Root”, “服务未启动”, “Token 无效”, “SafeMode 已开启”, “该应用是系统应用”. Do not collapse every failure into “网络错误”.

## 10. Build order

1. **Scaffold.** Create Gradle project, package, theme, bottom navigation, five empty destinations. Run `assembleDebug`.
   **Done when:** app launches on Dashboard and all tabs open without crash.
2. **Data layer.** Room entities/DAOs/database, SharedPreferences settings, encrypted token store, repositories, schema export.
   **Done when:** unit tests for token/settings/DAO pass and the app builds.
3. **Root + device.** RootShell, root check, device/battery/storage info, snapshot insertion, Dashboard.
   **Done when:** no-root device shows disabled root actions; rooted device shows real values; snapshot survives restart.
4. **Apps.** PackageManager app list, search, filters, detail, permission summary, icons.
   **Done when:** all installed apps are listed; search/filter works; no root actions yet.
5. **Actions + History.** ActionExecutor, freeze/unfreeze/force-stop, SafeMode, confirmation, audit records, unfreeze-all.
   **Done when:** freeze/unfreeze a test user app on PHK110; no-root path disables actions; History reflects every attempt.
6. **Provider + export.** RootPilotProvider, in-app ContentResolver self-test, JSON/CSV export via SAF, internal report copy.
   **Done when:** provider returns rows; exported file opens; clear works.
7. **Service + API.** AgentService, foreground notification, AgentServer, token auth, GET endpoints, in-app HTTP self-test, request log.
   **Done when:** service starts/stops; self-test returns 200; missing token returns 401; GET status/apps work with root off.
8. **Broadcast.** Dynamic battery receiver, static boot receiver, snapshot-saved broadcast; connect to Dashboard/History.
   **Done when:** charger plug/unplug updates state; reboot creates a record; no crash.
9. **Polish + tests + report.** Error states, dark theme, accessibility, screenshots, report, demo.
   **Done when:** `assembleDebug`, `testDebugUnitTest`, `lintDebug` pass; emulator instrumentation passes; report and 5-minute demo are ready.

## 11. Verification

Commands:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
# emulator only
.\gradlew.bat connectedDebugAndroidTest
```

Manual matrix:

| Scenario | Expected |
| --- | --- |
| No root, fresh install | App opens; Dashboard shows “未授权 Root”; Apps/History/Provider/API GET work |
| Rooted PHK110 | Root check true; device values; freeze/unfreeze a test user app |
| Freeze without SafeMode off | UI blocked; API returns 403 with `SAFE_MODE` |
| API without token | 401 |
| API write with `AllowAgentControl=false` | 403 with `AGENT_CONTROL_OFF` |
| Agent self-test | 200 + JSON status + latency |
| Charger plug/unplug | Dashboard battery/charging updates; no crash |
| Reboot | Boot record in History |
| Export | JSON/CSV readable; token absent |
| Restart app | Records persist; token persists |

Instrumented tests run on an emulator. On PHK110 use only `adb install -r` and manual checks; the Gradle connected test task previously uninstalled the app and destroyed private data.

## 12. Reference projects

- `E:\MyApplication` — course reference. Read its tab structure and the chapter mapping. Reuse patterns for file storage, SharedPreferences, SQLite/ContentProvider, foreground Service, static/dynamic receivers, and `HttpURLConnection`/socket. Do not copy its package, app name, or seed data.
- `E:\Companion` — personal experimental app. Reuse the `RootBridge` command/timeout pattern, Compose theme ideas, and Room conventions. Do not copy notification listening, WeChat parsing, camera, location, sensors, DeepSeek, or its permissions.

## 13. Known pitfalls

- **Cleartext loopback:** Android blocks cleartext HTTP by default. Add `res/xml/network_security_config.xml` permitting cleartext only for `localhost`/`127.0.0.1`, keep base cleartext false, and verify the in-app self-test.
- **Foreground service:** use `foregroundServiceType="specialUse"` with a subtype property, request `POST_NOTIFICATIONS`, start the service from the visible Activity, and keep a Stop action on the notification.
- **Package visibility:** `QUERY_ALL_PACKAGES` is needed to list all apps on Android 11+; root `pm list packages` is the fallback. Explain this in the report.
- **Freeze scope:** `pm disable-user` on a launcher/IME can make the phone hard to use. Exclude home/IME/self/system packages; require confirmation; keep unfreeze easy.
- **Provider export:** keep `exported="false"`; the HTTP API is the external seam. Do not expose a writable provider.
- **Token leakage:** never log headers, never put the token in a URL, never export it.
- **Instrumented tests:** they can uninstall the app and wipe private data. Use an emulator; keep PHK110 for manual acceptance only.
- **Compose state:** no Room/root/package queries in composables; use ViewModel + `StateFlow`; use `collectAsStateWithLifecycle`.
- **Root prompts:** Magisk/root managers may show a prompt; handle denial and timeout without blocking the UI.

## 14. Report and demo

Report sections: project goal and scope; architecture/MVVM; Room schema; ContentProvider; Service and Broadcast; Network/API contract; root security model and fallback; testing evidence; limitations; future Agent integration. Include screenshots of all five destinations, a rooted action log, a provider self-test, and an API response.

Five-minute demo:

1. Install without root; Dashboard, Apps, History, Provider self-test.
2. Enable root on PHK110; check root; freeze/unfreeze a test app; show action log.
3. Start Agent service; run in-app self-test; show 401 without token and 200 with token.
4. Plug/unplug charger; show broadcast-driven update.
5. Export JSON; show token absent; close with future Agent/API direction.

**Definition of done:** the APK builds, runs without root, performs the rooted actions on the test device, demonstrates every course component above, and the report explains both the architecture and the security boundaries.
