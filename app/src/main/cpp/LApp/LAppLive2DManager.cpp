/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include "LAppLive2DManager.hpp"
#include <string>
#include <vector>
#include <GLES2/gl2.h>
#include <Rendering/CubismRenderer.hpp>
#include "LAppPal.hpp"
#include "LAppDefine.hpp"
#include "LAppDelegate.hpp"
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

