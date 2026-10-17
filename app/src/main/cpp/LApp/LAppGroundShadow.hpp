/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#pragma once

#include <GLES2/gl2.h>

/**
 * @brief 地面软阴影：在模型下层画一个居中的椭圆软阴影，给悬浮窗里的角色提供落地感。
 *
 * 纯 GLES2 着色器实现（不用纹理）：顶点着色器把单位四边形放到 NDC 的指定中心/半径，
 * 片元着色器按到中心距离做二次衰减，得到边缘柔和的椭圆阴影。
 * 着色器创建/链接失败时置位失败标记并直接跳过，不影响模型本身的渲染。
 */
class LAppGroundShadow
{
public:
    /**
     * @brief シングルトン取得
     */
    static LAppGroundShadow* GetInstance();

    /**
     * @brief シングルトン解放
     */
    static void ReleaseInstance();

    /**
     * @brief 以 NDC 坐标绘制椭圆软阴影（调用前应先画阴影、后画模型，保证阴影在下层）
     *
     * @param[in]   centerX     阴影中心的 NDC X
     * @param[in]   centerY     阴影中心的 NDC Y
     * @param[in]   radiusX     阴影在 NDC X 方向的半径
     * @param[in]   radiusY     阴影在 NDC Y 方向的半径
     * @param[in]   alpha       阴影中心的最大不透明度（0.0~1.0）
     */
    void Draw(float centerX, float centerY, float radiusX, float radiusY, float alpha);

private:
    LAppGroundShadow();
    ~LAppGroundShadow();

    /**
     * @brief 懒创建着色器程序；失败返回 false（并置 _failed 不再重试）
     */
    bool EnsureProgram();

    GLuint _program;         ///< 阴影视着器程序
    GLint  _attrLocal;       ///< 单位四边形顶点属性
    GLint  _uniformCenter;   ///< 阴影中心 uniform
    GLint  _uniformRadius;   ///< 阴影半径 uniform
    GLint  _uniformColor;    ///< 阴影颜色 uniform
    bool   _failed;          ///< 创建失败标记（失败后不再重试）
};
