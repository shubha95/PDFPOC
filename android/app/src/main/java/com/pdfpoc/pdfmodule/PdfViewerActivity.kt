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
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

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

@Composable
fun PdfRendererView(uri: Uri) {
    var pageCount by remember { mutableStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmaps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var scale by remember { mutableStateOf(0.3f) } // Default at 30%
    var loadingProgress by remember { mutableStateOf(0f) }
    var isInitialLoading by remember { mutableStateOf(true) }
    var isRerendering by remember { mutableStateOf(false) }
    var lastRenderedScale by remember { mutableStateOf(0.3f) }
    
    val coroutineScope = rememberCoroutineScope()
    val renderMutex = remember { Mutex() }
    val isRendering = remember { AtomicBoolean(false) }
    var oldBitmapsToRecycle by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    
    // Get screen width and density for proper sizing
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    // Function to render pages at specific scale - THREAD SAFE with robust error handling
    suspend fun renderPagesAtScale(targetScale: Float): List<Bitmap> = withContext(Dispatchers.Default) {
        // Mark that rendering is in progress
        if (!isRendering.compareAndSet(false, true)) {
            // Already rendering, skip this request
            return@withContext emptyList()
        }
        
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val renderedBitmaps = mutableListOf<Bitmap>()
        
        try {
            // Store reference to old bitmaps (don't recycle yet - they're still being displayed!)
            val currentBitmaps = bitmaps
            
            val contentResolver = context.contentResolver
            pfd = contentResolver.openFileDescriptor(uri, "r")
            
            if (pfd == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to open PDF file", Toast.LENGTH_SHORT).show()
                }
                return@withContext emptyList()
            }
            
            renderer = PdfRenderer(pfd)
            
            // Store non-null reference for use in closures
            val pdfRenderer = renderer
            val totalPages = pdfRenderer.pageCount
            
            // Use lower resolution for better memory management
            val scaleFactor = (targetScale * density * 4f).coerceIn(1.5f, 4f)
            
            // Render pages sequentially to avoid memory spikes
            for (i in 0 until totalPages) {
                var page: PdfRenderer.Page? = null
                try {
                    page = pdfRenderer.openPage(i)
                    
                    val width = (page.width * scaleFactor).toInt()
                    val height = (page.height * scaleFactor).toInt()
                    
                    // Check if we can allocate this bitmap
                    val runtime = Runtime.getRuntime()
                    val maxMemory = runtime.maxMemory()
                    val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                    val availableMemory = maxMemory - usedMemory
                    val bitmapSize = (width * height * 4).toLong()
                    
                    val bitmap = if (bitmapSize > availableMemory * 0.8) {
                        // Not enough memory, use lower quality
                        val reducedScale = scaleFactor * 0.7f
                        val reducedWidth = (page.width * reducedScale).toInt()
                        val reducedHeight = (page.height * reducedScale).toInt()
                        
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Low memory: Using reduced quality for page ${i + 1}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        
                        Bitmap.createBitmap(reducedWidth, reducedHeight, Bitmap.Config.RGB_565)
                    } else {
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    }
                    
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    renderedBitmaps.add(bitmap)
                    
                    withContext(Dispatchers.Main) {
                        loadingProgress = (i + 1).toFloat() / totalPages.toFloat()
                    }
                    
                } catch (e: OutOfMemoryError) {
                    e.printStackTrace()
                    System.gc()
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Out of memory at page ${i + 1}. Stopping render.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    
                    // Stop rendering, return what we have
                    break
                    
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                    // Page might be closed or invalid
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Error rendering page ${i + 1}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Log but continue with next page
                    
                } finally {
                    // Always close the page
                    try {
                        page?.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            // Schedule old bitmaps for recycling AFTER new ones are displayed
            if (currentBitmaps.isNotEmpty() && renderedBitmaps.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    oldBitmapsToRecycle = currentBitmaps
                }
            }
            
            renderedBitmaps
            
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            System.gc()
            
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Out of memory. Please close other apps and try again.",
                    Toast.LENGTH_LONG
                ).show()
            }
            
            // Recycle any bitmaps we created
            renderedBitmaps.forEach { bitmap ->
                try {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            emptyList()
            
        } catch (e: SecurityException) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Permission denied: Cannot access PDF file",
                    Toast.LENGTH_LONG
                ).show()
            }
            emptyList()
            
        } catch (e: java.io.IOException) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "IO Error: Cannot read PDF file",
                    Toast.LENGTH_LONG
                ).show()
            }
            emptyList()
            
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Error rendering PDF: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            emptyList()
            
        } finally {
            // ALWAYS clean up resources
            try {
                renderer?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            try {
                pfd?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // Mark rendering as complete
            isRendering.set(false)
        }
    }
    
    // Recycle old bitmaps after a delay (ensure they're not being displayed)
    suspend fun recycleOldBitmaps() = withContext(Dispatchers.Default) {
        delay(100) // Wait 500ms to ensure new bitmaps are displayed
        oldBitmapsToRecycle.forEach { bitmap ->
            try {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        withContext(Dispatchers.Main) {
            oldBitmapsToRecycle = emptyList()
        }
    }

    // Cleanup bitmaps when composable is disposed
    DisposableEffect(Unit) {
        onDispose {
            // Recycle all bitmaps
            val allBitmaps = bitmaps + oldBitmapsToRecycle
            allBitmaps.forEach { bitmap ->
                try {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Initial load with robust error handling
    LaunchedEffect(uri) {
        isInitialLoading = true
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        
        try {
            val contentResolver = context.contentResolver
            val tempPfd = contentResolver.openFileDescriptor(uri, "r")
            
            if (tempPfd == null) {
                Toast.makeText(
                    context,
                    "Failed to open PDF file",
                    Toast.LENGTH_LONG
                ).show()
                return@LaunchedEffect
            }
            
            // Store in the nullable var for cleanup in finally block
            pfd = tempPfd
            
            // Use non-null tempPfd directly
            renderer = PdfRenderer(tempPfd)
            pageCount = renderer!!.pageCount
            
            val newBitmaps = renderPagesAtScale(scale)
            if (newBitmaps.isNotEmpty()) {
                renderMutex.withLock {
                    bitmaps = newBitmaps
                    lastRenderedScale = scale
                }
                // Recycle old bitmaps in background
                launch { recycleOldBitmaps() }
            } else {
                Toast.makeText(
                    context,
                    "Failed to render PDF pages",
                    Toast.LENGTH_LONG
                ).show()
            }
            
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            System.gc()
            Toast.makeText(
                context,
                "Out of memory. Please close other apps and try again.",
                Toast.LENGTH_LONG
            ).show()
            
        } catch (e: SecurityException) {
            e.printStackTrace()
            Toast.makeText(
                context,
                "Permission denied: Cannot access PDF file",
                Toast.LENGTH_LONG
            ).show()
            
        } catch (e: java.io.IOException) {
            e.printStackTrace()
            Toast.makeText(
                context,
                "IO Error: Cannot read PDF file",
                Toast.LENGTH_LONG
            ).show()
            
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                context,
                "Error loading PDF: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            
        } finally {
            // Always clean up resources
            try {
                renderer?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            try {
                pfd?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            isInitialLoading = false
        }
    }
    
    // Re-render when scale changes significantly with robust error handling
    LaunchedEffect(scale) {
        if (!isInitialLoading && bitmaps.isNotEmpty()) {
            val scaleDifference = kotlin.math.abs(scale - lastRenderedScale)
            if (scaleDifference >= 0.05f) { // Re-render if 5% or more change
                // Check if already rendering
                if (isRendering.get()) {
                    // Skip this request, already rendering
                    return@LaunchedEffect
                }
                
                isRerendering = true
                launch(Dispatchers.Default) {
                    try {
                        val newBitmaps = renderPagesAtScale(scale)
                        if (newBitmaps.isNotEmpty()) {
                            // Use mutex to safely update bitmaps
                            renderMutex.withLock {
                                bitmaps = newBitmaps
                                lastRenderedScale = scale
                            }
                            // Recycle old bitmaps in background after delay
                            launch { recycleOldBitmaps() }
                        } else {
                            // Failed to render, revert to last working scale
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    "Failed to render at this zoom level",
                                    Toast.LENGTH_SHORT
                                ).show()
                                scale = lastRenderedScale
                            }
                        }
                        
                    } catch (e: OutOfMemoryError) {
                        e.printStackTrace()
                        System.gc()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Out of memory at this zoom level. Reverting to previous zoom.",
                                Toast.LENGTH_SHORT
                            ).show()
                            // Revert to last working scale
                            scale = lastRenderedScale
                        }
                        
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Render error. Please try again.",
                                Toast.LENGTH_SHORT
                            ).show()
                            scale = lastRenderedScale
                        }
                        
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Error during zoom: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                            scale = lastRenderedScale
                        }
                        
                    } finally {
                        withContext(Dispatchers.Main) {
                            isRerendering = false
                        }
                    }
                }
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
                        if (!isInitialLoading) {
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
                        progress = loadingProgress,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Zoom percentage slider
                if (!isInitialLoading && bitmaps.isNotEmpty()) {
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
                                onValueChange = { scale = it },
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
            if (bitmaps.isNotEmpty()) {
                val lazyListState = rememberLazyListState()
                val horizontalScrollState = rememberScrollState()
                
                // Get current visible page
                val currentPage = remember {
                    derivedStateOf {
                        lazyListState.firstVisibleItemIndex + 1
                    }
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                // Handle pinch zoom
                                scale = (scale * zoom).coerceIn(0.3f, 0.6f)
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
                        itemsIndexed(bitmaps) { index, bitmap ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.wrapContentSize()
                            ) {
                                // Calculate aspect ratio
                                val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                                
                                // Display at current scale - will scale smoothly while re-rendering happens in background
                                val displayWidth = (screenWidthDp - 32.dp) * scale
                                
                                // Calculate scale factor for smooth visual scaling
                                val visualScaleFactor = if (lastRenderedScale > 0) {
                                    scale / lastRenderedScale
                                } else {
                                    1f
                                }
                                
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
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Loading PDF pages...")
                        if (pageCount > 0) {
                            Text(
                                "Loading ${(loadingProgress * pageCount).toInt()} of $pageCount pages",
                                style = MaterialTheme.typography.caption
                            )
                        }
                    }
                }
            }
        }
    }
}