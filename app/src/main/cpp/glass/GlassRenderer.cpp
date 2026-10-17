/**
 * 液态玻璃渲染器实现（GLES2）。折射着色器移植自「ytbl 液态玻璃」（默檐），
 * 见 GlassRenderer.h 顶部的来源说明。
 */
#include "GlassRenderer.h"

#include <android/log.h>
#include <cmath>
#include <cstring>

#define LOG_TAG "GlassRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace glass {

// ── 顶点着色器：aPos = NDC 位置，aCoord = 面板内像素坐标（原点左上） ──
static const char* kVertexShader =
    "attribute vec2 aPos;\n"
    "attribute vec2 aCoord;\n"
    "varying vec2 vCoord;\n"
    "void main() {\n"
    "    gl_Position = vec4(aPos, 0.0, 1.0);\n"
    "    vCoord = aCoord;\n"
    "}\n";

// ── 片元着色器：圆角 SDF + 边缘折射位移 + 色散多采样 + 背景模糊 + 高光 + 内阴影 ──
// 注意：GLSL ES 1.00 不支持三目运算符 ?:，一律用 if / mix / step 改写。
static const char* kFragmentShader =
    "precision highp float;\n"
    "varying vec2 vCoord;\n"
    "uniform sampler2D uContent;\n"
    "uniform vec2 uSize;\n"
    "uniform vec2 uCaptureOrigin;\n"
    "uniform vec2 uCaptureSize;\n"
    "uniform vec2 uCaptureTexSize;\n"
    "uniform float uFlipY;\n"
    "uniform vec4 uRadii;\n"
    "uniform float uRefractionHeight;\n"
    "uniform float uRefractionAmount;\n"
    "uniform float uDispersion;\n"
    "uniform float uBlur;\n"
    "uniform float uOpacity;\n"
    "uniform vec3 uColorCtrl;\n"
    "uniform float uVibrancy;\n"
    "uniform vec4 uSurfaceColor;\n"
    "uniform float uTintMode;\n"
    "uniform float uHlMode;\n"
    "uniform vec4 uHlColor;\n"
    "uniform float uHlAlpha;\n"
    "uniform float uHlWidth;\n"
    "uniform float uHlBlur;\n"
    "uniform float uHlAngle;\n"
    "uniform float uHlFalloff;\n"
    "uniform vec4 uFallback;\n"
    "uniform vec4 uInnerColor;\n"
    "uniform vec2 uInnerOffset;\n"
    "uniform float uInnerRadius;\n"
    "uniform float uInnerAlpha;\n"
    "\n"
    "float radiusAt(vec2 coord, vec4 radii) {\n"
    "    if (coord.x >= 0.0) {\n"
    "        if (coord.y <= 0.0) return radii.y;\n"
    "        return radii.z;\n"
    "    }\n"
    "    if (coord.y <= 0.0) return radii.x;\n"
    "    return radii.w;\n"
    "}\n"
    "float sdRoundedRect(vec2 coord, vec2 halfSize, float radius) {\n"
    "    vec2 cornerCoord = abs(coord) - (halfSize - vec2(radius));\n"
    "    float outside = length(max(cornerCoord, vec2(0.0))) - radius;\n"
    "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n"
    "    return outside + inside;\n"
    "}\n"
    "vec2 gradSdRoundedRect(vec2 coord, vec2 halfSize, float radius) {\n"
    "    vec2 cornerCoord = abs(coord) - (halfSize - vec2(radius));\n"
    "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n"
    "        vec2 g = max(cornerCoord, vec2(0.0));\n"
    "        float gl2 = length(g);\n"
    "        if (gl2 > 0.0001) return sign(coord) * (g / gl2);\n"
    "        return vec2(0.0);\n"
    "    }\n"
    "    float gradX = step(cornerCoord.y, cornerCoord.x);\n"
    "    return sign(coord) * vec2(gradX, 1.0 - gradX);\n"
    "}\n"
    "float circleMap(float x) {\n"
    "    float safeX = clamp(x, 0.0, 1.0);\n"
    "    return 1.0 - sqrt(max(1.0 - safeX * safeX, 0.0));\n"
    "}\n"
    "vec2 safeNormalize(vec2 v) {\n"
    "    float len = length(v);\n"
    "    if (len > 0.0001) return v / len;\n"
    "    return vec2(0.0);\n"
    "}\n"
    "vec2 contentUv(vec2 pixel) {\n"
    "    vec2 storage = max(uCaptureTexSize, vec2(1.0));\n"
    "    vec2 valid = max((uCaptureSize - vec2(0.5)) / storage, vec2(0.0));\n"
    "    float yy = pixel.y;\n"
    "    if (uFlipY > 0.5) yy = uCaptureSize.y - pixel.y;\n"
    "    vec2 uv = vec2(pixel.x / storage.x, yy / storage.y);\n"
    "    return clamp(uv, vec2(0.0), valid);\n"
    "}\n"
    "vec4 backdropSample(vec2 pixel) {\n"
    "    vec4 c = texture2D(uContent, contentUv(pixel));\n"
    "    float fbA = clamp(c.a, 0.0, 1.0);\n"
    "    c.rgb += uFallback.rgb * uFallback.a * (1.0 - fbA);\n"
    "    c.a = fbA + uFallback.a * (1.0 - fbA);\n"
    "    return c;\n"
    "}\n"
    "vec4 sampleContent(vec2 coord) {\n"
    "    return backdropSample(coord + uCaptureOrigin);\n"
    "}\n"
    "vec4 blurContent(vec2 coord, float radius) {\n"
    "    vec4 c;\n"
    "    if (radius <= 0.5) {\n"
    "        c = sampleContent(coord);\n"
    "    } else {\n"
    "        float stepSize = radius * 0.5;\n"
    "        vec2 diag = vec2(stepSize) * 0.7;\n"
    "        c = sampleContent(coord);\n"
    "        c += sampleContent(coord + vec2(stepSize, 0.0));\n"
    "        c += sampleContent(coord - vec2(stepSize, 0.0));\n"
    "        c += sampleContent(coord + vec2(0.0, stepSize));\n"
    "        c += sampleContent(coord - vec2(0.0, stepSize));\n"
    "        c += sampleContent(coord + diag);\n"
    "        c += sampleContent(coord + vec2(diag.x, -diag.y));\n"
    "        c += sampleContent(coord + vec2(-diag.x, diag.y));\n"
    "        c += sampleContent(coord - diag);\n"
    "        c /= 9.0;\n"
    "    }\n"
    "    if (c.a <= 0.001) return vec4(0.0);\n"
    "    c.rgb = clamp(c.rgb / c.a, 0.0, 1.0);\n"
    "    return c;\n"
    "}\n"
    "vec3 applyColorControls(vec3 c, vec3 bcs) {\n"
    "    float brightness = bcs.x;\n"
    "    float contrast = bcs.y;\n"
    "    float saturation = bcs.z;\n"
    "    float invSat = 1.0 - saturation;\n"
    "    vec3 lum = vec3(0.213, 0.715, 0.072) * invSat;\n"
    "    float t = 0.5 - contrast * 0.5 + brightness;\n"
    "    return vec3(dot(c, contrast * (lum + vec3(saturation, 0.0, 0.0))) + t,\n"
    "                dot(c, contrast * (lum + vec3(0.0, saturation, 0.0))) + t,\n"
    "                dot(c, contrast * (lum + vec3(0.0, 0.0, saturation))) + t);\n"
    "}\n"
    "void main() {\n"
    "    vec2 halfSize = uSize * 0.5;\n"
    "    vec2 centeredCoord = vCoord - halfSize;\n"
    "    float radius = radiusAt(centeredCoord, uRadii);\n"
    "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n"
    "    vec2 contentCoord = vCoord;\n"
    "    float dispersion = uDispersion;\n"
    "    if (-sd < uRefractionHeight && uRefractionHeight > 0.0001 && uRefractionAmount > 0.0) {\n"
    "        float sdC = min(sd, 0.0);\n"
    "        float d = circleMap(1.0 - (-sdC / uRefractionHeight)) * (-uRefractionAmount);\n"
    "        float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
    "        vec2 grad = safeNormalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius));\n"
    "        contentCoord = vCoord + d * grad;\n"
    "    } else if (uRefractionAmount > 0.0) {\n"
    "        dispersion = 0.0;\n"
    "    }\n"
    "    vec4 color;\n"
    "    if (dispersion > 0.0 && uRefractionHeight > 0.0001 && uRefractionAmount > 0.0) {\n"
    "        float sdC = min(sd, 0.0);\n"
    "        float d = circleMap(1.0 - (-sdC / uRefractionHeight)) * (-uRefractionAmount);\n"
    "        float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
    "        vec2 grad = safeNormalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius));\n"
    "        vec2 refractedCoord = vCoord + d * grad;\n"
    "        float edgeX = abs(centeredCoord.x / max(halfSize.x, 0.001));\n"
    "        float edgeY = abs(centeredCoord.y / max(halfSize.y, 0.001));\n"
    "        float di = dispersion * clamp(edgeX * edgeY, 0.0, 1.0);\n"
    "        vec2 dc = d * grad * di;\n"
    "        color = vec4(0.0);\n"
    "        vec4 c1 = blurContent(refractedCoord + dc, uBlur);\n"
    "        color.r += c1.r / 3.5; color.a += c1.a / 7.0;\n"
    "        vec4 c2 = blurContent(refractedCoord + dc * (2.0 / 3.0), uBlur);\n"
    "        color.r += c2.r / 3.5; color.g += c2.g / 7.0; color.a += c2.a / 7.0;\n"
    "        vec4 c3 = blurContent(refractedCoord + dc * (1.0 / 3.0), uBlur);\n"
    "        color.r += c3.r / 3.5; color.g += c3.g / 3.5; color.a += c3.a / 7.0;\n"
    "        vec4 c4 = blurContent(refractedCoord, uBlur);\n"
    "        color.g += c4.g / 3.5; color.a += c4.a / 7.0;\n"
    "        vec4 c5 = blurContent(refractedCoord - dc * (1.0 / 3.0), uBlur);\n"
    "        color.g += c5.g / 3.5; color.b += c5.b / 3.0; color.a += c5.a / 7.0;\n"
    "        vec4 c6 = blurContent(refractedCoord - dc * (2.0 / 3.0), uBlur);\n"
    "        color.b += c6.b / 3.0; color.a += c6.a / 7.0;\n"
    "        vec4 c7 = blurContent(refractedCoord - dc, uBlur);\n"
    "        color.r += c7.r / 7.0; color.b += c7.b / 3.0; color.a += c7.a / 7.0;\n"
    "        color.a = clamp(color.a, 0.0, 1.0);\n"
    "    } else {\n"
    "        color = blurContent(contentCoord, uBlur);\n"
    "    }\n"
    "    vec3 bcs = uColorCtrl;\n"
    "    if (uVibrancy > 0.5) bcs.z *= 1.5;\n"
    "    color.rgb = applyColorControls(color.rgb, bcs);\n"
    "    color.a *= uOpacity;\n"
    "    if (uTintMode > 1.5) {\n"
    "        color = vec4(mix(color.rgb, uSurfaceColor.rgb, uSurfaceColor.a),\n"
    "                     color.a + uSurfaceColor.a * (1.0 - color.a));\n"
    "    } else if (uTintMode > 0.5) {\n"
    "        float luma = dot(color.rgb, vec3(0.213, 0.715, 0.072));\n"
    "        vec3 hue = uSurfaceColor.rgb * (0.35 + 1.3 * luma);\n"
    "        color.rgb = mix(color.rgb, hue, 0.75 * uSurfaceColor.a);\n"
    "        color.a = max(color.a, 0.75 * uSurfaceColor.a);\n"
    "    }\n"
    "    if (uHlMode > 0.5) {\n"
    "        float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
    "        vec2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);\n"
    "        float edgeDistance = abs(sd);\n"
    "        float edgeBand = 1.0 - smoothstep(max(uHlWidth - uHlBlur, 0.0),\n"
    "                                          uHlWidth + uHlBlur, edgeDistance);\n"
    "        float d = dot(grad, vec2(cos(uHlAngle), sin(uHlAngle)));\n"
    "        float intensity = pow(abs(d), max(uHlFalloff, 0.001));\n"
    "        if (uHlMode > 2.5) {\n"
    "            float ha = uHlAlpha * uHlColor.a * edgeBand;\n"
    "            color.rgb += uHlColor.rgb * ha;\n"
    "            color.a = clamp(color.a + ha, 0.0, 1.0);\n"
    "        } else if (uHlMode > 1.5) {\n"
    "            float ha = intensity * edgeBand * uHlAlpha * step(0.0, d);\n"
    "            color.rgb = mix(color.rgb, vec3(1.0), ha);\n"
    "            color.a += ha * (1.0 - color.a);\n"
    "        } else {\n"
    "            float ha = intensity * edgeBand * uHlAlpha;\n"
    "            color.rgb += uHlColor.rgb * ha;\n"
    "            color.a = clamp(color.a + ha, 0.0, 1.0);\n"
    "        }\n"
    "    }\n"
    "    if (uInnerAlpha > 0.001) {\n"
    "        vec2 offCoord = centeredCoord - uInnerOffset;\n"
    "        float isd = sdRoundedRect(offCoord, halfSize, radius);\n"
    "        float mask = clamp(isd / max(uInnerRadius, 1.0), 0.0, 1.0);\n"
    "        float inside = clamp(0.5 - sd, 0.0, 1.0);\n"
    "        float sa = mask * inside * uInnerAlpha;\n"
    "        color.rgb = mix(color.rgb, uInnerColor.rgb, sa);\n"
    "        color.a += sa * (1.0 - color.a);\n"
    "    }\n"
    "    color.a *= 1.0 - smoothstep(0.0, 0.75, sd);\n"
    "    gl_FragColor = color;\n"
    "}\n";

