package com.example.syntheticvision.data

import android.util.Log
import com.example.syntheticvision.model.FlightData
import java.io.BufferedReader
import java.io.InputStream

class DataParser {
    companion object {
        private const val TAG = "DataParser"
    }
    
    fun parseFlightData(inputStream: InputStream): List<FlightData> {
        val flightDataList = mutableListOf<FlightData>()
        var lineNumber = 0
        
        BufferedReader(inputStream.reader()).use { reader ->
            // Skip header line if exists
            var line = reader.readLine()
            lineNumber++
            
            // Parse each line
            while (reader.readLine()?.also { line = it } != null) {
                lineNumber++
                try {
                    val values = line.split(";")
                    if (values.size >= 9) {
                        val lat = values[6].toDouble()
                        val lon = values[7].toDouble()
                        
                        // Увеличиваем разницу между координатами для лучшей видимости на карте
                        val scaledLat = if (flightDataList.isEmpty()) lat else lat * 1.001
                        val scaledLon = if (flightDataList.isEmpty()) lon else lon * 1.001
                        
                        val flightData = FlightData(
                            roll = values[0].toFloat(),
                            pitch = values[1].toFloat(),
                            heading = values[2].toFloat(),
                            vx = values[3].toFloat(),
                            vy = values[4].toFloat(),
                            vz = values[5].toFloat(),
                            latitude = scaledLat,
                            longitude = scaledLon,
                            altitude = values[8].toFloat()
                        )
                        
                        // Логируем каждую 100-ю точку для отладки
                        if (lineNumber % 100 == 0) {
                            Log.d(TAG, "Parsed data point #$lineNumber: lat=$scaledLat, lon=$scaledLon")
                        }
                        
                        flightDataList.add(flightData)
                    }
                } catch (e: Exception) {
                    // Skip malformed lines
                    Log.e(TAG, "Error parsing line $lineNumber: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
        
        Log.d(TAG, "Parsed ${flightDataList.size} flight data points")
        return flightDataList
    }
} 