/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include "LAppModel.hpp"
#include <fstream>
#include <vector>
#include <CubismModelSettingJson.hpp>
#include <Motion/CubismMotion.hpp>
#include <Physics/CubismPhysics.hpp>
#include <CubismDefaultParameterId.hpp>
#include <Rendering/OpenGL/CubismRenderer_OpenGLES2.hpp>
#include <Utils/CubismString.hpp>
#include <Id/CubismIdManager.hpp>
#include <Motion/CubismMotionQueueEntry.hpp>
#include "LAppDefine.hpp"
#include "LAppPal.hpp"
#include "LAppTextureManager.hpp"
#include "LAppDelegate.hpp"

using namespace Live2D::Cubism::Framework;
using namespace Live2D::Cubism::Framework::DefaultParameterId;
using namespace LAppDefine;

namespace {
    csmByte* CreateBuffer(const csmChar* path, csmSizeInt* size)
    {
        if (DebugLogEnable)
        {
            LAppPal::PrintLog("[APP]create buffer: %s ", path);
        }
        return LAppPal::LoadFileAsBytes(path, size);
    }

    void DeleteBuffer(csmByte* buffer, const csmChar* path = "")
    {
        if (DebugLogEnable)
        {
            LAppPal::PrintLog("[APP]delete buffer: %s", path);
        }
        LAppPal::ReleaseBytes(buffer);
    }

    /**
     * @brief AI が待機中に直接制御する運動系パラメータか判定する。
     * 非待機アクション（タップ等）再生中はこれらの外部パラメータを適用しない。
     * 口（MouthOpenY）は会話用、呼吸（ParamBreath）は背景として常時適用する。
     */
    bool IsAiControlledMotionParameter(const csmChar* pid)
    {
        using namespace Live2D::Cubism::Framework::DefaultParameterId;
        return strcmp(pid, ParamAngleX) == 0
            || strcmp(pid, ParamAngleY) == 0
            || strcmp(pid, ParamAngleZ) == 0
            || strcmp(pid, ParamBodyAngleX) == 0
            || strcmp(pid, ParamBodyAngleZ) == 0
            || strcmp(pid, ParamEyeBallX) == 0
            || strcmp(pid, ParamEyeBallY) == 0
            || strcmp(pid, ParamEyeLOpen) == 0
            || strcmp(pid, ParamEyeROpen) == 0;
    }
}

LAppModel::LAppModel()
    : CubismUserModel()
    , _modelSetting(NULL)
    , _userTimeSeconds(0.0f)
    , _loadFailed(false)
{
    if (DebugLogEnable)
    {
        _debugMode = true;
    }

    _idParamAngleX = CubismFramework::GetIdManager()->GetId(ParamAngleX);
    _idParamAngleY = CubismFramework::GetIdManager()->GetId(ParamAngleY);
    _idParamAngleZ = CubismFramework::GetIdManager()->GetId(ParamAngleZ);
    _idParamBodyAngleX = CubismFramework::GetIdManager()->GetId(ParamBodyAngleX);
    _idParamEyeBallX = CubismFramework::GetIdManager()->GetId(ParamEyeBallX);
    _idParamEyeBallY = CubismFramework::GetIdManager()->GetId(ParamEyeBallY);
}

LAppModel::~LAppModel()
{
    _renderBuffer.DestroyRenderTarget();

    ReleaseMotions();
    ReleaseExpressions();

    // _modelSetting 可能为 NULL（模型文件缺失时 SetupModel 提前返回），必须判空
    if (_modelSetting != NULL)
    {
        for (csmInt32 i = 0; i < _modelSetting->GetMotionGroupCount(); i++)
        {
            const csmChar* group = _modelSetting->GetMotionGroupName(i);
            ReleaseMotionGroup(group);
        }
        delete _modelSetting;
        _modelSetting = NULL;
    }
}

