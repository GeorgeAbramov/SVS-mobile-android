package com.example.syntheticvision.view

import android.content.Context
import android.database.sqlite.SQLiteException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Log
import org.osmdroid.tileprovider.IRegisterReceiver
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck
import org.osmdroid.tileprovider.modules.MapTileFilesystemProvider
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.modules.SqliteArchiveTileWriter
import org.osmdroid.tileprovider.modules.TileWriter
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import android.database.sqlite.SQLiteDatabase
import android.database.Cursor
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.constants.OpenStreetMapTileProviderConstants
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MinimapOverlay
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider

/**
 * MapView для отображения карт, включая поддержку MBTiles
 */
class MapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : MapView(context, attrs) {
    
    companion object {
        private const val TAG = "MapView"
        private const val DEFAULT_MIN_ZOOM = 3
        private const val DEFAULT_MAX_ZOOM = 18
        private const val THIS_TABLE_NAME = "this" // таблица 'this' вместо 'tiles' для MBTiles
    }
    
    // Источник данных для MBTiles
    private var mbTilesSource: OnlineTileSourceBase? = null
    
    // Путь к файлу MBTiles
    private var mbTilesPath: String? = null
    
    /**
     * Устанавливает источник данных из MBTiles-файла
     */
    fun setupOfflineSource(mbTilesFile: File) {
        try {
            mbTilesPath = mbTilesFile.absolutePath
            
            // Сохраняем путь в локальную переменную
            val tilesPath = mbTilesPath
            if (tilesPath == null) {
                Log.e(TAG, "MBTiles path is null")
                return
            }
            
            // Открываем базу данных SQLite
            val database = SQLiteDatabase.openDatabase(
                tilesPath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            
            try {
                // Проверяем, есть ли в базе таблица 'this' вместо стандартной 'tiles'
                // (это может случиться, когда MBTiles создаются нестандартным способом)
                val hasThisTable = checkTableExists(database, THIS_TABLE_NAME)
                val hasTilesTable = checkTableExists(database, "tiles")
                
                Log.d(TAG, "MBTiles database has 'this' table: $hasThisTable, 'tiles' table: $hasTilesTable")
                
                if (hasThisTable) {
                    // Используем 'this' таблицу вместо 'tiles'
                    Log.d(TAG, "Using 'this' table for MBTiles")
                    setupThisTableSource(database)
                } else if (hasTilesTable) {
                    // Используем стандартную 'tiles' таблицу
                    Log.d(TAG, "Using standard 'tiles' table for MBTiles")
                    setupTilesTableSource(database)
                } else {
                    Log.e(TAG, "MBTiles file does not contain required tables")
                    return
                }
                
                // Устанавливаем источник тайлов и обновляем карту
                this.setTileSource(mbTilesSource)
                this.invalidate()
                
                // Устанавливаем границы карты из метаданных
                setupMapBounds(database)
                
            } finally {
                database.close()
            }
            
            Log.d(TAG, "MBTiles source set up successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up MBTiles source", e)
        }
    }
    
    /**
     * Проверяет, существует ли таблица в базе данных
     */
    private fun checkTableExists(database: SQLiteDatabase, tableName: String): Boolean {
        var cursor: Cursor? = null
        try {
            // Запрос к sqlite_master для проверки существования таблицы
            cursor = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(tableName)
            )
            return cursor != null && cursor.count > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if table exists: $tableName", e)
            return false
        } finally {
            cursor?.close()
        }
    }
    
    /**
     * Настраивает источник тайлов из стандартной таблицы 'tiles'
     */
    private fun setupTilesTableSource(database: SQLiteDatabase) {
        try {
            // Читаем метаданные
            val metadata = readMetadata(database)
            
            // Получаем границы масштабирования
            val minZoom = metadata["minzoom"]?.toIntOrNull() ?: DEFAULT_MIN_ZOOM
            val maxZoom = metadata["maxzoom"]?.toIntOrNull() ?: DEFAULT_MAX_ZOOM
            
            // Сохраняем путь в локальную переменную
            val tilesPath = mbTilesPath
            if (tilesPath == null) {
                Log.e(TAG, "MBTiles path is null")
                return
            }
            
            // Создаем источник тайлов
            mbTilesSource = MBTilesStandardSource(
                "mbtiles_${System.currentTimeMillis()}",
                minZoom,
                maxZoom,
                256,
                ".png",
                arrayOf(""),
                tilesPath
            )
            
            Log.d(TAG, "Set up standard MBTiles source with zoom levels: $minZoom-$maxZoom")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up standard tiles source", e)
        }
    }
    
