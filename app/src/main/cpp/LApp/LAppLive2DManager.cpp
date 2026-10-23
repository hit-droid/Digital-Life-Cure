/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include "LAppLive2DManager.hpp"
#include <math.h>
#include <string>
#include <vector>
#include <GLES2/gl2.h>
#include <Model/CubismModel.hpp>
#include <Rendering/CubismRenderer.hpp>
#include "LAppPal.hpp"
#include "LAppDefine.hpp"
#include "LAppDelegate.hpp"
#include "LAppGroundShadow.hpp"
#include "LAppModel.hpp"
#include "LAppView.hpp"

using namespace Csm;
using namespace LAppDefine;
using namespace std;

namespace {
    LAppLive2DManager* s_instance = NULL;

    // インポートしたモデルディレクトリの動的リスト（LAppDefine::ModelDir の後ろに連結される）
    std::vector<std::string> g_extraModelDirs;
    std::vector<std::string> g_extraModelJsonBases;

    void FinishedMotion(ACubismMotion* self)
    {
        LAppPal::PrintLog("Motion Finished: %x", self);
    }

    // 计算模型所有可见 drawable 顶点在模型本地坐标中的包围盒。
    // 用来给地面阴影定位：水平取包围盒中心，纵向取包围盒底部。
    bool ComputeModelBounds(Csm::CubismModel* model, float* outMinX, float* outMaxX, float* outMinY)
    {
        if (model == NULL)
        {
            return false;
        }

        const csmInt32 drawableCount = model->GetDrawableCount();
        bool hasVertex = false;
        float minX = 0.0f;
        float maxX = 0.0f;
        float minY = 0.0f;

        for (csmInt32 d = 0; d < drawableCount; ++d)
        {
            // 跳过不可见 drawable（如离屏/遮罩部件），避免它们的顶点把包围盒撑大。
            if (!model->GetDrawableDynamicFlagIsVisible(d))
            {
                continue;
            }

            const csmInt32 vertexCount = model->GetDrawableVertexCount(d);
            const csmFloat32* vertices = model->GetDrawableVertices(d);
            if (vertices == NULL || vertexCount <= 0)
            {
                continue;
            }

            for (csmInt32 j = 0; j < vertexCount; ++j)
            {
                const float x = vertices[Csm::Constant::VertexOffset + j * Csm::Constant::VertexStep];
                const float y = vertices[Csm::Constant::VertexOffset + j * Csm::Constant::VertexStep + 1];

                if (!hasVertex)
                {
                    minX = maxX = x;
                    minY = y;
                    hasVertex = true;
                }
                else
                {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                }
            }
        }

        if (!hasVertex)
        {
            return false;
        }

        *outMinX = minX;
        *outMaxX = maxX;
        *outMinY = minY;
        return true;
    }

    // 地面线状态：把「地面」稳定在模型本地坐标中，
    // 避免角色抬身/跳跃时包围盒底上移、把阴影一起带到空中。
    struct GroundLineState
    {
        const void* modelKey;   ///< 当前缓存的模型实例
        float       groundY;    ///< 稳定后的地面线（模型本地 Y）
        bool        valid;

        GroundLineState() : modelKey(NULL), groundY(0.0f), valid(false) {}
    };

    GroundLineState g_groundLine;

    // 用当前帧观测到的包围盒最低点更新地面线：
    // 观测点更低（正常站立）立即贴合；更高（抬身/跳跃）只极缓慢回弹，
    // 保证动作过程中地面线不被拉高，同时慢慢忘记偶发的一次性下探。切换模型时重置。
    float UpdateGroundY(const void* modelKey, float observedMinY)
    {
        if (!g_groundLine.valid || g_groundLine.modelKey != modelKey)
        {
            g_groundLine.modelKey = modelKey;
            g_groundLine.groundY = observedMinY;
            g_groundLine.valid = true;
            return g_groundLine.groundY;
        }

        if (observedMinY < g_groundLine.groundY)
        {
            g_groundLine.groundY = observedMinY;
        }
        else
        {
            g_groundLine.groundY += (observedMinY - g_groundLine.groundY) * 0.002f;
        }

        return g_groundLine.groundY;
    }
}

LAppLive2DManager* LAppLive2DManager::GetInstance()
{
    if (s_instance == NULL)
    {
        s_instance = new LAppLive2DManager();
    }

    return s_instance;
}

void LAppLive2DManager::ReleaseInstance()
{
    if (s_instance != NULL)
    {
        delete s_instance;
    }

    s_instance = NULL;
}

