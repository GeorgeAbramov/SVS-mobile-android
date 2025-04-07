package com.example.syntheticvision

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.syntheticvision.connection.BluetoothManager as SvsBluetoothManager
import com.example.syntheticvision.connection.WiFiManager
import com.example.syntheticvision.data.DataManager
import com.example.syntheticvision.data.MapManager
import com.example.syntheticvision.databinding.ActivityMainBinding
import com.example.syntheticvision.model.FlightData
import com.example.syntheticvision.view.FlightDataView
import com.example.syntheticvision.view.SyntheticVisionView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.reactivex.disposables.CompositeDisposable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.osmdroid.views.overlay.Marker
import org.osmdroid.util.GeoPoint

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private lateinit var syntheticVisionView: SyntheticVisionView
    private lateinit var syntheticVisionSystem: SyntheticVisionSystem
    
    // UI components
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var bluetoothButton: Button
    private lateinit var wifiButton: Button
    private lateinit var connectionDialog: CardView
    private lateinit var connectionDialogTitle: TextView
    private lateinit var addressInput: TextInputEditText
    private lateinit var portInput: TextInputEditText
    private lateinit var portInputLayout: TextInputLayout
    private lateinit var connectButton: Button
    private lateinit var cancelConnectionButton: Button
    private lateinit var altitudeText: TextView
    private lateinit var speedText: TextView
    private lateinit var headingText: TextView
    private lateinit var dataSourceText: TextView
    private lateinit var mapView: com.example.syntheticvision.view.MapView
    
    // Connection type
    private var connectionType = -1 // -1: none, 0: bluetooth, 1: wifi
    
    // RxJava components
    private val disposables = CompositeDisposable()
    
    // Имя файла данных рельефа в assets
    private val terrainFileName = "osm-2020-02-10-v3.11_europe_russia-european-part.mbtiles" 
    
    // Имя файла данных полета
    private val flightDataFileName = "navdata-for-parsing.csv"
    
    // System components
    private lateinit var bluetoothManager: SvsBluetoothManager
    private lateinit var wifiManager: WiFiManager
    
    // Флаг для отслеживания состояния симуляции
    private var isSimulationRunning = false
    
    // Путь к папке с картами
    private val mapDir = "/storage/emulated/0/Download/SVS_Maps"
    
    // Request codes for permissions
    private val REQUEST_PERMISSIONS_CODE = 1
    
    // Permission request launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            // All permissions granted
            if (connectionType == 0) {
                showBluetoothConnectionDialog()
            }
        } else {
            Toast.makeText(this, "Permissions required for Bluetooth connection", Toast.LENGTH_LONG).show()
        }
    }
    
    // Bluetooth enable launcher
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            showBluetoothConnectionDialog()
        } else {
            Toast.makeText(this, "Bluetooth must be enabled", Toast.LENGTH_LONG).show()
        }
    }
    
    // Маркер самолета на карте
    private lateinit var aircraftMarker: Marker
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check and request permissions
        checkAndRequestPermissions()
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize views
        setupViews()
        
        // Initialize the synthetic vision system
        syntheticVisionSystem = SyntheticVisionSystem(
            this,
            syntheticVisionView
        )
        
        // Инициализируем компоненты
        setupComponents()
        
        // Load terrain data
        try {
            // Загрузка данных рельефа
            val mapLoaded = syntheticVisionSystem.loadTerrainData(terrainFileName)
            if (mapLoaded) {
                Toast.makeText(this, "Terrain data loaded", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to load terrain data", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load terrain data: ${e.message}", Toast.LENGTH_LONG).show()
        }
        
        // Load flight data
        try {
            assets.open(flightDataFileName).use { inputStream ->
                syntheticVisionSystem.loadFlightData(inputStream)
            }
            Toast.makeText(this, "Flight data loaded", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Toast.makeText(this, "Failed to load flight data: ${e.message}", Toast.LENGTH_LONG).show()
        }
        
        // Set button click listeners
        setupButtonListeners()
        
        // Subscribe to flight data updates
        subscribeToFlightData()
    }
    
    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        // Check for required permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        // Add Bluetooth permissions based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }

        // Request permissions if needed
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS_CODE)
        }
    }
    
    private fun setupViews() {
        // Initialize synthetic vision view using view binding
        syntheticVisionView = binding.syntheticVisionView
        
        // Initialize map view
        mapView = binding.mapView
        
        // Настройка маркера самолета
        setupAircraftMarker()
        
        // Initialize other UI components
        startButton = binding.startButton
        stopButton = binding.stopButton
        bluetoothButton = binding.bluetoothButton
        wifiButton = binding.wifiButton
        connectionDialog = binding.connectionDialog
        connectionDialogTitle = binding.connectionDialogTitle
        addressInput = binding.addressInput
        portInput = binding.portInput
        portInputLayout = binding.portInputLayout
        connectButton = binding.connectButton
        cancelConnectionButton = binding.cancelConnectionButton
        altitudeText = binding.altitudeText
        speedText = binding.speedText
        headingText = binding.headingText
        dataSourceText = binding.dataSourceText
    }
    
    private fun setupComponents() {
        // Инициализация основных компонентов
        syntheticVisionSystem = SyntheticVisionSystem(
            this,
            syntheticVisionView
        )
        
        // Инициализация соединения
        bluetoothManager = SvsBluetoothManager(this) { data ->
            syntheticVisionSystem.updateFlightData(data)
        }
        
        wifiManager = WiFiManager(this) { data ->
            syntheticVisionSystem.updateFlightData(data)
        }
        
        // Инициализация карты - проверяем наличие MBTiles файла
        val mapManager = MapManager(this)
        val mapFile = mapManager.findMapFile()
        if (mapFile != null) {
            mapView.setupOfflineSource(mapFile)
            Toast.makeText(this, "Карта загружена: ${mapFile.name}", Toast.LENGTH_SHORT).show()
            syntheticVisionSystem.setMapSource(mapFile.name)
        } else {
            // Проверяем файл в assets
            try {
                val assetsList = assets.list("") ?: emptyArray()
                val mbtFiles = assetsList.filter { it.endsWith(".mbtiles") }
                if (mbtFiles.isNotEmpty()) {
                    val assetFile = mbtFiles[0]
                    val tempFile = File(cacheDir, "temp_map.mbtiles")
                    
                    // Копируем файл из assets во временный файл
                    assets.open(assetFile).use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    mapView.setupOfflineSource(tempFile)
                    Toast.makeText(this, "Карта загружена из ресурсов: $assetFile", Toast.LENGTH_SHORT).show()
                    syntheticVisionSystem.setMapSource(assetFile)
                } else {
                    Toast.makeText(this, "Карта не найдена", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при загрузке карты из ресурсов", e)
                Toast.makeText(this, "Ошибка при загрузке карты: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun setupButtonListeners() {
        // Simulation control buttons
        startButton.setOnClickListener {
            syntheticVisionSystem.startSimulation()
            isSimulationRunning = true
            updateControlButtonsState(true)
        }
        
        stopButton.setOnClickListener {
            syntheticVisionSystem.stopSimulation()
            syntheticVisionSystem.disconnect()
            isSimulationRunning = false
            updateControlButtonsState(false)
            
            // Сбрасываем маркер в начальное положение
            resetAircraftMarker()
        }
        
        // Connection buttons
        bluetoothButton.setOnClickListener {
            checkAndRequestBluetoothPermissions()
        }
        
        wifiButton.setOnClickListener {
            if (syntheticVisionSystem.isWiFiAvailable()) {
                showWiFiConnectionDialog()
            } else {
                Toast.makeText(this, "WiFi is not available", Toast.LENGTH_LONG).show()
            }
        }
        
        // Connection dialog buttons
        connectButton.setOnClickListener {
            val address = addressInput.text.toString()
            if (address.isEmpty()) {
                Toast.makeText(this, "Please enter an address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val connected = when (connectionType) {
                0 -> syntheticVisionSystem.connectBluetooth(address)
                1 -> {
                    val port = portInput.text.toString().toIntOrNull() ?: 8080
                    syntheticVisionSystem.connectWiFi(address, port)
                }
                else -> false
            }
            
            if (connected) {
                Toast.makeText(this, "Connected successfully", Toast.LENGTH_SHORT).show()
                connectionDialog.visibility = View.GONE
                updateControlButtonsState(true)
            } else {
                Toast.makeText(this, "Failed to connect", Toast.LENGTH_SHORT).show()
            }
        }
        
        cancelConnectionButton.setOnClickListener {
            connectionDialog.visibility = View.GONE
        }
    }
    
    private fun subscribeToFlightData() {
        // Subscribe to flight data updates to update UI
        disposables.add(
            syntheticVisionSystem.getFlightDataObservable()
                .subscribe(
                    { data -> updateFlightDataUI(data) },
                    { error -> error.printStackTrace() }
                )
        )
    }
    
    private fun updateFlightDataUI(data: FlightData) {
        // Update flight data display
        altitudeText.text = "ALT: ${data.altitude.toInt()} m"
        speedText.text = "SPD: ${data.speed.toInt()} m/s"
        headingText.text = "HDG: ${data.heading.toInt()}°"
        
        // Update data source display
        val sourceText = when (syntheticVisionSystem.getCurrentDataSource()) {
            DataManager.SOURCE_FILE -> "SRC: File"
            DataManager.SOURCE_BLUETOOTH -> "SRC: Bluetooth"
            DataManager.SOURCE_WIFI -> "SRC: WiFi"
            else -> "SRC: Unknown"
        }
        dataSourceText.text = sourceText
        
        // Обновляем положение маркера самолета на карте
        updateAircraftPosition(data)
    }
    
    /**
     * Обновляет положение маркера самолета на карте
     */
    private fun updateAircraftPosition(data: FlightData) {
        // Обновляем положение маркера на карте
        val position = GeoPoint(data.latitude, data.longitude)
        aircraftMarker.position = position
        
        // Поворачиваем маркер согласно курсу самолета
        aircraftMarker.rotation = data.heading
        
        // Обновление карты
        mapView.invalidate()
        
        // Центрируем карту на самолете и устанавливаем масштаб
        if (isSimulationRunning) {
            // Устанавливаем масштаб для лучшего обзора, когда начинается симуляция
            // и центрируем карту на позиции самолета
            mapView.controller.setZoom(15.0)
            mapView.controller.animateTo(position)
        }
    }
    
    private fun updateControlButtonsState(isRunning: Boolean) {
        startButton.visibility = if (isRunning) View.GONE else View.VISIBLE
        stopButton.visibility = if (isRunning) View.VISIBLE else View.GONE
        bluetoothButton.isEnabled = !isRunning
        wifiButton.isEnabled = !isRunning
    }
    
    private fun showBluetoothConnectionDialog() {
        connectionType = 0
        connectionDialogTitle.text = "Connect via Bluetooth"
        addressInput.hint = "MAC Address (e.g. 00:11:22:33:44:55)"
        addressInput.setText("")
        portInputLayout.visibility = View.GONE
        connectionDialog.visibility = View.VISIBLE
    }
    
    private fun showWiFiConnectionDialog() {
        connectionType = 1
        connectionDialogTitle.text = "Connect via WiFi"
        addressInput.hint = "IP Address (e.g. 192.168.1.100)"
        addressInput.setText("")
        portInputLayout.visibility = View.VISIBLE
        portInput.setText("8080")
        connectionDialog.visibility = View.VISIBLE
    }
    
    private fun checkAndRequestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            
            if (permissions.all { 
                    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED 
                }) {
                proceedWithBluetooth()
            } else {
                requestPermissionLauncher.launch(permissions)
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            
            if (permissions.all { 
                    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED 
                }) {
                proceedWithBluetooth()
            } else {
                requestPermissionLauncher.launch(permissions)
            }
        }
    }
    
    private fun proceedWithBluetooth() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btManager.adapter
        } else {
            BluetoothAdapter.getDefaultAdapter()
        }
        
        if (btAdapter?.isEnabled == true) {
            showBluetoothConnectionDialog()
        } else {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        }
    }
    
    /**
     * Настраивает маркер самолета на карте
     */
    private fun setupAircraftMarker() {
        aircraftMarker = Marker(mapView)
        aircraftMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        aircraftMarker.setTitle("Aircraft")
        
        // Устанавливаем иконку самолета
        aircraftMarker.icon = ContextCompat.getDrawable(this, R.drawable.ic_airplane)
        
        // Устанавливаем начальное положение маркера в центре карты
        aircraftMarker.position = GeoPoint(0.0, 0.0)
        
        // Добавляем маркер на карту
        mapView.overlays.add(aircraftMarker)
        
        // Настраиваем масштаб карты
        mapView.controller.setZoom(12.0)
    }
    
    /**
     * Сбрасывает маркер самолета в начальное положение
     */
    private fun resetAircraftMarker() {
        // Получаем начальные координаты из первой точки данных симуляции, если доступны
        if (syntheticVisionSystem.hasFlightData()) {
            val initialData = syntheticVisionSystem.getInitialFlightData()
            val position = GeoPoint(initialData.latitude, initialData.longitude)
            aircraftMarker.position = position
        } else {
            // Если данных нет, используем нулевые координаты
            aircraftMarker.position = GeoPoint(0.0, 0.0)
        }
        
        aircraftMarker.rotation = 0f
        mapView.invalidate()
    }
    
    override fun onResume() {
        super.onResume()
        // Необходимо вызывать для правильной работы osmdroid
        mapView.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        // Необходимо вызывать для правильной работы osmdroid
        mapView.onPause()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disposables.clear()
        syntheticVisionSystem.dispose()
    }
} 