    /**
     * Настраивает источник тайлов из таблицы 'this' (нестандартный случай)
     */
    private fun setupThisTableSource(database: SQLiteDatabase) {
        try {
            // Читаем метаданные
            val metadata = readMetadata(database)
            
            // Получаем границы масштабирования
            val minZoom = metadata["minzoom"]?.toIntOrNull() ?: DEFAULT_MIN_ZOOM
            val maxZoom = metadata["maxzoom"]?.toIntOrNull() ?: DEFAULT_MAX_ZOOM
            
            Log.d(TAG, "Setting up 'this' table source with zoom: $minZoom-$maxZoom")
            
            // Проверяем, какие столбцы есть в таблице 'this'
            val columns = getTableColumns(database, THIS_TABLE_NAME)
            Log.d(TAG, "Columns in 'this' table: $columns")
            
            // Сохраняем путь в локальную переменную
            val tilesPath = mbTilesPath
            if (tilesPath == null) {
                Log.e(TAG, "MBTiles path is null")
                return
            }
            
            // Создаем источник тайлов для таблицы 'this'
            mbTilesSource = MBTilesThisTableSource(
                "mbtiles_this_${System.currentTimeMillis()}",
                minZoom,
                maxZoom,
                256,
                ".png",
                arrayOf(""),
                tilesPath
            )
            
            Log.d(TAG, "Set up 'this' table MBTiles source with zoom levels: $minZoom-$maxZoom")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up 'this' table source", e)
        }
    }
    
    /**
     * Получает список столбцов в таблице
     */
    private fun getTableColumns(database: SQLiteDatabase, tableName: String): List<String> {
        val columns = mutableListOf<String>()
        var cursor: Cursor? = null
        
        try {
            cursor = database.rawQuery("PRAGMA table_info($tableName)", null)
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val columnName = cursor.getString(cursor.getColumnIndex("name"))
                    columns.add(columnName)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting table columns", e)
        } finally {
            cursor?.close()
        }
        
        return columns
    }
    
    /**
     * Читает метаданные из MBTiles
     */
    private fun readMetadata(database: SQLiteDatabase): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        var cursor: Cursor? = null
        
        try {
            if (checkTableExists(database, "metadata")) {
                cursor = database.query(
                    "metadata",
                    arrayOf("name", "value"),
                    null,
                    null,
                    null,
                    null,
                    null
                )
                
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        val name = cursor.getString(0)
                        val value = cursor.getString(1)
                        metadata[name] = value
                        Log.d(TAG, "Metadata: $name = $value")
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading metadata", e)
        } finally {
            cursor?.close()
        }
        
        return metadata
    }
    