LAppLive2DManager::LAppLive2DManager()
    : _viewMatrix(NULL)
    , _sceneIndex(0)
{
    _viewMatrix = new CubismMatrix44();

    ChangeScene(_sceneIndex);
}

LAppLive2DManager::~LAppLive2DManager()
{
    ReleaseAllModel();
}

void LAppLive2DManager::ReleaseAllModel()
{
    // 模型实例销毁后地址可能被新模型复用，地面线状态按实例指针缓存，这里必须一并重置。
    g_groundLine.valid = false;

    for (csmUint32 i = 0; i < _models.GetSize(); i++)
    {
        delete _models[i];
    }

    _models.Clear();
}

LAppModel* LAppLive2DManager::GetModel(csmUint32 no) const
{
    if (no < _models.GetSize())
    {
        return _models[no];
    }

    return NULL;
}

void LAppLive2DManager::OnDrag(csmFloat32 x, csmFloat32 y) const
{
    for (csmUint32 i = 0; i < _models.GetSize(); i++)
    {
        LAppModel* model = GetModel(i);
        if (model == NULL || model->GetModel() == NULL || model->GetModelSetting() == NULL)
        {
            continue;
        }

        model->SetDragging(x, y);
    }
}

csmInt32 LAppLive2DManager::HitTestZone(csmFloat32 x, csmFloat32 y)
{
    // 純粋な問い合わせ。OnTap と違い表情・モーションには一切触れない
    // （呼び出し側はタップ判定前に「頭か体か」だけを知りたい）。
    for (csmUint32 i = 0; i < _models.GetSize(); i++)
    {
        LAppModel* model = _models[i];
        if (model == NULL || model->GetModel() == NULL || model->GetModelSetting() == NULL)
        {
            continue;
        }
        if (model->HitTest(HitAreaNameHead, x, y))
        {
            return 1;
        }
        if (model->HitTest(HitAreaNameBody, x, y))
        {
            return 2;
        }
    }
    return 0;
}

void LAppLive2DManager::OnTap(csmFloat32 x, csmFloat32 y)
{
    if (DebugLogEnable)
    {
        LAppPal::PrintLog("[APP]tap point: {x:%.2f y:%.2f}", x, y);
    }

    for (csmUint32 i = 0; i < _models.GetSize(); i++)
    {
        if (_models[i] == NULL || _models[i]->GetModel() == NULL || !_models[i]->GetModelSetting()) {
            continue;
        }
        const csmBool hitHead = _models[i]->HitTest(HitAreaNameHead, x, y);
        const csmBool hitBody = _models[i]->HitTest(HitAreaNameBody, x, y);

        if (hitHead)
        {
            if (DebugLogEnable)
            {
                LAppPal::PrintLog("[APP]hit area: [%s]", HitAreaNameHead);
            }
            if (_models[i]->GetModelSetting() && _models[i]->GetModelSetting()->GetExpressionCount() > 0) {
                _models[i]->SetRandomExpression();
            }
            continue;
        }

        if (hitBody)
        {
            if (DebugLogEnable)
            {
                LAppPal::PrintLog("[APP]hit area: [%s]", HitAreaNameBody);
            }
            if (_models[i]->GetModelSetting() && _models[i]->GetModelSetting()->GetMotionCount(MotionGroupTapBody) > 0) {
                _models[i]->StartRandomMotion(MotionGroupTapBody, PriorityNormal, FinishedMotion);
            }
            continue;
        }

        if (_models[i]->GetModelSetting() && _models[i]->GetModelSetting()->GetMotionCount(MotionGroupTapBody) > 0) {
            _models[i]->StartRandomMotion(MotionGroupTapBody, PriorityNormal, FinishedMotion);
        }
    }
}

