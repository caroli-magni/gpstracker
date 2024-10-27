@file:Suppress("SpellCheckingInspection", "CyclicClassDependency", "unused")

package com.carolimagni.gpstracker

import android.Manifest
import android.R.drawable.ic_menu_mylocation
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.widget.Toast
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
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Timer
import kotlin.time.Duration.Companion.seconds


/**
 * asynchronous HTTP client to submit POST requests to ntfy instance.
 */
val client: HttpClient = HttpClient(Android) {
}

/**
 * Main Foreground Service class, spawned from the GPSTracker Activity.
 * It schedules a task at a fixed interval, which requests GPS Location
 * and sends the result via HTTPS POST, with the destination intended to
 * be a ntfy instance.
 */
class GpsTrackerService : Service() {
    private val timer = Timer()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var timerJob: Job? = null
    private val coroutineScope = CoroutineScope(Job())

    /**
     * When the service is destroyed, stop requesting Location updates,
     * stop the scheduled task and TimerTask children.
     */
    override fun onDestroy() {
        //Log.e("GPS Tracker", "Destroying service...")
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        timerJob?.cancel()
        coroutineScope.coroutineContext.cancelChildren()
    }

    /**
     * We don't intend to Bind to this service at the moment, return null.
     */
    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    /**
     * companion object Factory used for shared variable and functions across
     * the launched co-routines
     */
    companion object Factory {
        /** Requested update interval - LOCATION_UPDATES_INTERVAL_MS */
        private val LOCATION_UPDATES_INTERVAL_MS = 5.seconds.inWholeMilliseconds

        /** Fastest allowed interval of location updates - LOCATION_MIN_UPDATE_DELAY */
        private val LOCATION_MIN_UPDATE_DELAY = 1.seconds.inWholeMilliseconds

        /** Longest a location update may be delayed - LOCATION_MAX_UPDATE_DELAY */
        private val LOCATION_MAX_UPDATE_DELAY = 6.seconds.inWholeMilliseconds

        /** Co-routine scope, later used for sending HTTP POST to ntfy server */
        private val job = SupervisorJob()
        internal val scope: CoroutineScope
            get() = CoroutineScope(Dispatchers.IO + job)

        /** Timestamp of last GPS readout, internal var so both the co-routine
         * can read it and the onLocationResult() callback func can write it */
        internal var gpsPollTimestamp = Calendar.getInstance().time
        private var jsonPayload = ""

    }

    /**
     * On service creation, create a thread that counts the time since
     * last reported location, restarting if exceeding 30s timeout.
     * Then continue and start the FusedLocationProviderClient setup.
     */
    override fun onCreate() {
        super.onCreate()

        /**
         * Create message handler for service watchdog thread.
         * Receives an emited message every 30s, runs this body
         */
        val handler = @SuppressLint("HandlerLeak")
        object : Handler() {
            override fun handleMessage(msg: Message) {
                super.handleMessage(msg)
                val timeNow = Calendar.getInstance().time.time
                val timeThen = gpsPollTimestamp.time
                val timeDiff = (timeNow - timeThen) / 1000


                //Log.e("GPS Tracker", "Time since last poll: ${timeDiff}s")
                if (timeDiff >= 60) {
                    //Log.e("GPS Tracker", "Haven't received a poll in 60s, restarting service...")
                    Toast.makeText(applicationContext, "GPS Tracker timed out! Restarting service...", Toast.LENGTH_LONG).show()
                    /**
                     * Remove previous fusedLocationClient, then re-create
                     * and request location updates again.
                     */
                    fusedLocationClient.removeLocationUpdates(locationCallback)
                    setupLocationUpdates()
                    startLocationUpdates()
                }
            }
        }
        /**
         * Create thread that emits message to handleMessage() func.
         * This triggers the check every 30s to restart the Location updates
         */
        Thread {
            while (true) {
                try {
                    Thread.sleep(30000)
                    handler.sendEmptyMessage(0)
                } catch (c: Throwable) {
                    c.printStackTrace()
                }
            }
        }.start()
        setupLocationUpdates()
    }

