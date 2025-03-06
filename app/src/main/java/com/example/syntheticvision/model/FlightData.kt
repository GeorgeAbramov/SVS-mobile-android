package com.example.syntheticvision.model

data class FlightData(
    val roll: Float,      // крен в градусах
    val pitch: Float,     // тангаж в градусах
    val heading: Float,   // курс в градусах
    val vx: Float,        // скорость по оси X в м/с
    val vy: Float,        // скорость по оси Y в м/с
    val vz: Float,        // скорость по оси Z в м/с
    val latitude: Double,  // широта в градусах
    val longitude: Double, // долгота в градусах
    val altitude: Float   // высота в метрах
) {
    // Рассчитаем общую скорость
    val speed: Float
        get() = Math.sqrt((vx * vx + vy * vy + vz * vz).toDouble()).toFloat()
} 