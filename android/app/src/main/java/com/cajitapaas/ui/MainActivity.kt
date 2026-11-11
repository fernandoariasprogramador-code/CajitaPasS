package com.cajitapaas.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.cajitapaas.core.PaaSRuntimeService

/**
 * Actividad principal de CPS (CajitaPaaS):
 * Inicia el ForegroundService con WakeLock/WifiLock + servidor en puerto 4000
 * y abre inmediatamente el Panel Administrativo Web dentro de la APK.
 */
class MainActivity : Activity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startPaaSService()

        val wv = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0D131A"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webViewClient = WebViewClient()
        }

        setContentView(wv)
        webView = wv

        // Pequeña espera de 600ms para que el servidor del ForegroundService abra el puerto 4000
        wv.postDelayed({
            wv.loadUrl("http://127.0.0.1:4000")
        }, 600)
    }

    private fun startPaaSService() {
        val serviceIntent = Intent(this, PaaSRuntimeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
