package com.xcommunity.app.google_oauth

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension

/**
 * Signs in with Google. Google Identity Services refuses the Android System
 * WebView and only accepts Grindr's origin, so this loads web.grindr.com in a
 * private GeckoView session with the Xcommunity Google OAuth WebExtension
 * built in (assets/xcommunity-google-oauth, bundled from the git submodule by
 * app/build.gradle.kts). The extension hands the access token over native
 * messaging.
 *
 * If the Xcommunity app started this activity for a result and is signed with
 * the same key, the token goes back to it. Otherwise the token is shown with
 * a copy button.
 */
class OAuthActivity : ComponentActivity() {

  companion object {
    const val XCOMMUNITY_PACKAGE = "com.xcommunity.app"
    const val EXTRA_TOKEN = "com.xcommunity.app.google_oauth.extra.TOKEN"

    private const val TAG = "XcommunityOAuth"
    // #autostart: the extension opens Google's sign-in right away instead of
    // waiting for a tap on its button (popupPromptDelegate allows the popup).
    private const val HELPER_URL = "https://web.grindr.com/#autostart"
    private const val NATIVE_APP = "xcommunity_google_oauth"
    private const val EXTENSION_ID = "xcommunity-google-oauth-webextension@xcommunity.app"
    private const val EXTENSION_URL = "resource://android/assets/xcommunity-google-oauth/"
  }

  private lateinit var root: FrameLayout
  private lateinit var runtime: GeckoRuntime
  private lateinit var geckoView: GeckoView
  private lateinit var session: GeckoSession
  private var popupSession: GeckoSession? = null
  private var extension: WebExtension? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    super.onCreate(savedInstanceState)

    geckoView = GeckoView(this)
    root = FrameLayout(this).apply {
      setBackgroundColor(Color.BLACK)
      addView(geckoView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }
    setContentView(root)
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
      val bars = insets.getInsets(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
      )
      v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }
    geckoView.coverUntilFirstPaint(Color.BLACK)

    runtime = GeckoRuntime.getDefault(this)
    runtime.settings.preferredColorScheme = GeckoRuntimeSettings.COLOR_SCHEME_DARK

    session = GeckoSession(privateSessionSettings()).apply {
      navigationDelegate = newSessionDelegate
      promptDelegate = popupPromptDelegate
    }
    session.open(runtime)
    geckoView.setSession(session)

    // The extension's delegate must be registered before the page loads:
    // GeckoView queues messages sent while none is.
    runtime.webExtensionController
      .ensureBuiltIn(EXTENSION_URL, EXTENSION_ID)
      .then<WebExtension> { installed ->
        extension = installed
        bindMessageDelegate()
        runtime.webExtensionController.setAllowedInPrivateBrowsing(installed!!, true)
      }
      .accept({
        session.loadUri(HELPER_URL)
      }, { e ->
        Log.e(TAG, "extension install failed", e)
        Toast.makeText(this, R.string.sign_in_failed, Toast.LENGTH_LONG).show()
        finish()
      })
  }

  // One delegate per runtime and native app name, and the last registration
  // wins, so take it back whenever this activity is in front again.
  override fun onResume() {
    super.onResume()
    bindMessageDelegate()
  }

  private fun bindMessageDelegate() {
    extension?.setMessageDelegate(messageDelegate, NATIVE_APP)
  }

  override fun onDestroy() {
    if (::session.isInitialized) {
      popupSession?.close()
      if (session.isOpen) session.close()
      runtime.storageController.clearData(StorageController.ClearFlags.ALL)
    }
    super.onDestroy()
  }

  private fun privateSessionSettings() =
    GeckoSessionSettings.Builder().usePrivateMode(true).build()

  /** Only the Xcommunity app, signed with the same key as this app. */
  private fun isTrustedCaller(): Boolean {
    if (callingActivity?.packageName != XCOMMUNITY_PACKAGE) return false
    return try {
      packageManager.checkSignatures(packageName, XCOMMUNITY_PACKAGE) ==
        PackageManager.SIGNATURE_MATCH
    } catch (_: Exception) {
      false
    }
  }

  private fun deliver(token: String) {
    if (isTrustedCaller()) {
      setResult(RESULT_OK, Intent().putExtra(EXTRA_TOKEN, token))
      finish()
    } else {
      showToken(token)
    }
  }

  private fun showToken(token: String) {
    popupSession?.close()
    popupSession = null
    if (session.isOpen) session.close()
    val dp = resources.displayMetrics.density
    fun pad(v: Int) = (v * dp).toInt()
    fun label(text: CharSequence, size: Float, color: Int) = TextView(this).apply {
      this.text = text
      textSize = size
      setTextColor(color)
    }

    val tokenView = label(token, 13f, Color.WHITE).apply {
      setTextIsSelectable(true)
      setPadding(pad(12), pad(12), pad(12), pad(12))
      setBackgroundColor(0xFF1E1E1E.toInt())
    }
    val copy = Button(this).apply {
      setText(R.string.copy_token)
      setOnClickListener { copyToken(token) }
    }
    val close = Button(this).apply {
      setText(R.string.close)
      setOnClickListener { finish() }
    }
    val column = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(pad(24), pad(24), pad(24), pad(24))
      addView(label(getString(R.string.token_title), 20f, Color.WHITE))
      addView(tokenView, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
      ).apply { topMargin = pad(16); bottomMargin = pad(16) })
      addView(copy)
      addView(close)
      addView(label(getString(R.string.token_note), 13f, 0xFFAAAAAA.toInt()).apply {
        setPadding(0, pad(16), 0, 0)
      })
    }
    root.removeAllViews()
    root.addView(column, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
  }

  private fun copyToken(token: String) {
    val clip = ClipData.newPlainText("token", token).apply {
      description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
      }
    }
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    Toast.makeText(this, R.string.token_copied, Toast.LENGTH_SHORT).show()
  }

  // Answer within 10s and with a primitive, see the extension's README.
  private val messageDelegate = object : WebExtension.MessageDelegate {
    override fun onMessage(
      nativeApp: String,
      message: Any,
      sender: WebExtension.MessageSender,
    ): GeckoResult<Any> {
      val msg = message as? JSONObject ?: return refuse("message is not an object")
      return when (msg.optString("type")) {
        "token" -> {
          val token = msg.optString("token").trim()
          if (token.isEmpty()) return refuse("empty token")
          deliver(token)
          GeckoResult.fromValue(true)
        }
        // The page already shows the reason and offers to try again.
        "error" -> {
          Log.e(TAG, "extension error: ${msg.optString("error")}")
          GeckoResult.fromValue(true)
        }
        else -> refuse("unknown message type")
      }
    }
  }

  private fun refuse(reason: String): GeckoResult<Any> =
    GeckoResult.fromException(IllegalArgumentException(reason))

  // GIS opens Google's sign-in as a popup window.
  private val popupPromptDelegate = object : GeckoSession.PromptDelegate {
    override fun onPopupPrompt(
      session: GeckoSession,
      prompt: GeckoSession.PromptDelegate.PopupPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
      GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
  }

  private val newSessionDelegate = object : GeckoSession.NavigationDelegate {
    override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
      val popup = GeckoSession(privateSessionSettings())
      popupSession = popup
      popup.contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onCloseRequest(session: GeckoSession) = closePopup()
      }
      geckoView.setSession(popup)
      return GeckoResult.fromValue(popup)
    }
  }

  private fun closePopup() {
    geckoView.setSession(session)
    popupSession?.close()
    popupSession = null
  }
}