void LAppModel::LoadAssets(const csmChar* dir, const csmChar* fileName)
{
    _modelHomeDir = dir;

    if (_debugMode)
    {
        LAppPal::PrintLog("[APP]load model setting: %s", fileName);
    }

    csmSizeInt size;
    const csmString path = csmString(dir) + fileName;

    csmByte* buffer = CreateBuffer(path.GetRawString(), &size);
    // 模型定义文件缺失时必须提前返回：buffer 为 NULL 时解析会空指针解引用（SIGSEGV at 0x0）
    if (buffer == NULL || size <= 0)
    {
        LAppPal::PrintLog("[APP]model setting missing, skip model: %s", path.GetRawString());
        _loadFailed = true;
        DeleteBuffer(buffer, path.GetRawString());
        return;
    }
    ICubismModelSetting* setting = new CubismModelSettingJson(buffer, size);
    DeleteBuffer(buffer, path.GetRawString());

    if (setting == NULL)
    {
        LAppPal::PrintLog("[APP]failed to parse model setting: %s", path.GetRawString());
        _loadFailed = true;
        return;
    }

    SetupModel(setting);

    if (_model == NULL || _modelSetting == NULL)
    {
        LAppPal::PrintLog("[APP]Failed to LoadAssets(): %s", path.GetRawString());
        _loadFailed = true;
        if (_modelSetting == NULL)
        {
            delete setting;
        }
        return;
    }

    CreateRenderer(LAppDelegate::GetInstance()->GetWindowWidth(), LAppDelegate::GetInstance()->GetWindowHeight());

    SetupTextures();
    LAppPal::PrintLog("[APP]model ready: %s (textures=%d)", fileName, _modelSetting->GetTextureCount());
}


