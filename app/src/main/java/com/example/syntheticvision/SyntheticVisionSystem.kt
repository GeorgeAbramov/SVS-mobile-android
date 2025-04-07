package com.example.syntheticvision

import android.content.Context
import com.example.syntheticvision.collision.CollisionDetector
import com.example.syntheticvision.data.DataManager
import com.example.syntheticvision.model.FlightData
import com.example.syntheticvision.terrain.TerrainModel
import com.example.syntheticvision.view.SyntheticVisionView
import com.example.syntheticvision.view.FlightDataView
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import java.io.File
import java.io.InputStream
import java.util.Timer
import java.util.TimerTask
import android.util.Log

class SyntheticVisionSystem(
    private val context: Context,
    private val syntheticVisionView: SyntheticVisionView
) {
    private val TAG = "SyntheticVisionSystem"
    private var currentMapSource = "None"
    
    // Alternative constructor with FlightDataView parameter
    constructor(
        context: Context,
        syntheticVisionView: SyntheticVisionView,
        flightDataView: FlightDataView
    ) : this(context, syntheticVisionView) {
        // Initialize flight data view if needed
    }
    
    private val dataManager = DataManager(context)
    private val terrainModel = TerrainModel(context)
    private val collisionDetector = CollisionDetector(terrainModel)
    
    private var flightDataList: List<FlightData> = emptyList()
    private var currentDataIndex = 0
    private var timer: Timer? = null
    
    // RxJava компоненты
    private val disposables = CompositeDisposable()
    
    // Режим работы: симуляция или реальное время
    private var realTimeMode = false
    
    init {
        // Initialize terrain model
        syntheticVisionView.setTerrainModel(terrainModel)
        
        // Подписываемся на данные в реальном времени
        disposables.add(
            dataManager.getFlightDataObservable()
                .subscribe(
                    { data -> processRealTimeData(data) },
                    { error -> error.printStackTrace() }
                )
        )
    }
    
    // Load terrain data
    fun loadTerrainData(fileName: String): Boolean {
        return terrainModel.loadTerrainData(fileName)
    }
    
    // Get map installation instructions
    fun getMapInstallationInstructions(): String {
        return terrainModel.getMapInstallationInstructions()
    }
    
    // Check if map is loaded
    fun isMapLoaded(): Boolean {
        return terrainModel.isMapLoaded()
    }
    
    // Load flight data from CSV
    fun loadFlightData(inputStream: InputStream) {
        flightDataList = dataManager.loadFlightDataFromFile(inputStream)
    }
    
    // Start simulation from file
    fun startSimulation(updateRateMs: Long = 1000) {
        if (flightDataList.isEmpty()) return
        
        // Проверяем, загружена ли карта
        if (!terrainModel.isMapLoaded()) {
            // Если карта не загружена, переключаемся на демо-данные
            terrainModel.createDemoTerrainData()
        }
        
        // Переключаемся в режим симуляции
        realTimeMode = false
        
        // Reset index
        currentDataIndex = 0
        
        // Stop existing timer if any
        stopSimulation()
        
        // Create new timer
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (currentDataIndex < flightDataList.size) {
                    val currentData = flightDataList[currentDataIndex]
                    
                    // Обрабатываем данные
                    processFlightData(currentData)
                    
                    currentDataIndex++
                } else {
                    // End of data, stop simulation
                    stopSimulation()
                }
            }
        }, 0, updateRateMs)
    }
    
    // Stop simulation
    fun stopSimulation() {
        timer?.cancel()
        timer = null
    }
    
    // Connect to BINS via Bluetooth
    fun connectBluetooth(deviceAddress: String): Boolean {
        val result = dataManager.connectBluetooth(deviceAddress)
        if (result) {
            realTimeMode = true
            stopSimulation() // Stop simulation if running
        }
        return result
    }
    
    // Connect to BINS via WiFi
    fun connectWiFi(ipAddress: String, port: Int = 8080): Boolean {
        val result = dataManager.connectWiFi(ipAddress, port)
        if (result) {
            realTimeMode = true
            stopSimulation() // Stop simulation if running
        }
        return result
    }
    
    // Disconnect from BINS
    fun disconnect() {
        dataManager.disconnect()
        realTimeMode = false
    }
    
    // Check if Bluetooth is supported
    fun isBluetoothSupported(): Boolean {
        return dataManager.isBluetoothSupported()
    }
    
    // Check if Bluetooth is enabled
    fun isBluetoothEnabled(): Boolean {
        return dataManager.isBluetoothEnabled()
    }
    
    // Check if WiFi is available
    fun isWiFiAvailable(): Boolean {
        return dataManager.isWiFiAvailable()
    }
    
    // Get current data source
    fun getCurrentDataSource(): Int {
        return dataManager.getCurrentDataSource()
    }
    
    // Get map source description
    fun getMapSource(): String {
        return currentMapSource
    }
    
    // Set map source description
    fun setMapSource(source: String) {
        currentMapSource = source
    }
    
    // Get flight data observable for UI updates
    fun getFlightDataObservable(): Observable<FlightData> {
        return dataManager.getFlightDataObservable()
    }
    
    // Check if flight data is available
    fun hasFlightData(): Boolean {
        return flightDataList.isNotEmpty()
    }
    
    // Get the initial flight data point (first position)
    fun getInitialFlightData(): FlightData {
        return if (flightDataList.isNotEmpty()) {
            flightDataList[0]
        } else {
            // Return default flight data if no data is available
            FlightData(
                roll = 0f,
                pitch = 0f,
                heading = 0f,
                vx = 0f,
                vy = 0f,
                vz = 0f,
                latitude = 0.0,
                longitude = 0.0,
                altitude = 0f
            )
        }
    }
    
    // Process real-time data from BINS
    private fun processRealTimeData(data: FlightData) {
        if (realTimeMode) {
            processFlightData(data)
        }
    }
    
    // Process flight data (common for simulation and real-time)
    private fun processFlightData(data: FlightData) {
        // Update collision detector
        collisionDetector.addFlightData(data)
        
        // Calculate trajectory
        val trajectory = collisionDetector.calculateTrajectory()
        
        // Обновляем вид и отправляем данные в Observable
        syntheticVisionView.post {
            syntheticVisionView.updateFlightData(data)
        }
        
        // Отправляем данные наблюдателям для обновления маркера и других компонентов
        dataManager.emitFlightData(data)
    }
    
    /**
     * Updates flight data directly from external sources
     */
    fun updateFlightData(data: FlightData) {
        processFlightData(data)
    }
    
    // Clean up resources
    fun dispose() {
        stopSimulation()
        disconnect()
        disposables.clear()
    }
} 