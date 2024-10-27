package com.carolimagni.gpstracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.provider.Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat


/**
 * Variables for checking if Location/GPS is on and if we
 * have the appropriate permission to toggle GPS like a system app
 */
private var gpsCannotBeChanged = false
private var gpsEnabled = false

/**
 * Main GPSTracker Activity. An Activity is needed to launch a foreground service that persists.
 * It simply launches the Service and closes the Activity.
 */
@Suppress("CyclicClassDependency", "CyclicClassDependency", "CyclicClassDependency", "CyclicClassDependency")
class GPSTracker : ComponentActivity() {

    private var gpsPermitted = false

    /**
     * Function to check we have all three Location permissions necessary to get accurate background readouts.
     */
    private fun checkLocationPermission(): Boolean {
        gpsPermitted = (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_LOCATION) == PackageManager.PERMISSION_GRANTED
                )
        return gpsPermitted
    }

    /**
     * Function to check we have all three Location permissions necessary to get accurate background readouts.
     */
    private fun checkNotificationPermission(): Boolean {
        return (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, GpsTrackerService::class.java)

        if (!checkLocationPermission()) {
            Toast.makeText(applicationContext, "Please allow Location permission! Allow then relaunch.", Toast.LENGTH_LONG).show()
            openAppPermissions()
            finish()
        }

        if (!checkNotificationPermission()) {
            Toast.makeText(applicationContext, "Please allow Notification permission! Allow then relaunch.", Toast.LENGTH_LONG).show()
            openAppPermissions()
            finish()
        }

        val locationManager = this.getSystemService(LOCATION_SERVICE) as LocationManager

        //Log.e("GPS Tracker", "GPS State check #1...")
        try {
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
        }
        if (!gpsEnabled) {
            //Log.e("GPS Tracker", "Trying to toggle GPS via ...")
            val context = this
            LocationHelper.tryToggleLocationStatus(context)
        }

        if (gpsCannotBeChanged && !gpsEnabled) {
            //Log.e("GPS Tracker", "GPS cannot be enabled, so we've prompted the user and are not starting the service'")
            Toast.makeText(applicationContext, "Please enable Location! Enable then relaunch.", Toast.LENGTH_LONG).show()
            promptOpenLocationSetting()
        }

        if (gpsEnabled && gpsPermitted) {
            //Log.i("GPS Tracker", "GPS detected on, starting the service")
            Toast.makeText(applicationContext, "GPS detected on, starting the service", Toast.LENGTH_LONG).show()
            startService(serviceIntent)
            finish()
        }
    }


    private fun promptOpenLocationSetting(): Boolean {

        //Log.i("GPS Tracker", "Creating ACTION_LOCATION_SOURCE_SETTINGS AlertDialog")
        AlertDialog.Builder(this)
            .setTitle("Location setting")
            .setMessage("Location must be enabled for this app to work")
            .setPositiveButton("Open Settings") { _, _ -> //Prompt the user once explanation has been shown
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                //Log.i("GPS Tracker", "Launching ACTION_LOCATION_SOURCE_SETTINGS")
                startActivity(intent)
                val locationManager = this.getSystemService(LOCATION_SERVICE) as LocationManager
                try {
                    gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                } catch (_: Exception) {
                }
            }
            .create()
            .show()
        //Log.e("GPS Tracker", "Returning finding...")
        return gpsEnabled
    }

    private fun openAppPermissions() {
        Toast.makeText(applicationContext, "Location permission not enabled!", Toast.LENGTH_LONG).show()

        /**
         *  Open the permissions menu for this app if we don't have permission
         */
        Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse(getString(R.string.package_name))).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            startActivity(this)

        }

    }

    internal object LocationHelper {
        @Suppress("DEPRECATION")
        fun tryToggleLocationStatus(context: Context) {
            try {
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.LOCATION_MODE,
                    Integer.valueOf(LOCATION_MODE_HIGH_ACCURACY).toString()
                )
            } catch (_: Exception) {
            }
            val locationManager = context.getSystemService(LOCATION_SERVICE) as LocationManager
            Thread.sleep(200)
            //Log.e("GPS Tracker", "GPS State check #2...")
            try {
                gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            } catch (_: Exception) {
            }

            if (!gpsEnabled) {
                //Log.e("GPS Tracker", "locationManager reported GPS is disabled")
                //Log.e("GPS Tracker", "Couldn't enable GPS! Grant WRITE_SECURE_SETTINGS or toggle GPS. Not automatically starting service.")
                gpsCannotBeChanged = true

            }
        }
    }
}

/**
 * BootUpReceiver class to facilitate RECEIVE_BOOT_COMPLETED
 */
class BootUpReceiver : BroadcastReceiver() {
    /**
     * Start this activity when the device boots
     */
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, GpsTrackerService::class.java)
        serviceIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startService(serviceIntent)
    }
}