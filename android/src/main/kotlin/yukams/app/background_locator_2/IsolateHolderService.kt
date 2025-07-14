package yukams.app.background_locator_2

import android.app.*
import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import android.content.pm.PackageManager
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import yukams.app.background_locator_2.pluggables.DisposePluggable
import yukams.app.background_locator_2.pluggables.InitPluggable
import yukams.app.background_locator_2.pluggables.Pluggable
import yukams.app.background_locator_2.provider.*
import java.util.HashMap
import androidx.core.app.ActivityCompat

class IsolateHolderService : MethodChannel.MethodCallHandler, LocationUpdateListener, Service() {
    companion object {
        @JvmStatic
        val ACTION_SHUTDOWN = "SHUTDOWN"

        @JvmStatic
        val ACTION_START = "START"

        @JvmStatic
        val ACTION_UPDATE_NOTIFICATION = "UPDATE_NOTIFICATION"

        @JvmStatic
        private val WAKELOCK_TAG = "IsolateHolderService::WAKE_LOCK"

        @JvmStatic
        var backgroundEngine: FlutterEngine? = null

        @JvmStatic
        private val notificationId = 1

        @JvmStatic
        var isServiceRunning = false

        @JvmStatic
        var isServiceInitialized = false

        fun getBinaryMessenger(context: Context?): BinaryMessenger? {
            val messenger = backgroundEngine?.dartExecutor?.binaryMessenger
            return messenger
                ?: if (context != null) {
                    backgroundEngine = FlutterEngine(context)
                    backgroundEngine?.dartExecutor?.binaryMessenger
                }else{
                    messenger
                }
        }
    }

    private var notificationChannelName = "Flutter Locator Plugin"
    private var notificationTitle = "Start Location Tracking"
    private var notificationMsg = "Track location in background"
    private var notificationBigMsg =
        "Background location is on to keep the app up-tp-date with your location. This is required for main features to work properly when the app is not running."
    private var notificationIconColor = 0
    private var icon = 0
    private var wakeLockTime = 60 * 60 * 1000L // 1 hour default wake lock time
    private var locatorClient: BLLocationProvider? = null
    internal lateinit var backgroundChannel: MethodChannel
    internal var context: Context? = null
    private var pluggables: ArrayList<Pluggable> = ArrayList()

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.e("IsolateHolderService", "onCreate called")
        
        // Start foreground immediately to avoid ForegroundServiceDidNotStartInTimeException
        try {
            startForeground(notificationId, getNotification())
            Log.e("IsolateHolderService", "startForeground called successfully in onCreate")
        } catch (e: Exception) {
            Log.e("IsolateHolderService", "Error starting foreground in onCreate: ${e.message}")
        }
        
