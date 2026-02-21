package io.onexr.demo

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.onexr.OneXrClient
import io.onexr.OneXrEndpoint
import io.onexr.XrControlEvent
import io.onexr.XrControlProtocolException
import io.onexr.XrDimmerLevel
import io.onexr.XrDeviceConfigErrorCode
import io.onexr.XrDeviceConfigException
import io.onexr.XrDisplayInputMode
import io.onexr.XrSceneMode
import io.onexr.demo.databinding.ActivityControlsDemoBinding
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ControlsDemoActivity : AppCompatActivity() {
    private lateinit var binding: ActivityControlsDemoBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var connectJob: Job? = null
    private var controlEventsJob: Job? = null
    private var client: OneXrClient? = null
    private var logsVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlsDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonConnectControl.setOnClickListener { connectControlSession() }
        binding.buttonViewLogs.setOnClickListener { setLogsVisible(!logsVisible) }
        binding.buttonClearLogs.setOnClickListener { binding.textOutput.text = "" }
        binding.buttonCopyLogs.setOnClickListener { copyLogsToClipboard() }

        binding.buttonGetId.setOnClickListener {
            runControlCommand("get_id") { xrClient ->
                val id = xrClient.getId()
                appendLog("id=$id")
            }
        }
        binding.buttonGetSoftwareVersion.setOnClickListener {
            runControlCommand("get_software_version") { xrClient ->
                val version = xrClient.getSoftwareVersion()
                appendLog("softwareVersion=$version")
            }
        }
        binding.buttonGetDspVersion.setOnClickListener {
            runControlCommand("get_dsp_version") { xrClient ->
                val version = xrClient.getDspVersion()
                appendLog("dspVersion=$version")
            }
        }
        binding.buttonGetConfigRaw.setOnClickListener {
            runControlCommand("get_config_raw") { xrClient ->
                val raw = xrClient.getConfigRaw()
                val preview = raw.replace("\n", " ").take(160)
                appendLog("configRaw length=${raw.length} preview=${preview}")
            }
        }
        binding.buttonGetConfig.setOnClickListener {
            runControlCommand("get_config") { xrClient ->
                val config = xrClient.getConfig()
                appendLog(
                    "config status=success fsn=${config.fsn} version=${config.glassesVersion} " +
                        "gyroBiasSamples=${config.imu.gyroBiasTemperatureData.size} " +
                        "hasRgb=${config.rgbCamera != null} hasSlam=${config.slamCamera != null}"
                )
            }
        }

        binding.buttonSceneButtonsEnabled.setOnClickListener {
            runControlCommand("set_scene_mode_buttons_enabled") { xrClient ->
                xrClient.setSceneMode(XrSceneMode.ButtonsEnabled)
            }
        }
        binding.buttonSceneButtonsDisabled.setOnClickListener {
            runControlCommand("set_scene_mode_buttons_disabled") { xrClient ->
                xrClient.setSceneMode(XrSceneMode.ButtonsDisabled)
            }
        }

        binding.buttonInputRegular.setOnClickListener {
            runControlCommand("set_display_input_mode_regular") { xrClient ->
                xrClient.setDisplayInputMode(XrDisplayInputMode.Regular)
            }
        }
        binding.buttonInputSideBySide.setOnClickListener {
            runControlCommand("set_display_input_mode_side_by_side") { xrClient ->
                xrClient.setDisplayInputMode(XrDisplayInputMode.SideBySide)
            }
        }

        binding.buttonSetBrightness.setOnClickListener {
            val brightness = binding.inputBrightness.text.toString().trim().toIntOrNull()
            if (brightness == null) {
                appendLog("brightness must be an integer in range 0..9")
                return@setOnClickListener
            }
            runControlCommand("set_brightness") { xrClient ->
                xrClient.setBrightness(brightness)
            }
        }

        binding.buttonDimmerLightest.setOnClickListener {
            runControlCommand("set_dimmer_lightest") { xrClient ->
                xrClient.setDimmer(XrDimmerLevel.Lightest)
            }
        }
        binding.buttonDimmerMiddle.setOnClickListener {
            runControlCommand("set_dimmer_middle") { xrClient ->
                xrClient.setDimmer(XrDimmerLevel.Middle)
            }
        }
        binding.buttonDimmerDimmest.setOnClickListener {
            runControlCommand("set_dimmer_dimmest") { xrClient ->
                xrClient.setDimmer(XrDimmerLevel.Dimmest)
            }
        }

        setLogsVisible(false)
        setControlButtonsEnabled(false)
        binding.textStatus.text = getString(R.string.controls_status_idle)
    }

    override fun onDestroy() {
        val xrClient = client
        client = null
        connectJob?.cancel()
        connectJob = null
        controlEventsJob?.cancel()
        controlEventsJob = null
        if (xrClient != null) {
            runBlocking {
                try {
                    xrClient.stop()
                } catch (t: Throwable) {
                    appendLog("control stop error=${t.javaClass.simpleName}:${t.message ?: "no-message"}")
                }
            }
        }
        uiScope.cancel()
        super.onDestroy()
    }

    private fun connectControlSession() {
        if (connectJob?.isActive == true) {
            return
        }

        val host = binding.inputHost.text.toString().trim()
        val ports = parsePorts(binding.inputPorts.text.toString())
        if (host.isEmpty()) {
            appendLog("host is empty")
            return
        }

        val endpoint = buildEndpoint(host, ports)
        val previousClient = client
        if (previousClient != null) {
            uiScope.launch {
                try {
                    previousClient.stop()
                } catch (t: Throwable) {
                    appendLog("previous control stop error=${t.javaClass.simpleName}:${t.message ?: "no-message"}")
                }
            }
        }

        val xrClient = OneXrClient(applicationContext, endpoint)
        client = xrClient

        binding.textStatus.text = getString(R.string.controls_status_connecting)
        setControlButtonsEnabled(false)
        appendLog("=== controls start ${nowIso()} host=${endpoint.host} control=${endpoint.controlPort} ===")

        connectJob = uiScope.launch {
            try {
                val id = xrClient.getId()
                binding.textStatus.text = getString(R.string.controls_status_connected)
                appendLog("controls ready id=$id")
                setControlButtonsEnabled(true)
                attachControlEventCollector(xrClient)
            } catch (_: CancellationException) {
                appendLog("control connect cancelled")
            } catch (t: Throwable) {
                binding.textStatus.text = getString(
                    R.string.controls_status_error,
                    t.message ?: t.javaClass.simpleName
                )
                appendLog("control connect error=${t.javaClass.simpleName}:${t.message ?: "no-message"}")
                setControlButtonsEnabled(false)
            } finally {
                connectJob = null
            }
        }
    }

    private fun attachControlEventCollector(xrClient: OneXrClient) {
        controlEventsJob?.cancel()
        controlEventsJob = uiScope.launch {
            xrClient.advanced.controlEvents.collect { event ->
                when (event) {
                    is XrControlEvent.KeyStateChange -> {
                        appendLog(
                            "[XR][CONTROL][KEY] type=${event.keyType} state=${event.keyState} deviceTimeNs=${event.deviceTimeNs}"
                        )
                    }

                    is XrControlEvent.UnknownMessage -> Unit
                }
            }
        }
    }

    private fun runControlCommand(
        commandName: String,
        action: suspend (OneXrClient) -> Unit
    ) {
        val xrClient = client
        if (xrClient == null) {
            appendLog("$commandName ignored (controls not started)")
            return
        }
        uiScope.launch {
            try {
                appendLog("$commandName start")
                action(xrClient)
                appendLog("$commandName ok")
            } catch (_: CancellationException) {
                appendLog("$commandName cancelled")
            } catch (t: Throwable) {
                appendLog(
                    "$commandName error status=${classifyCommandErrorStatus(t)} detail=${t.javaClass.simpleName}:${t.message ?: "no-message"}"
                )
            }
        }
    }

    private fun classifyCommandErrorStatus(error: Throwable): String {
        return when (error) {
            is XrControlProtocolException -> "transport_error"
            is XrDeviceConfigException -> when (error.code) {
                XrDeviceConfigErrorCode.PARSE_ERROR -> "parse_error"
                XrDeviceConfigErrorCode.SCHEMA_VALIDATION_ERROR -> "schema_validation_error"
            }

            else -> "runtime_error"
        }
    }

    private fun setControlButtonsEnabled(enabled: Boolean) {
        binding.buttonGetId.isEnabled = enabled
        binding.buttonGetSoftwareVersion.isEnabled = enabled
        binding.buttonGetDspVersion.isEnabled = enabled
        binding.buttonGetConfigRaw.isEnabled = enabled
        binding.buttonGetConfig.isEnabled = enabled
        binding.buttonSceneButtonsEnabled.isEnabled = enabled
        binding.buttonSceneButtonsDisabled.isEnabled = enabled
        binding.buttonInputRegular.isEnabled = enabled
        binding.buttonInputSideBySide.isEnabled = enabled
        binding.buttonSetBrightness.isEnabled = enabled
        binding.inputBrightness.isEnabled = enabled
        binding.buttonDimmerLightest.isEnabled = enabled
        binding.buttonDimmerMiddle.isEnabled = enabled
        binding.buttonDimmerDimmest.isEnabled = enabled
    }

    private fun setLogsVisible(visible: Boolean) {
        logsVisible = visible
        binding.logsPanel.visibility = if (visible) View.VISIBLE else View.GONE
        binding.buttonViewLogs.text = if (visible) {
            getString(R.string.action_hide_logs)
        } else {
            getString(R.string.action_view_logs)
        }
    }

    private fun appendLog(line: String) {
        binding.textOutput.append(line)
        binding.textOutput.append("\n")
        binding.outputScroll.post {
            binding.outputScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun copyLogsToClipboard() {
        val logs = binding.textOutput.text?.toString().orEmpty()
        if (logs.isBlank()) {
            Toast.makeText(this, R.string.logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java) ?: run {
            Toast.makeText(this, R.string.clipboard_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("xr-control-logs", logs))
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }

    private fun buildEndpoint(host: String, ports: List<Int>): OneXrEndpoint {
        val controlPort = ports.getOrNull(0) ?: 52999
        val streamPort = ports.getOrNull(1) ?: if (controlPort == 52999) 52998 else 52999
        return OneXrEndpoint(
            host = host,
            controlPort = controlPort,
            streamPort = streamPort
        )
    }

    private fun parsePorts(raw: String): List<Int> {
        return raw.split(",")
            .mapNotNull { token -> token.trim().toIntOrNull()?.takeIf { it in 1..65535 } }
            .distinct()
    }

    private fun nowIso(): String {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}
