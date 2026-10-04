# Shader preview (desktop, no phone needed)

Runs the app's REAL GLSL (extracted from `Shaders.kt`) on a software GPU so you can look at the avatar and
sanity-check shader changes before building the APK.

    pip install moderngl numpy pillow     # needs libEGL + Mesa (llvmpipe) on Linux
    python3 tools/shader-preview/avatar_preview.py   # writes avatar_presets.png / avatar_expr.png

This previews the shaders only. It does not replace testing on a phone.