static GLuint compileShader(GLenum type, const char* src) {
    GLuint shader = glCreateShader(type);
    if (shader == 0) return 0;
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (ok != GL_TRUE) {
        char log[1024];
        GLsizei n = 0;
        glGetShaderInfoLog(shader, sizeof(log) - 1, &n, log);
        log[n] = '\0';
        LOGE("shader compile failed: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool GlassRenderer::init() {
    if (program_ != 0) return true;

    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (vs == 0 || fs == 0) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return false;
    }

    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[1024];
        GLsizei n = 0;
        glGetProgramInfoLog(program, sizeof(log) - 1, &n, log);
        log[n] = '\0';
        LOGE("program link failed: %s", log);
        glDeleteProgram(program);
        return false;
    }

    program_ = program;
    uSize_ = glGetUniformLocation(program_, "uSize");
    uRadii_ = glGetUniformLocation(program_, "uRadii");
    uRefractionHeight_ = glGetUniformLocation(program_, "uRefractionHeight");
    uRefractionAmount_ = glGetUniformLocation(program_, "uRefractionAmount");
    uDispersion_ = glGetUniformLocation(program_, "uDispersion");
    uBlur_ = glGetUniformLocation(program_, "uBlur");
    uOpacity_ = glGetUniformLocation(program_, "uOpacity");
    uColorCtrl_ = glGetUniformLocation(program_, "uColorCtrl");
    uVibrancy_ = glGetUniformLocation(program_, "uVibrancy");
    uSurfaceColor_ = glGetUniformLocation(program_, "uSurfaceColor");
    uTintMode_ = glGetUniformLocation(program_, "uTintMode");
    uHlColor_ = glGetUniformLocation(program_, "uHlColor");
    uHlAlpha_ = glGetUniformLocation(program_, "uHlAlpha");
    uHlWidth_ = glGetUniformLocation(program_, "uHlWidth");
    uHlBlur_ = glGetUniformLocation(program_, "uHlBlur");
    uHlAngle_ = glGetUniformLocation(program_, "uHlAngle");
    uHlFalloff_ = glGetUniformLocation(program_, "uHlFalloff");
    uHlMode_ = glGetUniformLocation(program_, "uHlMode");
    uInnerColor_ = glGetUniformLocation(program_, "uInnerColor");
    uInnerAlpha_ = glGetUniformLocation(program_, "uInnerAlpha");
    uInnerRadius_ = glGetUniformLocation(program_, "uInnerRadius");
    uInnerOffset_ = glGetUniformLocation(program_, "uInnerOffset");
    uContent_ = glGetUniformLocation(program_, "uContent");
    uCaptureOrigin_ = glGetUniformLocation(program_, "uCaptureOrigin");
    uCaptureSize_ = glGetUniformLocation(program_, "uCaptureSize");
    uCaptureTexSize_ = glGetUniformLocation(program_, "uCaptureTexSize");
    uFlipY_ = glGetUniformLocation(program_, "uFlipY");
    uFallback_ = glGetUniformLocation(program_, "uFallback");

    if (vbo_ == 0) glGenBuffers(1, &vbo_);
    LOGI("glass renderer initialized");
    return true;
}

