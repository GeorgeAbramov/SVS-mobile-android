package com.example.syntheticvision

import android.content.Context
import com.example.syntheticvision.collision.CollisionDetector
import com.example.syntheticvision.data.DataParser
import com.example.syntheticvision.model.FlightData
import com.example.syntheticvision.terrain.TerrainModel
import com.example.syntheticvision.view.MapView
import com.example.syntheticvision.view.SyntheticVisionView
import java.io.InputStream
import java.util.Timer
import java.util.TimerTask

class SyntheticVisionSystem(
    private val context: Context,
    private val mapView: MapView,
    private val syntheticVisionView: SyntheticVisionView
) {
    private val dataParser = DataParser()
    private val terrainModel = TerrainModel(context)
    private val collisionDetector = CollisionDetector(terrainModel)
    
    private var flightDataList: List<FlightData> = emptyList()
    private var currentDataIndex = 0
    private var timer: Timer? = null
    
    init {
        // Initialize terrain model
        syntheticVisionView.setTerrainModel(terrainModel)
    }
    
    // Load terrain data
    fun loadTerrainData(fileName: String) {
        terrainModel.loadTerrainData(fileName)
    }
    
    // Load flight data from CSV
    fun loadFlightData(inputStream: InputStream) {
        flightDataList = dataParser.parseFlightData(inputStream)
    }
    
    // Start simulation
    fun startSimulation(updateRateMs: Long = 1000) {
        if (flightDataList.isEmpty()) return
        
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
                    
                    // Update collision detector
                    collisionDetector.addFlightData(currentData)
                    
                    // Calculate trajectory
                    val trajectory = collisionDetector.calculateTrajectory()
                    
                    // Update views on UI thread
                    mapView.post {
                        mapView.updateFlightData(currentData)
                        mapView.setTrajectoryPoints(trajectory)
                    }
                    
                    syntheticVisionView.post {
                        syntheticVisionView.updateFlightData(currentData)
                    }
                    
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
} 