void LAppModel::SetupModel(ICubismModelSetting* setting)
{
    _updating = true;
    _initialized = false;

    _modelSetting = setting;

    csmByte* buffer;
    csmSizeInt size;

    //Cubism Model
    if (strcmp(_modelSetting->GetModelFileName(), "") != 0)
    {
        csmString path = _modelSetting->GetModelFileName();
        path = _modelHomeDir + path;

        if (_debugMode)
        {
            LAppPal::PrintLog("[APP]create model: %s", setting->GetModelFileName());
        }

        buffer = CreateBuffer(path.GetRawString(), &size);
        if (buffer == NULL || size <= 0)
        {
            LAppPal::PrintLog("[APP]model moc missing: %s", path.GetRawString());
            delete _modelSetting;
            _modelSetting = NULL;
            _updating = false;
            return;
        }
        LoadModel(buffer, size);
        DeleteBuffer(buffer, path.GetRawString());

        if (_model == NULL)
        {
            LAppPal::PrintLog("[APP]failed to create model: %s", setting->GetModelFileName());
            return;
        }
    }

    //Expression
    if (_modelSetting->GetExpressionCount() > 0)
    {
        const csmInt32 count = _modelSetting->GetExpressionCount();
        for (csmInt32 i = 0; i < count; i++)
        {
            csmString name = _modelSetting->GetExpressionName(i);
            csmString path = _modelSetting->GetExpressionFileName(i);
            path = _modelHomeDir + path;

            buffer = CreateBuffer(path.GetRawString(), &size);
            ACubismMotion* motion = NULL;
            if (buffer != NULL && size > 0)
            {
                motion = LoadExpression(buffer, size, name.GetRawString());
            }
            else
            {
                LAppPal::PrintLog("[APP]expression missing: %s", path.GetRawString());
            }

            if (_expressions[name] != NULL)
            {
                ACubismMotion::Delete(_expressions[name]);
                _expressions[name] = NULL;
            }
            _expressions[name] = motion;

            DeleteBuffer(buffer, path.GetRawString());
        }
    }

    //Physics
    if (strcmp(_modelSetting->GetPhysicsFileName(), "") != 0)
    {
        csmString path = _modelSetting->GetPhysicsFileName();
        path = _modelHomeDir + path;

        buffer = CreateBuffer(path.GetRawString(), &size);
        if (buffer != NULL && size > 0)
        {
            LoadPhysics(buffer, size);
        }
        else
        {
            LAppPal::PrintLog("[APP]physics missing: %s", path.GetRawString());
        }
        DeleteBuffer(buffer, path.GetRawString());
    }

    //Pose
    if (strcmp(_modelSetting->GetPoseFileName(), "") != 0)
    {
        csmString path = _modelSetting->GetPoseFileName();
        path = _modelHomeDir + path;

        buffer = CreateBuffer(path.GetRawString(), &size);
        if (buffer != NULL && size > 0)
        {
            LoadPose(buffer, size);
        }
        else
        {
            LAppPal::PrintLog("[APP]pose missing: %s", path.GetRawString());
        }
        DeleteBuffer(buffer, path.GetRawString());
    }

    //EyeBlink
    // AI が外部から直接制御するため、ネイティブの自動まばたきを無効化する
    // if (_modelSetting->GetEyeBlinkParameterCount() > 0)
    // {
    //     _eyeBlink = CubismEyeBlink::Create(_modelSetting);
    // }

    //Breath
    // AI が外部から直接制御するため、ネイティブの自動呼吸を無効化する
    // {
    //     _breath = CubismBreath::Create();
    //
    //     csmVector<CubismBreath::BreathParameterData> breathParameters;
    //
    //     breathParameters.PushBack(CubismBreath::BreathParameterData(_idParamAngleX, 0.0f, 15.0f, 6.5345f, 0.5f));
    //     breathParameters.PushBack(CubismBreath::BreathParameterData(_idParamAngleY, 0.0f, 8.0f, 3.5345f, 0.5f));
    //     breathParameters.PushBack(CubismBreath::BreathParameterData(_idParamAngleZ, 0.0f, 10.0f, 5.5345f, 0.5f));
    //     breathParameters.PushBack(CubismBreath::BreathParameterData(_idParamBodyAngleX, 0.0f, 4.0f, 15.5345f, 0.5f));
    //     breathParameters.PushBack(CubismBreath::BreathParameterData(CubismFramework::GetIdManager()->GetId(ParamBreath), 0.5f, 0.5f, 3.2345f, 0.5f));
    //
    //     _breath->SetParameters(breathParameters);
    // }

    //UserData
    if (strcmp(_modelSetting->GetUserDataFile(), "") != 0)
    {
        csmString path = _modelSetting->GetUserDataFile();
        path = _modelHomeDir + path;
        buffer = CreateBuffer(path.GetRawString(), &size);
        if (buffer != NULL && size > 0)
        {
            LoadUserData(buffer, size);
        }
        else
        {
            LAppPal::PrintLog("[APP]userdata missing: %s", path.GetRawString());
        }
        DeleteBuffer(buffer, path.GetRawString());
    }

    // EyeBlinkIds
    {
        csmInt32 eyeBlinkIdCount = _modelSetting->GetEyeBlinkParameterCount();
        for (csmInt32 i = 0; i < eyeBlinkIdCount; ++i)
        {
            _eyeBlinkIds.PushBack(_modelSetting->GetEyeBlinkParameterId(i));
        }
    }

    // LipSyncIds
    {
        csmInt32 lipSyncIdCount = _modelSetting->GetLipSyncParameterCount();
        for (csmInt32 i = 0; i < lipSyncIdCount; ++i)
        {
            _lipSyncIds.PushBack(_modelSetting->GetLipSyncParameterId(i));
        }
    }

    //Layout
    csmMap<csmString, csmFloat32> layout;
    _modelSetting->GetLayoutMap(layout);
    _modelMatrix->SetupFromLayout(layout);

    _model->SaveParameters();

    for (csmInt32 i = 0; i < _modelSetting->GetMotionGroupCount(); i++)
    {
        const csmChar* group = _modelSetting->GetMotionGroupName(i);
        PreloadMotionGroup(group);
    }

    _motionManager->StopAllMotions();

    _updating = false;
    _initialized = true;
}

