package com.msp1974.vacompanion.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.app.AlertDialog
import androidx.webkit.WebViewClientCompat
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.settings.PageLoadingStage
import com.msp1974.vacompanion.ui.VAViewModel
import timber.log.Timber
import androidx.core.net.toUri

class CustomWebViewClient(val viewModel: VAViewModel): WebViewClientCompat()  {
    val log = Logger()
    val config = viewModel.config!!
    private val firebase = FirebaseManager.getInstance(config.context)
    private val resources = viewModel.resources!!
    private var lastScreenSaverEnforceMs: Long = 0L

    companion object {

        private const val APP_PREFIX = "app://"
        private const val INTENT_PREFIX = "intent:"
        private const val SCREEN_SAVER_PREFIX = "/dashboard-screensaver"
        private const val CLOCK_PREFIX = "/view-assist/clock"
        private const val SCREEN_SAVER_ENFORCE_COOLDOWN_MS = 1500L
        private const val NAVIGATE_GUARD_ENFORCE_COOLDOWN_MS = 1000L
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail?
    ): Boolean {
        log.e("Webview render process gone: $detail")
        var reason = FirebaseManager.RENDER_PROCESS_CRASHED
        if (detail?.didCrash() != true) {
            reason = FirebaseManager.RENDER_PROCESS_KILLED
        }
        firebase.logEvent (reason, mapOf("detail" to detail.toString()))
        try {
            view.reload()
        } catch (e: Exception) {
            log.e("Failed to reload webview: $e")
            // Broadcast to activity to handle webview restart
            BroadcastSender.sendBroadcast(config.context, BroadcastSender.WEBVIEW_CRASH)
        }
        return true
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return handleUrlOverride(
            view = view,
            url = request.url.toString(),
            source = "request",
            isMainFrame = request.isForMainFrame,
            hasGesture = request.hasGesture(),
            method = request.method
        )
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        return handleUrlOverride(
            view = view,
            url = url,
            source = "legacy",
            isMainFrame = true,
            hasGesture = null,
            method = null
        )
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        Timber.d("Page started: $url saverActive=${config.screenSaverActive}")
        setPageLoadingState(PageLoadingStage.STARTED)
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (view != null && !url.isNullOrBlank()) {
            injectRouteDiagnostics(view)
        }
        if (viewModel.vacaState.value.webViewPageLoadingStage == PageLoadingStage.AUTHORISED) {
            Handler(Looper.getMainLooper()).postDelayed({
                setPageLoadingState(PageLoadingStage.LOADED)
            }, 1000)
            Timber.d("Page finished loading: $url")
        }
        super.onPageFinished(view, url)
    }

    fun setPageLoadingState(stage: PageLoadingStage) {
        viewModel.setWebViewPageLoadingState(stage)
    }

