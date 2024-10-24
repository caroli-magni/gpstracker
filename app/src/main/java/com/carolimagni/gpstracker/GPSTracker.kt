package com.carolimagni.gpstracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

class GPSTracker : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serviceIntent = Intent(this, GpsTrackerService::class.java)
        startService(serviceIntent)
    }
}
