package com.example.snispoofing

import android.content.Context
import android.os.Build
import java.io.File

object ProxyHelper {
    fun getBinaryPath(context: Context): String {
        val libDir = context.applicationInfo.nativeLibraryDir
        val candidates = listOf("libsni_spoofing.so")

        for (candidate in candidates) {
            val file = File(libDir, candidate)
            if (file.exists()) {
                return file.absolutePath
            }
        }

        val filesInLibDir = File(libDir).list()?.joinToString(", ") ?: "null"
        throw IllegalStateException("Binary not found in $libDir. Files present: $filesInLibDir")
    }
}
