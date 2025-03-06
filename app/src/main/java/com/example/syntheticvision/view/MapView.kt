package com.example.syntheticvision.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.example.syntheticvision.model.FlightData
import kotlin.math.cos
import kotlin.math.sin

class MapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    // Flight data
    private var flightDataList = mutableListOf<FlightData>()
    
    // Trajectory points
    private var trajectoryPoints: List<Triple<Double, Double, Float>> = emptyList()
    
    // Map boundaries
    private var minLatitude = Double.MAX_VALUE
    private var maxLatitude = Double.MIN_VALUE
    private var minLongitude = Double.MAX_VALUE
    private var maxLongitude = Double.MIN_VALUE
    
    // Paints
    private val pathPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    
    private val trajectoryPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    
    private val aircraftPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 40f
        isAntiAlias = true
    }
    
    // Update flight data
    fun updateFlightData(data: FlightData) {
        flightDataList.add(data)
        
        // Update map boundaries
        minLatitude = minOf(minLatitude, data.latitude)
        maxLatitude = maxOf(maxLatitude, data.latitude)
        minLongitude = minOf(minLongitude, data.longitude)
        maxLongitude = maxOf(maxLongitude, data.longitude)
        
        // Keep only the recent points for performance
        if (flightDataList.size > 100) {
            flightDataList.removeAt(0)
        }
        
        invalidate()
    }
    
    // Set trajectory points
    fun setTrajectoryPoints(points: List<Triple<Double, Double, Float>>) {
        trajectoryPoints = points
        
        // Update map boundaries with trajectory points
        for ((lat, lon, _) in points) {
            minLatitude = minOf(minLatitude, lat)
            maxLatitude = maxOf(maxLatitude, lat)
            minLongitude = minOf(minLongitude, lon)
            maxLongitude = maxOf(maxLongitude, lon)
        }
        
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (flightDataList.isEmpty()) return
        
        val width = width.toFloat()
        val height = height.toFloat()
        
        // Add some padding
        val padding = 50f
        
        // Calculate scaling factors
        val latRange = (maxLatitude - minLatitude).coerceAtLeast(0.001)
        val lonRange = (maxLongitude - minLongitude).coerceAtLeast(0.001)
        
        val latScale = (height - 2 * padding) / latRange
        val lonScale = (width - 2 * padding) / lonRange
        
        // Draw flight path
        val pathPath = Path()
        var firstPoint = true
        
        for (data in flightDataList) {
            val x = padding + ((data.longitude - minLongitude) * lonScale).toFloat()
            val y = height - padding - ((data.latitude - minLatitude) * latScale).toFloat()
            
            if (firstPoint) {
                pathPath.moveTo(x, y)
                firstPoint = false
            } else {
                pathPath.lineTo(x, y)
            }
        }
        
        canvas.drawPath(pathPath, pathPaint)
        
        // Draw trajectory
        if (trajectoryPoints.isNotEmpty()) {
            val trajectoryPath = Path()
            firstPoint = true
            
            for ((lat, lon, _) in trajectoryPoints) {
                val x = padding + ((lon - minLongitude) * lonScale).toFloat()
                val y = height - padding - ((lat - minLatitude) * latScale).toFloat()
                
                if (firstPoint) {
                    trajectoryPath.moveTo(x, y)
                    firstPoint = false
                } else {
                    trajectoryPath.lineTo(x, y)
                }
            }
            
            canvas.drawPath(trajectoryPath, trajectoryPaint)
        }
        
        // Draw current aircraft position
        val currentData = flightDataList.last()
        val aircraftX = padding + ((currentData.longitude - minLongitude) * lonScale).toFloat()
        val aircraftY = height - padding - ((currentData.latitude - minLatitude) * latScale).toFloat()
        
        // Draw aircraft symbol (triangle pointing in the heading direction)
        val headingRad = Math.toRadians(currentData.heading.toDouble())
        val triangleSize = 20f
        
        val aircraftPath = Path()
        aircraftPath.moveTo(
            aircraftX + triangleSize * sin(headingRad).toFloat(),
            aircraftY - triangleSize * cos(headingRad).toFloat()
        )
        aircraftPath.lineTo(
            aircraftX + triangleSize * sin(headingRad + 2.5).toFloat(),
            aircraftY - triangleSize * cos(headingRad + 2.5).toFloat()
        )
        aircraftPath.lineTo(
            aircraftX + triangleSize * sin(headingRad - 2.5).toFloat(),
            aircraftY - triangleSize * cos(headingRad - 2.5).toFloat()
        )
        aircraftPath.close()
        
        canvas.drawPath(aircraftPath, aircraftPaint)
        
        // Draw flight info
        canvas.drawText(
            "Lat: ${String.format("%.6f", currentData.latitude)}",
            padding,
            padding + textPaint.textSize,
            textPaint
        )
        
        canvas.drawText(
            "Lon: ${String.format("%.6f", currentData.longitude)}",
            padding,
            padding + textPaint.textSize * 2,
            textPaint
        )
        
        canvas.drawText(
            "Alt: ${currentData.altitude.toInt()} m",
            padding,
            padding + textPaint.textSize * 3,
            textPaint
        )
        
        canvas.drawText(
            "Hdg: ${currentData.heading.toInt()}°",
            padding,
            padding + textPaint.textSize * 4,
            textPaint
        )
    }
} 