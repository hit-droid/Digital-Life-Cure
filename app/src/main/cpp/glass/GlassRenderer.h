/**
 * 液态玻璃渲染器（GLES2）。
 *
 * 来源说明：折射着色器的数学（圆角 SDF、边缘折射位移、色散多采样、9 抽模糊、
 * 高光带、内阴影、色彩控制）移植自「ytbl 液态玻璃」库（作者·默檐，参考 kyant
 * AndroidLiquidGlass）；本文件为其 Android 端**无 ImGui 依赖的精简重写**，
 * 仅保留渲染所需部分，接口改为按面板逐个绘制。
 *
 * 与 ytbl 的差异：ytbl 依赖 ImGui 的绘制列表与 GLES3；此处改为 GLES2 +
 * 自建圆角矩形网格，可在本工程既有的 GLES2 上下文（Live2D 走 CSM_TARGET_ANDROID_ES2）
 * 中直接使用，且背景既支持拷贝当前帧缓冲（glCopyTexSubImage2D），
 * 也支持由 Java 侧上传位图纹理（页面快照）。
 */
#pragma once

#include <GLES2/gl2.h>

namespace glass {

/** 单块玻璃面板的全部参数（对应着色器 uniform，单位除角度外均为像素） */
struct PanelParams {
    // 面板矩形（surface 像素坐标，原点左上）
    float x = 0.0f, y = 0.0f, w = 0.0f, h = 0.0f;
    // 四角圆角半径：左上、右上、右下、左下
    float radiusTl = 0.0f, radiusTr = 0.0f, radiusBr = 0.0f, radiusBl = 0.0f;

    // 边缘折射：进入面板多少像素内发生弯折、最大位移多少像素
    float refractionHeight = 0.0f;
    float refractionAmount = 0.0f;
    // 色散强度（0 关闭）；越大边缘彩虹越明显
    float dispersion = 0.0f;
    // 背景模糊半径（像素）
    float blurRadius = 0.0f;

    float opacity = 1.0f;          // 玻璃整体透明度
    float vibrancy = 0.0f;         // 鲜艳度（>0.5 时饱和度 ×1.5）
    float brightness = 0.0f;       // 色彩控制
    float contrast = 1.0f;
    float saturation = 1.0f;

    // 表面染色（tintMode：0 不染 / 1 luma 着色 / 2 直接混合）
    float surfaceR = 0.0f, surfaceG = 0.0f, surfaceB = 0.0f, surfaceA = 0.0f;
    float tintMode = 0.0f;

    // 边缘高光（highlightMode：0 关 / 1 加色 / 2 混白 / 3 环境光）
    float highlightR = 1.0f, highlightG = 1.0f, highlightB = 1.0f, highlightA = 0.5f;
    float highlightAlpha = 1.0f;
    float highlightWidth = 0.5f;
    float highlightBlur = 0.25f;
    float highlightAngle = 45.0f;
    float highlightFalloff = 1.0f;
    float highlightMode = 0.0f;

    // 内阴影
    float innerColorR = 0.0f, innerColorG = 0.0f, innerColorB = 0.0f, innerColorA = 1.0f;
    float innerAlpha = 0.0f;
    float innerRadius = 24.0f;
    float innerOffsetX = 0.0f, innerOffsetY = 24.0f;

    // 背景纹理采样：面板左上角在快照中的像素偏移，快照尺寸，纹理存储尺寸
    float captureOriginX = 0.0f, captureOriginY = 0.0f;
    float captureW = 0.0f, captureH = 0.0f;
    float captureTexW = 0.0f, captureTexH = 0.0f;
    // 纹理是否为帧缓冲拷贝（GL 原点在左下，需要翻转 v）
    float flipY = 0.0f;
    // 无纹理或纹理透明时的兜底底色
    float fallbackR = 0.0f, fallbackG = 0.0f, fallbackB = 0.0f, fallbackA = 1.0f;
};

/** 渲染目标尺寸（surface 像素），用于把面板矩形换算到 NDC */
struct SurfaceSize {
    float w = 1.0f, h = 1.0f;
};

class GlassRenderer {
public:
    /** 编译着色器、准备网格。返回 false 表示 GLSL 编译/链接失败（调用方应降级） */
    bool init();
    bool ready() const { return program_ != 0; }
    void shutdown();

    /** 绘制一块玻璃面板；contentTex 为 0 时使用 fallback 底色 */
    void drawPanel(const PanelParams& p, GLuint contentTex);

    /** 当前渲染目标尺寸（由 Java 每次 surface 变化时更新） */
    void setSurfaceSize(float w, float h) { surface_.w = w; surface_.h = h; }

    /** 绑定到 GL 上下文时清理上一帧状态 */
    void beginFrame();

private:
    GLuint program_ = 0;
    GLuint vbo_ = 0;
    SurfaceSize surface_;

    GLint uSize_ = -1, uRadii_ = -1, uRefractionHeight_ = -1, uRefractionAmount_ = -1;
    GLint uDispersion_ = -1, uBlur_ = -1, uOpacity_ = -1, uColorCtrl_ = -1, uVibrancy_ = -1;
    GLint uSurfaceColor_ = -1, uTintMode_ = -1;
    GLint uHlColor_ = -1, uHlAlpha_ = -1, uHlWidth_ = -1, uHlBlur_ = -1, uHlAngle_ = -1;
    GLint uHlFalloff_ = -1, uHlMode_ = -1;
    GLint uInnerColor_ = -1, uInnerAlpha_ = -1, uInnerRadius_ = -1, uInnerOffset_ = -1;
    GLint uContent_ = -1, uCaptureOrigin_ = -1, uCaptureSize_ = -1, uCaptureTexSize_ = -1;
    GLint uFlipY_ = -1, uFallback_ = -1;

    void drawQuad(const PanelParams& p);
};

} // namespace glass
