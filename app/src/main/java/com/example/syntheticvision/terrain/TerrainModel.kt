package com.example.syntheticvision.terrain

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.floor

class TerrainModel(private val context: Context) {
    // Данные высот рельефа
    private var heightMap: Array<Array<Float>> = emptyArray()
    
    // Географические границы карты высот
    private var minLatitude: Double = 0.0
    private var maxLatitude: Double = 0.0
    private var minLongitude: Double = 0.0
    private var maxLongitude: Double = 0.0
    
    // Разрешение карты высот
    private var latResolution: Double = 0.0
    private var lonResolution: Double = 0.0
    
    // Метаданные из MBTiles
    private var minZoom: Int = 0
    private var maxZoom: Int = 0
    private var defaultZoom: Int = 0  // Уровень масштабирования для загрузки данных
    
    /**
     * Загружает данные о рельефе из файла MBTiles
     */
    fun loadTerrainData(fileName: String) {
        try {
            Log.d("TerrainModel", "Loading terrain data from $fileName")
            
            // Копируем файл из assets во временный файл для доступа к нему через SQLite
            val tempFile = copyAssetToTemp(fileName)
            
            // Открываем базу данных SQLite
            val database = SQLiteDatabase.openDatabase(
                tempFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            
            // Читаем метаданные из таблицы metadata
            readMetadata(database)
            
            // Загружаем данные о высотах из тайлов
            loadHeightData(database)
            
            // Закрываем базу данных
            database.close()
            
            // Удаляем временный файл
            tempFile.delete()
            
            Log.d("TerrainModel", "Terrain data loaded successfully")
        } catch (e: Exception) {
            Log.e("TerrainModel", "Error loading terrain data: ${e.message}")
            e.printStackTrace()
            
            // В случае ошибки создаем тестовые данные для демонстрации
            createDemoTerrainData()
        }
    }
    
    /**
     * Копирует файл из assets во временный файл
     */
    private fun copyAssetToTemp(assetName: String): File {
        val tempFile = File(context.cacheDir, "temp_mbtiles.db")
        
        if (tempFile.exists()) {
            tempFile.delete()
        }
        
        context.assets.open(assetName).use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        
        return tempFile
    }
    
    /**
     * Читает метаданные MBTiles из таблицы metadata
     */
    private fun readMetadata(database: SQLiteDatabase) {
        val cursor = database.query(
            "metadata",
            arrayOf("name", "value"),
            null,
            null,
            null,
            null,
            null
        )
        
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0)
                val value = it.getString(1)
                
                when (name) {
                    "bounds" -> {
                        // Формат bounds: "minLon,minLat,maxLon,maxLat"
                        val parts = value.split(",")
                        if (parts.size == 4) {
                            minLongitude = parts[0].toDouble()
                            minLatitude = parts[1].toDouble()
                            maxLongitude = parts[2].toDouble()
                            maxLatitude = parts[3].toDouble()
                        }
                    }
                    "minzoom" -> minZoom = value.toIntOrNull() ?: 0
                    "maxzoom" -> maxZoom = value.toIntOrNull() ?: 0
                }
            }
        }
        
        // Выбираем уровень масштабирования для загрузки данных
        // Обычно выбирается средний уровень для баланса между детализацией и производительностью
        defaultZoom = ((minZoom + maxZoom) / 2).coerceAtLeast(10).coerceAtMost(14)
        
        Log.d("TerrainModel", "Metadata loaded: bounds=[$minLongitude,$minLatitude,$maxLongitude,$maxLatitude], zoom=$defaultZoom")
    }
    
    /**
     * Загружает данные о высотах из тайлов
     */
    private fun loadHeightData(database: SQLiteDatabase) {
        // Вычисляем границы тайлов для выбранного масштаба
        val (minTileX, minTileY) = latLonToTile(maxLatitude, minLongitude, defaultZoom)
        val (maxTileX, maxTileY) = latLonToTile(minLatitude, maxLongitude, defaultZoom)
        
        // Создаем сетку для загрузки высот
        val gridWidth = 256  // Стандартный размер тайла
        val gridHeight = 256
        
        // Определяем размер итоговой сетки высот
        val totalWidth = ((maxTileX - minTileX + 1) * gridWidth).coerceAtMost(1024)
        val totalHeight = ((maxTileY - minTileY + 1) * gridHeight).coerceAtMost(1024)
        
        // Создаем массив высот нужного размера
        heightMap = Array(totalHeight) { Array(totalWidth) { 0f } }
        
        // Загружаем и обрабатываем каждый тайл
        for (tileX in minTileX..maxTileX) {
            for (tileY in minTileY..maxTileY) {
                loadTile(database, tileX, tileY, defaultZoom, minTileX, minTileY, gridWidth, gridHeight)
            }
        }
        
        // Вычисляем разрешение сетки
        latResolution = (maxLatitude - minLatitude) / totalHeight
        lonResolution = (maxLongitude - minLongitude) / totalWidth
        
        Log.d("TerrainModel", "Height map created: ${heightMap.size}x${heightMap[0].size}")
    }
    
    /**
     * Загружает данные о высотах из одного тайла
     */
    private fun loadTile(
        database: SQLiteDatabase,
        tileX: Int, tileY: Int, zoom: Int,
        offsetTileX: Int, offsetTileY: Int,
        gridWidth: Int, gridHeight: Int
    ) {
        val cursor = database.query(
            "tiles",
            arrayOf("tile_data"),
            "zoom_level = ? AND tile_column = ? AND tile_row = ?",
            arrayOf(zoom.toString(), tileX.toString(), tileY.toString()),
            null,
            null,
            null
        )
        
        if (cursor.moveToFirst()) {
            val tileData = cursor.getBlob(0)
            
            try {
                // Декодируем тайл как растровое изображение
                val bitmap = BitmapFactory.decodeByteArray(tileData, 0, tileData.size)
                
                if (bitmap != null) {
                    // Вычисляем позицию в итоговом массиве высот
                    val startX = ((tileX - offsetTileX) * gridWidth).coerceAtLeast(0)
                    val startY = ((tileY - offsetTileY) * gridHeight).coerceAtLeast(0)
                    
                    // Извлекаем данные о высотах из растрового изображения
                    extractHeightsFromBitmap(bitmap, startX, startY)
                    
                    // Освобождаем ресурсы
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                Log.e("TerrainModel", "Error decoding tile at ($tileX, $tileY, $zoom): ${e.message}")
            }
        }
        
        cursor.close()
    }
    
    /**
     * Извлекает данные о высотах из растрового изображения тайла
     */
    private fun extractHeightsFromBitmap(bitmap: Bitmap, startX: Int, startY: Int) {
        val width = bitmap.width.coerceAtMost(heightMap[0].size - startX)
        val height = bitmap.height.coerceAtMost(heightMap.size - startY)
        
        // В MBTiles с данными высот обычно используется RGB кодирование
        // где каждый цвет пикселя представляет высоту
        // R + G*256 + B*65536 - распространенная схема
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (startY + y < heightMap.size && startX + x < heightMap[0].size) {
                    val pixel = bitmap.getPixel(x, y)
                    
                    // Извлекаем RGB компоненты
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    
                    // Преобразуем в высоту (пример: используем только красный канал)
                    // Настоящий алгоритм зависит от формата ваших данных
                    val height = r + g * 256 + b * 65536
                    
                    // Нормализуем и сохраняем
                    heightMap[startY + y][startX + x] = height * 0.1f // примерный масштаб
                }
            }
        }
    }
    
    /**
     * Преобразует географические координаты в координаты тайла
     */
    private fun latLonToTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val n = 2.0.pow(zoom)
        val tileX = ((lon + 180.0) / 360.0 * n).toInt()
        val latRad = Math.toRadians(lat)
        val tileY = ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()
        return Pair(tileX, tileY)
    }
    
    /**
     * Создает демонстрационные данные рельефа для тестирования
     */
    private fun createDemoTerrainData() {
        Log.d("TerrainModel", "Creating demo terrain data")
        
        // Размер карты высот
        val latSize = 100
        val lonSize = 100
        
        // Устанавливаем границы (примерные значения - настройте под ваш регион)
        minLatitude = 59.0
        maxLatitude = 61.0
        minLongitude = 29.0
        maxLongitude = 31.0
        
        // Вычисляем разрешение
        latResolution = (maxLatitude - minLatitude) / (latSize - 1)
        lonResolution = (maxLongitude - minLongitude) / (lonSize - 1)
        
        // Создаем массив высот с простым рельефом
        heightMap = Array(latSize) { i ->
            Array(lonSize) { j ->
                // Простая формула для создания холмов и впадин
                100f + 50f * Math.sin(i * 0.1).toFloat() + 50f * Math.cos(j * 0.1).toFloat()
            }
        }
    }
    
    /**
     * Получает высоту в указанной точке (широта/долгота)
     */
    fun getHeightAt(latitude: Double, longitude: Double): Float {
        if (heightMap.isEmpty() || 
            latitude < minLatitude || latitude > maxLatitude ||
            longitude < minLongitude || longitude > maxLongitude) {
            return 0f // Значение по умолчанию, если за пределами границ
        }
        
        // Вычисляем индексы в карте высот
        val latIndex = ((latitude - minLatitude) / latResolution).toInt()
        val lonIndex = ((longitude - minLongitude) / lonResolution).toInt()
        
        // Проверка границ
        val safeLatIndex = min(max(0, latIndex), heightMap.size - 1)
        val safeLonIndex = min(max(0, lonIndex), heightMap[0].size - 1)
        
        return heightMap[safeLatIndex][safeLonIndex]
    }
    
    /**
     * Получает высоты для указанной области
     */
    fun getHeightsForArea(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double
    ): List<Triple<Double, Double, Float>> {
        val heights = mutableListOf<Triple<Double, Double, Float>>()
        
        // Проверяем, загружена ли карта высот
        if (heightMap.isEmpty()) return heights
        
        // Вычисляем индексы в карте высот
        val minLatIndex = max(0, ((minLat - minLatitude) / latResolution).toInt())
        val maxLatIndex = min(heightMap.size - 1, ((maxLat - minLatitude) / latResolution).toInt())
        val minLonIndex = max(0, ((minLon - minLongitude) / lonResolution).toInt())
        val maxLonIndex = min(heightMap[0].size - 1, ((maxLon - minLongitude) / lonResolution).toInt())
        
        for (latIdx in minLatIndex..maxLatIndex) {
            for (lonIdx in minLonIndex..maxLonIndex) {
                val lat = minLatitude + latIdx * latResolution
                val lon = minLongitude + lonIdx * lonResolution
                heights.add(Triple(lat, lon, heightMap[latIdx][lonIdx]))
            }
        }
        
        return heights
    }
} 