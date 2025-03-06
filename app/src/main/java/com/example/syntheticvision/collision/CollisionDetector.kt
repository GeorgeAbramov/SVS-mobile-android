package com.example.syntheticvision.collision

import com.example.syntheticvision.model.FlightData
import com.example.syntheticvision.terrain.TerrainModel
import kotlin.math.*

class CollisionDetector(private val terrainModel: TerrainModel) {
    companion object {
        // Constants for the modified moving average
        const val SMOOTHING_INTERVAL = 5
        
        // Prediction time horizon in seconds
        const val PREDICTION_TIME = 60f
        
        // Earth radius in meters
        const val EARTH_RADIUS = 6378137.0
    }
    
    // Flight data smoothing using modified moving average
    private val smoothedData = mutableListOf<FlightData>()
    
    // Add new flight data and apply smoothing
    fun addFlightData(data: FlightData) {
        if (smoothedData.isEmpty()) {
            smoothedData.add(data)
            return
        }
        
        // Apply modified moving average
        val lastSmoothed = smoothedData.last()
        val smoothingFactor = 2.0f / (SMOOTHING_INTERVAL + 1)
        
        val smoothedRoll = lastSmoothed.roll + smoothingFactor * (data.roll - lastSmoothed.roll)
        val smoothedPitch = lastSmoothed.pitch + smoothingFactor * (data.pitch - lastSmoothed.pitch)
        val smoothedHeading = lastSmoothed.heading + smoothingFactor * (data.heading - lastSmoothed.heading)
        val smoothedVx = lastSmoothed.vx + smoothingFactor * (data.vx - lastSmoothed.vx)
        val smoothedVy = lastSmoothed.vy + smoothingFactor * (data.vy - lastSmoothed.vy)
        val smoothedVz = lastSmoothed.vz + smoothingFactor * (data.vz - lastSmoothed.vz)
        val smoothedLat = lastSmoothed.latitude + smoothingFactor * (data.latitude - lastSmoothed.latitude)
        val smoothedLon = lastSmoothed.longitude + smoothingFactor * (data.longitude - lastSmoothed.longitude)
        val smoothedAlt = lastSmoothed.altitude + smoothingFactor * (data.altitude - lastSmoothed.altitude)
        
        val smoothedData = FlightData(
            smoothedRoll, smoothedPitch, smoothedHeading,
            smoothedVx, smoothedVy, smoothedVz,
            smoothedLat, smoothedLon, smoothedAlt
        )
        
        this.smoothedData.add(smoothedData)
        
        // Keep only recent data
        if (this.smoothedData.size > SMOOTHING_INTERVAL * 2) {
            this.smoothedData.removeAt(0)
        }
    }
    
    // Calculate predicted trajectory points
    fun calculateTrajectory(steps: Int = 60): List<Triple<Double, Double, Float>> {
        if (smoothedData.isEmpty()) return emptyList()
        
        val currentData = smoothedData.last()
        val trajectory = mutableListOf<Triple<Double, Double, Float>>()
        
        // Current position
        var lat = currentData.latitude
        var lon = currentData.longitude
        var alt = currentData.altitude
        
        // Convert flight data to Earth-centered, Earth-fixed (ECEF) coordinates
        val (x, y, z) = latLonAltToECEF(lat, lon, alt.toDouble())
        
        // Convert velocity from body frame to ECEF frame using direction cosine matrix
        val dcm = createDCM(
            Math.toRadians(currentData.roll.toDouble()),
            Math.toRadians(currentData.pitch.toDouble()),
            Math.toRadians(currentData.heading.toDouble())
        )


        
        val (vx, vy, vz) = bodyToECEF(currentData.vx.toDouble(), currentData.vy.toDouble(), currentData.vz.toDouble(), dcm)
        
        // Add current position to trajectory
        trajectory.add(Triple(lat, lon, alt))
        
        // Time step in seconds
        val dt = PREDICTION_TIME / steps
        
        // Calculate trajectory points
        for (i in 1..steps) {
            // Update position
            val newX = x + vx * dt * i
            val newY = y + vy * dt * i
            val newZ = z + vz * dt * i
            
            // Convert back to lat/lon/alt
            val (newLat, newLon, newAlt) = ecefToLatLonAlt(newX, newY, newZ)
            
            // Add to trajectory
            trajectory.add(Triple(newLat, newLon, newAlt.toFloat()))
        }
        
        return trajectory
    }
    
