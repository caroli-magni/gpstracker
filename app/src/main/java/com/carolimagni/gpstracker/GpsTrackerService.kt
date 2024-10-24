package com.carolimagni.gpstracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
//import android.util.Log

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.post
import io.ktor.client.request.setBody

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.util.Calendar
import java.util.Timer
import java.util.TimerTask

import kotlin.time.Duration.Companion.seconds

val client = HttpClient(Android)

class GpsTrackerService : Service() {

    private val timer = Timer()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var timerJob: Job? = null
    private val coroutineScope = CoroutineScope(Job())

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        timerJob?.cancel()
        coroutineScope.coroutineContext.cancelChildren()
    }

    override fun onBind(p0: Intent?): IBinder? {
        TODO("Not yet implemented")
    }

    private fun setupLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    _locationFlow.value = location
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val requester = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 100
        )
            .setIntervalMillis(LOCATION_UPDATES_INTERVAL_MS)
            .setMinUpdateIntervalMillis(TICKER_PERIOD_SECONDS)
            .setWaitForAccurateLocation(true)
            .setMaxUpdateDelayMillis(LOCATION_MAX_UPDATE_DELAY)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setDurationMillis(Long.MAX_VALUE)
            .build()

        fusedLocationClient.requestLocationUpdates(
            requester, locationCallback, Looper.getMainLooper()
        )
    }

    companion object Factory {
        private val LOCATION_UPDATES_INTERVAL_MS = 5.seconds.inWholeMilliseconds
        private val TICKER_PERIOD_SECONDS = 2.4.seconds.inWholeMilliseconds
        private val LOCATION_MAX_UPDATE_DELAY = 0.1.seconds.inWholeMilliseconds
        private val job = SupervisorJob()
        private val scope: CoroutineScope
            get() = CoroutineScope(Dispatchers.IO + job)
        private val _locationFlow = MutableStateFlow<Location?>(null)
        private var locationFlow: StateFlow<Location?> = _locationFlow
        private var displayableLocation by mutableStateOf<String?>(null)

        fun executeTask() {
            scope.launch {
                withContext(Dispatchers.Main) {
                    locationFlow.map {
                        it?.let { location ->
                            "${location.latitude}, ${location.longitude}"
                        }
                    }.collectLatest {
                        displayableLocation = it
                    }
                }
            }
            scope.launch {
                val currentTime = Calendar.getInstance().time
                client.post("https://ntfy.xxx.xyz:1111/xxx") {
                    setBody("$currentTime ${this@Factory.displayableLocation}")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        timer.schedule(
            /* task = */ GetLocationTask(),
            /* delay = */ 0,
            /* period = */ 5000
        )
        setupLocationUpdates()
    }

    internal object NotificationsHelper {
        private const val NOTIFICATION_CHANNEL_ID = "gps_tracker_notification_channel"
        fun createNotificationChannel(context: Context) {
            val notificationManager =
                context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "GPS Tracker",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        fun buildNotification(context: Context): Notification {
            return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("GPS Tracker")
                .setContentText("GPS Tracker")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(
                    Intent(
                        context,
                        GPSTracker::class.java
                    ).let { notificationIntent ->
                        PendingIntent.getActivity(
                            context,
                            0,
                            notificationIntent,
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    })
                .build()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationsHelper.createNotificationChannel(this)
        ServiceCompat.startForeground(
            this,
            1,
            NotificationsHelper.buildNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        startLocationUpdates()
        return START_STICKY
    }

    class GetLocationTask : TimerTask() {
        override fun run() {
            executeTask()
        }
    }
}