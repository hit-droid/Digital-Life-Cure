/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include <jni.h>
#include <unwind.h>
#include <dlfcn.h>
#include <signal.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <ctime>
#include <unistd.h>
#include <android/log.h>
#include "JniBridgeC.hpp"
#include "LAppDelegate.hpp"
#include "LAppPal.hpp"
#include "LAppLive2DManager.hpp"
#include "LAppDefine.hpp"
#include "LAppModel.hpp"
#include <CubismDefaultParameterId.hpp>
#include <Id/CubismIdManager.hpp>
#include <Model/CubismModel.hpp>

using namespace Csm;

static JavaVM* g_JVM;
static jclass  g_JniBridgeJavaClass;
static jmethodID g_LoadFileMethodId;
static jmethodID g_MoveTaskToBackMethodId;

// ─── Native Crash Handler ───────────────────────────────────────────

static char g_crashPath[512] = {0};

static const char* SIGNAME(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        default:      return "UNKNOWN";
    }
}

struct BacktraceState {
    void** current;
    void** end;
};

static _Unwind_Reason_Code unwindCallback(struct _Unwind_Context* ctx, void* arg) {
    BacktraceState* state = static_cast<BacktraceState*>(arg);
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc) {
        if (state->current == state->end) {
            return _URC_END_OF_STACK;
        }
        *state->current++ = reinterpret_cast<void*>(pc);
    }
    return _URC_NO_REASON;
}

static size_t captureBacktrace(void** buffer, size_t max) {
    BacktraceState state = {buffer, buffer + max};
    _Unwind_Backtrace(unwindCallback, &state);
    return state.current - buffer;
}

static void writeNativeCrash(const char* text) {
    if (g_crashPath[0] == '\0') return;
    FILE* fp = fopen(g_crashPath, "a");
    if (fp) {
        fputs(text, fp);
        fputs("\n---\n\n", fp);
        fclose(fp);
    }
}

static struct sigaction old_sigsegv;
static struct sigaction old_sigabrt;
static struct sigaction old_sigbus;
static struct sigaction old_sigfpe;
static struct sigaction old_sigill;

static void crashHandler(int sig, siginfo_t* info, void* ucontext) {
    char buf[8192] = {0};
    int off = 0;

    time_t now = time(NULL);
    struct tm* t = localtime(&now);
    off += snprintf(buf + off, sizeof(buf) - off,
        "=== Native Crash Report ===\n"
        "Time: %04d-%02d-%02d %02d:%02d:%02d\n"
        "Signal: %s (%d)\n"
        "Fault address: %p\n\n"
        "Backtrace:\n",
        t->tm_year + 1900, t->tm_mon + 1, t->tm_mday,
        t->tm_hour, t->tm_min, t->tm_sec,
        SIGNAME(sig), sig, info->si_addr);

    void* frames[64];
    size_t count = captureBacktrace(frames, 64);

    for (size_t i = 0; i < count; i++) {
        void* addr = frames[i];
        Dl_info dlInfo;
        int resolved = dladdr(addr, &dlInfo);
        if (resolved && dlInfo.dli_fname) {
            const char* fname = dlInfo.dli_fname;
            const char* base = strrchr(fname, '/');
            if (base) fname = base + 1;
            if (dlInfo.dli_sname) {
                off += snprintf(buf + off, sizeof(buf) - off,
                    "  #%02zu  %s (%s+%p) [%p]\n",
                    i, fname, dlInfo.dli_sname, addr, addr);
            } else {
                off += snprintf(buf + off, sizeof(buf) - off,
                    "  #%02zu  %s [%p]\n", i, fname, addr);
            }
        } else {
            off += snprintf(buf + off, sizeof(buf) - off,
                "  #%02zu  [%p]\n", i, addr);
        }
    }

    __android_log_print(ANDROID_LOG_FATAL, "NATIVE_CRASH", "%s", buf);
    writeNativeCrash(buf);

    // Re-raise original signal with old handler
    struct sigaction* old = NULL;
    switch (sig) {
        case SIGSEGV: old = &old_sigsegv; break;
        case SIGABRT: old = &old_sigabrt; break;
        case SIGBUS:  old = &old_sigbus;  break;
        case SIGFPE:  old = &old_sigfpe;  break;
        case SIGILL:  old = &old_sigill;  break;
    }
    if (old && old->sa_handler != SIG_DFL && old->sa_handler != SIG_IGN) {
        if (old->sa_flags & SA_SIGINFO) {
            old->sa_sigaction(sig, info, ucontext);
        } else {
            old->sa_handler(sig);
        }
    }
    _exit(1);
}

