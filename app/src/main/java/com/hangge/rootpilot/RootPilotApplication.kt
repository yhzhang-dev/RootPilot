package com.hangge.rootpilot

import android.app.Application
import android.content.Context
import com.hangge.rootpilot.data.export.ReportWriter
import com.hangge.rootpilot.data.local.RootPilotDatabase
import com.hangge.rootpilot.data.prefs.KeyValueStore
import com.hangge.rootpilot.data.prefs.KeystoreTokenCipher
import com.hangge.rootpilot.data.prefs.SettingsStore
import com.hangge.rootpilot.data.prefs.SharedPreferencesKeyValueStore
import com.hangge.rootpilot.data.prefs.TokenStore
import com.hangge.rootpilot.data.repository.AppRepository
import com.hangge.rootpilot.data.repository.DeviceRepository
import com.hangge.rootpilot.data.repository.HistoryRepository
import com.hangge.rootpilot.data.root.RootShell
import com.hangge.rootpilot.data.system.AppInfoSource
import com.hangge.rootpilot.data.system.AppProtection
import com.hangge.rootpilot.data.system.BatteryMonitor
import com.hangge.rootpilot.data.system.DeviceInfoSource
import com.hangge.rootpilot.data.system.AgentDeviceInfoSource
import com.hangge.rootpilot.data.system.AgentFileStore
import com.hangge.rootpilot.data.system.AgentLocationSource
import com.hangge.rootpilot.domain.ActionExecutor
import com.hangge.rootpilot.domain.DeviceAgentController
import com.hangge.rootpilot.service.AgentServer
import com.hangge.rootpilot.service.AgentEventHub
import com.hangge.rootpilot.service.AgentNotificationCenter
import com.hangge.rootpilot.service.AgentTelemetryManager

/**
 * Manual dependency injection (no Hilt). One container per process; ViewModels receive their
 * dependencies through small factories declared next to each screen.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext
    val application: Application = appContext as Application

    val database: RootPilotDatabase = RootPilotDatabase.build(appContext)

    private val keyValueStore: KeyValueStore = SharedPreferencesKeyValueStore(
        appContext.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE),
    )

    val settingsStore: SettingsStore = SettingsStore(keyValueStore)
    val tokenStore: TokenStore = TokenStore(keyValueStore, KeystoreTokenCipher())

    val rootShell: RootShell = RootShell()

    private val deviceInfoSource = DeviceInfoSource(appContext)
    val agentDeviceInfoSource = AgentDeviceInfoSource(appContext)
    val agentFileStore = AgentFileStore(appContext)
    val agentLocationSource = AgentLocationSource(appContext)
    val agentEventHub = AgentEventHub()
    val notificationCenter = AgentNotificationCenter(agentEventHub)
    val telemetryManager = AgentTelemetryManager(agentLocationSource, agentDeviceInfoSource, agentEventHub)
    private val appInfoSource = AppInfoSource(appContext)
    val appProtection = AppProtection(appContext)
    private val batteryMonitor = BatteryMonitor(appContext)

    val deviceRepository: DeviceRepository = DeviceRepository(
        context = appContext,
        snapshotDao = database.deviceSnapshotDao(),
        actionDao = database.actionRecordDao(),
        deviceInfo = deviceInfoSource,
        batteryMonitor = batteryMonitor,
    )

    val appRepository: AppRepository = AppRepository(appInfoSource)

    val historyRepository: HistoryRepository = HistoryRepository(
        snapshotDao = database.deviceSnapshotDao(),
        actionDao = database.actionRecordDao(),
        apiRequestDao = database.apiRequestDao(),
    )

    val actionExecutor: ActionExecutor = ActionExecutor(
        rootShell = rootShell,
        settings = settingsStore,
        protection = appProtection,
        actionDao = database.actionRecordDao(),
    )

    val deviceAgentController: DeviceAgentController = DeviceAgentController(
        context = appContext,
        settings = settingsStore,
        rootShell = rootShell,
        actionDao = database.actionRecordDao(),
    )

    val reportWriter: ReportWriter = ReportWriter(appContext)

    val agentServer: AgentServer = AgentServer(
        settings = settingsStore,
        tokenStore = tokenStore,
        deviceRepository = deviceRepository,
        appRepository = appRepository,
        historyRepository = historyRepository,
        actionExecutor = actionExecutor,
        apiRequestDao = database.apiRequestDao(),
        rootShell = rootShell,
        deviceInfo = agentDeviceInfoSource,
        deviceController = deviceAgentController,
        fileStore = agentFileStore,
        locationSource = agentLocationSource,
        eventHub = agentEventHub,
        notificationCenter = notificationCenter,
        telemetry = telemetryManager,
    )

    init {
        batteryMonitor.start()
    }
}

class RootPilotApplication : Application() {

    /**
     * Lazy because ContentProviders are instantiated before Application.onCreate: the provider's
     * onCreate touches this property, which must therefore not depend on onCreate having run.
     */
    val container: AppContainer by lazy { AppContainer(this) }
}
