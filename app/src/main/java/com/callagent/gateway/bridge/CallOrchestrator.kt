package com.callagent.gateway.bridge

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.telecom.Call
import android.util.Log
import com.callagent.gateway.RootShell
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.rtp.RtpPacket
import com.callagent.gateway.rtp.RtpSession
import com.callagent.gateway.sip.SipCall
import com.callagent.gateway.sip.SipClient
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Orchestrates the bidirectional GSM ↔ SIP bridge.
 *
 * Two call flows:
 *
 * INBOUND (someone calls the Israeli SIM):
 *   1. GSM rings → keep ringing, place SIP call to Asterisk
 *   2. Asterisk/agent answers SIP → answer GSM call
 *   3. GSM goes active → RTP starts immediately
 *   4. Audio flows: GSM speaker/mic ↔ RTP/SIP (shared hardware)
 *   5. Either side hangs up → terminate both
 *
 *   Caller hears normal ringing until the agent is ready, then
 *   picks up and hears the agent immediately — no dead air.
 *
 * OUTBOUND (Asterisk wants to call an Israeli number):
 *   1. SIP INVITE arrives with X-GSM-Forward header
 *   2. Dial GSM call to the destination
 *   3. GSM answers → SIP 200 OK
 *   4. Audio flows: SIP RTP ↔ GSM speaker/mic (shared hardware)
 *   5. Either side hangs up → terminate both
 */