    /**
     * Start requesting updates from the FusedLocationClient we setup earlier.
     * LOCATION_UPDATES_INTERVAL_MS, LOCATION_MAX_UPDATE_DELAY and
     * LOCATION_MIN_UPDATE_DELAY can be modified to adjust GPS Tracker push timings.
     * See docs for .setIntervalMillis() etc. for example.
     */
    internal fun startLocationUpdates() {
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
        /**
         * locationRequest built by play-services LocationRequest.Builder Constructor
         * to create a LocationRequest that requests updates at a given interval,
         * given minimum interval, given maximum delay, of fine granularity,
         * continually and with no limit.
         */
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, LOCATION_MIN_UPDATE_DELAY
        )
            .setIntervalMillis(LOCATION_UPDATES_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_MIN_UPDATE_DELAY)
            .setMaxUpdateDelayMillis(LOCATION_MAX_UPDATE_DELAY)
            .setMaxUpdateAgeMillis(LOCATION_UPDATES_INTERVAL_MS)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(Long.MAX_VALUE)
            .setMaxUpdates(Integer.MAX_VALUE)
            .build()

        /**
         * Finally, start requesting Location updates from our
         * constructed fusedLocationClient
         */
        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
    }

    /**
     * Function to create getFusedLocationProviderClient for later use
     */
    internal fun setupLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    // Get timestamp of received Location update
                    gpsPollTimestamp = Calendar.getInstance().time
                    //Log.e("GPS Tracker", "location: $location")
                    scope.launch {
                        jsonPayload = "{" +
                                "  \"time\": \"$gpsPollTimestamp\",\n" +
                                "  \"gpsposition\": \"${location.latitude}, ${location.longitude}\"" +
                                "}"
                        try {
                            /**
                             * TOPIC_STRING is the URL to the ntfy topic, including port
                             */
                            val tokenString = resources.getString(R.string.TOKEN_STRING)
                            val topicString = resources.getString(R.string.TOPIC_STRING)
                            client.post(topicString) {
                                /**
                                 * TOKEN_STRING is a base64-encoded JSON string, built using the following; in Bash
                                 *  *#  token=$(echo -n "Basic `echo -n ':tokenvalue' | base64 -w 0`" | base64 -w 0 | tr -d '=')
                                 * Note the blank username and the token value as the password field in ':tokenvalue;
                                 */
                                parameter("auth", tokenString)
                                setBody(jsonPayload)
                            }
                        } catch (c: Throwable) {
                            c.printStackTrace()
                        }

                    }
                }
            }
        }
    }

    /**
     * Notification helper object used to construct Notification
     * channels and then later build notifications. A notification
     * is required to maintain a foreground service with location
     * foreground service access.
     */
    internal object NotificationsHelper {

        /**
         * Create notification channel with id "gps_tracker_notification_channel"
         */
        private const val NOTIFICATION_CHANNEL_ID = "gps_tracker_notification_channel"
        fun createNotificationChannel(context: Context) {
            val notificationManager =
                context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "@string/app_name",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        /**
         * Use NotificationCompats Builder notification Constructor to create an
         * anchored notification with FOREGROUND_SERVICE_IMMEDIATE behaviour,
         * ic_menu_mylocation icon from android SDK on channel id "gps_tracker_notification_channel"
         */
        @JvmStatic
        fun buildNotification(context: Context): Notification {
            return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("GPS Tracker")
                .setContentText("Status: enabled, interval ${LOCATION_UPDATES_INTERVAL_MS / 1000}s")
                .setSmallIcon(ic_menu_mylocation)
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

    /**
     * On service start, create a notification channel, start the
     * Foreground service, and start requesting location updates.
     */
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

}