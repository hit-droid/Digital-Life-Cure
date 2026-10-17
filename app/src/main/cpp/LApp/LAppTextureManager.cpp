/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include "LAppTextureManager.hpp"
#include <iostream>
#include <string.h>
#define STBI_NO_STDIO
#define STBI_ONLY_PNG
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"
#include "LAppPal.hpp"

namespace {
    // ES2 只在 2 的幂纹理上支持 mipmap：用位运算判定，避免为 NPOT 生成 mip 触发 GL 错误。
    bool IsPowerOfTwo(int v)
    {
        return v > 0 && (v & (v - 1)) == 0;
    }

    // 部分 GPU 通过下列扩展允许对 NPOT 纹理生成 mipmap；判定只依赖当前 GL 上下文，
    // 首次查询后缓存（全部调用都发生在 GL 线程上，无并发）。
    bool SupportsNpotMipmap()
    {
        static int cached = -1;

        if (cached < 0)
        {
            const char* extensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
            cached = (extensions != NULL &&
                      (strstr(extensions, "GL_OES_texture_npot") != NULL ||
                       strstr(extensions, "GL_IMG_texture_npot") != NULL ||
                       strstr(extensions, "GL_NV_texture_npot_2D_mipmap") != NULL)) ? 1 : 0;
        }

        return cached != 0;
    }
}

LAppTextureManager::LAppTextureManager()
{
}

LAppTextureManager::~LAppTextureManager()
{
    ReleaseTextures();
}

LAppTextureManager::TextureInfo* LAppTextureManager::CreateTextureFromPngFile(std::string fileName)
{
    //search loaded texture already.
    for (Csm::csmUint32 i = 0; i < _textures.GetSize(); i++)
    {
        if (_textures[i]->fileName == fileName)
        {
            return _textures[i];
        }
    }

    GLuint textureId = 0;
    int width, height, channels;
    unsigned int size;
    unsigned char* png;
    unsigned char* address;

    address = LAppPal::LoadFileAsBytes(fileName, &size);
    if (address == NULL || size == 0)
    {
        LAppPal::PrintLog("[APP]texture asset load failed: %s", fileName.c_str());
        return NULL;
    }

    // png情報を取得する
    png = stbi_load_from_memory(
        address,
        static_cast<int>(size),
        &width,
        &height,
        &channels,
        STBI_rgb_alpha);
    if (png == NULL)
    {
        LAppPal::PrintLog("[APP]texture decode failed: %s", fileName.c_str());
        LAppPal::ReleaseBytes(address);
        return NULL;
    }
    {
#ifdef PREMULTIPLIED_ALPHA_ENABLE
        unsigned int* fourBytes = reinterpret_cast<unsigned int*>(png);
        for (int i = 0; i < width * height; i++)
        {
            unsigned char* p = png + i * 4;
            fourBytes[i] = Premultiply(p[0], p[1], p[2], p[3]);
        }
#endif
    }

    GLint maxTextureSize = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTextureSize);
    if (width > maxTextureSize || height > maxTextureSize)
    {
        LAppPal::PrintLog("[APP]texture exceeds GPU limit: %s (%dx%d, max=%d)", fileName.c_str(), width, height, maxTextureSize);
        stbi_image_free(png);
        LAppPal::ReleaseBytes(address);
        return NULL;
    }

    // OpenGL用のテクスチャを生成する
    glGenTextures(1, &textureId);
    if (textureId == 0)
    {
        LAppPal::PrintLog("[APP]texture creation failed: %s", fileName.c_str());
        stbi_image_free(png);
        LAppPal::ReleaseBytes(address);
        return NULL;
    }
    glBindTexture(GL_TEXTURE_2D, textureId);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, png);
    const GLenum uploadError = glGetError();
    if (uploadError != GL_NO_ERROR)
    {
        LAppPal::PrintLog("[APP]texture upload failed: %s (error=0x%x)", fileName.c_str(), uploadError);
        glDeleteTextures(1, &textureId);
        glBindTexture(GL_TEXTURE_2D, 0);
        stbi_image_free(png);
        LAppPal::ReleaseBytes(address);
        return NULL;
    }
    // 模型 UV 会贴到纹理边界，CLAMP_TO_EDGE 防止越界回绕采样（POT/NPOT 均合法）。
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    // 悬浮窗里模型被大幅缩小显示，仅 GL_LINEAR 会在缩小时产生锯齿/闪烁。
    // 2 的幂纹理直接生成 mipmap；NPOT 纹理在 ES2 下默认不支持 mipmap，
    // 仅当 GPU 提供 NPOT mipmap 扩展时才生成，否则安全退回 GL_LINEAR。
    const bool pot = IsPowerOfTwo(width) && IsPowerOfTwo(height);
    if (pot || SupportsNpotMipmap())
    {
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        if (!pot)
        {
            LAppPal::PrintLog("[APP]NPOT texture mipmapped via extension: %s (%dx%d)", fileName.c_str(), width, height);
        }
    }
    else
    {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    }
    glBindTexture(GL_TEXTURE_2D, 0);

    // 解放処理
    stbi_image_free(png);
    LAppPal::ReleaseBytes(address);

    LAppTextureManager::TextureInfo* textureInfo = new LAppTextureManager::TextureInfo();
    if (textureInfo != NULL)
    {
        textureInfo->fileName = fileName;
        textureInfo->width = width;
        textureInfo->height = height;
        textureInfo->id = textureId;

        _textures.PushBack(textureInfo);
        LAppPal::PrintLog("[APP]texture ready: %s (%dx%d, id=%u)", fileName.c_str(), width, height, textureId);
    }

    return textureInfo;

}

void LAppTextureManager::ReleaseTextures()
{
    for (Csm::csmUint32 i = 0; i < _textures.GetSize(); i++)
    {
        glDeleteTextures(1, &_textures[i]->id);
        delete _textures[i];
    }

    _textures.Clear();
}

void LAppTextureManager::ReleaseTexture(Csm::csmUint32 textureId)
{
    for (Csm::csmUint32 i = 0; i < _textures.GetSize(); i++)
    {
        if (_textures[i]->id != textureId)
        {
            continue;
        }
        glDeleteTextures(1, &_textures[i]->id);
        delete _textures[i];
        _textures.Remove(i);
        break;
    }
}

void LAppTextureManager::ReleaseTexture(std::string fileName)
{
    for (Csm::csmUint32 i = 0; i < _textures.GetSize(); i++)
    {
        if (_textures[i]->fileName == fileName)
        {
            glDeleteTextures(1, &_textures[i]->id);
            delete _textures[i];
            _textures.Remove(i);
            break;
        }
    }
}