void LAppLive2DManager::OnUpdate() const
{
    int width = LAppDelegate::GetInstance()->GetWindowWidth();
    int height = LAppDelegate::GetInstance()->GetWindowHeight();

    CubismMatrix44 projection;
    csmUint32 modelCount = _models.GetSize();
    for (csmUint32 i = 0; i < modelCount; ++i)
    {
        LAppModel* model = GetModel(i);
        if (model == NULL || model->GetModel() == NULL || model->GetModelSetting() == NULL)
        {
            continue;
        }
        if (model->GetModel()->GetCanvasWidth() > 1.0f && width < height)
        {
            // 横に長いモデルを縦長ウィンドウに表示する際モデルの横サイズでscaleを算出する
            model->GetModelMatrix()->SetWidth(2.0f);
            projection.Scale(1.0f, static_cast<float>(width) / static_cast<float>(height));
        }
        else
        {
            projection.Scale(static_cast<float>(height) / static_cast<float>(width), 1.0f);
        }

        // 必要があればここで乗算
        if (_viewMatrix != NULL)
        {
            projection.MultiplyByMatrix(_viewMatrix);
        }

        // モデル1体描画前コール
        LAppDelegate::GetInstance()->GetView()->PreModelDraw(*model);

        model->Update();

        // 地面软阴影：先画阴影、后画模型，使阴影作为独立的一层落在模型脚下。
        // 阴影锚在稳定的「地面线」而不是当前包围盒底部，避免角色抬身/跳跃时把阴影一起带上空中；
        // 抬升越高则阴影越大越淡。着色器创建失败会自动跳过。
        if (model->GetModel() != NULL && model->GetModelMatrix() != NULL)
        {
            float minX = 0.0f;
            float maxX = 0.0f;
            float minY = 0.0f;
            if (ComputeModelBounds(model->GetModel(), &minX, &maxX, &minY))
            {
                // 复刻 LAppModel::Draw 的矩阵合成顺序，保证锚点与模型顶点落在同一变换下。
                CubismMatrix44 shadowMatrix;
                shadowMatrix.SetMatrix(projection.GetArray());
                shadowMatrix.MultiplyByMatrix(model->GetModelMatrix());

                const float groundY = UpdateGroundY(model->GetModel(), minY);
                // 角色相对地面的抬升（本地单位，站立时约 0）。
                const float lift = minY - groundY;

                const float centerX = (minX + maxX) * 0.5f;
                const float halfWidth = (maxX - minX) * 0.5f;

                // 阴影取包围盒宽度的 62%，扁率（像素空间高/宽）0.26，落地时中心不透明度 0.34。
                const float ndcCenterX = shadowMatrix.TransformX(centerX);
                const float ndcCenterY = shadowMatrix.TransformY(groundY);
                const float radiusScale = 1.0f + 0.6f * lift;
                const float ndcRadiusX = fabsf(shadowMatrix.GetScaleX()) * halfWidth * 0.62f * radiusScale;
                // NDC 的 X/Y 单位对应的像素数不同，按窗口宽高比换算，保证阴影在像素空间是扁椭圆。
                const float ndcRadiusY = ndcRadiusX * 0.26f
                    * (static_cast<float>(width) / static_cast<float>(height));

                // 抬得越高阴影越淡，避免角色腾空时阴影仍贴脚形成「拖影」。
                const float alpha = 0.34f / (1.0f + 2.5f * lift);

                LAppGroundShadow::GetInstance()->Draw(ndcCenterX, ndcCenterY, ndcRadiusX, ndcRadiusY, alpha);
            }
        }

        model->Draw(projection);///< 参照渡しなのでprojectionは変質する

        // モデル1体描画後コール
        LAppDelegate::GetInstance()->GetView()->PostModelDraw(*model);
    }
}

void LAppLive2DManager::NextScene()
{
    csmInt32 no = (_sceneIndex + 1) % ModelDirSize;
    ChangeScene(no);
}

