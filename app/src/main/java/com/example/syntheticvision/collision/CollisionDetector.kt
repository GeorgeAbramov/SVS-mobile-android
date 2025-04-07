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
    
    // Calculate predicted trajectory points with trend prediction
    fun calculateTrajectory(steps: Int = 60): List<Triple<Double, Double, Float>> {
        if (smoothedData.isEmpty()) return emptyList()
        
        // Получаем текущие данные
        val currentData = smoothedData.last()
        val trajectory = mutableListOf<Triple<Double, Double, Float>>()
        
        // Если у нас недостаточно исторических данных для анализа тренда,
        // используем стандартный линейный прогноз
        if (smoothedData.size < 2) {
            // Стандартный линейный прогноз (как было раньше)
            return calculateLinearTrajectory(currentData, steps)
        }
        
        // Анализируем тренды изменения параметров на основе исторических данных
        val previousData = smoothedData[smoothedData.size - 2]
        
        // Рассчитываем скорость изменения параметров (тренды)
        val deltaTime = 0.5f // Предполагаем, что данные приходят каждые 0.5 секунды
        
        // Изменения скорости (ускорение) по осям
        val accelerationX = (currentData.vx - previousData.vx) / deltaTime
        val accelerationY = (currentData.vy - previousData.vy) / deltaTime
        val accelerationZ = (currentData.vz - previousData.vz) / deltaTime
        
        // Изменение высоты (вертикальная скорость)
        val verticalRate = (currentData.altitude - previousData.altitude) / deltaTime
        
        // Изменение курса (угловая скорость)
        val headingRate = normalizeAngleDelta(currentData.heading - previousData.heading) / deltaTime
        
        // Текущие координаты в ECEF
        val (x, y, z) = latLonAltToECEF(
            currentData.latitude, 
            currentData.longitude, 
            currentData.altitude.toDouble()
        )
        
        // Convert velocity from body frame to ECEF frame using direction cosine matrix
        val dcm = createDCM(
            Math.toRadians(currentData.roll.toDouble()),
            Math.toRadians(currentData.pitch.toDouble()),
            Math.toRadians(currentData.heading.toDouble())
        )
        
        // Преобразуем скорость из координат самолета в ECEF
        val (vx, vy, vz) = bodyToECEF(
            currentData.vx.toDouble(), 
            currentData.vy.toDouble(), 
            currentData.vz.toDouble(), 
            dcm
        )
        
        // Добавляем текущую позицию к траектории
        trajectory.add(Triple(currentData.latitude, currentData.longitude, currentData.altitude))
        
        // Шаг времени в секундах
        val dt = PREDICTION_TIME / steps
        
        // Переменные для отслеживания изменяющихся параметров
        var currentVx = vx
        var currentVy = vy 
        var currentVz = vz
        var currentHeading = currentData.heading.toDouble()
        var currentX = x
        var currentY = y
        var currentZ = z
        
        // Рассчитываем траекторию с учетом изменений
        for (i in 1..steps) {
            // Обновляем скорость с учетом ускорения
            currentVx += accelerationX * dt
            currentVy += accelerationY * dt
            currentVz += accelerationZ * dt
            
            // Обновляем курс
            currentHeading += headingRate * dt
            // Нормализуем курс (0-360 градусов)
            while (currentHeading >= 360.0) currentHeading -= 360.0
            while (currentHeading < 0.0) currentHeading += 360.0
            
            // Обновляем DCM для нового курса
            val newDcm = createDCM(
                Math.toRadians(currentData.roll.toDouble()),
                Math.toRadians(currentData.pitch.toDouble()),
                Math.toRadians(currentHeading)
            )
            
            // Преобразуем скорость обратно в ECEF с новым курсом
            val (newVx, newVy, newVz) = bodyToECEF(
                currentData.vx.toDouble() + accelerationX * dt * i, 
                currentData.vy.toDouble() + accelerationY * dt * i, 
                currentData.vz.toDouble() + accelerationZ * dt * i, 
                newDcm
            )
            
            // Обновляем позицию с учетом изменения скорости и курса
            currentX += newVx * dt
            currentY += newVy * dt
            currentZ += newVz * dt
            
            // Преобразуем координаты обратно в широту, долготу, высоту
            val (newLat, newLon, newAltBase) = ecefToLatLonAlt(currentX, currentY, currentZ)
            
            // Учитываем изменение высоты на основе вертикальной скорости
            val predictedAltitude = (currentData.altitude + verticalRate * dt * i).toFloat()
            
            // Добавляем точку к траектории
            trajectory.add(Triple(newLat, newLon, predictedAltitude))
        }
        
        return trajectory
    }
    
    // Вспомогательный метод для стандартного линейного прогноза
    private fun calculateLinearTrajectory(data: FlightData, steps: Int): List<Triple<Double, Double, Float>> {
        val trajectory = mutableListOf<Triple<Double, Double, Float>>()
        
        // Текущая позиция
        var lat = data.latitude
        var lon = data.longitude
        var alt = data.altitude
        
        // Convert flight data to ECEF coordinates
        val (x, y, z) = latLonAltToECEF(lat, lon, alt.toDouble())
        
        // Convert velocity from body frame to ECEF frame using direction cosine matrix
        val dcm = createDCM(
            Math.toRadians(data.roll.toDouble()),
            Math.toRadians(data.pitch.toDouble()),
            Math.toRadians(data.heading.toDouble())
        )
        
        val (vx, vy, vz) = bodyToECEF(data.vx.toDouble(), data.vy.toDouble(), data.vz.toDouble(), dcm)
        
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
    
    // Нормализует изменение угла для правильного расчета скорости изменения курса
    private fun normalizeAngleDelta(angleDelta: Float): Float {
        var delta = angleDelta
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return delta
    }
    
    // Detect collision with terrain
    fun detectCollision(): Float {
        val trajectory = calculateTrajectory()
        if (trajectory.isEmpty()) return -1f
        
        // Time step in seconds
        val dt = PREDICTION_TIME / trajectory.size
        
        // Минимальная высота для возникновения предупреждения
        val minCollisionAltitude = 50f  // минимум 50 м для срабатывания
        
        // Исключаем первые несколько точек для избежания ложных срабатываний
        // когда самолет только что взлетел или находится на земле
        val startIndex = 5  // пропускаем первые 5 точек
        
        for (i in startIndex until trajectory.size) {
            val (lat, lon, alt) = trajectory[i]
            
            // Получаем высоту рельефа в этой точке
            val terrainHeight = terrainModel.getHeightAt(lat, lon)
            
            // Проверяем на коллизию (если высота полета ниже высоты рельефа
            // или опасно близко к нему)
            val heightDifference = alt - terrainHeight
            
            if (alt > minCollisionAltitude && heightDifference <= 100f) {
                // Если высота над рельефом менее 100м и это не взлет/посадка,
                // возвращаем время до коллизии в секундах
                return i * dt
            }
        }
        
        // Коллизия не обнаружена
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