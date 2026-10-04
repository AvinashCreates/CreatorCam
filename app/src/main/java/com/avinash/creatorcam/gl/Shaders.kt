package com.avinash.creatorcam.gl

import android.opengl.GLES20

internal object Shaders {
    /** Full-screen quad. aPos is -1..1. uTexMat maps output UV (0..1, upright) to camera-texture UV. */
    const val CAMERA_VERTEX = """
attribute vec2 aPos;
uniform mat4 uTexMat;
varying vec2 vTex;
varying vec2 vUv;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vUv = aPos * 0.5 + 0.5;
    vTex = (uTexMat * vec4(vUv, 0.0, 1.0)).xy;
}
"""

    /** One pass: sharpen/soften -> saturation/mono -> contrast/brightness -> tint -> fade -> vignette. */
    const val CAMERA_FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTex;
varying vec2 vUv;
uniform samplerExternalOES uTex;
uniform vec2 uTexel;
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;
uniform float uSharpen;
uniform float uSoft;
uniform float uVignette;
uniform float uMono;
uniform float uFade;
uniform vec3 uTint;
void main() {
    vec3 c = texture2D(uTex, vTex).rgb;
    if (uSharpen > 0.001 || uSoft > 0.001) {
        vec2 r = uTexel * (1.0 + uSoft * 2.0);
        vec3 blur = (texture2D(uTex, vTex + vec2(r.x, 0.0)).rgb
                   + texture2D(uTex, vTex - vec2(r.x, 0.0)).rgb
                   + texture2D(uTex, vTex + vec2(0.0, r.y)).rgb
                   + texture2D(uTex, vTex - vec2(0.0, r.y)).rgb) * 0.25;
        c += (c - blur) * uSharpen;
        c = mix(c, blur, uSoft);
    }
    float luma = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(luma), c, uSaturation);
    c = mix(c, vec3(luma), uMono);
    c = (c - 0.5) * uContrast + 0.5 + uBrightness;
    c *= uTint;
    c = mix(c, c * 0.85 + 0.08, uFade);
    float d = distance(vUv, vec2(0.5));
    c *= 1.0 - uVignette * smoothstep(0.3, 0.8, d);
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""

    /**
     * Avatar quad. The avatar lives in "avatar units" (1 unit = head half-width, y up); the quad spans
     * [-2, 2]. It is placed in pixel space so rolling the head keeps the character undistorted.
     */
    const val AVATAR_VERTEX = """
attribute vec2 aPos;
uniform vec2 uCenter;
uniform float uSizePx;
uniform float uRot;
uniform vec2 uView;
varying vec2 vP;
void main() {
    vec2 p = aPos * 0.5 * uSizePx;
    float c = cos(uRot);
    float s = sin(uRot);
    vec2 r = vec2(c * p.x - s * p.y, s * p.x + c * p.y);
    gl_Position = vec4(uCenter + r / (uView * 0.5), 0.0, 1.0);
    vP = aPos * 2.0;
}
"""

    /**
     * Layered Memoji-style character, drawn procedurally with signed-distance fields so it is crisp at any
     * size, needs no bitmaps, and every layer can react to the face state:
     *   back hair -> ears -> head -> blush -> nose -> eyes (blink, gaze) -> brows -> mouth (smile/open)
     *   -> front hair -> accessory.  Yaw/pitch shift the facial-feature layer against the head (2.5D parallax).
     */
    const val AVATAR_FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vP;
uniform float uAA;
uniform float uAlpha;
uniform vec3 uSkin;
uniform vec3 uHair;
uniform vec3 uEye;
uniform float uHairStyle;
uniform float uAcc;
uniform vec2 uLook;
uniform vec2 uEyes;
uniform float uSmile;
uniform float uMouth;

float cov(float d) { return clamp(0.5 - d / uAA, 0.0, 1.0); }
float sdEll(vec2 p, vec2 r) {
    float k0 = length(p / r);
    float k1 = length(p / (r * r));
    return k1 < 0.00001 ? -min(r.x, r.y) : k0 * (k0 - 1.0) / k1;
}
float sdSeg(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a;
    vec2 ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}
float sdRBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
float smin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}
vec4 over(vec4 dst, vec3 col, float a) {
    return vec4(col * a + dst.rgb * (1.0 - a), a + dst.a * (1.0 - a));
}

