package de.ammar.qadam.service

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import de.ammar.qadam.Bridge
import de.ammar.qadam.LiveSignals
import de.ammar.qadam.QadamApp
import de.ammar.qadam.core.config.Thresholds
import de.ammar.qadam.core.engine.Action
import de.ammar.qadam.core.engine.ActionResult
import de.ammar.qadam.core.engine.Engine
import de.ammar.qadam.core.engine.OneShot
import de.ammar.qadam.core.engine.Output
import de.ammar.qadam.core.engine.Overlay
import de.ammar.qadam.core.engine.Tick
import de.ammar.qadam.core.logger.Reports
import de.ammar.qadam.core.logger.RowAccumulator
import de.ammar.qadam.core.logger.RowContext
import de.ammar.qadam.core.logger.RowTiming
import de.ammar.qadam.core.logger.SamplingPlanner
import de.ammar.qadam.core.model.EventRow
import de.ammar.qadam.core.model.EventType
import de.ammar.qadam.core.model.Label
import de.ammar.qadam.core.rules.AppCategorizer
import de.ammar.qadam.core.rules.ProtectedApps
import de.ammar.qadam.core.rules.Settings
import de.ammar.qadam.core.signals.StepLog
import de.ammar.qadam.data.Repo
import de.ammar.qadam.qlog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The logger and rule engine (SPEC §4, §6–§8), running as a foreground service: a
 * background part that Android keeps alive because it shows a permanent notification.
 *
 * Every few seconds it reads the phone's state, hands a [Tick] to `core`'s [Engine], and
 * carries out what comes back: overlays, audio focus, grayscale, vibration, events. Every
 * 30 s (screen on or audio) or 5 min (otherwise) it writes one summary row.
 *
 * Everything runs on the main thread; database calls suspend and run elsewhere.
 */