void GlassRenderer::shutdown() {
    if (program_ != 0) {
        glDeleteProgram(program_);
        program_ = 0;
    }
    if (vbo_ != 0) {
        glDeleteBuffers(1, &vbo_);
        vbo_ = 0;
    }
    uSize_ = uRadii_ = uRefractionHeight_ = uRefractionAmount_ = -1;
}

void GlassRenderer::beginFrame() {
    if (program_ == 0) return;
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
}

void GlassRenderer::drawQuad(const PanelParams& p) {
    const float w = surface_.w > 0.5f ? surface_.w : 1.0f;
    const float h = surface_.h > 0.5f ? surface_.h : 1.0f;
    const float left = 2.0f * p.x / w - 1.0f;
    const float right = 2.0f * (p.x + p.w) / w - 1.0f;
    const float top = 1.0f - 2.0f * p.y / h;
    const float bottom = 1.0f - 2.0f * (p.y + p.h) / h;

    const float verts[] = {
        // 位置(x,y)           面板内坐标(px)
        left,  top,    0.0f, 0.0f,
        right, top,    p.w,  0.0f,
        right, bottom, p.w,  p.h,
        left,  top,    0.0f, 0.0f,
        right, bottom, p.w,  p.h,
        left,  bottom, 0.0f, p.h,
    };

    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STREAM_DRAW);

    GLint aPos = glGetAttribLocation(program_, "aPos");
    GLint aCoord = glGetAttribLocation(program_, "aCoord");
    const GLsizei stride = 4 * sizeof(float);
    if (aPos >= 0) {
        glEnableVertexAttribArray((GLuint) aPos);
        glVertexAttribPointer((GLuint) aPos, 2, GL_FLOAT, GL_FALSE, stride, (const void*) 0);
    }
    if (aCoord >= 0) {
        glEnableVertexAttribArray((GLuint) aCoord);
        glVertexAttribPointer((GLuint) aCoord, 2, GL_FLOAT, GL_FALSE, stride,
                              (const void*) (2 * sizeof(float)));
    }
    glDrawArrays(GL_TRIANGLES, 0, 6);

    if (aPos >= 0) glDisableVertexAttribArray((GLuint) aPos);
    if (aCoord >= 0) glDisableVertexAttribArray((GLuint) aCoord);
}