    // Detect collision with terrain
    fun detectCollision(): Float {
        val trajectory = calculateTrajectory()
        if (trajectory.isEmpty()) return -1f
        
        // Time step in seconds
        val dt = PREDICTION_TIME / trajectory.size
        
        for (i in trajectory.indices) {
            val (lat, lon, alt) = trajectory[i]
            
            // Get terrain height at this lat/lon
            val terrainHeight = terrainModel.getHeightAt(lat, lon)
            
            // Check for collision (if altitude is below terrain height)
            if (alt <= terrainHeight) {
                // Return time to collision in seconds
                return i * dt
            }
        }
        
        // No collision detected
        return -1f
    }
    
    // Get danger level for a point: 0 = safe, 1 = warning (yellow), 2 = danger (red)
    fun getDangerLevel(lat: Double, lon: Double, alt: Float): Int {
        val terrainHeight = terrainModel.getHeightAt(lat, lon)
        
        // Danger thresholds (can be adjusted)
        val criticalThreshold = 100f  // meters
        val warningThreshold = 300f   // meters
        
        val heightDifference = alt - terrainHeight
        
        return when {
            heightDifference <= criticalThreshold -> 2  // Critical (red)
            heightDifference <= warningThreshold -> 1   // Warning (yellow)
            else -> 0  // Safe (no color)
        }
    }
    
    // Create Direction Cosine Matrix from Euler angles
    private fun createDCM(roll: Double, pitch: Double, yaw: Double): Array<Array<Double>> {
        val sinRoll = sin(roll)
        val cosRoll = cos(roll)
        val sinPitch = sin(pitch)
        val cosPitch = cos(pitch)
        val sinYaw = sin(yaw)
        val cosYaw = cos(yaw)
        
        return arrayOf(
            arrayOf(
                cosYaw * cosPitch,
                cosYaw * sinPitch * sinRoll - sinYaw * cosRoll,
                cosYaw * sinPitch * cosRoll + sinYaw * sinRoll
            ),
            arrayOf(
                sinYaw * cosPitch,
                sinYaw * sinPitch * sinRoll + cosYaw * cosRoll,
                sinYaw * sinPitch * cosRoll - cosYaw * sinRoll
            ),
            arrayOf(
                -sinPitch,
                cosPitch * sinRoll,
                cosPitch * cosRoll
            )
        )
    }
    
    // Convert body frame velocities to ECEF frame
    private fun bodyToECEF(
        vxBody: Double, vyBody: Double, vzBody: Double,
        dcm: Array<Array<Double>>
    ): Triple<Double, Double, Double> {
        val vxECEF = dcm[0][0] * vxBody + dcm[0][1] * vyBody + dcm[0][2] * vzBody
        val vyECEF = dcm[1][0] * vxBody + dcm[1][1] * vyBody + dcm[1][2] * vzBody
        val vzECEF = dcm[2][0] * vxBody + dcm[2][1] * vyBody + dcm[2][2] * vzBody
        
        return Triple(vxECEF, vyECEF, vzECEF)
    }
    
    // Convert lat/lon/alt to ECEF coordinates
    private fun latLonAltToECEF(
        lat: Double, lon: Double, alt: Double
    ): Triple<Double, Double, Double> {
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        
        val n = EARTH_RADIUS / sqrt(1 - 0.00669438 * sin(latRad) * sin(latRad))
        
        val x = (n + alt) * cos(latRad) * cos(lonRad)
        val y = (n + alt) * cos(latRad) * sin(lonRad)
        val z = (n * (1 - 0.00669438) + alt) * sin(latRad)
        
        return Triple(x, y, z)
    }
    
    // Convert ECEF coordinates to lat/lon/alt
    private fun ecefToLatLonAlt(
        x: Double, y: Double, z: Double
    ): Triple<Double, Double, Double> {
        val e2 = 0.00669438
        val a = EARTH_RADIUS
        val b = a * sqrt(1 - e2)
        
        val p = sqrt(x * x + y * y)
        val theta = atan2(z * a, p * b)
        
        val lon = atan2(y, x)
        val lat = atan2(
            z + e2 * e2 * b * sin(theta) * sin(theta) * sin(theta),
            p - e2 * a * cos(theta) * cos(theta) * cos(theta)
        )
        
        val n = a / sqrt(1 - e2 * sin(lat) * sin(lat))
        val alt = p / cos(lat) - n
        
        return Triple(
            Math.toDegrees(lat),
            Math.toDegrees(lon),
            alt
        )
    }
} 