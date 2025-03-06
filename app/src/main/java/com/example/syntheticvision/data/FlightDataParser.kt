package com.example.syntheticvision.data

import com.example.syntheticvision.model.FlightData
import java.io.BufferedReader
import java.io.InputStream

class FlightDataParser {
    
    /**
     * Анализирует CSV-файл с данными полета
     * Формат: roll;pitch;heading;Vx;Vy;Vz;latitude;longitude;altitude
     */
    fun parseFlightData(inputStream: InputStream): List<FlightData> {
        val flightDataList = mutableListOf<FlightData>()
        
        BufferedReader(inputStream.reader()).use { reader ->
            // Пропустим заголовок, если он есть
            var line = reader.readLine()
            
            // Обработаем каждую строку
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
                    // Пропустим некорректные строки
                    e.printStackTrace()
                }
            }
        }
        
        return flightDataList
    }
} 