vec4 drawEye(vec4 c, vec2 f, float cx, float open) {
    vec2 q = f - vec2(cx, 0.10);
    float o = mix(0.07, 1.0, smoothstep(0.10, 0.80, open)) * (1.0 - 0.30 * uSmile);
    float dE = sdEll(q, vec2(0.205, max(0.175 * o, 0.012)));
    float inE = cov(dE);
    float showWhite = smoothstep(0.12, 0.32, o);
    c = over(c, vec3(0.97, 0.97, 0.99), inE * showWhite);
    vec2 gaze = vec2(uLook.x * 0.05, uLook.y * 0.03);
    float dI = length(q - gaze) - 0.118;
    vec3 ic = mix(uEye * 1.15, uEye * 0.55, smoothstep(0.03, 0.118, length(q - gaze)));
    c = over(c, ic, cov(dI) * inE * showWhite);
    c = over(c, vec3(0.04, 0.03, 0.04), cov(length(q - gaze) - 0.060) * inE * showWhite);
    c = over(c, vec3(1.0), cov(length(q - gaze - vec2(0.040, 0.045)) - 0.026) * inE * showWhite);
    float lid = cov(abs(dE) - 0.020) * smoothstep(-0.07, 0.03, q.y);
    c = over(c, vec3(0.17, 0.10, 0.09), lid);
    return c;
}

vec4 drawBrow(vec4 c, vec2 f, float cx, float lift) {
    vec2 q = f - vec2(cx, 0.43 + lift);
    float inner = cx < 0.0 ? 1.0 : -1.0;
    vec2 a = vec2(-inner * 0.19, -0.018);
    vec2 m = vec2(0.0, 0.028);
    vec2 b = vec2(inner * 0.19, 0.004 + 0.05 * uMouth);
    float d = min(sdSeg(q, a, m), sdSeg(q, m, b)) - 0.036;
    return over(c, mix(uHair * 0.8, vec3(0.20, 0.13, 0.11), 0.45), cov(d));
}

vec4 drawMouth(vec4 c, vec2 f) {
    vec2 q = f - vec2(0.0, -0.50);
    float w = 0.27 + 0.13 * uSmile + 0.03 * uMouth;
    float nx = q.x / w;
    float inx = 1.0 - smoothstep(0.90, 1.0, abs(nx));
    float yu = 0.16 * uSmile * nx * nx - 0.03 * uSmile;
    float prof = pow(max(1.0 - nx * nx, 0.0), 0.7);
    float yl = yu - uMouth * 0.30 * prof;
    float opened = smoothstep(0.04, 0.12, uMouth);
    float dv = min(yu - q.y, q.y - yl);
    float inside = cov(-dv) * inx * opened;
    c = over(c, vec3(0.30, 0.07, 0.10), inside);
    float tongue = cov(sdEll(q - vec2(0.0, yl + 0.075), vec2(0.50 * w, 0.085))) * inside;
    c = over(c, vec3(0.88, 0.38, 0.44), tongue);
    float teethAmt = smoothstep(0.30, 0.65, uSmile + 0.6 * uMouth);
    float teeth = cov(-(q.y - (yu - 0.085))) * inside * teethAmt;
    c = over(c, vec3(0.99, 0.98, 0.97), teeth);
    vec3 lipC = vec3(0.58, 0.24, 0.25);
    float lipTop = cov(abs(q.y - yu) - 0.017) * inx;
    float lipBot = cov(abs(q.y - yl) - 0.021) * inx * opened;
    c = over(c, lipC, max(lipTop, lipBot));
    return c;
}