    /**
     * Устанавливает границы карты на основе метаданных
     */
    private fun setupMapBounds(database: SQLiteDatabase) {
        try {
            val metadata = readMetadata(database)
            val bounds = metadata["bounds"]
            
            if (bounds != null) {
                // Формат bounds: "minLon,minLat,maxLon,maxLat"
                val parts = bounds.split(",")
                if (parts.size == 4) {
                    val minLon = parts[0].toDouble()
                    val minLat = parts[1].toDouble()
                    val maxLon = parts[2].toDouble()
                    val maxLat = parts[3].toDouble()
                    
                    // Создаем границы карты
                    val boundingBox = BoundingBox(maxLat, maxLon, minLat, minLon)
                    
                    // Устанавливаем границы и центрируем карту
                    this.post {
                        this.zoomToBoundingBox(boundingBox, true)
                        
                        // Центрируем карту
                        val centerLat = (maxLat + minLat) / 2
                        val centerLon = (maxLon + minLon) / 2
                        this.controller.setCenter(GeoPoint(centerLat, centerLon))
                        
                        // Устанавливаем масштаб, чтобы границы помещались на экране
                        val zoomLevel = this.zoomLevelDouble
                        Log.d(TAG, "Map centered at $centerLat, $centerLon with zoom $zoomLevel")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting map bounds", e)
        }
    }
    
    /**
     * Источник тайлов для стандартной таблицы 'tiles' в MBTiles
     */
    private inner class MBTilesStandardSource(
        name: String, minZoom: Int, maxZoom: Int,
        tileSizePixels: Int, imageFilenameEnding: String,
        baseUrl: Array<String>, private val mbTilesPath: String
    ) : XYTileSource(name, minZoom, maxZoom, tileSizePixels, imageFilenameEnding, baseUrl) {
        
        fun getTileDrawable(pTileSource: Long): Drawable? {
            // Преобразуем индекс тайла в координаты
            val zoom = MapTileIndex.getZoom(pTileSource)
            val x = MapTileIndex.getX(pTileSource)
            val y = MapTileIndex.getY(pTileSource)
            
            Log.d(TAG, "Getting tile: z=$zoom, x=$x, y=$y from standard MBTiles")
            
            var cursor: Cursor? = null
            var database: SQLiteDatabase? = null
            
            try {
                // Открываем базу данных
                database = SQLiteDatabase.openDatabase(
                    mbTilesPath,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                )
                
                // В MBTiles y-координата инвертирована
                val flippedY = (1 shl zoom) - 1 - y
                
                // Запрос к базе данных
                cursor = database.query(
                    "tiles",
                    arrayOf("tile_data"),
                    "zoom_level = ? AND tile_column = ? AND tile_row = ?",
                    arrayOf(zoom.toString(), x.toString(), flippedY.toString()),
                    null,
                    null,
                    null
                )
                
                if (cursor != null && cursor.moveToFirst()) {
                    // Получаем данные тайла
                    val tileData = cursor.getBlob(0)
                    
                    // Преобразуем в Bitmap
                    if (tileData != null && tileData.isNotEmpty()) {
                        Log.d(TAG, "Tile data found: ${tileData.size} bytes")
                        val bitmap = BitmapFactory.decodeByteArray(tileData, 0, tileData.size)
                        if (bitmap != null) {
                            return BitmapDrawable(context.resources, bitmap)
                        }
                    }
                } else {
                    Log.d(TAG, "No tile data found for z=$zoom, x=$x, y=$flippedY")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error retrieving tile", e)
            } finally {
                cursor?.close()
                database?.close()
            }
            
            return null
        }
    }
    
    /**
     * Источник тайлов для нестандартной таблицы 'this' в MBTiles
     * (используется, когда таблица названа 'this' вместо 'tiles' для избежания конфликта с SQL)
     */
    private inner class MBTilesThisTableSource(
        name: String, minZoom: Int, maxZoom: Int,
        tileSizePixels: Int, imageFilenameEnding: String,
        baseUrl: Array<String>, private val mbTilesPath: String
    ) : OnlineTileSourceBase(name, minZoom, maxZoom, tileSizePixels, imageFilenameEnding, baseUrl) {
        
        override fun getTileURLString(pMapTileIndex: Long): String {
            // Формируем URL в формате z/x/y
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return "$zoom/$x/$y"
        }
        
        fun getTileDrawable(pTileSource: Long): Drawable? {
            try {
                // Получаем URL тайла
                val tileUrl = getTileURLString(pTileSource)
                Log.d(TAG, "Getting tile: $tileUrl from 'this' table MBTiles")
                
                // Разбираем URL на компоненты
                val parts = tileUrl.split("/")
                if (parts.size != 3) {
                    Log.e(TAG, "Invalid tile URL format: $tileUrl")
                    return null
                }
                
                val zoom = parts[0].toInt()
                val x = parts[1].toInt()
                val y = parts[2].toInt()
                
                var cursor: Cursor? = null
                var database: SQLiteDatabase? = null
                
                try {
                    // Открываем базу данных
                    database = SQLiteDatabase.openDatabase(
                        mbTilesPath,
                        null,
                        SQLiteDatabase.OPEN_READONLY
                    )
                    
                    if (database == null) {
                        Log.e(TAG, "Failed to open database: $mbTilesPath")
                        return null
                    }
                    
                    // В MBTiles y-координата инвертирована
                    val flippedY = (1 shl zoom) - 1 - y
                    
                    // Запрос к базе данных с 'this' таблицей
                    cursor = database.query(
                        "\"$THIS_TABLE_NAME\"", // Кавычки нужны из-за зарезервированного слова 'this'
                        arrayOf("tile_data"),
                        "zoom_level = ? AND tile_column = ? AND tile_row = ?",
                        arrayOf(zoom.toString(), x.toString(), flippedY.toString()),
                        null,
                        null,
                        null
                    )
                    
                    if (cursor != null && cursor.moveToFirst()) {
                        // Получаем данные тайла
                        val tileData = cursor.getBlob(0)
                        
                        // Преобразуем в Bitmap
                        if (tileData != null && tileData.isNotEmpty()) {
                            Log.d(TAG, "Tile data found: ${tileData.size} bytes")
                            val bitmap = BitmapFactory.decodeByteArray(tileData, 0, tileData.size)
                            if (bitmap != null) {
                                return BitmapDrawable(context.resources, bitmap)
                            } else {
                                Log.e(TAG, "Failed to decode tile bitmap")
                            }
                        } else {
                            Log.e(TAG, "Tile data is empty")
                        }
                    } else {
                        Log.d(TAG, "No tile data found for z=$zoom, x=$x, y=$flippedY")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error retrieving tile", e)
                    e.printStackTrace()
                } finally {
                    cursor?.close()
                    database?.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing tile", e)
            }
            
            return null
        }
    }
} 