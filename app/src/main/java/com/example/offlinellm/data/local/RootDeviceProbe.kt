package com.example.offlinellm.data.local

import android.os.Build
import java.io.File

/**
 * Collects device / vendor clues for experimental root builds.
 * Not a performance backend — diagnostics only.
 */
object RootDeviceProbe {
    fun run(includeRoot: Boolean): String {
        val sb = StringBuilder()
        sb.appendLine("=== OfflineLLM device probe ===")
        sb.appendLine("time=${System.currentTimeMillis()}")
        sb.appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("hardware=${Build.HARDWARE} board=${Build.BOARD}")
        sb.appendLine("soc=${Build.SOC_MODEL} abis=${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine("sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}")
        sb.appendLine("suPresent=${RootShell.isSuPresent()} rootGranted=${RootShell.isRootGranted()}")
        sb.appendLine()

        sb.appendLine("-- interesting paths (app view) --")
        val paths = listOf(
            "/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/vendor/lib64/egl/libGLES_adreno.so",
            "/vendor/lib64/libvulkan.so",
            "/system/lib64/libvulkan.so",
            "/vendor/lib64/libcdsprpc.so",
            "/vendor/lib64/libadsprpc.so",
            "/vendor/lib64/libhexagon_nn_skel.so",
            "/dev/adsprpc-smd",
            "/dev/ion",
            "/dev/kgsl-3d0",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
        )
        for (p in paths) {
            val f = File(p)
            sb.appendLine(
                "  $p exists=${f.exists()} canRead=${f.canRead()} " +
                    "len=${try { if (f.isFile) f.length() else -1L } catch (_: Throwable) { -1L }}"
            )
        }

        if (includeRoot && RootShell.ensureRoot()) {
            sb.appendLine()
            sb.appendLine("-- root ls (vendor OpenCL / dsp / vulkan) --")
            val cmds = listOf(
                "ls -l /vendor/lib64/libOpenCL* 2>/dev/null | head -20",
                "ls -l /vendor/lib64/*adsp* 2>/dev/null | head -20",
                "ls -l /vendor/lib64/*cdsp* 2>/dev/null | head -20",
                "ls -l /vendor/lib64/*hexagon* 2>/dev/null | head -20",
                "ls -l /vendor/lib64/*vulkan* 2>/dev/null | head -20",
                "ls -l /dev/*adsp* /dev/*cdsp* /dev/ion /dev/kgsl* 2>/dev/null | head -30",
                "getprop ro.board.platform; getprop ro.hardware; getprop ro.soc.model",
                "id",
            )
            for (c in cmds) {
                val r = RootShell.su(c, timeoutSec = 15)
                sb.appendLine("$ $c")
                if (r.stdout.isNotBlank()) sb.append(r.stdout.trimEnd()).append('\n')
                if (r.stderr.isNotBlank()) sb.append("stderr: ").append(r.stderr.trimEnd()).append('\n')
                sb.appendLine("exit=${r.code}")
            }
        } else if (includeRoot) {
            sb.appendLine()
            sb.appendLine("root not granted — skipped privileged probe")
        }

        sb.appendLine()
        sb.appendLine("Note: finding libcdsprpc/hexagon does NOT mean llama.cpp can use NPU.")
        sb.appendLine("NPU needs QNN/HTP runtime + model path; this build only eases FS access.")
        return sb.toString()
    }
}