void LAppModel::PreloadMotionGroup(const csmChar* group)
{
    const csmInt32 count = _modelSetting->GetMotionCount(group);

    for (csmInt32 i = 0; i < count; i++)
    {
        //ex) idle_0
        csmString name = Utils::CubismString::GetFormatedString("%s_%d", group, i);
        csmString path = _modelSetting->GetMotionFileName(group, i);
        path = _modelHomeDir + path;

        if (_debugMode)
        {
            LAppPal::PrintLog("[APP]load motion: %s => [%s_%d] ", path.GetRawString(), group, i);
        }

        csmByte* buffer;
        csmSizeInt size;
        buffer = CreateBuffer(path.GetRawString(), &size);
        CubismMotion* tmpMotion = NULL;
        if (buffer != NULL && size > 0)
        {
            tmpMotion = static_cast<CubismMotion*>(LoadMotion(buffer, size, name.GetRawString()));
        }
        else
        {
            LAppPal::PrintLog("[APP]motion missing: %s", path.GetRawString());
        }
        DeleteBuffer(buffer, path.GetRawString());

        if (tmpMotion == NULL)
        {
            continue;
        }

        csmFloat32 fadeTime = _modelSetting->GetMotionFadeInTimeValue(group, i);
        if (fadeTime >= 0.0f)
        {
            tmpMotion->SetFadeInTime(fadeTime);
        }

        fadeTime = _modelSetting->GetMotionFadeOutTimeValue(group, i);
        if (fadeTime >= 0.0f)
        {
            tmpMotion->SetFadeOutTime(fadeTime);
        }
        tmpMotion->SetEffectIds(_eyeBlinkIds, _lipSyncIds);

        if (_motions[name] != NULL)
        {
            ACubismMotion::Delete(_motions[name]);
        }
        _motions[name] = tmpMotion;
    }
}

void LAppModel::ReleaseMotionGroup(const csmChar* group) const
{
    const csmInt32 count = _modelSetting->GetMotionCount(group);
    for (csmInt32 i = 0; i < count; i++)
    {
        csmString voice = _modelSetting->GetMotionSoundFileName(group, i);
        if (strcmp(voice.GetRawString(), "") != 0)
        {
            csmString path = voice;
            path = _modelHomeDir + path;
        }
    }
}

/**
* @brief すべてのモーションデータの解放
*
* すべてのモーションデータを解放する。
*/
void LAppModel::ReleaseMotions()
{
    for (csmMap<csmString, ACubismMotion*>::const_iterator iter = _motions.Begin(); iter != _motions.End(); ++iter)
    {
        ACubismMotion::Delete(iter->Second);
    }

    _motions.Clear();
}

/**
* @brief すべての表情データの解放
*
* すべての表情データを解放する。
*/
void LAppModel::ReleaseExpressions()
{
    for (csmMap<csmString, ACubismMotion*>::const_iterator iter = _expressions.Begin(); iter != _expressions.End(); ++iter)
    {
        ACubismMotion::Delete(iter->Second);
    }

    _expressions.Clear();
}