static void installNativeCrashHandler() {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crashHandler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;

    sigaction(SIGSEGV, &sa, &old_sigsegv);
    sigaction(SIGABRT, &sa, &old_sigabrt);
    sigaction(SIGBUS,  &sa, &old_sigbus);
    sigaction(SIGFPE,  &sa, &old_sigfpe);
    sigaction(SIGILL,  &sa, &old_sigill);

    __android_log_print(ANDROID_LOG_INFO, "NATIVE_CRASH", "Native crash handler installed");
}

// ─── JNI ────────────────────────────────────────────────────────────

JNIEnv* GetEnv()
{
    JNIEnv* env = NULL;
    g_JVM->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    return env;
}

// The VM calls JNI_OnLoad when the native library is loaded
jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
    g_JVM = vm;

    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK)
    {
        return JNI_ERR;
    }

    jclass clazz = env->FindClass("com/maiden/pet/render/Live2DNative");
    g_JniBridgeJavaClass = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
    g_LoadFileMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass, "loadFile", "(Ljava/lang/String;)[B");
    g_MoveTaskToBackMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass, "moveTaskToBack", "()V");

    return JNI_VERSION_1_6;
}

void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved)
{
    JNIEnv *env = GetEnv();
    env->DeleteGlobalRef(g_JniBridgeJavaClass);
}

char* JniBridgeC::LoadFileAsBytesFromJava(const char* filePath, unsigned int* outSize)
{
    JNIEnv *env = GetEnv();

    // ファイルロード
    jbyteArray obj = (jbyteArray)env->CallStaticObjectMethod(g_JniBridgeJavaClass, g_LoadFileMethodId, env->NewStringUTF(filePath));
    if (obj == NULL) {
        *outSize = 0;
        return NULL;
    }
    *outSize = static_cast<unsigned int>(env->GetArrayLength(obj));

    char* buffer = new char[*outSize];
    env->GetByteArrayRegion(obj, 0, *outSize, reinterpret_cast<jbyte *>(buffer));

    return buffer;
}

void JniBridgeC::MoveTaskToBack()
{
    JNIEnv *env = GetEnv();

    // アプリ終了
    env->CallStaticVoidMethod(g_JniBridgeJavaClass, g_MoveTaskToBackMethodId, NULL);
}

