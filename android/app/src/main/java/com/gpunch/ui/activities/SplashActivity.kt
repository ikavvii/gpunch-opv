package com.gpunch.ui.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.gpunch.utils.SessionManager

/**
 * Entry point. Redirects to Dashboard if logged in, otherwise to Login.
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = SessionManager(this)
        val intent = if (session.isLoggedIn()) {
            Intent(this, DashboardActivity::class.java)
        } else {
            Intent(this, LoginActivity::class.java)
        }
        startActivity(intent)
        finish()
    }
}
