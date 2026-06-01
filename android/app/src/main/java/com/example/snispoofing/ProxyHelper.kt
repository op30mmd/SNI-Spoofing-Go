package com.example.snispoofing

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream

object ProxyHelper {
    private const val BINARY_NAME_ARM64 = "sni-spoofing-android-arm64"
    private const val BINARY_NAME_X64 = "sni-spoofing-android-x64"

    fun prepareBinary(context: Context): String {
        var binaryToExtract: String? = null
        for (abi in Build.SUPPORTED_ABIS) {
            if (abi.contains("arm64")) {
                binaryToExtract = BINARY_NAME_ARM64
                break
            } else if (abi.contains("x86_64") || abi.contains("amd64")) {
                binaryToExtract = BINARY_NAME_X64
                break
            }
        }

        if (binaryToExtract == null) {
            throw UnsupportedOperationException("Unsupported ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        }

        val destFile = File(context.filesDir, "sni-spoofing")

        // Always extract for simplicity in this example, or check version/hash
        context.assets.open(binaryToExtract).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }

        destFile.setExecutable(true)
        return destFile.absolutePath
    }
}
