package com.pcl.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Minimal call-only lock-screen surface. Never exposes Recents, Contacts or Settings. */
class IncomingCallActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var caller: TextView
    private lateinit var answerButton: Button
    private lateinit var declineButton: Button
    private var presentedToken: Long? = null
    private var submitted = false
    private val refresh = object : Runnable {
        override fun run() {
            val service = Hub.service
            if (service?.role != LinkService.ROLE_REMOTE || !service.isActive ||
                presentedToken == null || service.currentIncomingToken() != presentedToken) {
                finish()
                return
            }
            caller.text = service.display()
            if (submitted && service.callControlError?.startsWith("PENDING:") != true) submitted = false
            answerButton.isEnabled = !submitted
            declineButton.isEnabled = !submitted
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        presentedToken = Hub.service?.currentIncomingToken()
        val padding = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply {
            text = "Incoming SIM call"
            textSize = 24f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        caller = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, padding, 0, padding)
        }
        root.addView(caller)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        declineButton = Button(this).apply {
            text = "Decline"
            setOnClickListener {
                if (!submitted) {
                    submitted = true
                    isEnabled = false
                    answerButton.isEnabled = false
                    Hub.service?.rejectForCall(presentedToken)
                }
            }
        }
        buttons.addView(declineButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        answerButton = Button(this).apply {
            text = "Answer"
            setOnClickListener {
                if (!submitted) {
                    submitted = true
                    isEnabled = false
                    declineButton.isEnabled = false
                    Hub.service?.answerForCall(presentedToken)
                }
            }
        }
        buttons.addView(answerButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        presentedToken = Hub.service?.currentIncomingToken()
        submitted = false
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