class CallOrchestrator(
    private val context: Context,
    private val sipClient: SipClient
) : SipClient.Listener, GsmCallManager.Listener, SipCall.Listener {

    private var activeRtpSession: RtpSession? = null
    private var activeSipCall: SipCall? = null
    private var activeGsmCall: Call? = null
    @Volatile private var gsmActiveHandled = false
    @Volatile private var diallerInitiated = false
    @Volatile private var lastStateChangeTime = 0L
    @Volatile private var lastGsmActiveElapsed = 0L
    @Volatile private var outboundRtpPreparationStarted = false
    @Volatile private var recordAudioAuthorizedElapsed = 0L
    @Volatile private var recordAudioAuthorizationInProgress = false
    private val recordAudioAuthorizationLock = Object()
    private var pendingOutboundLocalRtpPort = 0
    private var activeRtpEndpointKey: String? = null
    private val callAttempts = CallAttemptTracker()

    // Pending RTP info: saved when SIP answers before GSM is picked up.
    // onGsmCallActive reads these to start RTP immediately after GSM pickup.
    private var pendingRtpAddr: String? = null
    private var pendingRtpPort: Int = 0
    private var pendingPayloadType: Int = 0
    private var pendingTelephoneEventPayloadType: Int? = null
    private var pendingLocalRtpPort: Int = 0

    // SIP call retry: if SIP fails while GSM is ringing, retry before giving up.
    // Transient network issues or socket races can kill the first attempt.
    private var sipCallRetries = 0
    private val MAX_SIP_RETRIES = 2

    /** Current bridge state */
    @Volatile var bridgeState: BridgeState = BridgeState.IDLE
        private set

    @Volatile var listener: OrchestratorListener? = null

    interface OrchestratorListener {
        fun onStateChanged(state: BridgeState, info: String)
        fun onError(error: String)
        fun onRtpStats(stats: String) {}
    }

    enum class BridgeState {
        IDLE,
        GSM_RINGING,        // Incoming GSM, waiting to answer
        GSM_ANSWERED,        // GSM answered, placing SIP call
        SIP_CALLING,         // SIP INVITE sent, waiting for answer
        SIP_RINGING,         // SIP ringing at Asterisk
        BRIDGED,             // Both sides active, audio flowing
        GSM_DIALING,         // Outbound: dialing GSM number
        TEARING_DOWN         // Hanging up
    }

    fun start() {
        sipClient.listener = this
        GsmCallManager.listener = this
        Log.i(TAG, "CallOrchestrator started")
    }

    fun stop() {
        tearDown("Orchestrator stopped")
        sipClient.listener = null
        GsmCallManager.listener = null
    }

    /** Initiate an outgoing GSM call from the dialler, then bridge to SIP */
    fun initiateDiallerCall(number: String) {
        if (bridgeState != BridgeState.IDLE) {
            // Check for stale state: if bridge has been non-IDLE for too long
            // without reaching BRIDGED, force a reset.  This happens on cold boot
            // when InCallService isn't bound yet and call events never arrive.
            val staleMs = System.currentTimeMillis() - lastStateChangeTime
            if (staleMs > STALE_STATE_TIMEOUT_MS) {
                Log.w(TAG, "Bridge stuck in $bridgeState for ${staleMs/1000}s — force resetting")
                forceReset("Stale state: $bridgeState for ${staleMs/1000}s")
            } else {
                Log.w(TAG, "Busy ($bridgeState) — cannot dial from dialler")
                listener?.onError("Busy — cannot dial")
                return
            }
        }
        Log.i(TAG, "Dialler-initiated call to $number")
        val attemptToken = callAttempts.begin()
        diallerInitiated = true
        gsmActiveHandled = false
        lastStateChangeTime = System.currentTimeMillis()
        bridgeState = BridgeState.GSM_DIALING
        listener?.onStateChanged(bridgeState, "Dialing $number")
        beginRecordAudioAuthorization()
        if (!GsmCallManager.makeCall(context, number)) {
            tearDown("GSM dial unavailable")
            return
        }

        startGsmDialTimeout(attemptToken)
    }

    // ── SipClient.Listener ──────────────────────────────

    override fun onRegistered() {
        Log.i(TAG, "SIP registered — ready for calls")
        listener?.onStateChanged(BridgeState.IDLE, "SIP registered")
    }

    override fun onRegistrationFailed() {
        Log.e(TAG, "SIP registration failed")
        listener?.onError("SIP registration failed")
    }

    /** Incoming SIP INVITE from Asterisk */
    override fun onIncomingCall(call: SipCall) {
        Log.i(TAG, "Incoming SIP call: ${call.callId}, gsm_forward=${call.gsmForwardNumber}")

        if (bridgeState != BridgeState.IDLE) {
            Log.w(TAG, "Busy — rejecting SIP call")
            call.hangup()
            return
        }

        val gsmDest = call.gsmForwardNumber?.let(::normalizeGsmNumber)
        if (gsmDest != null) {
            if (gsmDest.isEmpty()) {
                Log.e(TAG, "Invalid X-GSM-Forward value: '${call.gsmForwardNumber}'")
                call.hangup()
                return
            }
            Log.i(TAG, "Recognized GSM outbound target: $gsmDest")
            // OUTBOUND flow: Asterisk wants us to dial a GSM number
            handleOutboundFlow(call, gsmDest)
        } else {
            // Unexpected SIP call without forward header — answer anyway
            Log.w(TAG, "SIP INVITE without X-GSM-Forward header, answering directly")
            val rtpPort = allocateRtpPort()
            call.listener = this
            call.accept(rtpPort)
            activeSipCall = call
        }
    }

    /** Handles termination from both SipClient.Listener and SipCall.Listener */
    override fun onCallTerminated(call: SipCall) {
        Log.i(TAG, "SIP call terminated: ${call.callId} (bridge=$bridgeState, retries=$sipCallRetries)")
        if (call != activeSipCall) return

        // If GSM is still ringing and we haven't exhausted retries, try again.
        // Transient network issues or socket races can kill the first SIP attempt.
        if ((bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING)
            && sipCallRetries < MAX_SIP_RETRIES && activeGsmCall != null) {
            sipCallRetries++
            Log.w(TAG, "SIP call failed while GSM ringing — retrying ($sipCallRetries/$MAX_SIP_RETRIES)")
            listener?.onStateChanged(bridgeState, "SIP retry $sipCallRetries/$MAX_SIP_RETRIES")
            activeSipCall = null
            sipClient.removeCall(call.callId)
            // Retry after a short delay to let any transient issue settle
            val attemptToken = callAttempts.current()
            Thread({
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@Thread }
                if (!callAttempts.isCurrent(attemptToken)) return@Thread
                if (bridgeState != BridgeState.SIP_CALLING && bridgeState != BridgeState.SIP_RINGING) return@Thread
                activeGsmCall?.let { handleInboundFlow(it, attemptToken) }
                    ?: Log.e(TAG, "SIP retry: GSM call gone, aborting")
            }, "SIP-Retry-$sipCallRetries").start()
            return
        }

        tearDown("SIP call ended")
    }

    // ── GsmCallManager.Listener ─────────────────────────

    /** Incoming GSM call — this is the INBOUND flow trigger */
    override fun onIncomingGsmCall(call: Call, number: String) {
        Log.i(TAG, "Incoming GSM call from $number")

        if (activeGsmCall === call && bridgeState == BridgeState.GSM_RINGING) {
            Log.d(TAG, "Ignoring duplicate GSM ringing callback")
            return
        }

        if (bridgeState != BridgeState.IDLE) {
            Log.w(TAG, "Busy — rejecting GSM call")
            GsmCallManager.rejectCall(call)
            return
        }

        sipCallRetries = 0
        val attemptToken = callAttempts.begin()
        gsmActiveHandled = false
        beginRecordAudioAuthorization()
        bridgeState = BridgeState.GSM_RINGING
        activeGsmCall = call
        listener?.onStateChanged(bridgeState, "GSM call from $number")

        // Don't answer GSM yet — place SIP call to Asterisk first.
        // When the agent answers on SIP, we'll answer GSM so the caller
        // hears the agent immediately with no dead air.
        // The caller hears normal ringing in the meantime.
        Log.i(TAG, "GSM ringing from $number — placing SIP call first")
        Thread({ handleInboundFlow(call, attemptToken) }, "SIP-OutCall").start()
    }

    /** GSM call is now active (answered) */
    override fun onGsmCallActive(call: Call) {
        Log.i(TAG, "GSM call active")
        if (gsmActiveHandled && activeGsmCall === call) {
            Log.d(TAG, "Ignoring duplicate GSM active callback")
            return
        }
        gsmActiveHandled = true
        activeGsmCall = call
        lastGsmActiveElapsed = SystemClock.elapsedRealtime()

        when (bridgeState) {
            BridgeState.SIP_CALLING, BridgeState.SIP_RINGING -> {
                // INBOUND flow: GSM answered (triggered from onRtpReady).
                // SIP agent is ready — start RTP immediately so caller
                // hears the agent from the first moment.
                val addr = pendingRtpAddr
                val port = pendingRtpPort
                val pt = pendingPayloadType
                val telephoneEventPt = pendingTelephoneEventPayloadType
                val localPort = pendingLocalRtpPort
                pendingRtpAddr = null

                if (addr != null && port > 0) {
                    Thread({
                        if (!startRtp(
                                localPort,
                                addr,
                                port,
                                pt,
                                telephoneEventPt,
                                lastGsmActiveElapsed,
                            )) {
                            tearDown("RTP setup failed")
                            return@Thread
                        }
                        // Guard: tearDown may have run while startRtp was blocking
                        // (AudioRecord retries take 30+ seconds on cold boot).
                        // Don't overwrite IDLE — that causes "Busy" on next call.
                        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) {
                            Log.w(TAG, "Bridge torn down during RTP setup — not transitioning to BRIDGED")
                            return@Thread
                        }
                        activeRtpSession?.startPostConnectDiagnostics()
                        bridgeState = BridgeState.BRIDGED
                        listener?.onStateChanged(bridgeState, "Bridged (inbound)")
                        Log.i(TAG, "Inbound bridge established — zero dead air")
                    }, "RTP-Start").start()
                } else {
                    // Edge case: GSM answered but SIP RTP info not ready yet.
                    // This shouldn't happen in normal flow since we answer GSM
                    // from onRtpReady, but handle gracefully.
                    Log.w(TAG, "GSM active but no pending RTP info — waiting for SIP")
                    bridgeState = BridgeState.GSM_ANSWERED
                }
            }
            BridgeState.GSM_DIALING -> {
                if (diallerInitiated) {
                    // DIALLER flow: GSM active → place SIP call to Asterisk (like inbound)
                    diallerInitiated = false
                    bridgeState = BridgeState.GSM_ANSWERED
                    listener?.onStateChanged(bridgeState, "GSM answered, calling Asterisk")
                    val attemptToken = callAttempts.current()
                    Thread({ handleInboundFlow(call, attemptToken) }, "SIP-OutCall").start()
                } else {
                    // SIP-initiated OUTBOUND flow: GSM destination answered.
                    // Prepare media before sending SIP 200 OK so the remote
                    // party never enters an answered call with no RTP source.
                    Thread({
                        val sipCall = activeSipCall
                        if (sipCall == null) {
                            Log.e(TAG, "GSM answered but the SIP call is no longer active")
                            tearDown("SIP call disappeared")
                            return@Thread
                        }
                        val rtpPort = pendingOutboundLocalRtpPort.takeIf { it > 0 }
                            ?: allocateRtpPort()
                        sipCall.listener = this
                        val addr = sipCall.remoteRtpAddress ?: sipClient.serverDomain
                        val port = sipCall.remoteRtpPort
                        val pt = sipCall.negotiatedPayloadType

                        if (port <= 0) {
                            Log.e(TAG, "Cannot answer outbound SIP call: missing remote RTP port")
                            tearDown("Missing SIP RTP endpoint")
                            return@Thread
                        }

                        Log.i(TAG, "GSM answered — preparing RTP before SIP 200 OK")
                        if (!startRtp(
                                rtpPort,
                                addr,
                                port,
                                pt,
                                sipCall.negotiatedTelephoneEventPayloadType,
                                lastGsmActiveElapsed,
                            )) {
                            tearDown("RTP setup failed")
                            return@Thread
                        }
                        if (activeSipCall !== sipCall ||
                            bridgeState == BridgeState.IDLE ||
                            bridgeState == BridgeState.TEARING_DOWN) {
                            Log.w(TAG, "Call ended while RTP was starting — skipping SIP answer")
                            return@Thread
                        }

                        sipCall.accept(rtpPort, mediaReady = true)
                        activeRtpSession?.startPostConnectDiagnostics()
                        bridgeState = BridgeState.BRIDGED
                        listener?.onStateChanged(bridgeState, "Bridged (outbound)")
                        val activeToAnswerMs = SystemClock.elapsedRealtime() - lastGsmActiveElapsed
                        Log.i(
                            TAG,
                            "Outbound bridge established with media ready " +
                                "(GSM_ACTIVE->SIP_200=${activeToAnswerMs}ms)"
                        )
                    }, "SIP-Bridge").start()
                }
            }
            else -> {}
        }
    }

    override fun onGsmCallStateChanged(call: Call, state: Int) {
        val stateStr = when (state) {
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            else -> "OTHER($state)"
        }
        Log.d(TAG, "GSM state: $stateStr")

        // Track the GSM call object as soon as we see it, so teardown works
        // even if the call never reaches ACTIVE (e.g. wrong number, rejected)
        if (activeGsmCall == null && bridgeState != BridgeState.IDLE) {
            activeGsmCall = call
        }

        if (state == Call.STATE_DISCONNECTED && bridgeState != BridgeState.IDLE) {
            tearDown("GSM call disconnected")
        }
        if ((state == Call.STATE_DIALING || state == Call.STATE_CONNECTING)
            && bridgeState == BridgeState.GSM_DIALING && !diallerInitiated) {
            startOutboundRtpPreparationIfNeeded(callAttempts.current())
        }
    }

    override fun onGsmCallEnded(call: Call) {
        Log.i(TAG, "GSM call ended")
        // Tear down if this is our tracked call, OR if we're in a call state
        // but activeGsmCall was never set (call failed before going ACTIVE)
        if (call == activeGsmCall ||
            (activeGsmCall == null && bridgeState != BridgeState.IDLE)) {
            tearDown("GSM call ended")
        }
    }

    // ── SipCall.Listener ────────────────────────────────

    override fun onCallAnswered(call: SipCall) {
        Log.i(TAG, "SIP call answered: ${call.callId}")
    }

    // onCallTerminated is already implemented above (shared by SipClient.Listener and SipCall.Listener)

    override fun onRtpReady(call: SipCall, remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int) {
        val codecName = when (payloadType) {
            RtpPacket.PT_G722 -> "G.722"
            RtpPacket.PT_PCMA -> "PCMA"
            RtpPacket.PT_PCMU -> "PCMU"
            else -> "PT$payloadType"
        }
        Log.i(TAG, "RTP ready: $remoteRtpAddr:$remoteRtpPort codec=$codecName bridgeState=$bridgeState")

        if (bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING) {
            // Check if GSM is already active (dialler-initiated calls).
            // For inbound calls GSM is still ringing — answer it and wait for
            // onGsmCallActive to start RTP.  For dialler calls GSM is already
            // active so onGsmCallActive won't fire again — start RTP now.
            val gsmAlreadyActive = GsmCallManager.isCallActive

            if (gsmAlreadyActive) {
                Log.i(TAG, "SIP answered (codec=$codecName) — GSM already active, starting RTP now")
                val localRtpPort = call.localRtpPort
                Thread({
                    if (!startRtp(
                            localRtpPort,
                            remoteRtpAddr,
                            remoteRtpPort,
                            payloadType,
                            call.negotiatedTelephoneEventPayloadType,
                            lastGsmActiveElapsed
                        )) {
                        tearDown("RTP setup failed")
                        return@Thread
                    }
                    if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) {
                        Log.w(TAG, "Bridge torn down during RTP setup — not transitioning to BRIDGED")
                        return@Thread
                    }
                    activeRtpSession?.startPostConnectDiagnostics()
                    bridgeState = BridgeState.BRIDGED
                    listener?.onStateChanged(bridgeState, "Bridged (dialler)")
                    Log.i(TAG, "Dialler bridge established (codec=$codecName)")
                }, "RTP-Start").start()
            } else {
                // INBOUND flow: SIP/agent answered — save RTP info and answer GSM.
                // When GSM goes active (onGsmCallActive), RTP starts immediately
                // so the caller hears the agent from the first moment.
                pendingRtpAddr = remoteRtpAddr
                pendingRtpPort = remoteRtpPort
                pendingPayloadType = payloadType
                pendingTelephoneEventPayloadType = call.negotiatedTelephoneEventPayloadType
                pendingLocalRtpPort = call.localRtpPort

                Log.i(TAG, "SIP answered (codec=$codecName) — answering GSM call now")
                activeGsmCall?.let { GsmCallManager.answerCall(it) }
                    ?: Log.e(TAG, "SIP answered but no active GSM call to answer!")
            }
        } else if (bridgeState == BridgeState.GSM_ANSWERED) {
            // Edge case: GSM was already answered (e.g. user picked up manually)
            // before SIP was ready.  Start RTP now.
            val localRtpPort = call.localRtpPort
            if (!startRtp(
                    localRtpPort,
                    remoteRtpAddr,
                    remoteRtpPort,
                    payloadType,
                    call.negotiatedTelephoneEventPayloadType,
                    lastGsmActiveElapsed
                )) {
                tearDown("RTP setup failed")
                return
            }
            activeRtpSession?.startPostConnectDiagnostics()
            bridgeState = BridgeState.BRIDGED
            listener?.onStateChanged(bridgeState, "Bridged (inbound)")
            Log.i(TAG, "Bridge established (codec=$codecName)")
        } else {
            Log.w(TAG, "onRtpReady ignored — bridgeState=$bridgeState (expected SIP_CALLING or SIP_RINGING)")
            listener?.onError("RTP ready but bridge state wrong: $bridgeState")
            Log.i(TAG, "Inbound bridge established — GSM was already active (codec=$codecName)")
        }
    }

    // ── Inbound flow (GSM → SIP) ───────────────────────

    private fun handleInboundFlow(gsmCall: Call, attemptToken: Long) {
        if (!callAttempts.isCurrent(attemptToken)) {
            Log.i(TAG, "Ignoring stale inbound flow for attempt=$attemptToken")
            return
        }
        val callerNumber = gsmCall.details?.handle?.schemeSpecificPart ?: "unknown"
        Log.i(TAG, "Inbound flow: placing SIP call for GSM caller $callerNumber")

        bridgeState = BridgeState.SIP_CALLING
        listener?.onStateChanged(bridgeState, "Calling Asterisk for $callerNumber")

        val rtpPort = allocateRtpPort()
        val sipCall = sipClient.makeCall(
            targetExtension = sipClient.username, // call our own extension — Asterisk routes to agent
            localRtpPort = rtpPort,
            callerIdNumber = callerNumber,
            callerIdName = callerNumber
        )
        sipCall.listener = this
        activeSipCall = sipCall

        Log.i(TAG, "SIP INVITE sent to Asterisk (caller=$callerNumber, rtp=$rtpPort)")

        // Timeout: if Asterisk doesn't answer within 30s, tear down
        Thread({
            Thread.sleep(SIP_CALL_TIMEOUT_MS)
            if (callAttempts.isCurrent(attemptToken) && activeSipCall === sipCall &&
                (bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING)) {
                Log.w(TAG, "SIP call timeout — Asterisk didn't answer in ${SIP_CALL_TIMEOUT_MS / 1000}s")
                tearDown("Asterisk not answering")
            } else {
                Log.d(TAG, "Ignoring stale SIP timeout for attempt=$attemptToken")
            }
        }, "SIP-Timeout").start()
    }

    // ── Outbound flow (SIP → GSM) ──────────────────────

    private fun handleOutboundFlow(sipCall: SipCall, gsmDestination: String) {
        Log.i(TAG, "Outbound flow: dialing GSM $gsmDestination")

        val attemptToken = callAttempts.begin()
        bridgeState = BridgeState.GSM_DIALING
        activeSipCall = sipCall
        gsmActiveHandled = false
        outboundRtpPreparationStarted = false
        pendingOutboundLocalRtpPort = allocateRtpPort()
        listener?.onStateChanged(bridgeState, "Dialing $gsmDestination")
        beginRecordAudioAuthorization()

        // Send 180 Ringing to SIP caller while GSM dials
        sipCall.originalInvite?.let { invite ->
            val ringing = com.callagent.gateway.sip.SipBuilder.ringing180(invite, sipCall.localTag)
            sipClient.sendTo(ringing, sipCall.remoteContactAddress ?: sipClient.serverAddress)
        }

        // Dial via GSM SIM.  Surface failures immediately; otherwise the SIP
        // leg remains ringing while the bridge is stuck in GSM_DIALING.
        try {
            if (!GsmCallManager.makeCall(context, gsmDestination)) {
                tearDown("GSM dial unavailable")
                return
            }
            Log.i(TAG, "GSM ACTION_CALL started for $gsmDestination")
            // The INVITE already contains the RTP endpoint. Start expensive
            // media setup while GSM is dialing. A short delay lets Telecom
            // create its call audio use case first; a DIALING callback can
            // still start the idempotent prewarm earlier.
            Thread({
                try { Thread.sleep(OUTBOUND_RTP_PREWARM_DELAY_MS) }
                catch (_: InterruptedException) { return@Thread }
                startOutboundRtpPreparationIfNeeded(attemptToken)
            }, "RTP-Prewarm-Schedule").start()
        } catch (e: Exception) {
            Log.e(TAG, "GSM ACTION_CALL failed for $gsmDestination: ${e.message}", e)
            tearDown("GSM dial failed")
        }

        // Do not leave Asterisk ringing forever when Android never delivers
        // InCallService callbacks (missing default-dialer role, permission,
        // or a modem failure).
        startGsmDialTimeout(attemptToken, sipCall)
    }

    // ── RTP ─────────────────────────────────────────────

    private fun startOutboundRtpPreparationIfNeeded(attemptToken: Long) {
        if (!callAttempts.isCurrent(attemptToken)) return
        val sipCall: SipCall
        val localPort: Int
        synchronized(this) {
            if (outboundRtpPreparationStarted) return
            sipCall = activeSipCall ?: return
            localPort = pendingOutboundLocalRtpPort.takeIf { it > 0 } ?: return
            if (sipCall.remoteRtpPort <= 0 || sipCall.negotiatedPayloadType < 0) return
            outboundRtpPreparationStarted = true
        }

        val remoteAddr = sipCall.remoteRtpAddress ?: sipClient.serverDomain
        Log.i(
            TAG,
            "Prewarming outbound RTP while GSM is dialing: " +
                "$localPort -> $remoteAddr:${sipCall.remoteRtpPort}"
        )
        Thread({
            if (!callAttempts.isCurrent(attemptToken) || activeSipCall !== sipCall) return@Thread
            val started = startRtp(
                    localPort,
                    remoteAddr,
                    sipCall.remoteRtpPort,
                    sipCall.negotiatedPayloadType,
                    sipCall.negotiatedTelephoneEventPayloadType,
                    captureAfterElapsed = null
                )
            if (started && callAttempts.isCurrent(attemptToken) &&
                activeSipCall === sipCall && bridgeState == BridgeState.GSM_DIALING) {
                sipCall.sendEarlyMedia(localPort)
            } else if (!started && callAttempts.isCurrent(attemptToken) &&
                activeSipCall === sipCall && bridgeState == BridgeState.GSM_DIALING) {
                tearDown("RTP prewarm failed")
            }
        }, "RTP-Prewarm").start()
    }

    @Synchronized
    private fun startRtp(
        localPort: Int,
        remoteAddr: String,
        remotePort: Int,
        payloadType: Int = RtpPacket.PT_PCMA,
        telephoneEventPayloadType: Int? = null,
        captureAfterElapsed: Long? = null
    ): Boolean {
        val endpointKey = "$localPort|$remoteAddr|$remotePort|$payloadType|$telephoneEventPayloadType"
        val existing = activeRtpSession
        if (existing != null && activeRtpEndpointKey == endpointKey) {
            Log.i(TAG, "Reusing prewarmed RTP session for $remoteAddr:$remotePort")
            if (captureAfterElapsed != null && captureAfterElapsed > 0L) {
                if (!existing.awaitGsmToSipReady(captureAfterElapsed, MEDIA_READY_TIMEOUT_MS)) {
                    existing.stop()
                    if (activeRtpSession === existing) {
                        activeRtpSession = null
                        activeRtpEndpointKey = null
                    }
                    return false
                }
                existing.markSignalingConnected()
            }
            return true
        }

        activeRtpSession?.stop()
        activeRtpSession = null
        activeRtpEndpointKey = null
        val propagationDelay = prepareRecordAudioForRtp()
        val session = RtpSession(
            context,
            localPort,
            remoteAddr,
            remotePort,
            payloadType,
            telephoneEventPayloadType,
            appOpsPropagationDelayMs = propagationDelay
        )
        session.listener = object : RtpSession.Listener {
            override fun onRtpStarted() {
                Log.i(TAG, "RTP session started")
            }
            override fun onRtpStopped() {
                Log.i(TAG, "RTP session stopped")
            }
            override fun onRtpError(error: String) {
                Log.e(TAG, "RTP error: $error")
                listener?.onError("RTP: $error")
            }
            override fun onRtpTimeout() {
                Log.w(TAG, "RTP timeout — no audio from Asterisk, tearing down")
                tearDown("RTP timeout")
            }
            override fun onRtpStats(stats: String) {
                listener?.onRtpStats(stats)
            }
        }
        activeRtpSession = session
        activeRtpEndpointKey = endpointKey
        if (!session.start()) {
            if (activeRtpSession === session) {
                activeRtpSession = null
                activeRtpEndpointKey = null
            }
            return false
        }
        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) {
            session.stop()
            if (activeRtpSession === session) {
                activeRtpSession = null
                activeRtpEndpointKey = null
            }
            return false
        }
        if (captureAfterElapsed != null && captureAfterElapsed > 0L) {
            if (!session.awaitGsmToSipReady(captureAfterElapsed, MEDIA_READY_TIMEOUT_MS)) {
                session.stop()
                if (activeRtpSession === session) {
                    activeRtpSession = null
                    activeRtpEndpointKey = null
                }
                return false
            }
            session.markSignalingConnected()
        }
        return true
    }

    // ── Teardown ────────────────────────────────────────

    @Synchronized
    private fun tearDown(reason: String) {
        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) return
        callAttempts.invalidate()
        bridgeState = BridgeState.TEARING_DOWN
        diallerInitiated = false
        gsmActiveHandled = false
        Log.i(TAG, "Tearing down bridge: $reason")

        try {
            activeRtpSession?.stop()
            activeRtpSession = null
            activeRtpEndpointKey = null

            activeSipCall?.let {
                try {
                    if (it.state != SipCall.State.TERMINATED) it.hangup()
                } catch (e: Exception) {
                    Log.e(TAG, "Error hanging up SIP: ${e.message}")
                }
                sipClient.removeCall(it.callId)
            }
            activeSipCall = null

            activeGsmCall?.let { call ->
                try {
                    // Always disconnect — not just when ACTIVE.  If the SIP
                    // call fails before GSM is answered, the ringing GSM call
                    // was left dangling (S4 Mini: "second call never answered").
                    // Call.disconnect() works for RINGING, DIALING, and ACTIVE.
                    call.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting GSM: ${e.message}")
                }
            }
            activeGsmCall = null
            pendingRtpAddr = null
            pendingRtpPort = 0
            pendingPayloadType = 0
            pendingTelephoneEventPayloadType = null
            pendingLocalRtpPort = 0
            pendingOutboundLocalRtpPort = 0
            outboundRtpPreparationStarted = false
            lastGsmActiveElapsed = 0L
        } finally {
            bridgeState = BridgeState.IDLE
            lastStateChangeTime = System.currentTimeMillis()
            listener?.onStateChanged(BridgeState.IDLE, reason)
            Log.i(TAG, "Bridge torn down: $reason")
        }
    }

    // ── Utility ─────────────────────────────────────────

    private fun allocateRtpPort(): Int {
        // Find a free UDP port in the 30000-40000 range
        for (port in 30000..40000 step 2) {
            try {
                DatagramSocket(null).use { sock ->
                    sock.reuseAddress = true
                    sock.bind(InetSocketAddress(port))
                    return port
                }
            } catch (_: Exception) {
                continue
            }
        }
        throw RuntimeException("No free RTP port available")
    }

    private fun normalizeGsmNumber(raw: String): String {
        var value = raw.trim().trim('"', '\'')
        val bracketed = Regex("<([^>]+)>").find(value)?.groupValues?.getOrNull(1)
        if (bracketed != null) value = bracketed
        value = value.substringBefore(';').substringBefore(',').trim()
        value = value.removePrefix("tel:").removePrefix("TEL:")
            .removePrefix("sip:").removePrefix("SIP:")
            .substringBefore('@')
        val compact = value.filterNot { it == ' ' || it == '-' || it == '(' || it == ')' || it == '.' }
        if (compact.isEmpty() || compact.any { !it.isDigit() && it != '+' && it != '*' && it != '#' }) {
            return ""
        }
        val prefix = if (compact.startsWith("+")) "+" else ""
        val body = compact.removePrefix("+")
        if (body.isEmpty() || body.any { !it.isDigit() && it != '*' && it != '#' }) return ""
        return prefix + body
    }

    private fun startGsmDialTimeout(attemptToken: Long, expectedSipCall: SipCall? = null) {
        Thread({
            try { Thread.sleep(GSM_DIAL_TIMEOUT_MS) } catch (_: InterruptedException) { return@Thread }
            val sipCallMatches = expectedSipCall == null || activeSipCall === expectedSipCall
            if (callAttempts.isCurrent(attemptToken) && sipCallMatches &&
                bridgeState == BridgeState.GSM_DIALING) {
                Log.w(TAG, "GSM dial timeout — no ACTIVE callback in ${GSM_DIAL_TIMEOUT_MS / 1000}s")
                tearDown("GSM dial timeout")
            } else {
                Log.d(TAG, "Ignoring stale GSM timeout for attempt=$attemptToken")
            }
        }, "GSM-Dial-Timeout").start()
    }

    /** Start the root authorization while the call is still ringing/dialing. */
    private fun beginRecordAudioAuthorization() {
        synchronized(recordAudioAuthorizationLock) {
            if (recordAudioAuthorizationInProgress) return
            // Re-authorize once per call. Android may revoke the UID AppOp
            // between two calls even when the previous grant is recent.
            recordAudioAuthorizedElapsed = 0L
            recordAudioAuthorizationInProgress = true
        }

        Thread({
            val allowed = forceAllowRecordAudio()
            synchronized(recordAudioAuthorizationLock) {
                recordAudioAuthorizedElapsed =
                    if (allowed) SystemClock.elapsedRealtime() else 0L
                recordAudioAuthorizationInProgress = false
                recordAudioAuthorizationLock.notifyAll()
            }
            Log.i(TAG, "RECORD_AUDIO preauthorization complete: allowed=$allowed")
        }, "RecordAudio-Prepare").start()
    }

    /**
     * Return only the unelapsed AppOps propagation time. If the early request
     * failed or became stale, let RtpSession issue a fresh request itself.
     */
    private fun prepareRecordAudioForRtp(): Long? {
        synchronized(recordAudioAuthorizationLock) {
            val waitDeadline = SystemClock.elapsedRealtime() + RECORD_AUDIO_AUTH_WAIT_MS
            while (recordAudioAuthorizationInProgress) {
                val remaining = waitDeadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                try {
                    recordAudioAuthorizationLock.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }

            val age = SystemClock.elapsedRealtime() - recordAudioAuthorizedElapsed
            if (recordAudioAuthorizedElapsed > 0L && age < RECORD_AUDIO_AUTH_FRESH_MS) {
                return (GsmCallManager.profile.appopsPropagationMs - age).coerceAtLeast(0L)
            }
        }
        return null
    }

    /**
     * Force-allow RECORD_AUDIO via appops using root (Magisk).
     *
     * Android's AppOpsService revokes RECORD_AUDIO (app op 27) for
     * foreground services when the screen is off.  This must be
     * re-asserted before EVERY call, not just at startup.
     *
     * CRITICAL: Must use --uid flag to set the UID-level mode.
     * `appops set <pkg>` sets the package mode, but AudioFlinger checks
     * the UID mode (set by PermissionController).  UID mode overrides
     * package mode, so without --uid the allow is ineffective on cold boot.
     */
    private fun forceAllowRecordAudio(): Boolean {
        try {
            val pkg = context.packageName
            val t0 = System.currentTimeMillis()
            // Capture all output (2>&1) for diagnosis.  appops get is LAST
            // so exit code reflects verification, not a stray killall.
            val autoRevoke = if (Build.VERSION.SDK_INT >= 30)
                "appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>&1; " else ""
            val uidFlag = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
            val result = RootShell.execForOutput(
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "pm grant $pkg android.permission.RECORD_AUDIO 2>&1; " +
                autoRevoke +
                "appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                "appops set $pkg RECORD_AUDIO allow 2>&1; " +
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
            )
            val elapsed = System.currentTimeMillis() - t0
            val allowed = result.contains("allow", ignoreCase = true)
            Log.i(TAG, "appops RECORD_AUDIO: [$result] ok=$allowed (${elapsed}ms)")

            if (!allowed) {
                val fb = RootShell.execForOutput(
                    "cmd appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops set $pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
                )
                Log.w(TAG, "appops fallback cmd: [$fb]")
                return fb.contains("allow", ignoreCase = true)
            } else {
                Log.d(TAG, "appops RECORD_AUDIO verified: allow")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "appops force-allow failed: ${e.message}")
            return false
        }
    }

    /** Force-reset bridge to IDLE, clearing all state.  Used to recover from
     *  stale states where the normal tearDown path was never triggered. */
    @Synchronized
    private fun forceReset(reason: String) {
        Log.w(TAG, "Force-resetting bridge: $reason")
        callAttempts.invalidate()
        try {
            activeRtpSession?.stop()
        } catch (_: Exception) {}
        activeRtpSession = null
        activeRtpEndpointKey = null
        try {
            activeSipCall?.let {
                if (it.state != SipCall.State.TERMINATED) it.hangup()
                sipClient.removeCall(it.callId)
            }
        } catch (_: Exception) {}
        activeSipCall = null
        try {
            activeGsmCall?.disconnect()
        } catch (_: Exception) {}
        activeGsmCall = null
        gsmActiveHandled = false
        pendingRtpAddr = null
        pendingRtpPort = 0
        pendingPayloadType = 0
        pendingLocalRtpPort = 0
        pendingOutboundLocalRtpPort = 0
        outboundRtpPreparationStarted = false
        lastGsmActiveElapsed = 0L
        diallerInitiated = false
        bridgeState = BridgeState.IDLE
        lastStateChangeTime = System.currentTimeMillis()
        listener?.onStateChanged(BridgeState.IDLE, reason)
        Log.i(TAG, "Bridge force-reset complete: $reason")
    }

    companion object {
        private const val TAG = "CallOrchestrator"
        private const val SIP_CALL_TIMEOUT_MS = 30_000L
        private const val GSM_DIAL_TIMEOUT_MS = 45_000L
        private const val MEDIA_READY_TIMEOUT_MS = 3_000L
        private const val RECORD_AUDIO_AUTH_WAIT_MS = 3_000L
        private const val RECORD_AUDIO_AUTH_FRESH_MS = 60_000L
        private const val OUTBOUND_RTP_PREWARM_DELAY_MS = 250L
        /** If bridge is non-IDLE for this long, consider it stale */
        private const val STALE_STATE_TIMEOUT_MS = 60_000L
    }
}