void LAppLive2DManager::ChangeScene(Csm::csmInt32 index)
{
    _sceneIndex = index;
    if (DebugLogEnable)
    {
        LAppPal::PrintLog("[APP]model index: %d", _sceneIndex);
    }

    // ModelDir[]（内蔵）+ 動的追加（インポート）からモデルディレクトリ名を決定する.
    // ディレクトリ名とmodel3.jsonの名前を一致させておくこと.
    const csmChar* dirName = GetModelDirName(index);
    const csmChar* jsonBasePtr = GetModelJsonBase(index);
    if (dirName == NULL)
    {
        LAppPal::PrintLog("[APP]model index out of range: %d", _sceneIndex);
        ReleaseAllModel();
        return;
    }

    std::string model = dirName;
    std::string modelPath = ResourcesPath + model + "/";

    // 使用 ModelJsonName（如果为 NULL 则使用 ModelDir）
    std::string modelBase;
    if (jsonBasePtr != NULL && jsonBasePtr[0] != '\0') {
        modelBase = jsonBasePtr;
    } else {
        modelBase = model;
    }

    // 优先尝试 .model.json，不存在则尝试 .model3.json
    std::string modelJsonName = modelBase + ".model.json";
    {
        Csm::csmSizeInt size;
        std::string jsonPath = modelPath + modelJsonName;
        Csm::csmByte* buffer = LAppPal::LoadFileAsBytes(jsonPath.c_str(), &size);
        if (buffer == NULL) {
            // 不存在，改用 .model3.json
            modelJsonName = modelBase + ".model3.json";
        } else {
            LAppPal::ReleaseBytes(buffer);
        }
    }

    ReleaseAllModel();
    LAppModel* loadedModel = new LAppModel();
    _models.PushBack(loadedModel);
    loadedModel->LoadAssets(modelPath.c_str(), modelJsonName.c_str());

    // 模型定义/必需资源缺失：移除这个半初始化的模型，避免后续 Update/Draw 解引用空指针
    if (loadedModel->IsLoadFailed() || loadedModel->GetModel() == NULL)
    {
        LAppPal::PrintLog("[APP]model load failed, skip scene: %s%s",
                          modelPath.c_str(), modelJsonName.c_str());
        ReleaseAllModel();
        return;
    }

    /*
     * モデル半透明表示を行うサンプルを提示する。
     * ここでUSE_RENDER_TARGET、USE_MODEL_RENDER_TARGETが定義されている場合
     * 別のレンダリングターゲットにモデルを描画し、描画結果をテクスチャとして別のスプライトに張り付ける。
     */
    {
#if defined(USE_RENDER_TARGET)
        // LAppViewの持つターゲットに描画を行う場合、こちらを選択
        LAppView::SelectTarget useRenderTarget = LAppView::SelectTarget_ViewFrameBuffer;
#elif defined(USE_MODEL_RENDER_TARGET)
        // 各LAppModelの持つターゲットに描画を行う場合、こちらを選択
        LAppView::SelectTarget useRenderTarget = LAppView::SelectTarget_ModelFrameBuffer;
#else
        // デフォルトのメインフレームバッファへレンダリングする(通常)
        LAppView::SelectTarget useRenderTarget = LAppView::SelectTarget_None;
#endif

#if defined(USE_RENDER_TARGET) || defined(USE_MODEL_RENDER_TARGET)
        // モデル個別にαを付けるサンプルとして、もう1体モデルを作成し、少し位置をずらす
        _models.PushBack(new LAppModel());
        _models[1]->LoadAssets(modelPath.c_str(), modelJsonName.c_str());
        _models[1]->GetModelMatrix()->TranslateX(0.2f);
#endif

        LAppDelegate::GetInstance()->GetView()->SwitchRenderingTarget(useRenderTarget);

        // 別レンダリング先を選択した際の背景クリア色
        float clearColor[3] = { 1.0f, 1.0f, 1.0f };
        LAppDelegate::GetInstance()->GetView()->SetRenderTargetClearColor(clearColor[0], clearColor[1], clearColor[2]);
    }
}

csmUint32 LAppLive2DManager::GetModelNum() const
{
    return _models.GetSize();
}

csmInt32 LAppLive2DManager::GetModelDirCount()
{
    return static_cast<csmInt32>(ModelDirSize + g_extraModelDirs.size());
}

const csmChar* LAppLive2DManager::GetModelDirName(csmInt32 index)
{
    if (index < 0) return NULL;
    if (index < ModelDirSize) return ModelDir[index];
    csmUint32 extra = static_cast<csmUint32>(index - ModelDirSize);
    if (extra < g_extraModelDirs.size()) return g_extraModelDirs[extra].c_str();
    return NULL;
}

const csmChar* LAppLive2DManager::GetModelJsonBase(csmInt32 index)
{
    if (index < 0) return NULL;
    if (index < ModelDirSize) return ModelJsonName[index];
    csmUint32 extra = static_cast<csmUint32>(index - ModelDirSize);
    if (extra < g_extraModelDirs.size()) {
        // jsonBase 未指定时与目录同名
        if (g_extraModelJsonBases[extra].empty()) return g_extraModelDirs[extra].c_str();
        return g_extraModelJsonBases[extra].c_str();
    }
    return NULL;
}

void LAppLive2DManager::AddModelDir(const csmChar* dir, const csmChar* jsonBase)
{
    if (dir == NULL || dir[0] == '\0') return;
    std::string d(dir);
    for (size_t i = 0; i < g_extraModelDirs.size(); i++) {
        if (g_extraModelDirs[i] == d) return;  // 去重
    }
    g_extraModelDirs.push_back(d);
    g_extraModelJsonBases.push_back(jsonBase != NULL ? std::string(jsonBase) : "");
    LAppPal::PrintLog("[APP]imported model registered: %s (json=%s)", d.c_str(),
                      g_extraModelJsonBases.back().c_str());
}

void LAppLive2DManager::SetViewMatrix(CubismMatrix44* m)
{
    for (int i = 0; i < 16; i++) {
        _viewMatrix->GetArray()[i] = m->GetArray()[i];
    }
}

