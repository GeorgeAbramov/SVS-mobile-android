package com.example.syntheticvision

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.syntheticvision.databinding.ActivityMainBinding
import com.example.syntheticvision.view.MapView
import com.example.syntheticvision.view.SyntheticVisionView
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var mapView: MapView
    private lateinit var syntheticVisionView: SyntheticVisionView
    private lateinit var syntheticVisionSystem: SyntheticVisionSystem
    
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    
    // Имя файла данных рельефа в assets
    private val terrainFileName = "osm-2020-02-10-v3.11_europe_russia-european-part.mbtiles" 
    
    // Имя файла данных полета
    private val flightDataFileName = "navdata-for-parsing.csv"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize views
        setupViews()
        
        // Initialize the synthetic vision system
        syntheticVisionSystem = SyntheticVisionSystem(
            this,
            mapView,
            syntheticVisionView
        )
        
        // Load terrain data
        try {
            // Загрузка данных рельефа
            syntheticVisionSystem.loadTerrainData(terrainFileName)
            Toast.makeText(this, "Terrain data loaded", Toast.LENGTH_SHORT).show()
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
        startButton.setOnClickListener {
            syntheticVisionSystem.startSimulation(500) // Update every 500ms
            startButton.visibility = View.GONE
            stopButton.visibility = View.VISIBLE
        }
        
        stopButton.setOnClickListener {
            syntheticVisionSystem.stopSimulation()
            startButton.visibility = View.VISIBLE
            stopButton.visibility = View.GONE
        }
    }
    
    private fun setupViews() {
        // Get map container and synthetic vision container
        val mapContainer = binding.mapContainer
        val syntheticVisionContainer = binding.syntheticVisionContainer
        
        // Create MapView
        mapView = MapView(this)
        mapContainer.addView(mapView)
        
        // Create SyntheticVisionView
        syntheticVisionView = SyntheticVisionView(this)
        syntheticVisionContainer.addView(syntheticVisionView)
        
        // Initialize buttons
        startButton = binding.startButton
        stopButton = binding.stopButton
        stopButton.visibility = View.GONE
    }
} 