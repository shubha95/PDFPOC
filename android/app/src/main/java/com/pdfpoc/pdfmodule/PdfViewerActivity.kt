package com.pdfpoc.pdfmodule

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.TopAppBar
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Slider
import androidx.compose.material.Card
import androidx.compose.runtime.*
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

class PdfViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uriString = intent?.getStringExtra("pdf_uri") ?: ""
        val uri = Uri.parse(uriString)

        setContent {
            MaterialTheme {
                PdfRendererView(uri)
            }
        }
    }
}

// Data class for render requests
data class RenderRequest(
    val pageIndex: Int,
    val targetScale: Float,
    val priority: Int  // 0 = highest (visible), 1 = medium (near), 2 = low (prefetch)
)

@Composable
fun PdfRendererView(uri: Uri) {
    var pageCount by remember { mutableStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Changed: Use a cache map with scale info - only keep visible pages in memory
    var pageCache by remember { mutableStateOf<Map<Int, Pair<Bitmap, Float>>>(emptyMap()) }
    var scale by remember { mutableStateOf(0.3f) } // Default at 30%
    var isInitialLoading by remember { mutableStateOf(true) }
    var loadingProgress by remember { mutableStateOf(0) }  // 0-100 percentage
    var loadingStatus by remember { mutableStateOf("Opening PDF...") }
    var lastRenderedScale by remember { mutableStateOf(0.3f) }
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var isZoomInProgress by remember { mutableStateOf(false) }
    var isPinchZoomActive by remember { mutableStateOf(false) }  // Track active pinch gesture
    var lastPinchZoomTime by remember { mutableStateOf(0L) }  // Track last pinch zoom time
    var lastScaleUpdateTime by remember { mutableStateOf(0L) }  // Throttle scale updates
    
    val renderMutex = remember { Mutex() }
    
    // NEW: Render job queue with channel - processes requests sequentially
    val renderQueue = remember { Channel<RenderRequest>(capacity = Channel.UNLIMITED) }
    
    // Batch loading configuration - reduced for better performance
    val batchSize = 15  // Load 15 pages per batch (reduced from 20)
    var loadedBatches by remember { mutableStateOf<Set<Int>>(emptySet()) }  // Track which batches are loaded
    val maxCacheSize = 45  // Keep max 3 batches (45 pages) in memory (not strictly enforced since all pages are loaded)
    
    val renderingPages = remember { ConcurrentHashMap<Int, Long>() }  // Thread-safe - pageIndex -> timestamp when rendering started
    val queuedPages = remember { mutableStateOf<Set<Int>>(emptySet()) }  // Track pages queued for rendering
    val activeBitmaps = remember { mutableSetOf<Bitmap>() }  // Track active bitmaps to prevent recycling
    val RENDERING_TIMEOUT_MS = 10000L  // 10 seconds - if a page is "rendering" longer than this, consider it stuck
    
    // Get screen width and density for proper sizing
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    // Internal rendering function (called by worker)
    suspend fun renderPageInternal(pageIndex: Int, targetScale: Float): Bitmap? = withContext(Dispatchers.Default) {
        
        // Validate page index
        if (pageIndex < 0 || pageIndex >= pageCount) {
            android.util.Log.e("PdfRenderer", "Invalid page index: $pageIndex (pageCount: $pageCount)")
            return@withContext null
        }
        
        try {
            val renderer = pdfRenderer
            if (renderer == null) {
                android.util.Log.e("PdfRenderer", "Renderer is null for page $pageIndex")
                return@withContext null
            }
            
            var page: PdfRenderer.Page? = null
            var bitmap: Bitmap? = null
            
            try {
                // Use mutex to safely open page (PdfRenderer is not thread-safe)
                page = renderMutex.withLock {
                    try {
                        // Double-check renderer is still valid
                        val currentRenderer = pdfRenderer
                        if (currentRenderer == null) {
                            android.util.Log.e("PdfRenderer", "Renderer became null during lock")
                            return@withLock null
                        }
                        
                        // Validate page index again
                        if (pageIndex < 0 || pageIndex >= pageCount) {
                            android.util.Log.e("PdfRenderer", "Invalid page index in lock: $pageIndex")
                            return@withLock null
                        }
                        
                        currentRenderer.openPage(pageIndex)
                    } catch (e: IllegalStateException) {
                        android.util.Log.e("PdfRenderer", "IllegalState opening page $pageIndex: ${e.message}")
                        null
                    } catch (e: IllegalArgumentException) {
                        android.util.Log.e("PdfRenderer", "IllegalArgument opening page $pageIndex: ${e.message}")
                        null
                    } catch (e: Exception) {
                        android.util.Log.e("PdfRenderer", "Error opening page $pageIndex: ${e.message}")
                        e.printStackTrace()
                        null
                    }
                }
                
                if (page == null) {
                    return@withContext null
                }
                
                val scaleFactor = (targetScale * density * 4f).coerceIn(1.5f, 4f)
                val width = (page.width * scaleFactor).toInt()
                val height = (page.height * scaleFactor).toInt()
                
                // Check memory availability
                val runtime = Runtime.getRuntime()
                val maxMemory = runtime.maxMemory()
                val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                val availableMemory = maxMemory - usedMemory
                val bitmapSize = (width * height * 4).toLong()
                
                bitmap = try {
                    if (bitmapSize > availableMemory * 0.8) {
                        // Use lower quality if memory is tight
                        val reducedScale = scaleFactor * 0.7f
                        val reducedWidth = (page.width * reducedScale).toInt()
                        val reducedHeight = (page.height * reducedScale).toInt()
                        Bitmap.createBitmap(reducedWidth, reducedHeight, Bitmap.Config.RGB_565)
                    } else {
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    }
                } catch (e: OutOfMemoryError) {
                    android.util.Log.e("PdfRenderer", "OOM creating bitmap for page $pageIndex")
                    System.gc()
                    null
                }
                
                if (bitmap == null) {
                    return@withContext null
                }
                
                // Track this bitmap as active
                activeBitmaps.add(bitmap)
                
                // Render page to bitmap with error handling
                try {
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    android.util.Log.d("PdfRenderer", "Successfully rendered page $pageIndex")
                    bitmap
                } catch (e: IllegalStateException) {
                    android.util.Log.e("PdfRenderer", "IllegalState rendering page $pageIndex: ${e.message}")
                    // Clean up bitmap
                    try {
                        activeBitmaps.remove(bitmap)
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    } catch (ex: Exception) {
                        android.util.Log.e("PdfRenderer", "Error cleaning up bitmap: ${ex.message}")
                    }
                    null
                } catch (e: Exception) {
                    android.util.Log.e("PdfRenderer", "Error rendering page $pageIndex to bitmap: ${e.message}")
                    e.printStackTrace()
                    // Clean up bitmap
                    try {
                        activeBitmaps.remove(bitmap)
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    } catch (ex: Exception) {
                        android.util.Log.e("PdfRenderer", "Error cleaning up bitmap: ${ex.message}")
                    }
                    null
                }
                
            } catch (e: Exception) {
                android.util.Log.e("PdfRenderer", "Error rendering page $pageIndex: ${e.message}")
                e.printStackTrace()
                
                // Clean up bitmap if created
                bitmap?.let {
                    try {
                        if (!it.isRecycled) {
                            it.recycle()
                        }
                        activeBitmaps.remove(it)
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                }
                null
            } finally {
                try {
                    page?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PdfRenderer", "Unexpected error in renderPageInternal: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    // Watchdog to clear stuck pages in renderingPages
    LaunchedEffect(Unit) {
        launch(Dispatchers.Default) {
            while (true) {
                try {
                    delay(2000) // Check every 2 seconds
                    val currentTime = System.currentTimeMillis()
                    val stuckPages = mutableListOf<Int>()
                    
                    // Check for stuck pages in renderingPages
                    renderingPages.forEach { (pageIndex, startTime) ->
                        if (currentTime - startTime > RENDERING_TIMEOUT_MS) {
                            android.util.Log.w("PdfRenderer", "Page $pageIndex stuck in rendering state for ${currentTime - startTime}ms, clearing")
                            stuckPages.add(pageIndex)
                        }
                    }
                    
                    // Clear stuck pages from both renderingPages and queuedPages
                    if (stuckPages.isNotEmpty()) {
                        stuckPages.forEach { pageIndex ->
                            renderingPages.remove(pageIndex)
                        }
                        
                        withContext(Dispatchers.Main) {
                            val currentQueued = queuedPages.value
                            queuedPages.value = currentQueued - stuckPages.toSet()
                        }
                        
                        android.util.Log.d("PdfRenderer", "Cleared ${stuckPages.size} stuck pages: $stuckPages")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PdfRenderer", "Error in watchdog: ${e.message}")
                    e.printStackTrace()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    android.util.Log.d("PdfRenderer", "Watchdog cancelled")
                    throw e
                }
            }
        }
    }
    
    // NEW: Dedicated render worker - processes one page at a time smoothly
    LaunchedEffect(Unit) {
        launch(Dispatchers.Default) {
            android.util.Log.d("PdfRenderer", "Render worker started")
            
            try {
                for (request in renderQueue) {
                    var pageIndex: Int? = null
                    try {
                        pageIndex = request.pageIndex
                        
                        // Check if PDF renderer is still valid
                        if (pdfRenderer == null) {
                            android.util.Log.w("PdfRenderer", "PDF renderer is null, skipping page ${request.pageIndex}")
                            renderingPages.remove(request.pageIndex)
                            withContext(Dispatchers.Main) {
                                queuedPages.value = queuedPages.value - request.pageIndex
                            }
                            continue
                        }
                        
                        // Validate page index
                        if (request.pageIndex < 0 || request.pageIndex >= pageCount) {
                            android.util.Log.w("PdfRenderer", "Invalid page index ${request.pageIndex}, skipping")
                            renderingPages.remove(request.pageIndex)
                            withContext(Dispatchers.Main) {
                                queuedPages.value = queuedPages.value - request.pageIndex
                            }
                            continue
                        }
                        
                        // Check if zoom is in progress - skip if so to avoid conflicts
                        val zoomActive = withContext(Dispatchers.Main) { isZoomInProgress }
                        if (zoomActive) {
                            android.util.Log.d("PdfRenderer", "Zoom in progress, skipping page ${request.pageIndex}")
                            renderingPages.remove(request.pageIndex)
                            try {
                                withContext(Dispatchers.Main) {
                                    queuedPages.value = queuedPages.value - request.pageIndex
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("PdfRenderer", "Error updating queuedPages: ${e.message}")
                            }
                            continue
                        }
                        
                        // Check if scale has changed significantly since request was queued
                        // Skip if last rendered scale differs by more than 10% from requested scale
                        // This prevents rendering pages at stale scales during rapid zoom changes
                        val currentRenderedScale = withContext(Dispatchers.Main) { lastRenderedScale }
                        if (kotlin.math.abs(currentRenderedScale - request.targetScale) > 0.10f) {
                            android.util.Log.d("PdfRenderer", "Page ${request.pageIndex} scale stale (requested ${request.targetScale}, current rendered $currentRenderedScale), skipping")
                            renderingPages.remove(request.pageIndex)
                            try {
                                withContext(Dispatchers.Main) {
                                    queuedPages.value = queuedPages.value - request.pageIndex
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("PdfRenderer", "Error updating queuedPages: ${e.message}")
                            }
                            continue
                        }
                        
                        // Skip if already cached at this scale (with error handling)
                        val cached = try {
                            withContext(Dispatchers.Main) { pageCache[request.pageIndex] }
                        } catch (e: Exception) {
                            android.util.Log.e("PdfRenderer", "Error accessing pageCache: ${e.message}")
                            null
                        }
                        if (cached != null && kotlin.math.abs(cached.second - request.targetScale) < 0.08f) {
                            android.util.Log.d("PdfRenderer", "Page ${request.pageIndex} already cached at correct scale, skipping")
                            // Remove from queued if it was there
                            try {
                                withContext(Dispatchers.Main) {
                                    queuedPages.value = queuedPages.value - request.pageIndex
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("PdfRenderer", "Error updating queuedPages: ${e.message}")
                            }
                            continue
                        }
                        
                        // Check if page is being rendered - but allow stale entries to be re-rendered
                        val renderStartTime = renderingPages[request.pageIndex]
                        val currentTime = System.currentTimeMillis()
                        val isStale = renderStartTime != null && (currentTime - renderStartTime) > RENDERING_TIMEOUT_MS
                        
                        if (renderStartTime != null && !isStale) {
                            android.util.Log.d("PdfRenderer", "Page ${request.pageIndex} already in progress (started ${currentTime - renderStartTime}ms ago), skipping")
                            // Remove from queued if it was there
                            try {
                                withContext(Dispatchers.Main) {
                                    queuedPages.value = queuedPages.value - request.pageIndex
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("PdfRenderer", "Error updating queuedPages: ${e.message}")
                            }
                            continue
                        }
                        
                        if (isStale && renderStartTime != null) {
                            android.util.Log.w("PdfRenderer", "Page ${request.pageIndex} rendering timeout (${currentTime - renderStartTime}ms), re-rendering")
                            renderingPages.remove(request.pageIndex)  // Clear stale entry
                        }
                        
                        // Check memory before rendering
                        val runtime = Runtime.getRuntime()
                        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                        val maxMemory = runtime.maxMemory()
                        val memoryUsagePercent = (usedMemory * 100 / maxMemory).toInt()
                        
                        // If memory is high, clean cache before rendering
                        if (memoryUsagePercent > 75) {
                            android.util.Log.w("PdfRenderer", "Memory usage high ($memoryUsagePercent%), cleaning cache before rendering page ${request.pageIndex}")
                            // Clean cache aggressively - keep only current page's batch
                            val currentBatch = request.pageIndex / batchSize
                            val batchesToKeep = setOf(currentBatch - 1, currentBatch, currentBatch + 1)
                                .filter { batchNum -> batchNum >= 0 && batchNum * batchSize < pageCount }
                            
                            try {
                                withContext(Dispatchers.Main) {
                                    val pagesToKeep = pageCache.filter { (pageIndex, _) ->
                                        val pageBatch = pageIndex / batchSize
                                        batchesToKeep.contains(pageBatch)
                                    }
                                    
                                    // Recycle removed pages
                                    pageCache.forEach { (pageIndex, pair) ->
                                        if (!pagesToKeep.containsKey(pageIndex)) {
                                            try {
                                                activeBitmaps.remove(pair.first)
                                                if (!pair.first.isRecycled) {
                                                    pair.first.recycle()
                                                }
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        }
                                    }
                                    
                                    pageCache = pagesToKeep
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("PdfRenderer", "Error cleaning cache: ${e.message}")
                            }
                            System.gc()
                        }
                        
                        // Remove from queued and mark as rendering with timestamp
                        try {
                            withContext(Dispatchers.Main) {
                                queuedPages.value = queuedPages.value - request.pageIndex
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("PdfRenderer", "Error updating queuedPages: ${e.message}")
                        }
                        
                        renderingPages[request.pageIndex] = System.currentTimeMillis()
                        android.util.Log.d("PdfRenderer", "Worker rendering page ${request.pageIndex} at scale ${request.targetScale} (priority ${request.priority})")
                        
                        var bitmap: Bitmap? = null
                        var renderSuccess = false
                        
                        try {
                            bitmap = renderPageInternal(request.pageIndex, request.targetScale)
                        } catch (e: OutOfMemoryError) {
                            android.util.Log.e("PdfRenderer", "OOM rendering page ${request.pageIndex}, cleaning cache")
                            // Clean cache aggressively
                            try {
                                withContext(Dispatchers.Main) {
                                    val currentBatch = request.pageIndex / batchSize
                                    val batchesToKeep = setOf(currentBatch).filter { batchNum -> batchNum >= 0 }
                                    pageCache = pageCache.filter { (idx, _) ->
                                        val pageBatch = idx / batchSize
                                        batchesToKeep.contains(pageBatch)
                                    }
                                }
                            } catch (ex: Exception) {
                                android.util.Log.e("PdfRenderer", "Error cleaning cache: ${ex.message}")
                            }
                            System.gc()
                            delay(100)
                            bitmap = null // Return null, page will be retried later
                        } catch (e: Exception) {
                            android.util.Log.e("PdfRenderer", "Unexpected error rendering page ${request.pageIndex}: ${e.message}")
                            e.printStackTrace()
                            bitmap = null
                        }
                        
                        if (bitmap != null) {
                            // Recycle old bitmap safely
                            val oldCachedBitmap = cached?.first
                            if (oldCachedBitmap != null) {
                                try {
                                    activeBitmaps.remove(oldCachedBitmap)
                                    if (!oldCachedBitmap.isRecycled) {
                                        oldCachedBitmap.recycle()
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("PdfRenderer", "Error recycling bitmap: ${e.message}")
                                }
                            }
                            
                            // Update cache on main thread
                            try {
                                withContext(Dispatchers.Main) {
                                    pageCache = pageCache.toMutableMap().apply {
                                        put(request.pageIndex, bitmap!! to request.targetScale)
                                    }
                                }
                                renderSuccess = true
                                android.util.Log.d("PdfRenderer", "Worker completed page ${request.pageIndex}")
                            } catch (e: Exception) {
                                android.util.Log.e("PdfRenderer", "Error updating cache: ${e.message}")
                                // Still mark as success since bitmap was created
                                renderSuccess = true
                            }
                        }
                        
                        // ALWAYS remove from renderingPages, even on error
                        renderingPages.remove(request.pageIndex)
                        if (!renderSuccess) {
                            // Remove from queued if render failed
                            try {
                                withContext(Dispatchers.Main) {
                                    queuedPages.value = queuedPages.value - request.pageIndex
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("PdfRenderer", "Error updating queuedPages: ${e.message}")
                            }
                        }
                        
                        // Small delay to prevent CPU hogging
                        delay(5)
                        
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        android.util.Log.d("PdfRenderer", "Worker cancelled")
                        // Clean up on cancellation
                        if (pageIndex != null) {
                            renderingPages.remove(pageIndex)
                        }
                        throw e // Re-throw cancellation
                    } catch (e: Exception) {
                        android.util.Log.e("PdfRenderer", "Worker error processing request for page ${request.pageIndex}: ${e.message}")
                        e.printStackTrace()
                        // Ensure cleanup even if outer try fails
                        if (pageIndex != null) {
                            renderingPages.remove(pageIndex)
                            try {
                                withContext(Dispatchers.Main) {
                                    queuedPages.value = queuedPages.value - pageIndex
                                }
                            } catch (ex: Exception) {
                                android.util.Log.e("PdfRenderer", "Error cleaning up in outer catch: ${ex.message}")
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("PdfRenderer", "Render worker cancelled")
                throw e
            } catch (e: Exception) {
                android.util.Log.e("PdfRenderer", "Fatal error in render worker: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    // Function to calculate which batch a page belongs to
    fun getBatchNumber(pageIndex: Int): Int {
        return pageIndex / batchSize
    }
    
    // Function to get the page range for a batch
    fun getBatchRange(batchNumber: Int): IntRange {
        val startPage = batchNumber * batchSize
        val endPage = minOf((batchNumber + 1) * batchSize - 1, pageCount - 1)
        return startPage..endPage
    }
    
    // NEW: Clear render queue (call when scroll/zoom changes)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun clearRenderQueue() = withContext(Dispatchers.Default) {
        var cleared = 0
        try {
            while (!renderQueue.isEmpty) {
                renderQueue.tryReceive().getOrNull()?.let { cleared++ }
            }
        } catch (e: Exception) {
            android.util.Log.e("PdfRenderer", "Error clearing render queue: ${e.message}")
        }
        
        // Clear queued pages tracking and stuck rendering pages
        withContext(Dispatchers.Main) {
            queuedPages.value = emptySet()
        }
        
        // Also clear renderingPages to prevent stuck states
        renderingPages.clear()
        
        if (cleared > 0) {
            android.util.Log.d("PdfRenderer", "Cleared $cleared pending render requests from queue and all rendering states")
        }
    }
    
    // NEW: Load batch using queue system - submits pages to worker, no parallel jobs
    suspend fun loadBatch(batchNumber: Int, targetScale: Float, priority: Int = 2) = withContext(Dispatchers.Default) {
        if (loadedBatches.contains(batchNumber)) {
            android.util.Log.d("PdfRenderer", "Batch $batchNumber already loaded, skipping")
            return@withContext
        }
        
        val range = getBatchRange(batchNumber)
        android.util.Log.d("PdfRenderer", "Queuing batch $batchNumber: pages ${range.first}-${range.last} with priority $priority")
        
        // Submit each page to the render queue (worker will process them smoothly)
        for (pageIndex in range) {
            // Skip if already in cache at correct scale
            val cached = pageCache[pageIndex]
            val needsRender = cached == null || 
                kotlin.math.abs(cached.second - targetScale) > 0.08f
            
            if (needsRender) {
                val request = RenderRequest(pageIndex, targetScale, priority)
                renderQueue.send(request)  // Non-blocking send to queue
                
                // Track queued pages
                withContext(Dispatchers.Main) {
                    queuedPages.value = queuedPages.value + pageIndex
                }
                
                android.util.Log.d("PdfRenderer", "Queued page $pageIndex from batch $batchNumber")
            }
        }
        
        // Mark batch as loaded (pages are now queued for rendering)
        withContext(Dispatchers.Main) {
            loadedBatches = loadedBatches + batchNumber
        }
        
        android.util.Log.d("PdfRenderer", "Batch $batchNumber queued. Total batches loaded: ${loadedBatches.size}")
    }
    
    // Function to manage cache - remove batches that are far from current position
    suspend fun manageCacheSize(currentVisiblePage: Int) = withContext(Dispatchers.Default) {
        // Check memory usage and cache size
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryUsagePercent = (usedMemory * 100 / maxMemory).toInt()
        
        // More aggressive cache management if memory is high
        val effectiveCacheSize = if (memoryUsagePercent > 60) {
            maxCacheSize / 2  // Reduce cache size by half if memory > 60%
        } else if (memoryUsagePercent > 50) {
            (maxCacheSize * 2 / 3).toInt()  // Reduce to 2/3 if memory > 50%
        } else {
            maxCacheSize
        }
        
        if (pageCache.size > effectiveCacheSize || memoryUsagePercent > 70) {
            try {
                val currentBatch = getBatchNumber(currentVisiblePage)
                
                // Keep current batch and 1 batch before/after (3 batches total = 60 pages)
                val batchesToKeep = setOf(
                    currentBatch - 1,
                    currentBatch,
                    currentBatch + 1
                ).filter { it >= 0 && it * batchSize < pageCount }
                
                val pagesToKeep = mutableMapOf<Int, Pair<Bitmap, Float>>()
                val pagesToRemove = mutableListOf<Bitmap>()
                val batchesToUnload = mutableSetOf<Int>()
                
                pageCache.forEach { (pageIndex, pair) ->
                    val pageBatch = getBatchNumber(pageIndex)
                    if (batchesToKeep.contains(pageBatch)) {
                        pagesToKeep[pageIndex] = pair
                    } else {
                        pagesToRemove.add(pair.first)
                        batchesToUnload.add(pageBatch)
                    }
                }
                
                // Update cache on main thread
                withContext(Dispatchers.Main) {
                    pageCache = pagesToKeep
                    loadedBatches = loadedBatches.filter { batchesToKeep.contains(it) }.toSet()
                }
                
                // Small delay to ensure pages are not being displayed
                delay(100)
                
                // Safely recycle removed bitmaps
                pagesToRemove.forEach { bitmap ->
                    try {
                        // Don't recycle if still in active use
                        if (activeBitmaps.contains(bitmap)) {
                            android.util.Log.d("PdfRenderer", "Skipping recycle of active bitmap")
                            return@forEach
                        }
                        
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("PdfRenderer", "Error recycling bitmap: ${e.message}")
                        e.printStackTrace()
                    }
                }
                
                android.util.Log.d("PdfRenderer", "Cache cleaned: kept ${pagesToKeep.size} pages from batches $batchesToKeep, removed ${pagesToRemove.size} pages from batches $batchesToUnload")
            } catch (e: Exception) {
                android.util.Log.e("PdfRenderer", "Error in manageCacheSize: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // Cleanup bitmaps and PDF renderer when composable is disposed
    DisposableEffect(Unit) {
        onDispose {
            android.util.Log.d("PdfRenderer", "Disposing PDF viewer, cleaning up resources")
            
            // Close render queue
            try {
                renderQueue.close()
                android.util.Log.d("PdfRenderer", "Render queue closed")
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // Recycle all cached bitmaps
            pageCache.values.forEach { (bitmap, _) ->
                try {
                    activeBitmaps.remove(bitmap)
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            // Recycle any remaining active bitmaps
            activeBitmaps.forEach { bitmap ->
                try {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            activeBitmaps.clear()
            renderingPages.clear()
            
            // Close PDF renderer and file descriptor
            try {
                pdfRenderer?.close()
                pdfRenderer = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            try {
                pfd?.close()
                pfd = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            android.util.Log.d("PdfRenderer", "PDF viewer disposed successfully")
        }
    }

    // Initial load - open PDF and load ALL pages before showing viewer
    LaunchedEffect(uri) {
        isInitialLoading = true
        loadingProgress = 0
        loadingStatus = "Opening PDF..."
        
        try {
            val contentResolver = context.contentResolver
            val tempPfd = contentResolver.openFileDescriptor(uri, "r")
            
            if (tempPfd == null) {
                Toast.makeText(
                    context,
                    "Failed to open PDF file",
                    Toast.LENGTH_LONG
                ).show()
                isInitialLoading = false
                return@LaunchedEffect
            }
            
            // Store PDF renderer and file descriptor
            pfd = tempPfd
            pdfRenderer = PdfRenderer(tempPfd)
            pageCount = pdfRenderer!!.pageCount
            
            loadingStatus = "Preparing PDF..."
            lastRenderedScale = scale
            
            // Hybrid approach: Pre-load first few batches, then load rest on-demand
            // This prevents OOM for large PDFs while still providing smooth initial experience
            withContext(Dispatchers.Default) {
                val totalBatches = (pageCount + batchSize - 1) / batchSize
                val initialBatchesToLoad = minOf(4, totalBatches) // Pre-load first 4 batches (60 pages max)
                
                android.util.Log.d("PdfRenderer", "Pre-loading first $initialBatchesToLoad batches (pages 0-${initialBatchesToLoad * batchSize - 1})")
                
                // Pre-load initial batches sequentially
                for (batchNum in 0 until initialBatchesToLoad) {
                    val batchStart = batchNum * batchSize
                    val batchEnd = minOf((batchNum + 1) * batchSize - 1, pageCount - 1)
                    
                    loadingStatus = "Loading pages ${batchStart + 1}-${batchEnd + 1}..."
                    loadingProgress = ((batchNum + 1) * 100 / initialBatchesToLoad).coerceAtMost(90)
                    
                    android.util.Log.d("PdfRenderer", "Pre-loading batch $batchNum: pages $batchStart-$batchEnd")
                    
                    // Load this batch
                    loadBatch(batchNum, scale, priority = 0)
                    
                    // Wait for batch to complete
                    var batchComplete = false
                    var attempts = 0
                    while (!batchComplete && attempts < 1000) {
                        delay(10)
                        val allPagesInBatchLoaded = (batchStart..batchEnd).all { pageIndex ->
                            pageCache.containsKey(pageIndex)
                        }
                        if (allPagesInBatchLoaded) {
                            batchComplete = true
                        }
                        attempts++
                    }
                    
                    // Check memory before loading next batch
                    val runtime = Runtime.getRuntime()
                    val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                    val maxMemory = runtime.maxMemory()
                    val memoryUsagePercent = (usedMemory * 100 / maxMemory).toInt()
                    
                    android.util.Log.d("PdfRenderer", "Memory usage after batch $batchNum: $memoryUsagePercent%")
                    
                    // If memory usage is high, stop pre-loading and switch to on-demand
                    if (memoryUsagePercent > 70) {
                        android.util.Log.w("PdfRenderer", "Memory usage high ($memoryUsagePercent%), stopping pre-load at batch $batchNum")
                        break
                    }
                    
                    delay(50) // Small delay between batches
                }
                
                // Mark initial batches as loaded
                val loadedBatchesSet = (0 until minOf(initialBatchesToLoad, totalBatches)).toSet()
                withContext(Dispatchers.Main) {
                    loadedBatches = loadedBatchesSet
                }
                
                loadingProgress = 95
                
                // More accurate loading status
                val pagesLoaded = pageCache.size
                val totalPages = pageCount
                if (pagesLoaded < totalPages) {
                    loadingStatus = "Loaded $pagesLoaded of $totalPages pages. Remaining pages will load as you scroll."
                } else {
                    loadingStatus = "Ready!"
                }
                
                android.util.Log.d("PdfRenderer", "Initial pre-load complete. Cache size: ${pageCache.size}, Loaded batches: $loadedBatchesSet")
            }
            
            // Small delay to show completion
            delay(500)
            
            isInitialLoading = false
            
            // More accurate toast message
            val pagesLoaded = pageCache.size
            val totalPages = pageCount
            if (pagesLoaded < totalPages) {
                Toast.makeText(
                    context,
                    "PDF opened: $pagesLoaded of $totalPages pages loaded. Scroll to load more.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    context,
                    "PDF loaded: $pageCount pages ready",
                    Toast.LENGTH_SHORT
                ).show()
            }
            
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            android.util.Log.e("PdfRenderer", "OutOfMemoryError during initial load: ${e.message}")
            
            // Clear cache and trigger GC
            pageCache = emptyMap()
            activeBitmaps.clear()
            renderingPages.clear()
            queuedPages.value = emptySet()
            System.gc()
            
            Toast.makeText(
                context,
                "Out of memory loading PDF. Switching to on-demand loading.",
                Toast.LENGTH_LONG
            ).show()
            
            // Still show viewer, but with on-demand loading only
            isInitialLoading = false
            
        } catch (e: SecurityException) {
            e.printStackTrace()
            Toast.makeText(
                context,
                "Permission denied: Cannot access PDF file",
                Toast.LENGTH_LONG
            ).show()
            isInitialLoading = false
            
        } catch (e: java.io.IOException) {
            e.printStackTrace()
            Toast.makeText(
                context,
                "IO Error: Cannot read PDF file",
                Toast.LENGTH_LONG
            ).show()
            isInitialLoading = false
            
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                context,
                "Error loading PDF: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            isInitialLoading = false
        }
    }
    
    // Handle zoom changes - debounced to avoid constant re-rendering
    // Use scale as key so previous effect is cancelled when scale changes rapidly
    LaunchedEffect(scale) {
        if (!isInitialLoading && pageCount > 0) {
            try {
                // Validate scale values
                val currentScale = scale
                val currentLastRendered = lastRenderedScale
                
                if (currentScale.isNaN() || currentScale.isInfinite() || currentScale <= 0f) {
                    android.util.Log.w("PdfRenderer", "Invalid scale value: $currentScale, skipping zoom handler")
                    return@LaunchedEffect
                }
                
                if (currentLastRendered.isNaN() || currentLastRendered.isInfinite() || currentLastRendered <= 0f) {
                    android.util.Log.w("PdfRenderer", "Invalid lastRenderedScale: $currentLastRendered, resetting")
                    lastRenderedScale = currentScale
                    return@LaunchedEffect
                }
                
                val scaleDifference = kotlin.math.abs(currentScale - currentLastRendered)
                if (scaleDifference >= 0.08f) {
                    android.util.Log.d("PdfRenderer", "Scale changed from $currentLastRendered to $currentScale")
                    
                    // If pinch zoom is active, wait longer for gesture to complete
                    val waitTime = if (isPinchZoomActive) {
                        android.util.Log.d("PdfRenderer", "Pinch zoom active, waiting longer...")
                        1200L  // Wait 1.2 seconds for pinch gesture to complete
                    } else {
                        600L   // Wait 600ms for slider gesture
                    }
                    
                    // Mark that zoom is in progress immediately
                    isZoomInProgress = true
                    
                    try {
                        // Clear render queue and stuck rendering states
                        clearRenderQueue()
                        
                        // Clear all renderingPages to prevent stuck states (ConcurrentHashMap is thread-safe)
                        renderingPages.clear()
                        
                        withContext(Dispatchers.Main) {
                            queuedPages.value = emptySet()
                        }
                        
                        android.util.Log.d("PdfRenderer", "Cleared render queue and rendering states due to zoom change")
                    } catch (e: Exception) {
                        android.util.Log.e("PdfRenderer", "Error clearing render queue during zoom: ${e.message}")
                        e.printStackTrace()
                        // Continue anyway - don't crash
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Expected during rapid zoom changes
                        throw e
                    }
                    
                    // Wait for user to finish gesture (longer for pinch zoom)
                    delay(waitTime)
                    
                    // Check if scale changed again during the delay (cancellation check)
                    val updatedScale = scale
                    val updatedLastRendered = lastRenderedScale
                    
                    // Validate updated values
                    if (updatedScale.isNaN() || updatedScale.isInfinite() || updatedScale <= 0f ||
                        updatedLastRendered.isNaN() || updatedLastRendered.isInfinite() || updatedLastRendered <= 0f) {
                        android.util.Log.w("PdfRenderer", "Invalid scale values after wait, resetting")
                        isZoomInProgress = false
                        return@LaunchedEffect
                    }
                    
                    // Check if pinch zoom is still active - if so, wait more
                    if (isPinchZoomActive) {
                        android.util.Log.d("PdfRenderer", "Pinch zoom still active, waiting more...")
                        delay(500)
                        // Re-check scale after additional wait
                        val finalScale = scale
                        if (finalScale.isNaN() || finalScale.isInfinite() || finalScale <= 0f) {
                            android.util.Log.w("PdfRenderer", "Invalid final scale, resetting")
                            isZoomInProgress = false
                            return@LaunchedEffect
                        }
                        if (kotlin.math.abs(finalScale - updatedScale) > 0.05f) {
                            // Scale changed significantly, restart
                            android.util.Log.d("PdfRenderer", "Scale changed during wait, restarting zoom handler")
                            return@LaunchedEffect
                        }
                    }
                    
                    // Check if scale changed again during the delay
                    if (kotlin.math.abs(updatedScale - updatedLastRendered) < 0.08f) {
                        // Scale settled back, no need to re-render
                        isZoomInProgress = false
                        android.util.Log.d("PdfRenderer", "Scale settled back within threshold, skipping re-render")
                        return@LaunchedEffect
                    }
                    
                    android.util.Log.d("PdfRenderer", "Scale settled at $updatedScale, triggering re-render")
                    
                    // Update the target scale
                    lastRenderedScale = updatedScale
                    
                    // Clear loaded batches so pages reload at new scale
                    loadedBatches = emptySet()
                    
                    // Don't queue ALL pages - let scroll handler load them on-demand
                    // This prevents memory issues and conflicts
                    android.util.Log.d("PdfRenderer", "Zoom scale updated to $updatedScale, pages will reload on-demand")
                    
                    // Small delay to let UI update and ensure cleanup is complete
                    delay(300)
                    
                    isZoomInProgress = false
                    
                    android.util.Log.d("PdfRenderer", "Zoom complete, scroll loading enabled")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Expected when scale changes rapidly - previous effect cancelled
                android.util.Log.d("PdfRenderer", "Zoom handler cancelled due to rapid scale change")
                throw e
            } catch (e: Exception) {
                android.util.Log.e("PdfRenderer", "Error in zoom handler: ${e.message}")
                e.printStackTrace()
                // Reset zoom state on error
                isZoomInProgress = false
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { 
                        Text(
                            if (isInitialLoading) "Loading PDF..." 
                            else "PDF Viewer - $pageCount pages"
                        ) 
                    },
                    actions = {
                        if (!isInitialLoading && pageCount > 0) {
                            Button(
                                onClick = { 
                                    scale = (scale + 0.05f).coerceAtMost(0.6f)
                                },
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Text("Zoom In")
                            }
                            Button(
                                onClick = { 
                                    scale = (scale - 0.05f).coerceAtLeast(0.3f)
                                },
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Text("Zoom Out")
                            }
                            Button(
                                onClick = { 
                                    scale = 0.3f
                                },
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Text("Reset")
                            }
                        }
                    }
                )
                if (isInitialLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Zoom percentage slider
                if (!isInitialLoading && pageCount > 0) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        elevation = 4.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Zoom Level:",
                                    style = MaterialTheme.typography.body2
                                )
                                Text(
                                    text = "${(scale * 100).toInt()}%",
                                    style = MaterialTheme.typography.body1,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                            Slider(
                                value = scale,
                                onValueChange = { newScale ->
                                    // Round to nearest 5% to avoid too many re-renders
                                    val rounded = (kotlin.math.round(newScale * 20f) / 20f)
                                    // Only update if actually changed
                                    if (kotlin.math.abs(scale - rounded) > 0.01f) {
                                        scale = rounded
                                    }
                                },
                                valueRange = 0.3f..0.6f,
                                steps = 5, // Creates 5% increments: 30%, 35%, 40%, 45%, 50%, 55%, 60%
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "30%",
                                    style = MaterialTheme.typography.caption
                                )
                                Text(
                                    text = "60%",
                                    style = MaterialTheme.typography.caption
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!isInitialLoading && pageCount > 0) {
                val lazyListState = rememberLazyListState()
                val horizontalScrollState = rememberScrollState()
                
                // Get current visible page
                val currentPage = remember {
                    derivedStateOf {
                        lazyListState.firstVisibleItemIndex + 1
                    }
                }
                
                // Retry mechanism for visible pages that failed to load
                LaunchedEffect(lazyListState.firstVisibleItemIndex) {
                    delay(1000) // Wait 1 second after scroll settles
                    
                    if (isZoomInProgress) {
                        return@LaunchedEffect
                    }
                    
                    val visiblePages = lazyListState.layoutInfo.visibleItemsInfo.map { it.index }
                    val targetScale = lastRenderedScale
                    
                    // Check visible pages and retry failed ones
                    visiblePages.forEach { pageIndex ->
                        val cached = pageCache[pageIndex]
                        val isRendering = renderingPages.containsKey(pageIndex)
                        val isQueued = queuedPages.value.contains(pageIndex)
                        
                        // If page is not cached, not rendering, and not queued, retry it
                        if (cached == null && !isRendering && !isQueued) {
                            android.util.Log.d("PdfRenderer", "Retrying visible page $pageIndex that failed to load")
                            launch(Dispatchers.Default) {
                                try {
                                    val request = RenderRequest(pageIndex, targetScale, priority = 0)
                                    renderQueue.send(request)
                                    withContext(Dispatchers.Main) {
                                        queuedPages.value = queuedPages.value + pageIndex
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("PdfRenderer", "Error retrying page $pageIndex: ${e.message}")
                                }
                            }
                        }
                    }
                }
                
                // Load pages on-demand as user scrolls (for pages beyond initial pre-load)
                LaunchedEffect(lazyListState.firstVisibleItemIndex, lastRenderedScale) {
                    // Wait to avoid conflicts with zoom and let scroll settle
                    delay(300)
                    
                    // Skip if zoom is in progress
                    if (isZoomInProgress) {
                        android.util.Log.d("PdfRenderer", "Skipping scroll load - zoom in progress")
                        return@LaunchedEffect
                    }
                    
                    // Skip if pinch zoom is actively happening (within last 800ms)
                    val timeSinceLastPinch = System.currentTimeMillis() - lastPinchZoomTime
                    if (isPinchZoomActive || timeSinceLastPinch < 800) {
                        android.util.Log.d("PdfRenderer", "Skipping scroll load - pinch zoom active or recent (${timeSinceLastPinch}ms ago)")
                        return@LaunchedEffect
                    }
                    
                    val firstVisibleIndex = lazyListState.firstVisibleItemIndex
                    val currentBatch = getBatchNumber(firstVisibleIndex)
                    val targetScale = lastRenderedScale
                    
                    // Get visible pages - prioritize loading them first
                    val visiblePages = lazyListState.layoutInfo.visibleItemsInfo.map { it.index }
                    
                    // First, queue visible pages that need rendering (at correct scale or missing)
                    visiblePages.forEach { pageIndex ->
                        val cached = pageCache[pageIndex]
                        val needsRender = cached == null || 
                            kotlin.math.abs(cached.second - targetScale) > 0.08f
                        
                        if (needsRender && !renderingPages.containsKey(pageIndex) && !queuedPages.value.contains(pageIndex)) {
                            launch(Dispatchers.Default) {
                                try {
                                    val request = RenderRequest(pageIndex, targetScale, priority = 0)
                                    renderQueue.send(request)
                                    withContext(Dispatchers.Main) {
                                        queuedPages.value = queuedPages.value + pageIndex
                                    }
                                    android.util.Log.d("PdfRenderer", "Queued visible page $pageIndex at scale $targetScale")
                                } catch (e: Exception) {
                                    android.util.Log.e("PdfRenderer", "Error queueing visible page $pageIndex: ${e.message}")
                                }
                            }
                        }
                    }
                    
                    // Load current batch if not loaded (or needs re-render at new scale)
                    val batchNeedsLoad = !loadedBatches.contains(currentBatch) || 
                        (0 until batchSize).any { offset ->
                            val pageIndex = currentBatch * batchSize + offset
                            if (pageIndex >= pageCount) false
                            else {
                                val cached = pageCache[pageIndex]
                                cached == null || kotlin.math.abs(cached.second - targetScale) > 0.08f
                            }
                        }
                    
                    if (batchNeedsLoad) {
                        android.util.Log.d("PdfRenderer", "Loading batch $currentBatch on scroll (page $firstVisibleIndex) at scale $targetScale")
                        launch(Dispatchers.Default) {
                            try {
                                loadBatch(currentBatch, targetScale, priority = 1)
                                
                                // Mark batch as loaded
                                withContext(Dispatchers.Main) {
                                    loadedBatches = loadedBatches + currentBatch
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("PdfRenderer", "Error loading batch $currentBatch: ${e.message}")
                                e.printStackTrace()
                            }
                        }
                    }
                    
                    // Prefetch next batch if near end of current batch
                    val positionInBatch = firstVisibleIndex % batchSize
                    val nextBatch = currentBatch + 1
                    if (positionInBatch >= batchSize - 3 && nextBatch * batchSize < pageCount) {
                        val nextBatchNeedsLoad = !loadedBatches.contains(nextBatch) ||
                            ((nextBatch * batchSize) until minOf((nextBatch + 1) * batchSize, pageCount)).any { pageIndex ->
                                val cached = pageCache[pageIndex]
                                cached == null || kotlin.math.abs(cached.second - targetScale) > 0.08f
                            }
                        
                        if (nextBatchNeedsLoad) {
                            android.util.Log.d("PdfRenderer", "Prefetching batch $nextBatch")
                            launch(Dispatchers.Default) {
                                try {
                                    loadBatch(nextBatch, targetScale, priority = 2)
                                    withContext(Dispatchers.Main) {
                                        loadedBatches = loadedBatches + nextBatch
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("PdfRenderer", "Error prefetching batch $nextBatch: ${e.message}")
                                }
                            }
                        }
                    }
                    
                    // Prefetch previous batch if scrolling up
                    val prevBatch = currentBatch - 1
                    if (positionInBatch <= 2 && prevBatch >= 0) {
                        val prevBatchNeedsLoad = !loadedBatches.contains(prevBatch) ||
                            ((prevBatch * batchSize) until minOf((prevBatch + 1) * batchSize, pageCount)).any { pageIndex ->
                                val cached = pageCache[pageIndex]
                                cached == null || kotlin.math.abs(cached.second - targetScale) > 0.08f
                            }
                        
                        if (prevBatchNeedsLoad) {
                            android.util.Log.d("PdfRenderer", "Prefetching batch $prevBatch")
                            launch(Dispatchers.Default) {
                                try {
                                    loadBatch(prevBatch, targetScale, priority = 2)
                                    withContext(Dispatchers.Main) {
                                        loadedBatches = loadedBatches + prevBatch
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("PdfRenderer", "Error prefetching batch $prevBatch: ${e.message}")
                                }
                            }
                        }
                    }
                    
                    // Manage cache size - keep only nearby batches
                    try {
                        manageCacheSize(firstVisibleIndex)
                    } catch (e: Exception) {
                        android.util.Log.e("PdfRenderer", "Error managing cache: ${e.message}")
                        e.printStackTrace()
                    }
                }
                
                // LaunchedEffect to detect when pinch zoom gesture ends (no updates for 500ms)
                // Use a single job that gets cancelled and restarted
                LaunchedEffect(lastPinchZoomTime) {
                    try {
                        delay(500)
                        val currentTime = System.currentTimeMillis()
                        val timeSinceLastUpdate = currentTime - lastPinchZoomTime
                        if (timeSinceLastUpdate >= 500 && isPinchZoomActive) {
                            isPinchZoomActive = false
                            android.util.Log.d("PdfRenderer", "Pinch zoom gesture ended (no updates for ${timeSinceLastUpdate}ms)")
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Expected when new gesture starts
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("PdfRenderer", "Error in pinch gesture detection: ${e.message}")
                    }
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            try {
                                detectTransformGestures { _, _, zoom, _ ->
                                    try {
                                        // Validate zoom value
                                        if (zoom.isNaN() || zoom.isInfinite() || zoom <= 0f) {
                                            android.util.Log.w("PdfRenderer", "Invalid zoom value: $zoom")
                                            return@detectTransformGestures
                                        }
                                        
                                        // Handle pinch zoom - gesture handlers run on main thread
                                        val currentTime = System.currentTimeMillis()
                                        
                                        // Validate time
                                        if (currentTime <= 0) {
                                            return@detectTransformGestures
                                        }
                                        
                                        // Update state (Compose state is thread-safe for main thread access)
                                        isPinchZoomActive = true
                                        lastPinchZoomTime = currentTime
                                        
                                        // Throttle scale updates - only update every 50ms during pinch
                                        val timeSinceLastUpdate = currentTime - lastScaleUpdateTime
                                        if (timeSinceLastUpdate >= 50) {
                                            try {
                                                // Round to nearest 5% to avoid constant re-renders
                                                val currentScale = scale
                                                
                                                // Validate current scale
                                                if (currentScale.isNaN() || currentScale.isInfinite() || currentScale <= 0f) {
                                                    android.util.Log.w("PdfRenderer", "Invalid current scale: $currentScale, resetting to 0.3f")
                                                    scale = 0.3f
                                                    lastScaleUpdateTime = currentTime
                                                    return@detectTransformGestures
                                                }
                                                
                                                val newScale = (currentScale * zoom).coerceIn(0.3f, 0.6f)
                                                
                                                // Validate new scale
                                                if (newScale.isNaN() || newScale.isInfinite()) {
                                                    android.util.Log.w("PdfRenderer", "Invalid new scale: $newScale")
                                                    return@detectTransformGestures
                                                }
                                                
                                                val rounded = (kotlin.math.round(newScale * 20f) / 20f)
                                                
                                                // Validate rounded scale
                                                if (rounded.isNaN() || rounded.isInfinite()) {
                                                    android.util.Log.w("PdfRenderer", "Invalid rounded scale: $rounded")
                                                    return@detectTransformGestures
                                                }
                                                
                                                // Only update if change is significant (prevents micro-updates)
                                                if (kotlin.math.abs(currentScale - rounded) > 0.01f) {
                                                    scale = rounded
                                                    lastScaleUpdateTime = currentTime
                                                }
                                            } catch (e: Exception) {
                                                android.util.Log.e("PdfRenderer", "Error calculating scale: ${e.message}")
                                                e.printStackTrace()
                                                // Don't crash - just skip this update
                                            }
                                        }
                                    } catch (e: OutOfMemoryError) {
                                        android.util.Log.e("PdfRenderer", "OOM in pinch gesture handler")
                                        System.gc()
                                        // Don't crash - just skip this update
                                    } catch (e: Exception) {
                                        android.util.Log.e("PdfRenderer", "Error in pinch gesture handler: ${e.message}")
                                        e.printStackTrace()
                                        // Don't crash - just skip this update
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("PdfRenderer", "Error setting up gesture detector: ${e.message}")
                                e.printStackTrace()
                                // Don't crash - gesture detection will be retried
                            } catch (e: OutOfMemoryError) {
                                android.util.Log.e("PdfRenderer", "OOM setting up gesture detector")
                                System.gc()
                            }
                        }
                ) {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(horizontalScrollState)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        items(pageCount) { index ->
                            val cachedPage = pageCache[index]
                            val bitmap = cachedPage?.first
                            val bitmapScale = cachedPage?.second ?: scale
                            
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.wrapContentSize()
                            ) {
                                // Check if scale difference is significant (for zoom re-rendering)
                                val scaleDifference = kotlin.math.abs(scale - bitmapScale)
                                val needsRerender = scaleDifference > 0.08f
                                
                                // Use visual scaling for moderate differences (< 20%) to keep pages visible
                                val canUseVisualScaling = scaleDifference < 0.20f && bitmap != null && !bitmap.isRecycled
                                
                                if (bitmap != null && !bitmap.isRecycled) {
                                    // Page is loaded - show it with visual scaling if needed
                                    val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                                    val displayWidth = (screenWidthDp - 32.dp) * scale
                                    val visualScaleFactor = if (canUseVisualScaling) scale / bitmapScale else 1f
                                    
                                    Box {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "PDF Page ${index + 1}",
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .width(displayWidth)
                                                .aspectRatio(aspectRatio)
                                                .graphicsLayer(
                                                    scaleX = visualScaleFactor,
                                                    scaleY = visualScaleFactor
                                                )
                                        )
                                        
                                        // Show small indicator if actively re-rendering at new zoom level (not stale)
                                        val renderStartTime = renderingPages[index]
                                        val currentTime = System.currentTimeMillis()
                                        val isActivelyRendering = renderStartTime != null && 
                                            (currentTime - renderStartTime) < RENDERING_TIMEOUT_MS
                                        
                                        if (needsRerender && isActivelyRendering && !isZoomInProgress) {
                                            Card(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(8.dp),
                                                elevation = 4.dp,
                                                backgroundColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f)
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .padding(4.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    // Page not loaded yet - check if actively rendering or queued
                                    val renderStartTime = renderingPages[index]
                                    val currentTime = System.currentTimeMillis()
                                    val isActivelyRendering = renderStartTime != null && 
                                        (currentTime - renderStartTime) < RENDERING_TIMEOUT_MS
                                    val isQueued = queuedPages.value.contains(index)
                                    
                                    // Only show loading if actively rendering (not stale) or queued
                                    if (isActivelyRendering || isQueued) {
                                        Box(
                                            modifier = Modifier
                                                .width((screenWidthDp - 32.dp) * scale)
                                                .height((screenWidthDp - 32.dp) * scale * 1.4f) // Approximate A4 ratio
                                                .padding(8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(32.dp)
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(
                                                    text = "Loading page ${index + 1}...",
                                                    style = MaterialTheme.typography.caption
                                                )
                                            }
                                        }
                                    } else {
                                        // Page not loading (might be stuck) - show empty space
                                        // This prevents stuck loaders
                                        Box(
                                            modifier = Modifier
                                                .width((screenWidthDp - 32.dp) * scale)
                                                .height((screenWidthDp - 32.dp) * scale * 1.4f)
                                                .padding(8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            // Empty - page will load when scrolled into view or retried
                                        }
                                    }
                                }
                                
                                // Add page number label
                                Text(
                                    text = "Page ${index + 1} of $pageCount",
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    style = MaterialTheme.typography.caption
                                )
                            }
                        }
                    }
                    
                    // Current page indicator (floating badge)
                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        elevation = 8.dp
                    ) {
                        Text(
                            text = "Page ${currentPage.value} of $pageCount",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.body2
                        )
                    }
                }
            } else {
                // Show loading progress during initial load
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = loadingStatus,
                            style = MaterialTheme.typography.h6
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = loadingProgress / 100f,
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(8.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "$loadingProgress%",
                            style = MaterialTheme.typography.body1
                        )
                        if (pageCount > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "$pageCount pages",
                                style = MaterialTheme.typography.caption,
                                color = androidx.compose.ui.graphics.Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}