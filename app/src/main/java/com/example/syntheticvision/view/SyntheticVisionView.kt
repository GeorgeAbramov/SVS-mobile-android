package com.example.syntheticvision.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.syntheticvision.collision.CollisionDetector
import com.example.syntheticvision.model.FlightData
import com.example.syntheticvision.terrain.TerrainModel
import kotlin.math.max
import kotlin.math.min

class SyntheticVisionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    // Colors
    private val skyColor = Color.parseColor("#4A90E2")
    private val groundColor = Color.parseColor("#8B5A2B")
    private val warningColor = Color.YELLOW
    private val dangerColor = Color.RED
    private val lineColor = Color.GREEN
    
    // Paints
    private val skyPaint = Paint().apply {
        color = skyColor
        style = Paint.Style.FILL
    }
    
    private val groundPaint = Paint().apply {
        color = groundColor
        style = Paint.Style.FILL
    }
    
    private val warningPaint = Paint().apply {
        color = warningColor
        style = Paint.Style.FILL
        alpha = 180
    }
    
    private val dangerPaint = Paint().apply {
        color = dangerColor
        style = Paint.Style.FILL
        alpha = 180
    }
    
    private val linePaint = Paint().apply {
        color = lineColor
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
    }
    
    // Flight data
    private var currentFlightData: FlightData? = null
    
    // Terrain model and collision detector
    private var terrainModel: TerrainModel? = null
    private var collisionDetector: CollisionDetector? = null
    
    // Trajectory points
    private var trajectoryPoints: List<Triple<Double, Double, Float>> = emptyList()
    
    fun setTerrainModel(model: TerrainModel) {
        terrainModel = model
        collisionDetector = CollisionDetector(model)
    }
    
    fun updateFlightData(data: FlightData) {
        currentFlightData = data
        collisionDetector?.addFlightData(data)
        trajectoryPoints = collisionDetector?.calculateTrajectory() ?: emptyList()
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        
        // Draw sky
        canvas.drawRect(0f, 0f, width, height / 2, skyPaint)
        
        // Draw ground/horizon
        canvas.drawRect(0f, height / 2, width, height, groundPaint)
        
        // Get current flight data
        val flightData = currentFlightData ?: return
        
        // Draw attitude indicator (pitch and roll)
        drawAttitudeIndicator(canvas, flightData)
        
        // Draw terrain profile
        drawTerrainProfile(canvas)
        
        // Draw flight parameters
        drawFlightParameters(canvas, flightData)
    }
    
    private fun drawAttitudeIndicator(canvas: Canvas, data: FlightData) {
        val width = width.toFloat()
        val height = height.toFloat()
        val centerX = width / 2
        val centerY = height / 2
        
        // Save canvas state
        canvas.save()
        
        // Rotate canvas based on roll angle
        canvas.rotate(-data.roll, centerX, centerY)
        
        // Shift horizon based on pitch angle
        val pitchOffset = (data.pitch / 90) * height / 4
        
        // Draw horizon line
        canvas.drawLine(0f, centerY - pitchOffset, width, centerY - pitchOffset, linePaint)
        
        // Draw pitch ladder
        for (pitch in -40..40 step 10) {
            val y = centerY - pitchOffset - (pitch / 90f) * height / 4
            
            // Only draw pitch lines that are visible
            if (y >= 0 && y <= height) {
                // Length of the pitch line
                val lineLength = if (pitch == 0) width / 2 else width / 4
                
                canvas.drawLine(
                    centerX - lineLength / 2, y,
                    centerX + lineLength / 2, y,
                    linePaint
                )
                
                // Draw pitch value
                if (pitch != 0) {
                    canvas.drawText(
                        pitch.toString(),
                        centerX + lineLength / 2 + 10,
                        y + textPaint.textSize / 3,
                        textPaint
                    )
                }
            }
        }
        
        // Restore canvas state
        canvas.restore()
    }
    
    private fun drawTerrainProfile(canvas: Canvas) {
        if (trajectoryPoints.isEmpty() || terrainModel == null) return
        
        val width = width.toFloat()
        val height = height.toFloat()
        val bottomY = height
        
        // Path for terrain
        val terrainPath = Path()
        terrainPath.moveTo(0f, bottomY)
        
        // Map from trajectory points to screen coordinates
        val screenPoints = mutableListOf<Pair<Float, Float>>()
        
        // Find min/max altitude for scaling
        var minAltitude = Float.MAX_VALUE
        var maxAltitude = Float.MIN_VALUE
        
        // Get terrain heights along the trajectory
        val terrainHeights = trajectoryPoints.map { (lat, lon, _) ->
            val height = terrainModel?.getHeightAt(lat, lon) ?: 0f
            minAltitude = min(minAltitude, height)
            maxAltitude = max(maxAltitude, height)
            height
        }
        
        // Include aircraft altitude in the range
        val currentAltitude = currentFlightData?.altitude ?: 0f
        minAltitude = min(minAltitude, currentAltitude)
        maxAltitude = max(maxAltitude, currentAltitude)
        
        // Add some margin
        val altitudeRange = (maxAltitude - minAltitude).coerceAtLeast(100f)
        val scaleFactor = (height / 2) / altitudeRange
        
        // Draw terrain profile
        for (i in trajectoryPoints.indices) {
            val x = (i.toFloat() / trajectoryPoints.size) * width
            val terrainHeight = terrainHeights[i]
            val y = bottomY - (terrainHeight - minAltitude) * scaleFactor
            
            if (i == 0) {
                terrainPath.moveTo(x, y)
            } else {
                terrainPath.lineTo(x, y)
            }
            
            screenPoints.add(Pair(x, y))
        }
        
        // Close the path
        terrainPath.lineTo(width, bottomY)
        terrainPath.lineTo(0f, bottomY)
        terrainPath.close()
        
        // Draw terrain
        canvas.drawPath(terrainPath, groundPaint)
        
        // Draw current trajectory
        val trajectoryPath = Path()
        val currentAlt = currentFlightData?.altitude ?: 0f
        val startY = bottomY - (currentAlt - minAltitude) * scaleFactor
        
        trajectoryPath.moveTo(0f, startY)
        
        for (i in trajectoryPoints.indices) {
            val x = (i.toFloat() / trajectoryPoints.size) * width
            val alt = trajectoryPoints[i].third
            val y = bottomY - (alt - minAltitude) * scaleFactor
            trajectoryPath.lineTo(x, y)
            
            // Highlight danger areas
            if (i > 0) {
                val (lat, lon, alt) = trajectoryPoints[i]
                val dangerLevel = collisionDetector?.getDangerLevel(lat, lon, alt) ?: 0
                
                if (dangerLevel > 0) {
                    val prevX = (i - 1).toFloat() / trajectoryPoints.size * width
                    val prevAlt = trajectoryPoints[i - 1].third
                    val prevY = bottomY - (prevAlt - minAltitude) * scaleFactor
                    
                    // Create a rectangle for the danger area
                    val dangerRect = RectF(
                        prevX, min(prevY, y),
                        x, max(screenPoints[i].second, screenPoints[i-1].second)
                    )
                    
                    // Draw danger highlight
                    canvas.drawRect(
                        dangerRect,
                        if (dangerLevel == 2) dangerPaint else warningPaint
                    )
                }
            }
        }
        
        // Draw trajectory line
        canvas.drawPath(trajectoryPath, linePaint)
    }
    
    private fun drawFlightParameters(canvas: Canvas, data: FlightData) {
        val width = width.toFloat()
        val textPadding = 20f
        
        // Draw altitude
        canvas.drawText(
            "ALT: ${data.altitude.toInt()} m",
            textPadding,
            textPaint.textSize + textPadding,
            textPaint
        )
        
        // Draw speed
        canvas.drawText(
            "SPD: ${data.speed.toInt()} m/s",
            textPadding,
            textPaint.textSize * 2 + textPadding,
            textPaint
        )
        
        // Draw heading
        canvas.drawText(
            "HDG: ${data.heading.toInt()}°",
            width - 200f,
            textPaint.textSize + textPadding,
            textPaint
        )
        
        // Draw collision warning if detected
        val timeToCollision = collisionDetector?.detectCollision() ?: -1f
        if (timeToCollision > 0) {
            val warningText = "COLLISION WARNING: ${timeToCollision.toInt()} sec"
            textPaint.color = Color.RED
            textPaint.textSize = 60f
            
            canvas.drawText(
                warningText,
                width / 2 - textPaint.measureText(warningText) / 2,
                height / 2,
                textPaint
            )
            
            // Reset paint
            textPaint.color = Color.WHITE
            textPaint.textSize = 40f
        }
    }
} 