    @Deprecated("Deprecated in Java")
    override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String,
        failingUrl: String
    ) {
        view.loadUrl("file:///android_asset/web/error.html")
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError
    ) {
        log.e("SSL Error: $error")
        if (!config.ignoreSSLErrors) {
            var message = resources.getString(R.string.dialog_message_ssl_generic)
            when (error.primaryError) {
                SslError.SSL_UNTRUSTED -> message =
                    resources.getString(R.string.dialog_message_ssl_untrusted)
                SslError.SSL_EXPIRED -> message =
                    resources.getString(R.string.dialog_message_ssl_expired)
                SslError.SSL_IDMISMATCH -> message =
                    resources.getString(R.string.dialog_message_ssl_mismatch)
                SslError.SSL_NOTYETVALID -> message =
                    resources.getString(R.string.dialog_message_ssl_not_yet_valid)
            }
            message += resources.getString(R.string.dialog_message_ssl_continue)
            val alertDialog = AlertDialog.Builder(view.context)
            alertDialog.apply {
                setTitle(resources.getString(R.string.dialog_title_ssl_error))
                setMessage(message)
                setPositiveButton(resources.getString(R.string.dialog_button_yes)) { _: DialogInterface?, _: Int ->
                    handler.proceed()
                    config.ignoreSSLErrors = true
                }
                setNeutralButton(resources.getString(R.string.dialog_button_always_yes)) { _: DialogInterface?, _: Int ->
                    config.ignoreSSLErrors = true
                    config.alwaysIgnoreSSLErrors = true
                    handler.proceed()
                }
                setNegativeButton(resources.getString(R.string.dialog_button_no)) { _: DialogInterface?, _: Int ->
                    super.onReceivedSslError(view, handler, error)
                    view.loadUrl("file:///android_asset/web/error.html")
                }
            }.create().show()
        } else {
            handler.proceed()
        }
    }

    override fun doUpdateVisitedHistory(
        view: WebView,
        url: String,
        isReload: Boolean,
    ) {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val path = if (scheme == "http" || scheme == "https") {
            uri.path.orEmpty()
        } else {
            ""
        }

        if (config.screenSaverActive && (scheme == "http" || scheme == "https") &&
            !path.startsWith(SCREEN_SAVER_PREFIX)
        ) {
            log.d("Ignoring escaped path while saver active: $path")

            val now = System.currentTimeMillis()
            if (now - lastScreenSaverEnforceMs >= SCREEN_SAVER_ENFORCE_COOLDOWN_MS) {
                lastScreenSaverEnforceMs = now
                val saverUrl = AuthUtils.getHAUrl(config, withDashboardPath = false)
                    .removeSuffix("/") + SCREEN_SAVER_PREFIX
                log.d("Forcing saver path after escape -> $saverUrl")
                view.post { view.loadUrl(saverUrl) }
            }
            return
        }

        val guardActive = System.currentTimeMillis() < config.remoteNavigateGuardUntilMs
        val targetPath = config.remoteNavigateTargetPath
        val targetUrl = config.remoteNavigateTargetUrl
        if (!config.screenSaverActive &&
            guardActive &&
            path.startsWith(CLOCK_PREFIX) &&
            targetPath.isNotBlank() &&
            !targetPath.startsWith(CLOCK_PREFIX)
        ) {
            val now = System.currentTimeMillis()
            if (now - lastScreenSaverEnforceMs >= NAVIGATE_GUARD_ENFORCE_COOLDOWN_MS) {
                lastScreenSaverEnforceMs = now
                log.d(
                    "Blocked clock redirect during navigate guard path=$path targetPath=$targetPath " +
                        "until=${config.remoteNavigateGuardUntilMs}"
                )
                if (targetUrl.isNotBlank()) {
                    view.post { view.loadUrl(targetUrl) }
                }
            }
            return
        }

        config.currentPath = path
        Timber.d(
            "History update url=$url path=${config.currentPath} reload=$isReload " +
                    "saverActive=${config.screenSaverActive}"
        )
    }

    private fun handleUrlOverride(
        view: WebView,
        url: String,
        source: String,
        isMainFrame: Boolean,
        hasGesture: Boolean?,
        method: String?
    ): Boolean {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val path = uri?.path.orEmpty()
        val elapsed = if (config.screenSaverStartedAtMs > 0) {
            System.currentTimeMillis() - config.screenSaverStartedAtMs
        } else -1L

        Timber.d(
            "Nav attempt source=$source mainFrame=$isMainFrame gesture=$hasGesture method=$method " +
                    "url=$url path=$path saverActive=${config.screenSaverActive} elapsedSinceSaverMs=$elapsed"
        )

        val guardActive = System.currentTimeMillis() < config.remoteNavigateGuardUntilMs
        val targetPath = config.remoteNavigateTargetPath
        val targetUrl = config.remoteNavigateTargetUrl
        if (!config.screenSaverActive &&
            guardActive &&
            isMainFrame &&
            (scheme == "http" || scheme == "https") &&
            path.startsWith(CLOCK_PREFIX) &&
            targetPath.isNotBlank() &&
            !targetPath.startsWith(CLOCK_PREFIX)
        ) {
            log.d(
                "Blocked clock navigation during navigate guard (override) path=$path targetPath=$targetPath " +
                    "until=${config.remoteNavigateGuardUntilMs}"
            )
            if (targetUrl.isNotBlank()) {
                view.post { view.loadUrl(targetUrl) }
            }
            return true
        }

        if (config.screenSaverActive && isMainFrame && (scheme == "http" || scheme == "https")) {
            val lockActive = System.currentTimeMillis() < config.screenSaverNavLockUntilMs
            if (lockActive && !path.startsWith(SCREEN_SAVER_PREFIX)) {
                Timber.d("Blocked nav during saver lock window path=$path")
                return true
            }
        }

        try {
            val pm: PackageManager = config.context.packageManager
            val activityContext = config.context.takeIf { it is Activity } ?: return false

            if (url.contains(AuthUtils.CLIENT_URL)) {
                val authCode = AuthUtils.getReturnAuthCode(url)
                if (authCode != "") {
                    val auth = AuthUtils.authoriseWithAuthCode(AuthUtils.getHAUrl(config), authCode, !config.ignoreSSLErrors)
                    if (auth.accessToken == "") {
                        view.loadUrl(AuthUtils.getAuthUrl(AuthUtils.getHAUrl(config)))
                    } else {
                        config.accessToken = auth.accessToken
                        config.refreshToken = auth.refreshToken
                        config.tokenExpiry = auth.expires
                        view.loadUrl(AuthUtils.getURL(AuthUtils.getHAUrl(config)))
                    }
                }
                return true
            } else if (url.startsWith(APP_PREFIX)) {
                Timber.d("Launching app url=$url")
                val intent = pm.getLaunchIntentForPackage(url.substringAfter(APP_PREFIX))
                if (intent != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    activityContext.startActivity(intent)
                } else {
                    Timber.w("No intent to launch app found")
                }
                return true
            } else if (url.startsWith(INTENT_PREFIX)) {
                Timber.d("Launching intent url=$url")
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                val intentPackage = intent.`package`?.let { pm.getLaunchIntentForPackage(it) }
                if (intentPackage == null && !intent.`package`.isNullOrEmpty()) {
                    Timber.w("No app found for intent prefix")
                } else {
                    activityContext.startActivity(intent)
                }
                return true
            } else if (!url.toString().contains(url)) {
                Timber.d("Launching browser url=$url")
                val browserIntent = Intent(Intent.ACTION_VIEW, url.toUri())
                activityContext.startActivity(browserIntent)
                return true
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to override the URL")
        }
        return false
    }

    private fun injectRouteDiagnostics(view: WebView) {
        val saverActive = if (config.screenSaverActive) "true" else "false"
        val guardActive = if (System.currentTimeMillis() < config.remoteNavigateGuardUntilMs) "true" else "false"
        val guardTargetPathEscaped = config.remoteNavigateTargetPath
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val script = """
            (function() {
              try {
                window.__vacaScreensaverActive = $saverActive;
                window.__vacaNavigateGuardActive = $guardActive;
                window.__vacaNavigateGuardTargetPath = "$guardTargetPathEscaped";
                window.__vacaScreensaverPrefix = "$SCREEN_SAVER_PREFIX";
                window.__vacaClockPrefix = "$CLOCK_PREFIX";
                if (window.__vacaRouteHookInstalled) return;
                window.__vacaRouteHookInstalled = true;
                var send = function(type) {
                  try {
                    if (window.ViewAssistApp && window.ViewAssistApp.sendEvent) {
                      var payload = JSON.stringify({
                        type: type,
                        href: window.location.href,
                        path: window.location.pathname + window.location.search + window.location.hash,
                        ts: Date.now()
                      });
                      window.ViewAssistApp.sendEvent("vaca-route", payload);
                    }
                  } catch (e) {}
                };
                var toPath = function(target) {
                  try {
                    if (target == null) return window.location.pathname;
                    return new URL(target, window.location.href).pathname;
                  } catch (e) {
                    return "";
                  }
                };
                var shouldBlock = function(target, source) {
                  try {
                    var path = toPath(target);
                    if (window.__vacaScreensaverActive && path && path.indexOf(window.__vacaScreensaverPrefix) !== 0) {
                      send("blocked-" + source);
                      return true;
                    }
                    if (
                      window.__vacaNavigateGuardActive &&
                      path &&
                      path.indexOf(window.__vacaClockPrefix) === 0 &&
                      window.__vacaNavigateGuardTargetPath &&
                      window.__vacaNavigateGuardTargetPath.indexOf(window.__vacaClockPrefix) !== 0
                    ) {
                      send("blocked-clock-" + source);
                      return true;
                    }
                  } catch (e) {}
                  return false;
                };
                var pushState = history.pushState;
                history.pushState = function() {
                  if (shouldBlock(arguments[2], "pushState")) return;
                  var r = pushState.apply(this, arguments);
                  send("pushState");
                  return r;
                };
                var replaceState = history.replaceState;
                history.replaceState = function() {
                  if (shouldBlock(arguments[2], "replaceState")) return;
                  var r = replaceState.apply(this, arguments);
                  send("replaceState");
                  return r;
                };
                document.addEventListener("click", function(ev) {
                  try {
                    if (!window.__vacaScreensaverActive) return;
                    var node = ev.target;
                    while (node && node.tagName !== "A") node = node.parentElement;
                    if (!node) return;
                    var href = node.getAttribute("href");
                    if (shouldBlock(href, "anchor-click")) {
                      ev.preventDefault();
                      ev.stopPropagation();
                    }
                  } catch (e) {}
                }, true);
                window.addEventListener("popstate", function() { send("popstate"); });
                window.addEventListener("hashchange", function() { send("hashchange"); });
                send("hook-installed");
              } catch (e) {}
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }


}
