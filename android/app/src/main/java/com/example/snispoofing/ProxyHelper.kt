package com.example.snispoofing

import android.content.Context
import android.os.Build
import java.io.File

object ProxyHelper {
    fun getBinaryPath(context: Context): String {
        val abi = Build.SUPPORTED_ABIS[0]
        val libName = if (abi.contains("arm64")) {
            "libsni_spoofing.so"
        } else {
            "libsni_spoofing_x64.so"
        }

        val libFile = File(context.applicationInfo.nativeLibraryDir, libName)
        if (!libFile.exists()) {
            // Fallback for some environments or manual installs
            val alternativeLibFile = File(context.applicationInfo.nativeLibraryDir, "libsni_spoofing.so")
            if (alternativeLibFile.exists()) return alternativeLibFile.absolutePath

            throw IllegalStateException("Binary not found at ${libFile.absolutePath}")
        }
        return libFile.absolutePath
    }
}