extern "C"
{
    JNIEXPORT void JNICALL
    Java_com_maiden_pet_CrashHandler_nativeInit(JNIEnv *env, jclass type, jstring crashPath)
    {
        const char* path = env->GetStringUTFChars(crashPath, NULL);
        strncpy(g_crashPath, path, sizeof(g_crashPath) - 1);
        g_crashPath[sizeof(g_crashPath) - 1] = '\0';
        env->ReleaseStringUTFChars(crashPath, path);
        installNativeCrashHandler();
    }

    JNIEXPORT void JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeOnStart(JNIEnv *env, jclass type)
    {
        LAppDelegate::GetInstance()->OnStart();
    }

    JNIEXPORT void JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeOnPause(JNIEnv *env, jclass type)
    {
        LAppDelegate::GetInstance()->OnPause();
    }

    JNIEXPORT void JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeOnStop(JNIEnv *env, jclass type)
    {
        LAppDelegate::GetInstance()->OnStop();
    }

    JNIEXPORT void JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeOnDestroy(JNIEnv *env, jclass type)
    {
        LAppDelegate::GetInstance()->OnDestroy();
    }

    JNIEXPORT void JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeOnSurfaceCreated(JNIEnv *env, jclass type)
    {
        LAppDelegate::GetInstance()->OnSurfaceCreate();
    }

    JNIEXPORT void JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeOnSurfaceChanged(JNIEnv *env, jclass type, jint width, jint height)
    {
        LAppDelegate::GetInstance()->OnSurfaceChanged(width, height);
    }

    JNIEXPORT void JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeOnDrawFrame(JNIEnv *env, jclass type)
    {
        LAppDelegate::GetInstance()->Run();
    }

    JNIEXPORT void JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeOnTouchesBegan(JNIEnv *env, jclass type, jfloat pointX, jfloat pointY)
    {
        LAppDelegate::GetInstance()->OnTouchBegan(pointX, pointY);
    }

    JNIEXPORT void JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeOnTouchesEnded(JNIEnv *env, jclass type, jfloat pointX, jfloat pointY)
    {
        LAppDelegate::GetInstance()->OnTouchEnded(pointX, pointY);
    }

    JNIEXPORT void JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeOnTouchesMoved(JNIEnv *env, jclass type, jfloat pointX, jfloat pointY)
    {
        LAppDelegate::GetInstance()->OnTouchMoved(pointX, pointY);
    }

    JNIEXPORT void JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeSetExpression(JNIEnv *env, jclass type, jstring expressionId)
    {
        const char* expr = env->GetStringUTFChars(expressionId, NULL);
        LAppModel* model = LAppLive2DManager::GetInstance()->GetModel(0);
        if (model) model->SetExpression(expr);
        env->ReleaseStringUTFChars(expressionId, expr);
    }

    JNIEXPORT void JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeStartMotion(JNIEnv *env, jclass type, jstring groupName, jint index, jint priority)
    {
        const char* group = env->GetStringUTFChars(groupName, NULL);
        LAppModel* model = LAppLive2DManager::GetInstance()->GetModel(0);
        if (model) model->StartMotion(group, index, priority);
        env->ReleaseStringUTFChars(groupName, group);
    }

    JNIEXPORT void JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeSetMouth(JNIEnv *env, jclass type, jfloat open)
    {
        using namespace Live2D::Cubism::Framework::DefaultParameterId;
        LAppModel* model = LAppLive2DManager::GetInstance()->GetModel(0);
        if (model) {
            if (open <= 0.0f) {
                // 闭嘴时移除外部控制，让表情/动作接管嘴型
                model->RemoveExternalParameterValue(ParamMouthOpenY);
            } else {
                model->SetExternalParameterValue(ParamMouthOpenY, open);
            }
        }
    }

    JNIEXPORT void JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeSetParameterValue(JNIEnv *env, jclass type, jstring paramId, jfloat value)
    {
        const char* pid = env->GetStringUTFChars(paramId, NULL);
        LAppModel* model = LAppLive2DManager::GetInstance()->GetModel(0);
        if (model) {
            model->SetExternalParameterValue(pid, value);
        }
        env->ReleaseStringUTFChars(paramId, pid);
    }

    JNIEXPORT jboolean JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeIsMotionPlaying(JNIEnv *env, jclass type)
    {
        LAppModel* model = LAppLive2DManager::GetInstance()->GetModel(0);
        if (model && model->IsExternalActionPlaying()) {
            return JNI_TRUE;
        }
        return JNI_FALSE;
    }

    JNIEXPORT void JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeChangeScene(JNIEnv *env, jclass type, jint index)
    {
        LAppLive2DManager::GetInstance()->ChangeScene(index);
    }

    JNIEXPORT jint JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeGetModelCount(JNIEnv *env, jclass type)
    {
        return LAppDefine::ModelDirSize;
    }

    JNIEXPORT jstring JNICALL
    Java_com_maiden_pet_render_Live2DNative_nativeGetModelDirName(JNIEnv *env, jclass type, jint index)
    {
        if (index < 0 || index >= LAppDefine::ModelDirSize) {
            return env->NewStringUTF("");
        }
        return env->NewStringUTF(LAppDefine::ModelDir[index]);
    }
}