void LAppModel::Update()
{
    const csmFloat32 deltaTimeSeconds = LAppPal::GetDeltaTime();
    _userTimeSeconds += deltaTimeSeconds;

    _dragManager->Update(deltaTimeSeconds);
    const csmFloat32 dragX = _dragManager->GetX();
    const csmFloat32 dragY = _dragManager->GetY();

    // モーションによるパラメータ更新の有無
    csmBool motionUpdated = false;

    //-----------------------------------------------------------------
    _model->LoadParameters(); // 前回セーブされた状態をロード
    if (_motionManager->IsFinished())
    {
        // モーションの再生がない場合、待機モーションの中からランダムで再生する
        StartRandomMotion(MotionGroupIdle, PriorityIdle);
    }
    else
    {
        motionUpdated = _motionManager->UpdateMotion(_model, deltaTimeSeconds); // モーションを更新
    }
    // 非待機アクション（タップ等）が再生中か。AI 外部パラメータは再生中に待機微動を抑制する
    _externalActionPlaying.store(
        _currentMotionGroup.GetLength() > 0
        && strcmp(_currentMotionGroup.GetRawString(), MotionGroupIdle) != 0
        && !_motionManager->IsFinished());
    _model->SaveParameters(); // 状態を保存
    //-----------------------------------------------------------------

    // まばたき
    if (!motionUpdated)
    {
        if (_eyeBlink != NULL)
        {
            // メインモーションの更新がないとき
            _eyeBlink->UpdateParameters(_model, deltaTimeSeconds); // 目パチ
        }
    }

    if (_expressionManager != NULL)
    {
        _expressionManager->UpdateMotion(_model, deltaTimeSeconds); // 表情でパラメータ更新（相対変化）
    }

    //ドラッグによる変化
    //ドラッグによる顔の向きの調整
    _model->AddParameterValue(_idParamAngleX, dragX * 30); // -30から30の値を加える
    _model->AddParameterValue(_idParamAngleY, dragY * 30);
    _model->AddParameterValue(_idParamAngleZ, dragX * dragY * -30);

    //ドラッグによる体の向きの調整
    _model->AddParameterValue(_idParamBodyAngleX, dragX * 10); // -10から10の値を加える

    //ドラッグによる目の向きの調整
    _model->AddParameterValue(_idParamEyeBallX, dragX); // -1から1の値を加える
    _model->AddParameterValue(_idParamEyeBallY, dragY);

    // 呼吸など
    if (_breath != NULL)
    {
        _breath->UpdateParameters(_model, deltaTimeSeconds);
    }

    // 物理演算の設定
    if (_physics != NULL)
    {
        _physics->Evaluate(_model, deltaTimeSeconds);
    }

    // リップシンクの設定
    if (_lipSyncIds.GetSize() > 0)
    {
        csmFloat32 value = 0; // リアルタイムでリップシンクを行う場合、システムから音量を取得して0〜1の範囲で値を入力します。

        for (csmUint32 i = 0; i < _lipSyncIds.GetSize(); ++i)
        {
            _model->AddParameterValue(_lipSyncIds[i], value, 0.8f);
        }
    }

    // ポーズの設定
    if (_pose != NULL)
    {
        _pose->UpdateParameters(_model, deltaTimeSeconds);
    }

    _model->Update();

    // AI外部パラメータの適用（モーション/表情/物理の後に強制設定）
    // これによりAIが直接制御するパラメータが毎フレーム確実に反映される
    const bool actionPlaying = IsExternalActionPlaying();
    for (Csm::csmMap<Csm::csmString, Csm::csmFloat32>::const_iterator it = _externalParams.Begin();
         it != _externalParams.End(); ++it)
    {
        // 非待機アクション再生中は AI の待機微動パラメータを適用しない
        // （モーションのアニメーションを上書きしないため。口は会話用に適用継続）
        if (actionPlaying && IsAiControlledMotionParameter(it->First.GetRawString()))
        {
            continue;
        }
        Csm::CubismIdHandle id = Csm::CubismFramework::GetIdManager()->GetId(it->First.GetRawString());
        _model->SetParameterValue(id, it->Second);
    }

}

void LAppModel::SetExternalParameterValue(const Csm::csmChar* paramId, Csm::csmFloat32 value)
{
    _externalParams[paramId] = value;
}

void LAppModel::RemoveExternalParameterValue(const Csm::csmChar* paramId)
{
    for (Csm::csmMap<Csm::csmString, Csm::csmFloat32>::const_iterator it = _externalParams.Begin();
         it != _externalParams.End(); ++it)
    {
        if (strcmp(it->First.GetRawString(), paramId) == 0)
        {
            _externalParams.Erase(it);
            return;
        }
    }
}

void LAppModel::ClearExternalParameters()
{
    _externalParams.Clear();
}

