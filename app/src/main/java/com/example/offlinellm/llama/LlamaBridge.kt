package com.example.offlinellm.llama

/**
 * JNI bridge to native llama.cpp library.
 * Loads libllama.so / libggml*.so and provides inference calls.
 *
 * Build requirements:
 *   - Prebuilt arm64-v8a .so files in app/src/main/jniLibs/arm64-v8a/
 *   - Compiled with Hexagon NPU support (libggml-hexagon.so + libggml-htp-v73.so)
 *   - Or fallback: Vulkan/OpenCL GPU backend
 *
 * Build llama.cpp for Android:
 *   git clone https://github.com/ggml-org/llama.cpp
 *   cmake -B build-android \
 *     -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
 *     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
 *     -DGGML_VULKAN=ON -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF \
 *     -DLLAMA_CUDA=OFF -DBUILD_SHARED_LIBS=ON
 *   cmake --build build-android -j8
 *   cmake --install build-android --prefix pkg-android
 *   # Copy .so files from pkg-android/lib/ to jniLibs/arm64-v8a/
 */
object LlamaBridge {

    private var loaded = false

    /**
     * Load native libraries. Returns true if successful.
     * Tries backends in order: Hexagon NPU -> Vulkan GPU -> CPU
     */
    fun load(): Boolean {
        if (loaded) return true
        return try {
            // Try Hexagon NPU first (best perf on Snapdragon)
            try {
                System.loadLibrary("ggml-hexagon")
                System.loadLibrary("ggml-htp-v73")
            } catch (_: UnsatisfiedLinkError) {
                // NPU not available, try Vulkan
                try {
                    System.loadLibrary("ggml-vulkan")
                } catch (_: UnsatisfiedLinkError) {
                    // Vulkan not available, CPU fallback (built-in)
                }
            }
            System.loadLibrary("ggml-base")
            System.loadLibrary("ggml-cpu")
            System.loadLibrary("ggml")
            System.loadLibrary("llama")
            loaded = true
            true
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    /** Native: create a llama context and load model */
    external fun createContext(
        modelPath: String,
        nCtx: Int,
        nGpuLayers: Int,
        threads: Int
    ): Long

    /** Native: run inference, returns response text */
    external fun runInference(
        contextPtr: Long,
        prompt: String,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float
    ): String

    /** Native: streaming inference, calls callback for each token */
    external fun runInferenceStream(
        contextPtr: Long,
        prompt: String,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        callback: (String) -> Unit
    )

    /** Native: release model and context */
    external fun releaseContext(contextPtr: Long)

    /** Native: get backend info (which accelerator is active) */
    external fun getBackendInfo(): String

    /** Native: benchmark prompt processing speed */
    external fun benchmark(contextPtr: Long, pp: Int, tg: Int): String
}
