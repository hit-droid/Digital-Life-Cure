/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include "LAppGroundShadow.hpp"
#include "LAppPal.hpp"

namespace {
    LAppGroundShadow* s_instance = NULL;
}

LAppGroundShadow* LAppGroundShadow::GetInstance()
{
    if (s_instance == NULL)
    {
        s_instance = new LAppGroundShadow();
    }

    return s_instance;
}

void LAppGroundShadow::ReleaseInstance()
{
    if (s_instance != NULL)
    {
        delete s_instance;
    }

    s_instance = NULL;
}

LAppGroundShadow::LAppGroundShadow()
    : _program(0)
    , _attrLocal(-1)
    , _uniformCenter(-1)
    , _uniformRadius(-1)
    , _uniformColor(-1)
    , _failed(false)
{
}

LAppGroundShadow::~LAppGroundShadow()
{
}

bool LAppGroundShadow::EnsureProgram()
{
    if (_failed)
    {
        return false;
    }
    if (_program != 0)
    {
        return true;
    }

    // 単位四角形（-1..1）を uCenter/uRadius で NDC 空間の楕円へ写す。
    const char* vertexShaderSrc =
        "#version 100\n"
        "attribute vec2 aLocal;\n"
        "uniform vec2 uCenter;\n"
        "uniform vec2 uRadius;\n"
        "varying vec2 vLocal;\n"
        "void main(void){\n"
        "    vLocal = aLocal;\n"
        "    gl_Position = vec4(uCenter + aLocal * uRadius, 0.0, 1.0);\n"
        "}\n";

    // 中心から縁へ二次で減衰させ、境界の出ない柔らかい楕円にする。
    const char* fragmentShaderSrc =
        "#version 100\n"
        "precision mediump float;\n"
        "varying vec2 vLocal;\n"
        "uniform vec4 uColor;\n"
        "void main(void){\n"
        "    float d = length(vLocal);\n"
        "    float fall = clamp(1.0 - d, 0.0, 1.0);\n"
        "    fall = fall * fall;\n"
        "    gl_FragColor = vec4(uColor.rgb, uColor.a * fall);\n"
        "}\n";

    GLuint vertexShaderId = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vertexShaderId, 1, &vertexShaderSrc, NULL);
    glCompileShader(vertexShaderId);

    GLint compiled = 0;
    glGetShaderiv(vertexShaderId, GL_COMPILE_STATUS, &compiled);
    if (compiled == 0)
    {
        glDeleteShader(vertexShaderId);
        _failed = true;
        LAppPal::PrintLog("[APP]ground shadow vertex shader compile failed, skip shadow");
        return false;
    }

    GLuint fragmentShaderId = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fragmentShaderId, 1, &fragmentShaderSrc, NULL);
    glCompileShader(fragmentShaderId);

    glGetShaderiv(fragmentShaderId, GL_COMPILE_STATUS, &compiled);
    if (compiled == 0)
    {
        glDeleteShader(vertexShaderId);
        glDeleteShader(fragmentShaderId);
        _failed = true;
        LAppPal::PrintLog("[APP]ground shadow fragment shader compile failed, skip shadow");
        return false;
    }

    GLuint programId = glCreateProgram();
    glAttachShader(programId, vertexShaderId);
    glAttachShader(programId, fragmentShaderId);
    glLinkProgram(programId);

    GLint linked = 0;
    glGetProgramiv(programId, GL_LINK_STATUS, &linked);

    glDeleteShader(vertexShaderId);
    glDeleteShader(fragmentShaderId);

    if (linked == 0)
    {
        glDeleteProgram(programId);
        _failed = true;
        LAppPal::PrintLog("[APP]ground shadow program link failed, skip shadow");
        return false;
    }

    _attrLocal = glGetAttribLocation(programId, "aLocal");
    _uniformCenter = glGetUniformLocation(programId, "uCenter");
    _uniformRadius = glGetUniformLocation(programId, "uRadius");
    _uniformColor = glGetUniformLocation(programId, "uColor");

    if (_attrLocal < 0 || _uniformCenter < 0 || _uniformRadius < 0 || _uniformColor < 0)
    {
        glDeleteProgram(programId);
        _attrLocal = -1;
        _uniformCenter = -1;
        _uniformRadius = -1;
        _uniformColor = -1;
        _failed = true;
        LAppPal::PrintLog("[APP]ground shadow shader locations missing, skip shadow");
        return false;
    }

    _program = programId;
    LAppPal::PrintLog("[APP]ground shadow shader ready (program=%u)", _program);
    return true;
}

void LAppGroundShadow::Draw(float centerX, float centerY, float radiusX, float radiusY, float alpha)
{
    if (alpha <= 0.0f || radiusX <= 0.0f || radiusY <= 0.0f)
    {
        return;
    }

    if (!EnsureProgram())
    {
        return;
    }

    // 単位四角形（三角形ファン）。aLocal がそのまま楕円内の正規化座標になる。
    const GLfloat localVertex[] =
    {
        -1.0f, -1.0f,
         1.0f, -1.0f,
         1.0f,  1.0f,
        -1.0f,  1.0f,
    };

    glUseProgram(_program);

    // 阴影不写深度、不参与裁剪，且以普通 α 混合输出（非预乘），
    // 确保它作为独立的一层叠在模型下方，不干扰随后的模型渲染。
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_STENCIL_TEST);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    // 顶点用的是客户端数组；若残留了非零 VBO，glVertexAttribPointer 会把指针当成偏移，
    // 因此这里显式解绑，保证顶点数据按普通客户端数组解释。
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);

    glEnableVertexAttribArray(_attrLocal);
    glVertexAttribPointer(_attrLocal, 2, GL_FLOAT, GL_FALSE, 0, localVertex);

    glUniform2f(_uniformCenter, centerX, centerY);
    glUniform2f(_uniformRadius, radiusX, radiusY);
    glUniform4f(_uniformColor, 0.0f, 0.0f, 0.0f, alpha);

    glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

    glDisableVertexAttribArray(_attrLocal);
}
