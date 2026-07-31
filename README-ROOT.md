# OfflineLLM — branch `exp/root-mode`

Experimental **root helpers** (not on `main` quality bar).

## What this is
- Opt-in **Root mode** in Settings → System
- Request Magisk `su`
- Absolute GGUF folder path (e.g. `/storage/emulated/0/Model`)
- **Skip SAF→app cache materialize** when the app can `mmap` the file (after optional `chmod a+r`)
- Device **probe** of vendor OpenCL/Vulkan/DSP nodes (diagnostics only)

## What this is NOT
- Not NPU / Hexagon / QNN acceleration
- Not Vulkan re-enabled (still crashes Adreno 640)
- Not a guarantee of faster tokens/s — mainly faster **model load** and less flash wear

## Try on device
1. Install APK from this branch’s GitHub Release tag `v1.7.0-root-exp`
2. Magisk → grant root to OfflineLLM when prompted
3. Settings → System → enable Root mode → Request root
4. Set path to your GGUF folder (or “use SAF hint”)
5. Select model — logs should show `root direct HIT` instead of long `materialize START`

If direct path fails, app still falls back to SAF materialize.
