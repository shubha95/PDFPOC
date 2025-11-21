package com.pdfpoc.pdfmodule


import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.util.Base64
import com.facebook.react.bridge.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream


class PdfModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "PdfModule"

    /**
     * pages: Array<{title: string, body: string}>
     * widthPx, heightPx: desired pixel size for page rendering (e.g. 595x842 for A4 at 72dpi)
     */
    @ReactMethod
    fun createMultiPagePdfBase64(pages: ReadableArray, widthPx: Int, heightPx: Int, promise: Promise) {
        Thread {
            try {
                // Create PdfDocument
                val pdfDocument = PdfDocument()

                for (i in 0 until pages.size()) {
                    val pageObj = pages.getMap(i) ?: continue
                    val title = pageObj.getString("title") ?: "Untitled"
                    val body = pageObj.getString("body") ?: ""

                    // Render page content to bitmap
                    val bitmap = renderComposableToBitmap(
                        reactContext,
                        widthPx,
                        heightPx,
                        title,
                        body
                    )

                    // Add bitmap to pdf page
                    val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, i + 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    val canvas = page.canvas
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                    pdfDocument.finishPage(page)
                    // recycle bitmap if desired
                    bitmap.recycle()
                }

                // Write PDF to a temporary file
                val docsDir = reactContext.cacheDir // safe internal cache
                val outFile = File(docsDir, "compose_multi_page_${System.currentTimeMillis()}.pdf")
                val fos = FileOutputStream(outFile)
                pdfDocument.writeTo(fos)
                fos.flush()
                fos.close()
                pdfDocument.close()

                // Convert file to Base64
                val bytes = outFile.readBytes()
                val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)

                // Return object with base64 and optional path
                val result = Arguments.createMap().apply {
                    putString("base64", base64)
                    putString("path", outFile.absolutePath)
                }
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("PDF_CREATE_ERROR", e.message, e)
            }
        }.start()
    }

    /**
     * Open an already-existing PDF (file:// or content://) in a native Compose-based viewer.
     * JS should pass a URI string.
     */
    @ReactMethod
    fun openPdfInNativeViewer(uriString: String) {
        try {
            val intent = Intent(reactContext, PdfViewerActivity::class.java).apply {
                putExtra("pdf_uri", uriString)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactContext.startActivity(intent)
        } catch (e: Exception) {
            // no promise here — you could change to Promise-based if you want result feedback
            e.printStackTrace()
        }
    }
}