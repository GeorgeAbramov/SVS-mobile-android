package com.example.syntheticvision.data

import com.example.syntheticvision.model.FlightData
import java.io.BufferedReader
import java.io.InputStream

class DataParser {
    fun parseFlightData(inputStream: InputStream): List<FlightData> {
        val flightDataList = mutableListOf<FlightData>()
        
        BufferedReader(inputStream.reader()).use { reader ->
            // Skip header line if exists
            var line = reader.readLine()
            
            // Parse each line
            while (reader.readLine()?.also { line = it } != null) {
                try {
                    val values = line.split(";")
                    if (values.size >= 9) {
                        val flightData = FlightData(
                            roll = values[0].toFloat(),
                            pitch = values[1].toFloat(),
                            heading = values[2].toFloat(),
                            vx = values[3].toFloat(),
                            vy = values[4].toFloat(),
                            vz = values[5].toFloat(),
                            latitude = values[6].toDouble(),
                            longitude = values[7].toDouble(),
                            altitude = values[8].toFloat()
                        )
                        flightDataList.add(flightData)
                    }
                } catch (e: Exception) {
                    // Skip malformed lines
                    e.printStackTrace()
                }
            }
        }
        
        return flightDataList
    }
} 