CubismMotionQueueEntryHandle LAppModel::StartMotion(const csmChar* group, csmInt32 no, csmInt32 priority, ACubismMotion::FinishedMotionCallback onFinishedMotionHandler)
{
    if (priority == PriorityForce)
    {
        _motionManager->SetReservePriority(priority);
    }
    else if (!_motionManager->ReserveMotion(priority))
    {
        if (_debugMode)
        {
            LAppPal::PrintLog("[APP]can't start motion.");
        }
        return InvalidMotionQueueEntryHandleValue;
    }

    const csmString fileName = _modelSetting->GetMotionFileName(group, no);

    //ex) idle_0
    csmString name = Utils::CubismString::GetFormatedString("%s_%d", group, no);
    CubismMotion* motion = static_cast<CubismMotion*>(_motions[name.GetRawString()]);
    csmBool autoDelete = false;

    if (motion == NULL)
    {
        csmString path = fileName;
        path = _modelHomeDir + path;

        csmByte* buffer;
        csmSizeInt size;
        buffer = CreateBuffer(path.GetRawString(), &size);
        if (buffer == NULL || size <= 0)
        {
            LAppPal::PrintLog("[APP]motion missing on demand: %s", path.GetRawString());
            DeleteBuffer(buffer, path.GetRawString());
            return InvalidMotionQueueEntryHandleValue;
        }
        motion = static_cast<CubismMotion*>(LoadMotion(buffer, size, NULL, onFinishedMotionHandler));
        DeleteBuffer(buffer, path.GetRawString());
        if (motion == NULL)
        {
            LAppPal::PrintLog("[APP]failed to load motion: %s", path.GetRawString());
            return InvalidMotionQueueEntryHandleValue;
        }
        csmFloat32 fadeTime = _modelSetting->GetMotionFadeInTimeValue(group, no);
        if (fadeTime >= 0.0f)
        {
            motion->SetFadeInTime(fadeTime);
        }

        fadeTime = _modelSetting->GetMotionFadeOutTimeValue(group, no);
        if (fadeTime >= 0.0f)
        {
            motion->SetFadeOutTime(fadeTime);
        }
        motion->SetEffectIds(_eyeBlinkIds, _lipSyncIds);
        autoDelete = true; // 終了時にメモリから削除
    }
    else
    {
        motion->SetFinishedMotionHandler(onFinishedMotionHandler);
    }

    //voice
    csmString voice = _modelSetting->GetMotionSoundFileName(group, no);
    if (strcmp(voice.GetRawString(), "") != 0)
    {
        csmString path = voice;
        path = _modelHomeDir + path;
    }

    if (_debugMode)
    {
        LAppPal::PrintLog("[APP]start motion: [%s_%d]", group, no);
    }
    _currentMotionGroup = Csm::csmString(group);
    return  _motionManager->StartMotionPriority(motion, autoDelete, priority);
}

CubismMotionQueueEntryHandle LAppModel::StartRandomMotion(const csmChar* group, csmInt32 priority, ACubismMotion::FinishedMotionCallback onFinishedMotionHandler)
{
    if (_modelSetting->GetMotionCount(group) == 0)
    {
        return InvalidMotionQueueEntryHandleValue;
    }

    csmInt32 no = rand() % _modelSetting->GetMotionCount(group);

    return StartMotion(group, no, priority, onFinishedMotionHandler);
}

void LAppModel::DoDraw()
{
    if (_model == NULL)
    {
        return;
    }

    static csmUint32 s_drawFrameCount = 0;
    if ((s_drawFrameCount % 90) == 0)
    {
        LAppPal::PrintLog("[APP]drawing model (frame=%u)", s_drawFrameCount);
    }
    s_drawFrameCount++;

    GetRenderer<Rendering::CubismRenderer_OpenGLES2>()->DrawModel();
}

void LAppModel::Draw(CubismMatrix44& matrix)
{
    if (_model == NULL)
    {
        return;
    }

    matrix.MultiplyByMatrix(_modelMatrix);

    GetRenderer<Rendering::CubismRenderer_OpenGLES2>()->SetMvpMatrix(&matrix);

    DoDraw();
}

