/**
 * 液态玻璃渲染器的 JNI 桥接。
 *
 * Java 侧：com.digitallife.render.GlassNative
 * 参数以定长 float[] 传入（避免逐字段 JNI 往返），下标定义见 GLASS_PARAM_COUNT。
 */
#include <jni.h>
#include <android/log.h>

#include "GlassRenderer.h"

#define LOG_TAG "GlassJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 与 Java 侧 GlassNative.PARAM_* 常量一一对应
enum {
    P_X = 0, P_Y, P_W, P_H,
    P_RADIUS_TL, P_RADIUS_TR, P_RADIUS_BR, P_RADIUS_BL,
    P_REFRACTION_HEIGHT, P_REFRACTION_AMOUNT, P_DISPERSION, P_BLUR,
    P_OPACITY, P_VIBRANCY, P_BRIGHTNESS, P_CONTRAST, P_SATURATION,
    P_SURFACE_R, P_SURFACE_G, P_SURFACE_B, P_SURFACE_A, P_TINT_MODE,
    P_HL_R, P_HL_G, P_HL_B, P_HL_A, P_HL_ALPHA, P_HL_WIDTH, P_HL_BLUR,
    P_HL_ANGLE, P_HL_FALLOFF, P_HL_MODE,
    P_INNER_R, P_INNER_G, P_INNER_B, P_INNER_A, P_INNER_ALPHA, P_INNER_RADIUS,
    P_INNER_OFFSET_X, P_INNER_OFFSET_Y,
    P_CAPTURE_ORIGIN_X, P_CAPTURE_ORIGIN_Y, P_CAPTURE_W, P_CAPTURE_H,
    P_CAPTURE_TEX_W, P_CAPTURE_TEX_H, P_FLIP_Y,
    P_FALLBACK_R, P_FALLBACK_G, P_FALLBACK_B, P_FALLBACK_A,
    P_COUNT
};

// 每个 GL 上下文持有独立渲染器；本工程玻璃层只有一个 GL 线程，用 thread_local 足够安全。
static glass::GlassRenderer* rendererForCurrentThread() {
    thread_local glass::GlassRenderer instance;
    return &instance;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_digitallife_render_GlassNative_nativeInit(JNIEnv*, jclass) {
    glass::GlassRenderer* r = rendererForCurrentThread();
    bool ok = r->init();
    if (!ok) LOGE("玻璃渲染器初始化失败（GLSL 编译/链接错误）");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_digitallife_render_GlassNative_nativeShutdown(JNIEnv*, jclass) {
    rendererForCurrentThread()->shutdown();
}

JNIEXPORT void JNICALL
Java_com_digitallife_render_GlassNative_nativeSetSurfaceSize(JNIEnv*, jclass, jint w, jint h) {
    rendererForCurrentThread()->setSurfaceSize((float) w, (float) h);
}

JNIEXPORT void JNICALL
Java_com_digitallife_render_GlassNative_nativeBeginFrame(JNIEnv*, jclass) {
    rendererForCurrentThread()->beginFrame();
}

JNIEXPORT jboolean JNICALL
Java_com_digitallife_render_GlassNative_nativeDrawPanel(JNIEnv* env, jclass, jfloatArray arr,
                                                        jint texId) {
    if (arr == nullptr) return JNI_FALSE;
    if (env->GetArrayLength(arr) < P_COUNT) return JNI_FALSE;

    jfloat buf[P_COUNT];
    env->GetFloatArrayRegion(arr, 0, P_COUNT, buf);

    glass::GlassRenderer* r = rendererForCurrentThread();
    if (!r->ready()) return JNI_FALSE;

    glass::PanelParams p;
    p.x = buf[P_X];
    p.y = buf[P_Y];
    p.w = buf[P_W];
    p.h = buf[P_H];
    p.radiusTl = buf[P_RADIUS_TL];
    p.radiusTr = buf[P_RADIUS_TR];
    p.radiusBr = buf[P_RADIUS_BR];
    p.radiusBl = buf[P_RADIUS_BL];
    p.refractionHeight = buf[P_REFRACTION_HEIGHT];
    p.refractionAmount = buf[P_REFRACTION_AMOUNT];
    p.dispersion = buf[P_DISPERSION];
    p.blurRadius = buf[P_BLUR];
    p.opacity = buf[P_OPACITY];
    p.vibrancy = buf[P_VIBRANCY];
    p.brightness = buf[P_BRIGHTNESS];
    p.contrast = buf[P_CONTRAST];
    p.saturation = buf[P_SATURATION];
    p.surfaceR = buf[P_SURFACE_R];
    p.surfaceG = buf[P_SURFACE_G];
    p.surfaceB = buf[P_SURFACE_B];
    p.surfaceA = buf[P_SURFACE_A];
    p.tintMode = buf[P_TINT_MODE];
    p.highlightR = buf[P_HL_R];
    p.highlightG = buf[P_HL_G];
    p.highlightB = buf[P_HL_B];
    p.highlightA = buf[P_HL_A];
    p.highlightAlpha = buf[P_HL_ALPHA];
    p.highlightWidth = buf[P_HL_WIDTH];
    p.highlightBlur = buf[P_HL_BLUR];
    p.highlightAngle = buf[P_HL_ANGLE];
    p.highlightFalloff = buf[P_HL_FALLOFF];
    p.highlightMode = buf[P_HL_MODE];
    p.innerColorR = buf[P_INNER_R];
    p.innerColorG = buf[P_INNER_G];
    p.innerColorB = buf[P_INNER_B];
    p.innerColorA = buf[P_INNER_A];
    p.innerAlpha = buf[P_INNER_ALPHA];
    p.innerRadius = buf[P_INNER_RADIUS];
    p.innerOffsetX = buf[P_INNER_OFFSET_X];
    p.innerOffsetY = buf[P_INNER_OFFSET_Y];
    p.captureOriginX = buf[P_CAPTURE_ORIGIN_X];
    p.captureOriginY = buf[P_CAPTURE_ORIGIN_Y];
    p.captureW = buf[P_CAPTURE_W];
    p.captureH = buf[P_CAPTURE_H];
    p.captureTexW = buf[P_CAPTURE_TEX_W];
    p.captureTexH = buf[P_CAPTURE_TEX_H];
    p.flipY = buf[P_FLIP_Y];
    p.fallbackR = buf[P_FALLBACK_R];
    p.fallbackG = buf[P_FALLBACK_G];
    p.fallbackB = buf[P_FALLBACK_B];
    p.fallbackA = buf[P_FALLBACK_A];

    r->drawPanel(p, (GLuint) (texId > 0 ? texId : 0));
    return JNI_TRUE;
}

} // extern "C"