class QadamService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    private lateinit var repo: Repo
    private lateinit var notes: Notifications
    private lateinit var phone: PhoneState
    private lateinit var fg: ForegroundApp
    private lateinit var audioCtl: AudioController
    private lateinit var gray: Grayscale
    private var sensors: SensorHub? = null
    private var engine: Engine? = null

    private var settings = Settings()
    private var thresholds = Thresholds.DEFAULT
    private var protectedApps: Set<String> = ProtectedApps.BUILT_IN
    private var categorizer = AppCategorizer(settings)
    private var planner = SamplingPlanner(Thresholds.DEFAULT)
    private val acc = RowAccumulator(Thresholds.DEFAULT)
    private val stepLog = StepLog()

    private var stepsSinceTick = 0
    private var stepsTotal = 0
    private var scrollsTotal = 0
    private var tapsTotal = 0
    private var lastRowMs = 0L
    private var lastBeatMs = 0L
    private var savedState = ""
    private var lastOutput: Output? = null
    private var loop: Job? = null
    private val queued = mutableListOf<Intent>()

    // Small per-day caches, so the database isn't read every tick.
    private var installSeed = 0L
    private var morningDay: LocalDate? = null
    private var retentionDay: LocalDate? = null
    private var sampledDay: LocalDate? = null
    private val sampled = mutableSetOf<Long>()

    /** True once settings and state are loaded. */
    var isReady = false
        private set

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            wake()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Bridge.service = this
        repo = (application as QadamApp).repo
        notes = Notifications(this)
        if (!startInForeground()) {
            stopSelf()
            return
        }
        phone = PhoneState(this)
        fg = ForegroundApp(this)
        audioCtl = AudioController(this)
        gray = Grayscale(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // Screen on/off are protected system broadcasts (no app can fake them). The docs
        // recommend RECEIVER_EXPORTED for system broadcasts; the receiver only wakes the loop.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        loop = scope.launch {
            init()
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    qlog("tick failed: $e")
                }
                withTimeoutOrNull(nextIntervalMs()) { wakeups.receive() }
            }
        }
    }

    private fun startInForeground(): Boolean = try {
        val n = notes.foreground()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifications.ID_FOREGROUND, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(Notifications.ID_FOREGROUND, n)
        }
        true
    } catch (e: Exception) {
        // E.g. the health type's prerequisite (Activity recognition) is missing.
        qlog("could not start in the foreground: $e")
        false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action != null) {
            if (isReady) handleIntent(intent) else queued += intent
        }
        return START_STICKY
    }

    private suspend fun init() {
        settings = repo.settings()
        thresholds = settings.thresholds()
        protectedApps = ProtectedApps.BUILT_IN + ProtectedRuntime.resolve(this)
        categorizer = AppCategorizer(settings, protectedApps)
        planner = SamplingPlanner(thresholds)
        installSeed = repo.installSeed()
        val eng = Engine(settings, zone, protectedApps, repo.engineState())
        engine = eng
        savedState = eng.state.encode()
        val hub = SensorHub(this, thresholds, acc) { wallMs, n -> onSteps(wallMs, n) }
        sensors = hub
        hub.startAlwaysOn()

        val now = System.currentTimeMillis()
        val events = mutableListOf(EventRow(now, EventType.SERVICE_START))
        Reports.gapEvent(repo.lastHeartbeat(), now)?.let { events += it }
        events += EventRow(now, EventType.SENSOR_CHECK, hub.report().joinToString("; ") { "${it.name}: ${it.note}" })
        repo.addEvents(events)
        repo.heartbeat(now)
        lastBeatMs = now
        lastRowMs = now
        morningDay = repo.getString(Repo.K_MORNING)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        retentionDay = repo.getString(Repo.K_RETENTION)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        updateForegroundText()
        isReady = true
        qlog("service ready; level ${eng.state.level}")
        queued.forEach { handleIntent(it) }
        queued.clear()
    }

    private fun nextIntervalMs(): Long {
        val out = lastOutput
        val screenOn = if (::phone.isInitialized) phone.screenOn() else false
        val audio = if (::phone.isInitialized) phone.audioPlaying() else false
        var ms = when {
            out != null && out.level >= 2 && screenOn -> 2_000L // blocks must appear quickly
            screenOn || audio -> 10_000L
            else -> 60_000L
        }
        if (Bridge.uiVisible) ms = minOf(ms, 2_000L) // live debug view
        return ms
    }

    fun wake() {
        wakeups.trySend(Unit)
    }

    private fun onSteps(wallMs: Long, n: Int) {
        stepLog.add(wallMs, n)
        acc.addSteps(n)
        stepsSinceTick += n
        stepsTotal += n
    }

    // ------------------------------------------------------------------ the tick

    private suspend fun tick() {
        val eng = engine ?: return
        val now = System.currentTimeMillis()
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val screenOn = phone.screenOn()
        val audio = phone.audioPlaying()
        val inCall = phone.inCall()
        val front = fg.poll(now)
        // Screen on but locked: nothing is "in front" (never cover the lock screen).
        val app = if (screenOn && !phone.locked()) front else null

        val hub = sensors
        if (hub != null) {
            if (RowTiming.sensorsWanted(screenOn, audio)) hub.start(screenOn) else hub.stop()
        }

        val sc = Bridge.scrolls.getAndSet(0)
        val tp = Bridge.taps.getAndSet(0)
        acc.addScrolls(sc); acc.addTaps(tp)
        scrollsTotal += sc; tapsTotal += tp
        val st = stepsSinceTick
        stepsSinceTick = 0

        // Loosenings whose 24 hours are over (SPEC §8).
        repo.matureSettings(now)?.let { (s, pauses) ->
            applySettings(s)
            for (until in pauses) repo.addEvents(eng.startLongPause(now, until))
        }

        val out = eng.tick(Tick(now, screenOn, app, sc, st, audio, inCall))
        lastOutput = out
        applyOutput(out)
        saveStateIfChanged()

        sampling(now, screenOn)
        morning(now, screenOn)

        if (now - lastRowMs >= RowTiming.intervalMs(thresholds, screenOn, audio)) {
            writeRow(now, nowNs, out, screenOn, app, audio)
        }
        if (now - lastBeatMs >= 60_000L) {
            repo.heartbeat(now)
            lastBeatMs = now
        }
        retention(now)
        publishLive(now, out, screenOn, app, audio, inCall)
    }

    private suspend fun writeRow(now: Long, nowNs: Long, out: Output, screenOn: Boolean, app: String?, audio: Boolean) {
        val ctx = RowContext(
            timeMs = now,
            screenOn = screenOn,
            app = app,
            appCategory = categorizer.of(app),
            sessionS = if (app != null) ((now - fg.sinceMs) / 1000).toInt().coerceAtLeast(0) else 0,
            stepsLast5min = stepLog.inLast(5 * 60_000L, now),
            audio = audio,
            headphones = phone.headphones(),
            charging = phone.charging(),
            choreMode = out.chore,
            window = out.window,
            state = out.state,
            level = out.level,
        )
        repo.addRow(acc.flush(nowNs, ctx))
        lastRowMs = now
    }

    /** Carries out the engine's output. Screen effects first, database last. */
    private suspend fun applyOutput(out: Output) {
        Bridge.a11y?.showOverlay(out.overlay)
        audioCtl.set(out.audio)
        gray.set(out.grayscale)
        for (s in out.oneShots) {
            when (s) {
                OneShot.Vibrate -> vibrateOnce(this)
                is OneShot.NudgeNotification -> notes.showNudge(s.plan, s.canMore)
                OneShot.StopAudio -> audioCtl.stopOthers()
                OneShot.GoHome -> Bridge.a11y?.goHome()
            }
        }
        if (out.level != 1) notes.cancelNudge()
        repo.addEvents(out.events)
    }

    private suspend fun saveStateIfChanged() {
        val enc = engine?.state?.encode() ?: return
        if (enc != savedState) {
            repo.saveEngineState(enc)
            savedState = enc
        }
    }

    // ------------------------------------------------------------------ actions

    /** A button on an overlay or notification, or a tag. Returns the engine's answer. */
    fun onAction(a: Action): ActionResult? {
        val eng = engine ?: return null
        val r = eng.act(a, System.currentTimeMillis())
        lastOutput = r.output
        scope.launch {
            applyOutput(r.output)
            saveStateIfChanged()
        }
        if (a is Action.TagTapped) Bridge.lastTag.value = a.name to System.currentTimeMillis()
        wake()
        return r
    }

    /** "Pause 1 hour" etc. from the screens. */
    fun canMore(): Boolean = engine?.canMore(System.currentTimeMillis()) ?: false
    fun pausesLeft(): Int = engine?.pausesLeft() ?: 0
    fun overridesLeft(): Int = engine?.overridesLeft() ?: 0

    /** Re-shows the current overlay (after the accessibility service reconnects). */
    fun refreshOverlay() {
        lastOutput?.let { Bridge.a11y?.showOverlay(it.overlay) }
    }

    fun handleIntent(intent: Intent) {
        when (intent.action) {
            ActionReceiver.ACTION_LABEL -> {
                val label = intent.getStringExtra(ActionReceiver.EXTRA_LABEL)
                    ?.let { runCatching { Label.valueOf(it) }.getOrNull() } ?: return
                onLabel(label, intent.getLongExtra(ActionReceiver.EXTRA_PLANNED, 0L))
            }
            ActionReceiver.ACTION_LADDER -> {
                when (intent.getStringExtra(ActionReceiver.EXTRA_WHICH)) {
                    ActionReceiver.WHICH_UP -> onAction(Action.GettingUp)
                    ActionReceiver.WHICH_MORE -> onAction(Action.MoreMinutes)
                    ActionReceiver.WHICH_WRONG -> onAction(Action.Wrong)
                }
                notes.cancelNudge()
            }
            ACTION_TAG -> intent.getStringExtra(EXTRA_TAG)?.let { onAction(Action.TagTapped(it)) }
        }
    }

    private fun onLabel(label: Label, plannedMs: Long) {
        acc.setLabel(label)
        notes.cancelSampling()
        val now = System.currentTimeMillis()
        scope.launch { repo.addEvents(listOf(EventRow(now, EventType.LABEL, "${label.csv} planned=$plannedMs"))) }
        qlog("label ${label.csv}")
    }

    /** Settings changed on the Rules screen (immediately applied ones). */
    fun reloadSettings() {
        scope.launch { applySettings(repo.settings()) }
    }

    private fun applySettings(s: Settings) {
        settings = s
        thresholds = s.thresholds()
        categorizer = AppCategorizer(s, protectedApps)
        engine?.updateSettings(s)
        updateForegroundText()
        wake()
    }

    private fun updateForegroundText() {
        notes.updateForeground(
            when (settings.maxLevel) {
                0 -> "Logging. No interventions until you switch them on."
                1 -> "Logging. Level 1 nudges on."
                else -> "Logging. Full ladder on."
            },
        )
    }

    // ------------------------------------------------------------------ sampling, morning, retention

    private suspend fun sampling(now: Long, screenOn: Boolean) {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (sampledDay != today) {
            sampled.clear()
            val stored = repo.getString(Repo.K_SAMPLED)
            if (stored != null && stored.startsWith("$today|")) {
                stored.substringAfter("|").split(",").mapNotNullTo(sampled) { it.toLongOrNull() }
            }
            sampledDay = today
        }
        val plan: List<LocalDateTime> = planner.plan(today, settings.bedtime, installSeed)
        var changed = false
        for (dropped in planner.expired(now, plan, sampled, zone)) {
            sampled += dropped; changed = true
        }
        planner.due(now, screenOn, plan, sampled, zone)?.let { planned ->
            notes.showSampling(planned)
            sampled += planned; changed = true
            repo.addEvents(listOf(EventRow(now, EventType.SAMPLING_PROMPT, "planned=$planned")))
        }
        if (changed) repo.putString(Repo.K_SAMPLED, "$today|" + sampled.joinToString(","))
    }

    private suspend fun morning(now: Long, screenOn: Boolean) {
        if (!Reports.morningDue(now, zone, settings.nightEnd, morningDay, screenOn)) return
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        morningDay = today
        repo.putString(Repo.K_MORNING, today.toString())
        if (repo.summaryCount() == 0) return // nothing logged yet
        notes.showMorning(Reports.morningText(repo.totals(today.minusDays(1), zone)))
    }

    private suspend fun retention(now: Long) {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (retentionDay == today) return
        retentionDay = today
        repo.putString(Repo.K_RETENTION, today.toString())
        repo.retention(now, zone)
    }

    // ------------------------------------------------------------------ debug view

    private fun publishLive(now: Long, out: Output, screenOn: Boolean, app: String?, audio: Boolean, inCall: Boolean) {
        val hub = sensors
        Bridge.live.value = LiveSignals(
            timeMs = now,
            serviceRunning = true,
            accessibilityOn = Bridge.a11y != null,
            usageAccess = fg.hasAccess(),
            screenOn = screenOn,
            app = app,
            appCategory = categorizer.of(app),
            sessionS = if (app != null) ((now - fg.sinceMs) / 1000).toInt() else 0,
            audio = audio,
            headphones = phone.headphones(),
            charging = phone.charging(),
            inCall = inCall,
            sensorsRunning = hub?.running == true,
            stepsTotal = stepsTotal,
            stepsLast5min = stepLog.inLast(5 * 60_000L, now),
            stepSource = if (hub?.hardwareSteps == true) "step sensor" else "accelerometer",
            motionRms = hub?.lastMotion,
            gravity = hub?.lastGravity,
            proximityNear = hub?.lastNear,
            lux = hub?.lastLux,
            scrollsTotal = scrollsTotal,
            tapsTotal = tapsTotal,
            state = out.state,
            window = out.window,
            level = out.level,
            chore = out.chore,
            sensors = hub?.report().orEmpty(),
        )
    }

    override fun onDestroy() {
        qlog("service destroyed")
        isReady = false
        if (Bridge.service === this) Bridge.service = null
        runCatching { unregisterReceiver(screenReceiver) }
        sensors?.shutdown()
        Bridge.a11y?.showOverlay(Overlay.None)
        if (::audioCtl.isInitialized) audioCtl.set(de.ammar.qadam.core.engine.AudioControl.FREE)
        if (::gray.isInitialized) gray.set(false)
        Bridge.live.value = Bridge.live.value.copy(serviceRunning = false)
        val app = application as QadamApp
        val now = System.currentTimeMillis()
        app.appScope.launch { app.repo.addEvents(listOf(EventRow(now, EventType.SERVICE_STOP))) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_TAG = "de.ammar.qadam.TAG"
        const val EXTRA_TAG = "tag"

        /** Android 14+: the health service type needs Activity recognition granted. */
        fun canRun(context: Context): Boolean =
            Build.VERSION.SDK_INT < 34 ||
                context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        /** Starts the service (or hands it [intent] if it runs already). */
        fun start(context: Context, intent: Intent? = null) {
            if (!canRun(context)) {
                qlog("not starting: Activity recognition not granted")
                return
            }
            val i = (intent ?: Intent()).setClass(context, QadamService::class.java)
            try {
                context.startForegroundService(i)
            } catch (e: Exception) {
                qlog("start failed: $e")
            }
        }
    }
}
