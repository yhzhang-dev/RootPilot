package com.hangge.rootpilot.data.root

/**
 * The complete allowlist of commands that may be executed with root. There is intentionally no
 * "run arbitrary string" variant: every entry renders a fixed template, and package arguments are
 * validated in the constructor.
 */
sealed interface RootCommand {
    val commandLine: String

    data object Id : RootCommand {
        override val commandLine: String = "id"
    }

    data object DeviceModel : RootCommand {
        override val commandLine: String = "getprop ro.product.model"
    }

    data object AndroidRelease : RootCommand {
        override val commandLine: String = "getprop ro.build.version.release"
    }

    data object BatteryDump : RootCommand {
        override val commandLine: String = "dumpsys battery"
    }

    data object UserPackages : RootCommand {
        override val commandLine: String = "pm list packages -3"
    }

    data object ThermalZone0 : RootCommand {
        override val commandLine: String = "cat /sys/class/thermal/thermal_zone0/temp"
    }

    data object CurrentActivity : RootCommand {
        override val commandLine: String = "dumpsys activity activities"
    }

    data object CaptureScreen : RootCommand {
        const val OUTPUT_PATH = "/data/user/0/com.hangge.rootpilot/cache/agent-screen.png"
        override val commandLine: String = "screencap -p $OUTPUT_PATH && chmod 644 $OUTPUT_PATH"
    }

    data object DumpUiHierarchy : RootCommand {
        const val OUTPUT_PATH = "/data/user/0/com.hangge.rootpilot/cache/agent-ui.xml"
        override val commandLine: String = "uiautomator dump $OUTPUT_PATH >/dev/null && chmod 644 $OUTPUT_PATH"
    }

    data class Tap(val x: Int, val y: Int) : RootCommand {
        init { require(x in 0..20_000 && y in 0..20_000) }
        override val commandLine: String = "input tap $x $y"
    }

    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Int) : RootCommand {
        init {
            require(x1 in 0..20_000 && y1 in 0..20_000 && x2 in 0..20_000 && y2 in 0..20_000)
            require(durationMs in 50..5_000)
        }
        override val commandLine: String = "input swipe $x1 $y1 $x2 $y2 $durationMs"
    }

    data class KeyEvent(val keyCode: Int) : RootCommand {
        init { require(keyCode in 0..400) }
        override val commandLine: String = "input keyevent $keyCode"
    }

    data class InputText(val text: String) : RootCommand {
        init {
            require(text.length in 1..200)
            require(SAFE_TEXT.matches(text)) { "Text contains unsupported characters" }
        }
        override val commandLine: String = "input text ${text.replace(" ", "%s")}" 
    }

    data class DisableUser(val packageName: String) : RootCommand {
        init {
            PackageNames.requireValid(packageName)
        }

        override val commandLine: String = "pm disable-user --user 0 $packageName"
    }

    data class Enable(val packageName: String) : RootCommand {
        init {
            PackageNames.requireValid(packageName)
        }

        override val commandLine: String = "pm enable $packageName"
    }

    data class ForceStop(val packageName: String) : RootCommand {
        init {
            PackageNames.requireValid(packageName)
        }

        override val commandLine: String = "am force-stop $packageName"
    }

    companion object {
        private val SAFE_TEXT = Regex("[A-Za-z0-9 ._@+,:/\\-]+")
    }
}
