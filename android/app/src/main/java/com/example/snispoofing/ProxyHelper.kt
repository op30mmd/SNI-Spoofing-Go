package com.example.snispoofing

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream

object ProxyHelper {
    private const val BINARY_NAME_ARM64 = "sni-spoofing-android-arm64"
    private const val BINARY_NAME_X64 = "sni-spoofing-android-x64"

    fun prepareBinary(context: Context): String {
        val abi = Build.SUPPORTED_ABIS[0]
        val binaryToExtract = if (abi.contains("arm64")) {
            BINARY_NAME_ARM64
        } else {
            BINARY_NAME_X64
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
