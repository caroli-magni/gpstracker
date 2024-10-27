# gpstracker

A very basic GPS Tracker background service for Android 14 written in Kotlin. Sends to ntfy instance over HTTPS. 
Launching the app starts the service. If granted the right permissions, the Activity and Service will autostart on boot. 
It will also toggle GPS on automatically if granted the WRITE_SECURE_SETTINGs permission via adb.

# TO-DO
- Buttons to toggle service
- GPS poll rate input
- NTFY endpoint 


To help prevent standbys affecting tracking:
> adb shell am set-standby-bucket com.carolimagni.gpstracker never

To allow GPS Tracker to toggle GPS on if needed:
> adb shell pm grant com.carolimagni.gpstracker android.permission.WRITE_SECURE_SETTINGS



