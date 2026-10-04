package com.exam.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // PDFBox ke font/glyph resources ke liye zaroori
        PDFBoxResourceLoader.init(applicationContext)
    }
}