csmBool LAppModel::HitTest(const csmChar* hitAreaName, csmFloat32 x, csmFloat32 y)
{
    // 透明時は当たり判定なし。
    if (_opacity < 1)
    {
        return false;
    }
    const csmInt32 count = _modelSetting->GetHitAreasCount();
    for (csmInt32 i = 0; i < count; i++)
    {
        if (strcmp(_modelSetting->GetHitAreaName(i), hitAreaName) == 0)
        {
            const CubismIdHandle drawID = _modelSetting->GetHitAreaId(i);
            return IsHit(drawID, x, y);
        }
    }
    return false; // 存在しない場合はfalse
}

void LAppModel::SetExpression(const csmChar* expressionID)
{
    ACubismMotion* motion = _expressions[expressionID];
    if (_debugMode)
    {
        LAppPal::PrintLog("[APP]expression: [%s]", expressionID);
    }

    if (motion != NULL)
    {
        _expressionManager->StartMotion(motion, false);
    }
    else
    {
        if (_debugMode) LAppPal::PrintLog("[APP]expression[%s] is null ", expressionID);
    }
}

void LAppModel::SetRandomExpression()
{
    if (_expressions.GetSize() == 0)
    {
        return;
    }

    csmInt32 no = rand() % _expressions.GetSize();
    csmMap<csmString, ACubismMotion*>::const_iterator map_ite;
    csmInt32 i = 0;
    for (map_ite = _expressions.Begin(); map_ite != _expressions.End(); map_ite++)
    {
        if (i == no)
        {
            csmString name = (*map_ite).First;
            SetExpression(name.GetRawString());
            return;
        }
        i++;
    }
}

void LAppModel::ReloadRenderer()
{
    DeleteRenderer();

    CreateRenderer(LAppDelegate::GetInstance()->GetWindowWidth(), LAppDelegate::GetInstance()->GetWindowHeight());

    SetupTextures();
}

void LAppModel::SetupTextures()
{
    if (_modelSetting == NULL)
    {
        return;
    }

    Rendering::CubismRenderer_OpenGLES2* renderer =
        GetRenderer<Rendering::CubismRenderer_OpenGLES2>();
    if (renderer == NULL)
    {
        LAppPal::PrintLog("[APP]renderer not ready, skip textures");
        return;
    }

    for (csmInt32 modelTextureNumber = 0; modelTextureNumber < _modelSetting->GetTextureCount(); modelTextureNumber++)
    {
        // テクスチャ名が空文字だった場合はロード・バインド処理をスキップ
        if (strcmp(_modelSetting->GetTextureFileName(modelTextureNumber), "") == 0)
        {
            continue;
        }

        //OpenGLのテクスチャユニットにテクスチャをロードする
        csmString texturePath = _modelSetting->GetTextureFileName(modelTextureNumber);
        texturePath = _modelHomeDir + texturePath;

        LAppTextureManager::TextureInfo* texture = LAppDelegate::GetInstance()->GetTextureManager()->CreateTextureFromPngFile(texturePath.GetRawString());
        if (texture == NULL)
        {
            LAppPal::PrintLog("[APP]failed to bind model texture: %s", texturePath.GetRawString());
            continue;
        }
        const csmInt32 glTextueNumber = texture->id;

        //OpenGL
        renderer->BindTexture(modelTextureNumber, glTextueNumber);
    }

#ifdef PREMULTIPLIED_ALPHA_ENABLE
    renderer->IsPremultipliedAlpha(true);
#else
    renderer->IsPremultipliedAlpha(false);
#endif
}

void LAppModel::MotionEventFired(const csmString& eventValue)
{
    CubismLogInfo("%s is fired on LAppModel!!", eventValue.GetRawString());
}

Csm::Rendering::CubismRenderTarget_OpenGLES2& LAppModel::GetRenderBuffer()
{
    return _renderBuffer;
}