void main() {
    vec2 p = vP;
    if (abs(p.x) > 1.98 || abs(p.y) > 1.98) { gl_FragColor = vec4(0.0); return; }
    float yaw = uLook.x;
    float pit = uLook.y;
    float style = uHairStyle;
    float hw = 1.0 - 0.07 * abs(yaw);
    float dHead = smin(sdEll(p - vec2(0.0, 0.12), vec2(hw, 1.12)),
                       sdEll(p - vec2(0.0, -0.30), vec2(0.86 * hw, 0.86)), 0.35);
    vec3 hairBase = uHair;
    vec3 hairDark = uHair * 0.70;
    vec4 c = vec4(0.0);

    // ---- back hair
    float back = 0.0;
    if (style > 0.5 && style < 1.5) {
        back = cov(sdEll(p - vec2(0.0, -0.25), vec2(1.26, 1.58)));
    } else if (style > 1.5 && style < 2.5) {
        vec2 q = p - vec2(0.0, 0.25);
        float ang = atan(q.y, q.x);
        back = cov((length(q / vec2(1.50, 1.46)) - 1.0 - 0.045 * sin(ang * 11.0)) * 1.45);
    } else if (style > 2.5 && style < 3.5) {
        back = cov(length(p - vec2(0.0, 1.36)) - 0.46);
    }
    vec3 backCol = mix(hairDark, hairBase, smoothstep(-1.4, 1.4, p.y));
    c = over(c, backCol, back);

    // ---- ears (not with long/curly hair)
    if (style < 0.5 || style > 2.5) {
        vec2 e = vec2(abs(p.x) - 1.0 * hw + yaw * 0.0, p.y - 0.02);
        float dEar = sdEll(e, vec2(0.17, 0.26));
        c = over(c, uSkin * 0.93, cov(dEar));
        c = over(c, uSkin * 0.78, cov(sdEll(e + vec2(0.03, 0.0), vec2(0.08, 0.15))) * 0.6);
    }

    // ---- head
    float edge = smoothstep(-0.30, 0.0, dHead);
    vec3 sk = uSkin * (1.0 - 0.15 * edge);
    sk *= 1.0 + 0.07 * (1.0 - smoothstep(0.0, 0.9, length(p - vec2(-0.30, 0.45))));
    c = over(c, sk, cov(dHead));

    // ---- facial features (parallax with head turn / nod)
    vec2 f = p - vec2(yaw * 0.28, pit * 0.10);
    float inHead = cov(dHead + 0.06);
    float bl = (1.0 - smoothstep(0.0, 0.30, length((f - vec2(0.58, -0.22)) * vec2(1.0, 1.3))))
             + (1.0 - smoothstep(0.0, 0.30, length((f - vec2(-0.58, -0.22)) * vec2(1.0, 1.3))));
    c = over(c, vec3(0.96, 0.45, 0.46), clamp(bl, 0.0, 1.0) * (0.22 + 0.16 * uSmile) * inHead);
    c = over(c, uSkin * 0.68, 0.40 * cov(sdEll(f - vec2(0.0, -0.17), vec2(0.085, 0.055))));
    c = over(c, mix(uSkin, vec3(1.0), 0.35), 0.35 * cov(sdEll(f - vec2(0.0, -0.10), vec2(0.04, 0.05))));
    c = drawEye(c, f, -0.43, uEyes.x);
    c = drawEye(c, f, 0.43, uEyes.y);
    float lift = 0.07 * uMouth + 0.015 * uSmile;
    c = drawBrow(c, f, -0.43, lift);
    c = drawBrow(c, f, 0.43, lift);
    c = drawMouth(c, f);

    // ---- front hair
    if (style < 3.5) {
        vec2 hp = p - vec2(yaw * 0.08, 0.0);
        float dTop = sdEll(hp - vec2(0.0, 0.20), vec2(1.07 * hw, 1.16));
        float hl;
        if (style < 0.5) {
            hl = 0.70 + 0.10 * hp.x * hp.x - 0.20 * smoothstep(-0.9, 0.8, hp.x);
        } else if (style < 1.5) {
            hl = 0.80 - 0.32 * abs(hp.x);
        } else if (style < 2.5) {
            hl = 0.80 + 0.05 * sin(hp.x * 20.0);
        } else {
            hl = 0.74 + 0.06 * hp.x * hp.x;
        }
        float dHair = max(dTop, (hl - hp.y) * 0.9);
        // hair that hugs the outside of the head down to the ears, so the silhouette has no gaps
        float dRing = max(max(dTop, -dHead), -0.12 - hp.y);
        if (style < 0.5) {
            dHair = min(dHair, min(dRing, max(dTop, max(0.90 * hw - abs(hp.x), -0.12 - hp.y))));
        } else if (style < 1.5) {
            dHair = min(dHair, max(sdEll(hp - vec2(0.0, -0.10), vec2(1.13 * hw, 1.30)), 0.91 * hw - abs(hp.x)));
        }
        vec3 hc = mix(hairDark, hairBase, smoothstep(-0.2, 1.2, hp.y));
        float gq = (hp.y - (1.00 - 0.35 * hp.x * hp.x)) / 0.10;
        float g = exp(-gq * gq);
        hc = mix(hc, hc * 1.45 + 0.04, 0.50 * g);
        c = over(c, hc, cov(dHair));
    }
    if (style > 2.5 && style < 3.5) {
        // bun: lighter swirl on top of the back-hair circle
        float sw = cov(abs(length(p - vec2(0.0, 1.36)) - 0.24) - 0.02);
        c = over(c, hairDark, sw * 0.7 * cov(length(p - vec2(0.0, 1.36)) - 0.46 + 0.04));
    }

    // ---- accessories
    if (uAcc > 0.5 && uAcc < 1.5) {
        vec3 frame = vec3(0.11, 0.11, 0.14);
        float r1 = abs(length(f - vec2(-0.43, 0.08)) - 0.235) - 0.022;
        float r2 = abs(length(f - vec2(0.43, 0.08)) - 0.235) - 0.022;
        float br = sdSeg(f, vec2(-0.20, 0.13), vec2(0.20, 0.13)) - 0.016;
        float t1 = sdSeg(f, vec2(-0.66, 0.10), vec2(-1.00, 0.13)) - 0.014;
        float t2 = sdSeg(f, vec2(0.66, 0.10), vec2(1.00, 0.13)) - 0.014;
        float dg = min(min(r1, r2), min(br, min(t1, t2)));
        float sheen = cov(sdEll(f - vec2(-0.50, 0.17), vec2(0.07, 0.025)));
        c = over(c, vec3(1.0), 0.20 * sheen);
        c = over(c, frame, cov(dg));
    } else if (uAcc > 1.5 && uAcc < 2.5) {
        vec3 frame = vec3(0.05, 0.05, 0.07);
        float l1 = sdRBox(f - vec2(-0.43, 0.06), vec2(0.30, 0.21), 0.12);
        float l2 = sdRBox(f - vec2(0.43, 0.06), vec2(0.30, 0.21), 0.12);
        float br = sdSeg(f, vec2(-0.14, 0.14), vec2(0.14, 0.14)) - 0.022;
        float t1 = sdSeg(f, vec2(-0.72, 0.12), vec2(-1.00, 0.14)) - 0.016;
        float t2 = sdSeg(f, vec2(0.72, 0.12), vec2(1.00, 0.14)) - 0.016;
        c = over(c, frame, cov(min(br, min(t1, t2))));
        c = over(c, vec3(0.07, 0.07, 0.10), cov(min(l1, l2)) * 0.96);
        float streak = cov(sdSeg(f - vec2(0.0, 0.0), vec2(-0.55, 0.17), vec2(-0.36, -0.02)) - 0.018)
                     + cov(sdSeg(f, vec2(0.31, 0.17), vec2(0.50, -0.02)) - 0.018);
        c = over(c, vec3(1.0), 0.28 * clamp(streak, 0.0, 1.0));
    } else if (uAcc > 2.5 && uAcc < 3.5) {
        float band = max(abs(sdEll(p - vec2(0.0, 0.08), vec2(1.20 * hw, 1.34))) - 0.048, -(p.y + 0.02));
        c = over(c, vec3(0.16, 0.17, 0.21), cov(band));
        for (int i = 0; i < 2; i++) {
            float sgn = i == 0 ? -1.0 : 1.0;
            vec2 q = p - vec2(sgn * 1.14 * hw, 0.0);
            c = over(c, vec3(0.16, 0.17, 0.21), cov(sdRBox(q, vec2(0.17, 0.31), 0.13)));
            c = over(c, vec3(0.95, 0.30, 0.42), cov(length(q) - 0.085));
            c = over(c, vec3(0.30, 0.31, 0.36), cov(abs(sdRBox(q, vec2(0.17, 0.31), 0.13) + 0.035) - 0.012));
        }
    } else if (uAcc > 3.5) {
        vec3 cap = vec3(0.86, 0.29, 0.30);
        float dCap = max(sdEll(p - vec2(0.0, 0.26), vec2(1.13 * hw, 1.18)), 0.46 - p.y);
        float stripes = 0.5 + 0.5 * sin(p.x * 16.0);
        c = over(c, cap * (0.90 + 0.10 * stripes), cov(dCap));
        c = over(c, cap * 1.12, cov(sdRBox(p - vec2(0.0, 0.50), vec2(1.12 * hw, 0.13), 0.07)));
        c = over(c, vec3(0.95, 0.93, 0.90), cov(length(p - vec2(0.0, 1.46)) - 0.22));
    }

    gl_FragColor = c * uAlpha;
}
"""

    fun buildProgram(vertex: String, fragment: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("Program link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Shader compile failed: $log")
        }
        return shader
    }
}
