package pl.wojciechbaran.tomtomwatch

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.*
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import hirondelle.date4j.DateTime
import net.studioblueplanet.generics.SerialExecutor
import net.studioblueplanet.generics.ThreadExecutor
import net.studioblueplanet.logger.DebugLogger
import net.studioblueplanet.settings.ConfigSettings
import net.studioblueplanet.tomtomwatch.ActivityData
import net.studioblueplanet.tomtomwatch.CommunicationProcess
import net.studioblueplanet.tomtomwatch.GpxReader
import net.studioblueplanet.tomtomwatch.MapOsmDroid
import net.studioblueplanet.tomtomwatch.Route
import net.studioblueplanet.tomtomwatch.ThreadCommand
import net.studioblueplanet.tomtomwatch.TomTomWatchView
import net.studioblueplanet.tomtomwatch.TtbinFileWriter
import net.studioblueplanet.ttbin.GpxWriter
import net.studioblueplanet.ttbin.TomTomReader
import net.studioblueplanet.usb.UsbConnection
import net.studioblueplanet.usb.UsbFile
import net.studioblueplanet.usb.UsbInterface
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.views.MapView
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class MainActivity : Activity() {

    private val ACTION_USB_PERMISSION = "pl.wojciechbaran.tomtomwatch.USB_PERMISSION"
    private val REQUEST_CODE_SELECT_FOLDER = 1001
    private val REQUEST_CODE_EXPORT_GPX = 1002
    private val REQUEST_CODE_SETTINGS_FOLDER = 1003
    
    private val PREF_CONFIGURED = "app_configured"
    private val PREF_DEST_FOLDER_URI = "dest_folder_uri"
    private val PREF_STRAVA_TOKEN = "strava_access_token"
    private val PREF_STRAVA_REFRESH_TOKEN = "strava_refresh_token"
    private val PREF_STRAVA_EXPIRES_AT = "strava_expires_at"
    private val PREF_STRAVA_USERNAME = "strava_username"
    private val PREF_STRAVA_CLIENT_ID = "strava_client_id"
    private val PREF_STRAVA_CLIENT_SECRET = "strava_client_secret"

    private val STRAVA_REDIRECT_URI = "${BuildConfig.STRAVA_REDIRECT_SCHEME}://${BuildConfig.STRAVA_REDIRECT_HOST}"

    private lateinit var usbManager: UsbManager
    
    // View Containers
    private lateinit var preConnectView: View
    private lateinit var connectedView: View
    private lateinit var offlineView: View
    private lateinit var settingsView: View
    private lateinit var getActivitiesContainer: View
    private lateinit var activitiesListArea: View
    private lateinit var offlineListContainer: View
    private lateinit var offlinePreviewContainer: View
    
    // Global Header
    private lateinit var headerTitle: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var btnHeaderDisconnect: Button
    private lateinit var btnHeaderBack: Button

    // Pre-connect Views
    private lateinit var deviceListView: ListView
    private lateinit var preConnectInfo: TextView
    private lateinit var cacheSizeInfo: TextView
    private lateinit var btnQuit: Button
    private lateinit var btnOfflineMode: Button
    private lateinit var btnStravaLogin: Button

    // Connected Views (Online)
    private lateinit var fileListView: ListView
    private lateinit var watchTimeVal: TextView
    private lateinit var productIdVal: TextView
    private lateinit var firmwareVal: TextView
    private lateinit var serialVal: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnGetActivities: Button
    private lateinit var btnDownloadAll: Button
    private lateinit var btnGpsQuickFix: Button
    private lateinit var mapView: MapView
    
    // Offline Views
    private lateinit var offlineFileListView: ListView
    private lateinit var offlineMapView: MapView
    private lateinit var btnExportGpx: Button
    private lateinit var btnExportStrava: Button
    private lateinit var btnClosePreview: Button
    private lateinit var offlineActivityInfo: TextView
    
    // Settings Views
    private lateinit var settingsStoragePath: TextView
    private lateinit var btnSettingsSelectFolder: Button
    private lateinit var editSettingsStravaId: EditText
    private lateinit var editSettingsStravaSecret: EditText
    private lateinit var btnSettingsSave: Button
    private lateinit var checkAdvancedGuard: CheckBox
    private lateinit var advancedSettingsArea: View
    private lateinit var advancedSettingsContainer: LinearLayout

    // Global Views
    private lateinit var statusText: TextView
    private lateinit var statusScroll: ScrollView
    private lateinit var stravaUserText: TextView
    
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private lateinit var fileAdapter: ArrayAdapter<String>
    private lateinit var offlineFileAdapter: ArrayAdapter<String>
    
    private val deviceList = mutableListOf<UsbDevice>()
    private val activityDataList = mutableListOf<ActivityData>()
    private val offlineFileList = mutableListOf<OfflineFileEntry>()

    // Persistent USB Connection state
    private var connectedDevice: UsbDevice? = null
    private var activeDeviceConnection: UsbDeviceConnection? = null
    private var activeUsbInterface: android.hardware.usb.UsbInterface? = null
    private var activeWatchInterface: UsbInterface? = null
    
    // Business Logic components
    private var commProcess: CommunicationProcess? = null
    private var watchView: AndroidWatchView? = null
    private var offlineMapOsm: MapOsmDroid? = null

    private var selectedActivityIndex: Int = -1
    private var selectedOfflineEntry: OfflineFileEntry? = null
    private var destFolderUri: Uri? = null

    private val httpClient = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class OfflineFileEntry(val file: DocumentFile, val displayString: String, val dateTime: String, val serial: String)

    private data class StravaUploadParams(
        val name: String,
        val description: String,
        val externalId: String,
        val trainer: String,
        val commute: String,
        val useGzip: Boolean
    )

    inner class AndroidWatchView(process: CommunicationProcess?) : TomTomWatchView(process) {
        private val mapOsm = if (::mapView.isInitialized) MapOsmDroid(mapView) else null

        override fun showTrack(data: ActivityData?) {
            runOnUiThread {
                data?.let { mapOsm?.showTrack(it) }
            }
        }

        override fun showTrack(route: Route?) {
            runOnUiThread {
                route?.let { mapOsm?.showTrack(it) }
            }
        }

        override fun isDownloadMostRecent(): Boolean {
            return false
        }

        override fun clear() {
            runOnUiThread { 
                fileAdapter.clear()
                activityDataList.clear()
                selectedActivityIndex = -1
                mapOsm?.hideTrack()
            }
        }

        override fun showErrorDialog(message: String?) {
            runOnUiThread { Toast.makeText(this@MainActivity, "Error: $message", Toast.LENGTH_LONG).show() }
        }

        override fun showWarningDialog(message: String?) {
            runOnUiThread { Toast.makeText(this@MainActivity, "Warning: $message", Toast.LENGTH_SHORT).show() }
        }

        override fun showInfoDialog(message: String?) {
            runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
        }

        override fun showConfirmDialog(message: String?): Boolean {
            val latch = CountDownLatch(1)
            var result = false
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Confirm")
                    .setMessage(message)
                    .setPositiveButton("Yes") { _, _ -> result = true; latch.countDown() }
                    .setNegativeButton("No") { _, _ -> result = false; latch.countDown() }
                    .setOnCancelListener { latch.countDown() }
                    .show()
            }
            try {
                latch.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return result
        }

        override fun clearRoutes() {}

        override fun showTime(time: DateTime?) {
            runOnUiThread { watchTimeVal.text = time?.format("YYYY-MM-DD hh:mm:ss") ?: "N/A" }
        }

        override fun setProgress(promile: Int) {
            runOnUiThread { progressBar.progress = promile }
        }

        override fun setStatus(status: String?) {
            runOnUiThread { statusText.text = "Status: $status" }
        }

        override fun appendStatus(status: String?) {
            runOnUiThread {
                statusText.append(status)
                statusScroll.post {
                    statusScroll.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        override fun isAutoSaveTtbin(): Boolean = false

        override fun addListItem(data: ActivityData?, prefix: String?) {
            runOnUiThread {
                data?.let {
                    activityDataList.add(it)
                    fileAdapter.add(getActivityDescription(it, prefix))
                }
            }
        }

        override fun selectFirstListIndex() {
            runOnUiThread {
                if (fileAdapter.count > 0) {
                    fileListView.setSelection(0)
                    selectedActivityIndex = 0
                }
            }
        }

        override fun selectLastListIndex() {
            runOnUiThread {
                if (fileAdapter.count > 0) {
                    fileListView.setSelection(fileAdapter.count - 1)
                    selectedActivityIndex = fileAdapter.count - 1
                }
            }
        }

        override fun setDeviceName(deviceName: String?) {
            runOnUiThread { 
                if (connectedView.isVisible) {
                    headerTitle.text = deviceName ?: "Connected Watch"
                }
            }
        }

        override fun setFirmwareVersion(version: String?) {
            runOnUiThread { firmwareVal.text = version ?: "Unknown" }
        }

        override fun setSerial(serial: String?) {
            runOnUiThread { serialVal.text = serial ?: "N/A" }
        }

        override fun setProductId(id: Int) {
            runOnUiThread { productIdVal.text = "0x${Integer.toHexString(id)}" }
        }

        override fun addRoutesToListBox(routes: ArrayList<UsbFile>?, index: Int) {
            runOnUiThread {
                routes?.forEach { file ->
                    fileAdapter.add("Route: 0x${Integer.toHexString(file.fileId)} (${file.length} bytes)")
                }
            }
        }

        override fun addRoutesToListBoxLater(routes: ArrayList<UsbFile>?, index: Int) {
            addRoutesToListBox(routes, index)
        }

        override fun enableRouteButtons(enabled: Boolean) {}
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) communicateWithDevice(device)
                    else Toast.makeText(context, "Permission denied", Toast.LENGTH_SHORT).show()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> populateDeviceList()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && device == connectedDevice) disconnect()
                    populateDeviceList()
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        
        // Initialize ConfigSettings with Context to load from assets/internal storage
        val settings = ConfigSettings.getInstance()
        settings.setContext(applicationContext)
        // Set the DebugLogger log level
        DebugLogger.setDebugLevel(settings.getStringValue("debugLevel"));
        
        // Apply OsmDroid Cache Size from settings
        val cacheSizeMb = settings.getIntValue("osmdroidCacheSize").toLong()
        Configuration.getInstance().tileFileSystemCacheMaxBytes = cacheSizeMb * 1024 * 1024

        setContentView(R.layout.activity_main)

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        
        // Initialize Containers
        preConnectView = findViewById(R.id.pre_connect_view)
        connectedView = findViewById(R.id.connected_view)
        offlineView = findViewById(R.id.offline_view)
        settingsView = findViewById(R.id.settings_view)
        getActivitiesContainer = findViewById(R.id.get_activities_container)
        activitiesListArea = findViewById(R.id.activities_list_area)
        offlineListContainer = findViewById(R.id.offline_list_container)
        offlinePreviewContainer = findViewById(R.id.offline_preview_container)
        
        // Global Header
        headerTitle = findViewById(R.id.header_title)
        btnSettings = findViewById(R.id.btn_settings)
        btnHeaderDisconnect = findViewById(R.id.btn_header_disconnect)
        btnHeaderBack = findViewById(R.id.btn_header_back)

        // Initialize Pre-connect components
        deviceListView = findViewById(R.id.device_list)
        preConnectInfo = findViewById(R.id.pre_connect_info)
        cacheSizeInfo = findViewById(R.id.cache_size_info)
        btnQuit = findViewById(R.id.btn_quit)
        btnOfflineMode = findViewById(R.id.btn_offline_mode)
        btnStravaLogin = findViewById(R.id.btn_strava_login)

        // Initialize Connected components (Online)
        fileListView = findViewById(R.id.file_list)
        watchTimeVal = findViewById(R.id.watch_time_val)
        productIdVal = findViewById(R.id.product_id_val)
        firmwareVal = findViewById(R.id.firmware_val)
        serialVal = findViewById(R.id.serial_val)
        progressBar = findViewById(R.id.progress_bar)
        btnGetActivities = findViewById(R.id.btn_get_activities)
        btnDownloadAll = findViewById(R.id.btn_download_all)
        btnGpsQuickFix = findViewById(R.id.btn_gps_quickfix)
        mapView = findViewById(R.id.map_view)
        
        // Initialize Offline components
        offlineFileListView = findViewById(R.id.offline_file_list)
        offlineMapView = findViewById(R.id.offline_map_view)
        btnExportGpx = findViewById(R.id.btn_export_gpx)
        btnExportStrava = findViewById(R.id.btn_export_strava)
        btnClosePreview = findViewById(R.id.btn_close_preview)
        offlineActivityInfo = findViewById(R.id.offline_activity_info)
        
        // Initialize Settings components
        settingsStoragePath = findViewById(R.id.settings_storage_path)
        btnSettingsSelectFolder = findViewById(R.id.btn_settings_select_folder)
        editSettingsStravaId = findViewById(R.id.edit_settings_strava_id)
        editSettingsStravaSecret = findViewById(R.id.edit_settings_strava_secret)
        btnSettingsSave = findViewById(R.id.btn_settings_save)
        checkAdvancedGuard = findViewById(R.id.check_advanced_guard)
        advancedSettingsArea = findViewById(R.id.advanced_settings_area)
        advancedSettingsContainer = findViewById(R.id.advanced_settings_container)

        // Initialize Global components
        statusText = findViewById(R.id.status_text)
        statusScroll = findViewById(R.id.status_scroll)
        stravaUserText = findViewById(R.id.strava_user_text)

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        deviceListView.adapter = deviceAdapter
        
        fileAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        fileListView.adapter = fileAdapter
        
        offlineFileAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        offlineFileListView.adapter = offlineFileAdapter

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }

        btnSettings.setOnClickListener { showSettings() }
        btnSettingsSave.setOnClickListener { saveSettings() }
        btnSettingsSelectFolder.setOnClickListener { selectSettingsFolder() }
        btnHeaderBack.setOnClickListener { 
            if (settingsView.isVisible && !isConfigured()) {
                finish()
            } else {
                switchToMainMode() 
            }
        }
        btnHeaderDisconnect.setOnClickListener { disconnect() }

        btnQuit.setOnClickListener { finish() }
        btnOfflineMode.setOnClickListener { switchToOfflineMode() }
        btnGetActivities.setOnClickListener { fetchActivities() }
        btnDownloadAll.setOnClickListener { downloadAllActivities() }
        btnGpsQuickFix.setOnClickListener { uploadGpsQuickFix() }
        btnClosePreview.setOnClickListener { closeOfflinePreview() }
        btnExportGpx.setOnClickListener { startExportGpx() }
        btnExportStrava.setOnClickListener { showStravaExportDialog() }

        btnStravaLogin.setOnClickListener {
            val prefs = getPreferences(MODE_PRIVATE)
            if (prefs.contains(PREF_STRAVA_TOKEN)) {
                stravaLogout()
            } else {
                startStravaAuth()
            }
        }

        checkAdvancedGuard.setOnCheckedChangeListener { _, isChecked ->
            advancedSettingsArea.isVisible = isChecked
        }

        loadDestinationFolder()
        loadStravaInfo()
        updateStravaButtonsState()
        populateDeviceList()

        deviceListView.setOnItemClickListener { _, _, position, _ ->
            requestUsbPermission(deviceList[position])
        }

        fileListView.setOnItemClickListener { _, _, position, _ ->
            if (position < activityDataList.size) {
                selectedActivityIndex = position
                watchView?.showTrack(activityDataList[position])
            }
        }

        offlineFileListView.setOnItemClickListener { _, _, position, _ ->
            showOfflinePreview(offlineFileList[position])
        }

        if (!isConfigured()) {
            showSettings()
        } else {
            switchToMainMode()
        }

        handleIntent(intent)
        updateCacheSizeDisplay()
    }

    private fun isConfigured(): Boolean {
        return getPreferences(MODE_PRIVATE).getBoolean(PREF_CONFIGURED, false)
    }

    private fun showSettings() {
        preConnectView.isVisible = false
        connectedView.isVisible = false
        offlineView.isVisible = false
        settingsView.isVisible = true
        
        headerTitle.text = "Configuration"
        btnSettings.isVisible = false
        btnHeaderBack.isVisible = true
        btnHeaderDisconnect.isVisible = false

        settingsStoragePath.text = destFolderUri?.toString() ?: "Not set"
        editSettingsStravaId.setText(getStravaClientId())
        editSettingsStravaSecret.setText(getStravaClientSecret())

        checkAdvancedGuard.isChecked = false
        advancedSettingsArea.isVisible = false
        populateAdvancedSettings()
    }

    private fun populateAdvancedSettings() {
        advancedSettingsContainer.removeAllViews()
        val settingsList = ConfigSettings.getInstance().settings
        
        for (setting in settingsList) {
            val label = TextView(this)
            label.text = "${setting.name.trim()} (${setting.type.name})"
            label.setPadding(0, 24, 0, 8)
            label.setTypeface(null, Typeface.BOLD)
            advancedSettingsContainer.addView(label)

            val desc = TextView(this)
            desc.text = setting.description.trim()
            desc.textSize = 12f
            desc.setPadding(0, 0, 0, 8)
            advancedSettingsContainer.addView(desc)

            if (setting.type == ConfigSettings.SettingType.BOOLEAN) {
                val switch = Switch(this)
                switch.isChecked = setting.value == "true" || setting.value == "yes" || setting.value == "1"
                switch.tag = setting.name
                advancedSettingsContainer.addView(switch)
            } else {
                val edit = EditText(this)
                edit.setText(setting.value)
                edit.tag = setting.name
                edit.hint = "Default: ${setting.defaultValue}"
                advancedSettingsContainer.addView(edit)
            }
        }
    }

    private fun saveSettings() {
        val stravaId = editSettingsStravaId.text.toString()
        val stravaSecret = editSettingsStravaSecret.text.toString()

        if (destFolderUri == null) {
            Toast.makeText(this, "Please select storage directory", Toast.LENGTH_SHORT).show()
            return
        }

        // Save Strava and Basic Settings
        getPreferences(MODE_PRIVATE).edit().apply {
            putString(PREF_STRAVA_CLIENT_ID, stravaId)
            putString(PREF_STRAVA_CLIENT_SECRET, stravaSecret)
            putBoolean(PREF_CONFIGURED, true)
            apply()
        }

        // Save Advanced Settings ONLY if guarded section is visible
        if (advancedSettingsArea.isVisible) {
            val config = ConfigSettings.getInstance()
            for (i in 0 until advancedSettingsContainer.childCount) {
                val view = advancedSettingsContainer.getChildAt(i)
                val name = view.tag as? String ?: continue
                
                val value = when (view) {
                    is Switch -> if (view.isChecked) "true" else "false"
                    is EditText -> view.text.toString()
                    else -> null
                }
                
                if (value != null) {
                    config.updateSetting(name, value)
                }
            }
            config.saveSettings()
            DebugLogger.setDebugLevel(config.getStringValue("debugLevel"))
            
            // Re-apply OsmDroid Cache Size
            val cacheSizeMb = config.getIntValue("osmdroidCacheSize").toLong()
            Configuration.getInstance().tileFileSystemCacheMaxBytes = cacheSizeMb * 1024 * 1024
        }

        Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show()
        
        updateStravaButtonsState()
        switchToMainMode()
    }

    private fun selectSettingsFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQUEST_CODE_SETTINGS_FOLDER)
    }

    private fun getStravaClientId(): String {
        val prefs = getPreferences(MODE_PRIVATE)
        val id = prefs.getString(PREF_STRAVA_CLIENT_ID, null)
        return if (id.isNullOrBlank()) BuildConfig.STRAVA_CLIENT_ID else id
    }

    private fun getStravaClientSecret(): String {
        val prefs = getPreferences(MODE_PRIVATE)
        val secret = prefs.getString(PREF_STRAVA_CLIENT_SECRET, null)
        return if (secret.isNullOrBlank()) BuildConfig.STRAVA_CLIENT_SECRET else secret
    }

    private fun updateStravaButtonsState() {
        val hasClientId = getStravaClientId().isNotBlank()
        val hasToken = getPreferences(MODE_PRIVATE).contains(PREF_STRAVA_TOKEN)
        
        btnStravaLogin.isEnabled = hasClientId || hasToken
        btnExportStrava.isEnabled = hasClientId && hasToken
        
        btnStravaLogin.alpha = if (btnStravaLogin.isEnabled) 1.0f else 0.5f
        btnExportStrava.alpha = if (btnExportStrava.isEnabled) 1.0f else 0.5f
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null && uri.toString().startsWith(STRAVA_REDIRECT_URI)) {
                val code = uri.getQueryParameter("code")
                if (code != null) {
                    exchangeStravaCode(code)
                } else {
                    val error = uri.getQueryParameter("error")
                    if (error != null) {
                        Toast.makeText(this, "Strava Login Error: $error", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun startStravaAuth() {
        val clientId = getStravaClientId()
        if (clientId.isBlank()) {
            Toast.makeText(this, "Strava Client ID not configured", Toast.LENGTH_LONG).show()
            showSettings()
            return
        }

        val url = "https://www.strava.com/oauth/mobile/authorize" +
                "?client_id=$clientId" +
                "&redirect_uri=${Uri.encode(STRAVA_REDIRECT_URI)}" +
                "&response_type=code" +
                "&approval_prompt=auto" +
                "&scope=read,activity:write"
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        startActivity(intent)
    }

    private fun exchangeStravaCode(code: String) {
        val clientId = getStravaClientId()
        val clientSecret = getStravaClientSecret()

        val formBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .build()

        val request = Request.Builder()
            .url("https://www.strava.com/oauth/token")
            .post(formBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Token exchange failed", Toast.LENGTH_SHORT).show() }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val body = it.body?.string() ?: ""
                        runOnUiThread { 
                            Log.e("Strava", "Token exchange error: $body")
                            Toast.makeText(this@MainActivity, "Token exchange failed: ${it.code}", Toast.LENGTH_SHORT).show() 
                        }
                        return
                    }
                    val body = it.body?.string() ?: return
                    val json = JSONObject(body)
                    val accessToken = json.getString("access_token")
                    val refreshToken = json.getString("refresh_token")
                    val expiresAt = json.getLong("expires_at")
                    val athlete = json.getJSONObject("athlete")
                    val firstName = athlete.optString("firstname", "")
                    val lastName = athlete.optString("lastname", "")
                    val username = "$firstName $lastName".trim()

                    getPreferences(MODE_PRIVATE).edit()
                        .putString(PREF_STRAVA_TOKEN, accessToken)
                        .putString(PREF_STRAVA_REFRESH_TOKEN, refreshToken)
                        .putLong(PREF_STRAVA_EXPIRES_AT, expiresAt)
                        .putString(PREF_STRAVA_USERNAME, username)
                        .apply()

                    runOnUiThread {
                        loadStravaInfo()
                        updateStravaButtonsState()
                        Toast.makeText(this@MainActivity, "Logged into Strava as $username", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun refreshStravaToken(onComplete: (Boolean) -> Unit) {
        val clientId = getStravaClientId()
        val clientSecret = getStravaClientSecret()
        val refreshToken = getPreferences(MODE_PRIVATE).getString(PREF_STRAVA_REFRESH_TOKEN, null)

        if (refreshToken == null) {
            onComplete(false)
            return
        }

        val formBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url("https://www.strava.com/oauth/token")
            .post(formBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onComplete(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        if (it.code == 400 || it.code == 401) {
                            runOnUiThread { stravaLogout() }
                        }
                        onComplete(false)
                        return
                    }
                    val body = it.body?.string() ?: return
                    val json = JSONObject(body)
                    val newAccessToken = json.getString("access_token")
                    val newRefreshToken = json.getString("refresh_token")
                    val newExpiresAt = json.getLong("expires_at")

                    getPreferences(MODE_PRIVATE).edit()
                        .putString(PREF_STRAVA_TOKEN, newAccessToken)
                        .putString(PREF_STRAVA_REFRESH_TOKEN, newRefreshToken)
                        .putLong(PREF_STRAVA_EXPIRES_AT, newExpiresAt)
                        .apply()
                    onComplete(true)
                }
            }
        })
    }

    private fun ensureStravaToken(onReady: (String?) -> Unit) {
        val prefs = getPreferences(MODE_PRIVATE)
        val token = prefs.getString(PREF_STRAVA_TOKEN, null)
        val expiresAt = prefs.getLong(PREF_STRAVA_EXPIRES_AT, 0)
        
        // Refresh if expired or expires in less than 5 minutes
        if (token != null && (expiresAt * 1000) < (System.currentTimeMillis() + 300000)) {
            refreshStravaToken { success ->
                if (success) {
                    onReady(getPreferences(MODE_PRIVATE).getString(PREF_STRAVA_TOKEN, null))
                } else {
                    onReady(null)
                }
            }
        } else {
            onReady(token)
        }
    }

    private fun loadStravaInfo() {
        val prefs = getPreferences(MODE_PRIVATE)
        val username = prefs.getString(PREF_STRAVA_USERNAME, null)
        val token = prefs.getString(PREF_STRAVA_TOKEN, null)
        if (token != null && username != null) {
            stravaUserText.text = "Strava: $username"
            btnStravaLogin.text = "Logout from Strava"
        } else {
            stravaUserText.text = "Strava: Not Logged In"
            btnStravaLogin.text = "Login to Strava"
        }
    }

    private fun stravaLogout() {
        getPreferences(MODE_PRIVATE).edit()
            .remove(PREF_STRAVA_TOKEN)
            .remove(PREF_STRAVA_REFRESH_TOKEN)
            .remove(PREF_STRAVA_EXPIRES_AT)
            .remove(PREF_STRAVA_USERNAME)
            .apply()
        loadStravaInfo()
        updateStravaButtonsState()
        Toast.makeText(this, "Logged out from Strava", Toast.LENGTH_SHORT).show()
    }

    private fun switchToOfflineMode() {
        if (destFolderUri == null) {
            Toast.makeText(this, "Please set destination folder first", Toast.LENGTH_LONG).show()
            showSettings()
            return
        }
        preConnectView.isVisible = false
        connectedView.isVisible = false
        settingsView.isVisible = false
        offlineView.isVisible = true
        
        headerTitle.text = "Browse Local Storage"
        btnSettings.isVisible = false
        btnHeaderBack.isVisible = true
        btnHeaderDisconnect.isVisible = false
        
        updateStravaButtonsState()
        loadOfflineActivities()
    }

    private fun switchToMainMode() {
        offlineView.isVisible = false
        connectedView.isVisible = false
        settingsView.isVisible = false
        preConnectView.isVisible = true
        
        headerTitle.text = "TomTomWatch"
        btnSettings.isVisible = true
        btnHeaderBack.isVisible = false
        btnHeaderDisconnect.isVisible = false

        updateStravaButtonsState()
        updateCacheSizeDisplay()
    }

    private fun loadDestinationFolder() {
        val prefs = getPreferences(MODE_PRIVATE)
        val uriString = prefs.getString(PREF_DEST_FOLDER_URI, null)
        if (uriString != null) {
            destFolderUri = Uri.parse(uriString)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == REQUEST_CODE_SELECT_FOLDER || requestCode == REQUEST_CODE_SETTINGS_FOLDER) && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                destFolderUri = uri
                getPreferences(MODE_PRIVATE).edit().putString(PREF_DEST_FOLDER_URI, uri.toString()).apply()
                
                if (requestCode == REQUEST_CODE_SETTINGS_FOLDER) {
                    settingsStoragePath.text = uri.toString()
                }
            }
        } else if (requestCode == REQUEST_CODE_EXPORT_GPX && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                performGpxExport(uri)
            }
        }
    }

    private fun downloadAllActivities() {
        if (destFolderUri == null) {
            Toast.makeText(this, "Set folder first", Toast.LENGTH_SHORT).show()
            showSettings()
            return
        }
        Thread {
            activityDataList.forEach { data ->
                saveActivityToStorage(data)
            }
            runOnUiThread { Toast.makeText(this, "Download Complete", Toast.LENGTH_SHORT).show() }
        }.start()
    }

    private fun saveActivityToStorage(data: ActivityData) {
        val deviceSerial = data.activity?.deviceSerialNumber ?: "UnknownSerial"
        val dateStr = data.activity?.startDateTime?.format("YYYY-MM-DD") ?: "UnknownDate"
        val type = data.activity?.activityDescription ?: "Activity"
        val timeStr = data.activity?.startDateTime?.format("hh-mm-ss") ?: "00-00-00"
        val fileName = "${type}_${timeStr}.ttbin.gz"

        val rootDoc = destFolderUri?.let { DocumentFile.fromTreeUri(this, it) } ?: return
        val deviceDir = rootDoc.findFile(deviceSerial) ?: rootDoc.createDirectory(deviceSerial)
        val ttbinDir = deviceDir?.findFile("ttbin") ?: deviceDir?.createDirectory("ttbin")
        val dateDir = ttbinDir?.findFile(dateStr) ?: ttbinDir?.createDirectory(dateStr)

        if (dateDir?.findFile(fileName) != null) {
            watchView?.appendStatus("Skipped existing: $fileName\n")
            return
        }

        val file = dateDir?.createFile("application/gzip", fileName) ?: return
        try {
            contentResolver.openOutputStream(file.uri)?.use { os ->
                TtbinFileWriter.getInstance().writeGzippedTtbinToStream(os, data.file)
                watchView?.appendStatus("Saved: $fileName\n")
            }
        } catch (e: Exception) {
            Log.e("USB", "Error saving $fileName", e)
        }
    }

    private fun loadOfflineActivities() {
        val rootDoc = destFolderUri?.let { DocumentFile.fromTreeUri(this, it) } ?: return
        val results = mutableListOf<OfflineFileEntry>()

        rootDoc.listFiles().forEach { serialDir ->
            if (serialDir.isDirectory) {
                val serial = serialDir.name ?: "Unknown"
                val ttbinDir = serialDir.findFile("ttbin")
                ttbinDir?.listFiles()?.forEach { dateDir ->
                    if (dateDir.isDirectory) {
                        val dateStr = dateDir.name ?: ""
                        dateDir.listFiles().forEach { file ->
                            if (file.name?.endsWith(".ttbin.gz") == true) {
                                val fileName = file.name ?: ""
                                val parts = fileName.removeSuffix(".ttbin.gz").split("_")
                                val type = parts.getOrNull(0) ?: "Activity"
                                val timeStr = parts.getOrNull(1)?.replace("-", ":") ?: "00:00:00"
                                
                                val fullDateTime = "$dateStr $timeStr"
                                val display = "$fullDateTime - $type ($serial)"
                                
                                results.add(OfflineFileEntry(file, display, fullDateTime, serial))
                            }
                        }
                    }
                }
            }
        }

        results.sortByDescending { it.dateTime }

        offlineFileAdapter.clear()
        offlineFileList.clear()
        results.forEach {
            offlineFileList.add(it)
            offlineFileAdapter.add(it.displayString)
        }
    }

    private fun showOfflinePreview(entry: OfflineFileEntry) {
        selectedOfflineEntry = entry
        offlineListContainer.isVisible = false
        offlinePreviewContainer.isVisible = true
        offlineActivityInfo.text = "Loading..."
        
        Thread {
            try {
                contentResolver.openInputStream(entry.file.uri)?.use { bis ->
                    GZIPInputStream(bis).use { gzis ->
                        val bytes = gzis.readBytes()
                        val usbFile = UsbFile()
                        usbFile.fileData = bytes
                        usbFile.length = bytes.size
                        
                        val activity = TomTomReader.getInstance().readTtbinFile(usbFile)
                        if (activity == null) {
                            runOnUiThread { offlineActivityInfo.text = "Error: Could not parse activity data" }
                            return@use
                        }

                        val data = ActivityData()
                        data.activity = activity
                        data.file = usbFile
                        data.ttbinSaved = true

                        runOnUiThread {
                            if (offlineMapOsm == null) {
                                offlineMapOsm = MapOsmDroid(offlineMapView)
                            }
                            offlineMapOsm?.showTrack(data)
                            
                            offlineActivityInfo.text = TomTomWatchView.getActivityDescription(data, "local")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Offline", "Error previewing", e)
                runOnUiThread { offlineActivityInfo.text = "Error loading activity data" }
            }
        }.start()
    }

    private fun closeOfflinePreview() {
        offlinePreviewContainer.isVisible = false
        offlineListContainer.isVisible = true
        selectedOfflineEntry = null
        offlineActivityInfo.text = ""
        offlineMapOsm?.hideTrack()
    }

    private fun startExportGpx() {
        val entry = selectedOfflineEntry ?: return
        val name = entry.file.name?.replace(".ttbin.gz", ".gpx") ?: "activity.gpx"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, REQUEST_CODE_EXPORT_GPX)
    }

    private fun performGpxExport(uri: Uri) {
        val entry = selectedOfflineEntry ?: return
        Thread {
            try {
                contentResolver.openInputStream(entry.file.uri)?.use { bis ->
                    GZIPInputStream(bis).use { gzis ->
                        val bytes = gzis.readBytes()
                        val usbFile = UsbFile()
                        usbFile.fileData = bytes
                        val activity = TomTomReader.getInstance().readTtbinFile(usbFile)
                        
                        contentResolver.openOutputStream(uri)?.use { os ->
                            GpxWriter.getInstance().writeTrackToStream(os, activity, "TomTomWatch4Android")
                            runOnUiThread { Toast.makeText(this@MainActivity, "Exported Successfully", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Export", "Error exporting GPX", e)
            }
        }.start()
    }

    private fun showStravaExportDialog() {
        val entry = selectedOfflineEntry ?: return
        val token = getPreferences(MODE_PRIVATE).getString(PREF_STRAVA_TOKEN, null)
        if (token == null) {
            Toast.makeText(this, "Please login to Strava first", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            try {
                contentResolver.openInputStream(entry.file.uri)?.use { bis ->
                    GZIPInputStream(bis).use { gzis ->
                        val bytes = gzis.readBytes()
                        val usbFile = UsbFile()
                        usbFile.fileData = bytes
                        val activity = TomTomReader.getInstance().readTtbinFile(usbFile) ?: return@use

                        val serial = entry.serial
                        val type = activity.activityDescription ?: "Activity"
                        val startTime = activity.startDateTime?.format("YYYYMMDD_hhmmss") ?: "UnknownTime"
                        val externalId = "${serial}_${type}_${startTime}"

                        runOnUiThread {
                            val view = LayoutInflater.from(this).inflate(R.layout.dialog_strava_export, null)
                            val editName = view.findViewById<EditText>(R.id.edit_strava_name)
                            val editDescription = view.findViewById<EditText>(R.id.edit_strava_description)
                            val editExternalId = view.findViewById<EditText>(R.id.edit_strava_external_id)
                            val checkTrainer = view.findViewById<CheckBox>(R.id.check_strava_trainer)
                            val checkCommute = view.findViewById<CheckBox>(R.id.check_strava_commute)
                            val checkGzip = view.findViewById<CheckBox>(R.id.check_strava_gzip)

                            editExternalId.setText(externalId)

                            AlertDialog.Builder(this)
                                .setTitle("Export to Strava")
                                .setView(view)
                                .setPositiveButton("Upload") { _, _ ->
                                    val params = StravaUploadParams(
                                        name = editName.text.toString(),
                                        description = editDescription.text.toString(),
                                        externalId = editExternalId.text.toString(),
                                        trainer = if (checkTrainer.isChecked) "1" else "0",
                                        commute = if (checkCommute.isChecked) "1" else "0",
                                        useGzip = checkGzip.isChecked
                                    )
                                    performStravaExport(params)
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Strava", "Dialog prep error", e)
            }
        }.start()
    }

    private fun performStravaExport(params: StravaUploadParams) {
        val entry = selectedOfflineEntry ?: return
        val token = getPreferences(MODE_PRIVATE).getString(PREF_STRAVA_TOKEN, null) ?: return

        ensureStravaToken { token ->
            if (token == null) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to refresh Strava token. Please login again.", Toast.LENGTH_LONG).show()
                }
                return@ensureStravaToken
            }

            Thread {
                var tempFile: File? = null
                try {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Preparing upload...", Toast.LENGTH_SHORT).show()
                        watchView?.appendStatus("Strava: Preparing upload...\n")
                    }

                    contentResolver.openInputStream(entry.file.uri)?.use { bis ->
                        GZIPInputStream(bis).use { gzis ->
                            val bytes = gzis.readBytes()
                            val usbFile = UsbFile()
                            usbFile.fileData = bytes
                            val activity = TomTomReader.getInstance().readTtbinFile(usbFile) ?: return@use

                            tempFile = File.createTempFile("strava_upload", if (params.useGzip) ".gpx.gz" else ".gpx", cacheDir)
                            
                            FileOutputStream(tempFile).use { fos ->
                                if (params.useGzip) {
                                    GZIPOutputStream(fos).use { gzos ->
                                        GpxWriter.getInstance().writeTrackToStream(gzos, activity, "TomTomWatch4Android")
                                    }
                                } else {
                                    GpxWriter.getInstance().writeTrackToStream(fos, activity, "TomTomWatch4Android")
                                }
                            }

                            val builder = MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addFormDataPart("file", tempFile!!.name,
                                    tempFile!!.asRequestBody("application/octet-stream".toMediaType()))
                                .addFormDataPart("trainer", params.trainer)
                                .addFormDataPart("commute", params.commute)
                                .addFormDataPart("data_type", if (params.useGzip) "gpx.gz" else "gpx")
                                .addFormDataPart("external_id", params.externalId)

                            if (params.name.isNotBlank()) {
                                builder.addFormDataPart("name", params.name)
                            }
                            if (params.description.isNotBlank()) {
                                builder.addFormDataPart("description", params.description)
                            }

                            val requestBody = builder.build()

                            val request = Request.Builder()
                                .url("https://www.strava.com/api/v3/uploads")
                                .header("Authorization", "Bearer $token")
                                .post(requestBody)
                                .build()

                            httpClient.newCall(request).enqueue(object : Callback {
                                override fun onFailure(call: Call, e: IOException) {
                                    tempFile?.delete()
                                    runOnUiThread { 
                                        val msg = "Upload failed: ${e.message}"
                                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() 
                                        watchView?.appendStatus("Strava: $msg\n")
                                    }
                                }
                                override fun onResponse(call: Call, response: Response) {
                                    tempFile?.delete()
                                    response.use {
                                        if (it.isSuccessful) {
                                            val body = it.body?.string() ?: ""
                                            val json = JSONObject(body)
                                            val uploadId = json.optLong("id")
                                            runOnUiThread { 
                                                watchView?.appendStatus("Strava: Upload started (ID: $uploadId). Polling status...\n")
                                                pollUploadStatus(uploadId)
                                            }
                                        } else {
                                            val errBody = it.body?.string() ?: ""
                                            runOnUiThread { 
                                                val msg = "Upload failed: ${it.code} $errBody"
                                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() 
                                                watchView?.appendStatus("Strava: $msg\n")
                                            }
                                        }
                                    }
                                }
                            })
                        }
                    }
                } catch (e: Exception) {
                    tempFile?.delete()
                    Log.e("Strava", "Export error", e)
                    runOnUiThread { watchView?.appendStatus("Strava Error: ${e.message}\n") }
                }
            }.start()
        }
    }

    private fun pollUploadStatus(uploadId: Long) {
        ensureStravaToken { token ->
            if (token == null) {
                runOnUiThread { 
                    watchView?.appendStatus("Strava: Polling failed - could not refresh token.\n")
                }
                return@ensureStravaToken
            }

            val request = Request.Builder()
                .url("https://www.strava.com/api/v3/uploads/$uploadId")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { 
                        val msg = "Status check failed: ${e.message}"
                        watchView?.appendStatus("Strava: $msg\n")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) return
                        val body = it.body?.string() ?: return
                        val json = JSONObject(body)
                        val status = json.optString("status")
                        val error = json.optString("error")
                        val activityId = json.optLong("activity_id", 0L)

                        runOnUiThread {
                            watchView?.appendStatus("Strava Status: $status\n")
                            when {
                                error.isNotEmpty() && error != "null" -> {
                                    Toast.makeText(this@MainActivity, "Strava Error: $error", Toast.LENGTH_LONG).show()
                                    watchView?.appendStatus("Strava: Error details: $error\n")
                                }
                                status.contains("ready", ignoreCase = true) -> {
                                    Toast.makeText(this@MainActivity, "Strava activity ready! ID: $activityId", Toast.LENGTH_LONG).show()
                                    if (activityId != 0L) {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.strava.com/activities/$activityId"))
                                            startActivity(intent)
                                        } catch (e: Exception) {
                                            Log.e("Strava", "Failed to open Strava activity", e)
                                            watchView?.appendStatus("Strava: Activity ready but failed to open browser/app.\n")
                                        }
                                    }
                                }
                                else -> {
                                    // Continue polling after 2 seconds
                                    mainHandler.postDelayed({ pollUploadStatus(uploadId) }, 2000)
                                }
                            }
                        }
                    }
                }
            })
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) communicateWithDevice(device)
        else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun populateDeviceList() {
        val devices = usbManager.deviceList
        deviceAdapter.clear()
        deviceList.clear()
        if (devices.isEmpty()) {
            preConnectInfo.text = "No USB devices found.\n\nConnect your TomTom device via OTG."
            statusText.text = "Status: No device"
        } else {
            for (device in devices.values) {
                val manufacturer = device.manufacturerName ?: ""
                val product = device.productName ?: ""
                val vendorId = "0x%04X".format(device.vendorId)
                val productId = "0x%04X".format(device.productId)
                val serial = try {
                    if (usbManager.hasPermission(device)) device.serialNumber else null
                } catch (e: Exception) {
                    null
                }

                val title = if (manufacturer.isNotEmpty() || product.isNotEmpty()) {
                    "$manufacturer $product".trim()
                } else {
                    "USB Device"
                }
                
                val subtitle = buildString {
                    append("ID: $vendorId:$productId")
                    if (!serial.isNullOrBlank()) {
                        append("  S/N: $serial")
                    }
                }
                
                deviceAdapter.add("$title\n$subtitle")
                deviceList.add(device)
            }
            preConnectInfo.text = "Found ${devices.size} device(s). Tap one to connect."
            statusText.text = "Status: Found ${devices.size} device(s)"
        }
    }

    private fun communicateWithDevice(device: UsbDevice) {
        Thread {
            val intf = device.getInterface(0)
            val connection = usbManager.openDevice(device) ?: return@Thread
            if (connection.claimInterface(intf, true)) {
                try {
                    val usbConnection = UsbConnection(device, connection, intf)
                    val watchInterface = UsbInterface(usbConnection)
                    val process = CommunicationProcess(watchInterface, SerialExecutor(ThreadExecutor()), TomTomReader.getInstance(), GpxReader.getInstance())
                    val view = AndroidWatchView(process)
                    runOnUiThread {
                        connectedDevice = device
                        activeDeviceConnection = connection
                        activeUsbInterface = intf
                        activeWatchInterface = watchInterface
                        commProcess = process
                        watchView = view
                        preConnectView.isVisible = false
                        connectedView.isVisible = true
                        getActivitiesContainer.isVisible = true
                        activitiesListArea = findViewById(R.id.activities_list_area)
                        activitiesListArea.isVisible = false
                        
                        headerTitle.text = "Online Mode"
                        btnSettings.isVisible = false
                        btnHeaderBack.isVisible = false
                        btnHeaderDisconnect.isVisible = true
                        
                        process.startProcess(view)
                    }
                } catch (e: Exception) {
                    connection.releaseInterface(intf)
                    connection.close()
                }
            } else connection.close()
        }.start()
    }

    private fun fetchActivities() {
        runOnUiThread {
            getActivitiesContainer.isVisible = false
            activitiesListArea.isVisible = true
        }
        watchView?.checkAndPushCommand(ThreadCommand.THREADCOMMAND_DOWNLOADACTIVITIES)
    }

    private fun uploadGpsQuickFix() {
        watchView?.checkAndPushCommand(ThreadCommand.THREADCOMMAND_UPLOADGPSDATA)
    }

    private fun disconnect() {
        commProcess?.requestStop()
        activeWatchInterface?.closeConnection()
        activeDeviceConnection?.apply {
            activeUsbInterface?.let { releaseInterface(it) }
            close()
        }
        activeWatchInterface = null
        activeDeviceConnection = null
        activeUsbInterface = null
        connectedDevice = null
        commProcess = null
        watchView = null
        runOnUiThread {
            switchToMainMode()
            statusText.text = "Status: Disconnected"
            progressBar.progress = 0
            fileAdapter.clear()
            activityDataList.clear()
        }
    }

    private fun updateCacheSizeDisplay() {
        Thread {
            val cacheDir = Configuration.getInstance().osmdroidTileCache
            val sizeBytes = getFolderSize(cacheDir)
            val sizeKb = sizeBytes / 1024
            runOnUiThread {
                cacheSizeInfo.text = "Map Cache: $sizeKb KB"
            }
        }.start()
    }

    private fun getFolderSize(file: File): Long {
        var size: Long = 0
        if (file.exists()) {
            val files = file.listFiles()
            if (files != null) {
                for (f in files) {
                    size += if (f.isDirectory) {
                        getFolderSize(f)
                    } else {
                        f.length()
                    }
                }
            }
        }
        return size
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
        if (::offlineMapView.isInitialized) offlineMapView.onResume()
        updateCacheSizeDisplay()
    }

    override fun onPause() {
        super.onPause()
        if (::mapView.isInitialized) mapView.onPause()
        if (::offlineMapView.isInitialized) offlineMapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        disconnect()
    }
}
