package com.example.syntheticvision.filter

import com.example.syntheticvision.model.FlightData

class DataSmoother(private val smoothingInterval: Int = 5) {
    
    private val smoothedData = mutableListOf<FlightData>()
    
    /**
     * Применяет модифицированное скользящее среднее к новым данным
     */
    fun smooth(data: FlightData): FlightData {
        if (smoothedData.isEmpty()) {
            smoothedData.add(data)
            return data
        }
        
        // Применяем модифицированное скользящее среднее
        val lastSmoothed = smoothedData.last()
        val smoothingFactor = 2.0f / (smoothingInterval + 1)
        
        val smoothedData = FlightData(
            roll = lastSmoothed.roll + smoothingFactor * (data.roll - lastSmoothed.roll),
            pitch = lastSmoothed.pitch + smoothingFactor * (data.pitch - lastSmoothed.pitch),
            heading = lastSmoothed.heading + smoothingFactor * (data.heading - lastSmoothed.heading),
            vx = lastSmoothed.vx + smoothingFactor * (data.vx - lastSmoothed.vx),
            vy = lastSmoothed.vy + smoothingFactor * (data.vy - lastSmoothed.vy),
            vz = lastSmoothed.vz + smoothingFactor * (data.vz - lastSmoothed.vz),
            latitude = lastSmoothed.latitude + smoothingFactor * (data.latitude - lastSmoothed.latitude),
            longitude = lastSmoothed.longitude + smoothingFactor * (data.longitude - lastSmoothed.longitude),
            altitude = lastSmoothed.altitude + smoothingFactor * (data.altitude - lastSmoothed.altitude)
        )
        
        this.smoothedData.add(smoothedData)
        
        // Ограничиваем размер буфера
        if (this.smoothedData.size > smoothingInterval * 2) {
            this.smoothedData.removeAt(0)
        }
        
        return smoothedData
    }
} 