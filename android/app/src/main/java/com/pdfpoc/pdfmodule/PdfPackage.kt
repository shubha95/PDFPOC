package com.pdfpoc.pdfmodule

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext

class PdfPackage : ReactPackage {
    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
        return listOf(PdfModule(reactContext))
    }

    override fun createViewManagers(reactContext: ReactApplicationContext)
            = emptyList<com.facebook.react.uimanager.ViewManager<*, *>>()
}