        // Initialize the service in background
        try {
            startLocatorService(this)
        } catch (e: Exception) {
            Log.e("IsolateHolderService", "Error in startLocatorService: ${e.message}")
            // Don't stop the service if initialization fails, just log the error
        }
    }

    private fun start() {
        Log.e("IsolateHolderService", "start() called")
        
        try {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire(wakeLockTime)
                }
            }

            // Update notification if needed, but don't call startForeground again
            val notification = getNotification()
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notificationId, notification)

            pluggables.forEach {
                context?.let { it1 -> it.onServiceStart(it1) }
            }
            
            Log.e("IsolateHolderService", "start() completed successfully")
        } catch (e: Exception) {
            Log.e("IsolateHolderService", "Error in start(): ${e.message}")
        }
    }

    private fun getNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Notification channel is available in Android O and up
            val channel = NotificationChannel(
                Keys.CHANNEL_ID, notificationChannelName,
                NotificationManager.IMPORTANCE_LOW
            )

            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val intent = Intent(this, getMainActivityClass(this))
        intent.action = Keys.NOTIFICATION_ACTION

        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this,
            1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, Keys.CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(notificationMsg)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(notificationBigMsg)
            )
            .setSmallIcon(icon)
            .setColor(notificationIconColor)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true) // so when data is updated don't make sound and alert in android 8.0+
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.e("IsolateHolderService", "onStartCommand => intent.action : ${intent?.action}")
        
        try {
            if(intent == null) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("IsolateHolderService", "Location permissions not granted, stopping service")
                    stopSelf()
                    return START_NOT_STICKY
                }
                else {
                    Log.e("IsolateHolderService", "Intent is null but permissions are granted, continuing")
                    return START_STICKY
                }
            }

            when {
                ACTION_SHUTDOWN == intent?.action -> {
                    Log.e("IsolateHolderService", "Received SHUTDOWN action")
                    isServiceRunning = false
                    shutdownHolderService()
                }
                ACTION_START == intent?.action -> {
                    Log.e("IsolateHolderService", "Received START action")
                    if (isServiceRunning) {
                        Log.e("IsolateHolderService", "Service already running, shutting down first")
                        isServiceRunning = false
                        shutdownHolderService()
                    }

                    if (!isServiceRunning) {
                        Log.e("IsolateHolderService", "Starting service")
                        isServiceRunning = true
                        startHolderService(intent)
                    }
                }
                ACTION_UPDATE_NOTIFICATION == intent?.action -> {
                    Log.e("IsolateHolderService", "Received UPDATE_NOTIFICATION action")
                    if (isServiceRunning) {
                        updateNotification(intent)
                    }
                }
                else -> {
                    Log.e("IsolateHolderService", "Unknown action: ${intent?.action}")
                }
            }

            return START_STICKY
        } catch (e: Exception) {
            Log.e("IsolateHolderService", "Error in onStartCommand: ${e.message}")
            e.printStackTrace()
            return START_STICKY
        }
    }

    private fun startHolderService(intent: Intent) {
        Log.e("IsolateHolderService", "startHolderService")
        
        try {
            notificationChannelName =
                intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_CHANNEL_NAME) ?: "Flutter Locator Plugin"
            notificationTitle =
                intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE) ?: "Start Location Tracking"
            notificationMsg = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG) ?: "Track location in background"
            notificationBigMsg =
                intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG) ?: "Background location is on to keep the app up-to-date with your location. This is required for main features to work properly when the app is not running."
            
            val iconNameDefault = "ic_launcher"
            var iconName = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_ICON)
            if (iconName == null || iconName.isEmpty()) {
                iconName = iconNameDefault
            }
            icon = resources.getIdentifier(iconName, "mipmap", packageName)
            if (icon == 0) {
                icon = resources.getIdentifier(iconNameDefault, "mipmap", packageName)
            }
            notificationIconColor =
                intent.getLongExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_ICON_COLOR, 0).toInt()
            wakeLockTime = intent.getIntExtra(Keys.SETTINGS_ANDROID_WAKE_LOCK_TIME, 60) * 60 * 1000L

            // Initialize location client
            try {
                locatorClient = context?.let { getLocationClient(it) }
                locatorClient?.requestLocationUpdates(getLocationRequest(intent))
                Log.e("IsolateHolderService", "Location client initialized successfully")
            } catch (e: Exception) {
                Log.e("IsolateHolderService", "Error initializing location client: ${e.message}")
            }

            // Fill pluggable list
            if (intent.hasExtra(Keys.SETTINGS_INIT_PLUGGABLE)) {
                pluggables.add(InitPluggable())
            }

            if (intent.hasExtra(Keys.SETTINGS_DISPOSABLE_PLUGGABLE)) {
                pluggables.add(DisposePluggable())
            }

            start()
            Log.e("IsolateHolderService", "startHolderService completed successfully")
        } catch (e: Exception) {
            Log.e("IsolateHolderService", "Error in startHolderService: ${e.message}")
            e.printStackTrace()
            // Don't stop the service, just log the error
        }
    }

    private fun shutdownHolderService() {
        Log.e("IsolateHolderService", "shutdownHolderService")
        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                if (isHeld) {
                    release()
                }
            }
        }

        locatorClient?.removeLocationUpdates()
        stopForeground(true)
        stopSelf()

        pluggables.forEach {
            context?.let { it1 -> it.onServiceDispose(it1) }
        }
    }

    private fun updateNotification(intent: Intent) {
        Log.e("IsolateHolderService", "updateNotification")
        if (intent.hasExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE)) {
            notificationTitle =
                intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE).toString()
        }

        if (intent.hasExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG)) {
            notificationMsg =
                intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG).toString()
        }

        if (intent.hasExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG)) {
            notificationBigMsg =
                intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG).toString()
        }

        val notification = getNotification()
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    private fun getMainActivityClass(context: Context): Class<*>? {
        val packageName = context.packageName
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        val className = launchIntent?.component?.className ?: return null

        return try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            e.printStackTrace()
            null
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                Keys.METHOD_SERVICE_INITIALIZED -> {
                    isServiceRunning = true
                }
                else -> result.notImplemented()
            }

            result.success(null)
        } catch (e: Exception) {

        }
    }

    override fun onDestroy() {
        isServiceRunning = false
        super.onDestroy()
    }


    private fun getLocationClient(context: Context): BLLocationProvider {
        return when (PreferencesManager.getLocationClient(context)) {
            LocationClient.Google -> GoogleLocationProviderClient(context, this)
            LocationClient.Android -> AndroidLocationProviderClient(context, this)
        }
    }

    override fun onLocationUpdated(location: HashMap<Any, Any>?) {
        try {
            context?.let {
                FlutterInjector.instance().flutterLoader().ensureInitializationComplete(
                    it, null
                )
            }

            //https://github.com/flutter/plugins/pull/1641
            //https://github.com/flutter/flutter/issues/36059
            //https://github.com/flutter/plugins/pull/1641/commits/4358fbba3327f1fa75bc40df503ca5341fdbb77d
            // new version of flutter can not invoke method from background thread
            if (location != null) {
                val callback =
                    context?.let {
                        PreferencesManager.getCallbackHandle(
                            it,
                            Keys.CALLBACK_HANDLE_KEY
                        )
                    } as Long

                val result: HashMap<Any, Any> =
                    hashMapOf(
                        Keys.ARG_CALLBACK to callback,
                        Keys.ARG_LOCATION to location
                    )

                sendLocationEvent(result)
            }
        } catch (e: Exception) {

        }
    }

    private fun sendLocationEvent(result: HashMap<Any, Any>) {
        //https://github.com/flutter/plugins/pull/1641
        //https://github.com/flutter/flutter/issues/36059
        //https://github.com/flutter/plugins/pull/1641/commits/4358fbba3327f1fa75bc40df503ca5341fdbb77d
        // new version of flutter can not invoke method from background thread

        if (backgroundEngine != null) {
            context?.let {
                val backgroundChannel =
                    MethodChannel(
                        getBinaryMessenger(it)!!,
                        Keys.BACKGROUND_CHANNEL_ID
                    )
                Handler(it.mainLooper)
                    .post {
                        Log.d("plugin", "sendLocationEvent $result")
                        backgroundChannel.invokeMethod(Keys.BCM_SEND_LOCATION, result)
                    }
            }
        }
    }
}