void GlassRenderer::drawPanel(const PanelParams& p, GLuint contentTex) {
    if (program_ == 0) return;
    if (p.w <= 0.5f || p.h <= 0.5f) return;

    glUseProgram(program_);

    glUniform2f(uSize_, p.w, p.h);
    glUniform4f(uRadii_, p.radiusTl, p.radiusTr, p.radiusBr, p.radiusBl);
    glUniform1f(uRefractionHeight_, p.refractionHeight);
    glUniform1f(uRefractionAmount_, p.refractionAmount);
    glUniform1f(uDispersion_, p.dispersion);
    glUniform1f(uBlur_, p.blurRadius);
    glUniform1f(uOpacity_, p.opacity);
    glUniform3f(uColorCtrl_, p.brightness, p.contrast, p.saturation);
    glUniform1f(uVibrancy_, p.vibrancy);
    glUniform4f(uSurfaceColor_, p.surfaceR, p.surfaceG, p.surfaceB, p.surfaceA);
    glUniform1f(uTintMode_, p.tintMode);
    glUniform4f(uHlColor_, p.highlightR, p.highlightG, p.highlightB, p.highlightA);
    glUniform1f(uHlAlpha_, p.highlightAlpha);
    glUniform1f(uHlWidth_, p.highlightWidth);
    glUniform1f(uHlBlur_, p.highlightBlur);
    glUniform1f(uHlAngle_, p.highlightAngle);
    glUniform1f(uHlFalloff_, p.highlightFalloff);
    glUniform1f(uHlMode_, p.highlightMode);
    glUniform4f(uInnerColor_, p.innerColorR, p.innerColorG, p.innerColorB, p.innerColorA);
    glUniform1f(uInnerAlpha_, p.innerAlpha);
    glUniform1f(uInnerRadius_, p.innerRadius);
    glUniform2f(uInnerOffset_, p.innerOffsetX, p.innerOffsetY);
    glUniform2f(uCaptureOrigin_, p.captureOriginX, p.captureOriginY);
    glUniform2f(uCaptureSize_, p.captureW, p.captureH);
    glUniform2f(uCaptureTexSize_, p.captureTexW, p.captureTexH);
    glUniform1f(uFlipY_, p.flipY);
    glUniform4f(uFallback_, p.fallbackR, p.fallbackG, p.fallbackB, p.fallbackA);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, contentTex);
    glUniform1i(uContent_, 0);

    drawQuad(p);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);
}

} // namespace glass
