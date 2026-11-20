package com.wangzan101.mika.core.cap.image

import com.wangzan101.mika.core.model.GeneratedImage
import com.wangzan101.mika.core.model.ImageCapabilities
import com.wangzan101.mika.core.model.ImageReference
import com.wangzan101.mika.core.model.ImageRequest
import com.wangzan101.mika.core.model.ImageResponse

/**
 * 图片能力的 App 侧实现:调图片模型、把产物写进工作区、把工作区里的图存进相册。
 * 工作区路径的规范化、符号链接检查、文件句柄核实都由 App 侧做;核心只认 `/workspace/...` 这种路径。
 */
interface ImagePort {
    /** 当前选的图片模型名，只用于记录。 */
    val modelName: String

    /** 当前模型能做什么；工具在发请求前按它拒绝不支持的调用。 */
    val capabilities: ImageCapabilities

    /** 发一次生成/改图请求；失败抛 [com.wangzan101.mika.core.model.ModelError] 或 [com.wangzan101.mika.core.model.ImageError]。 */
    suspend fun generate(request: ImageRequest): ImageResponse

    /** 读工作区里的一张参考图；路径越界、不是图片、太大时抛 [com.wangzan101.mika.core.model.ImageError]。 */
    suspend fun readReference(path: String): ImageReference

    /** 把一张图写进工作区 `/workspace/out/images/`，返回最终路径和字节数；写入经工作区 gate，不覆盖、不跟随链接。 */
    suspend fun store(image: GeneratedImage, nameHint: String?, prompt: String): StoredImage

    /** 把工作区里的一张图存进相册 `Pictures/Mika`，返回相册路径和 content URI。 */
    suspend fun saveToGallery(path: String, name: String?): SavedImage
}

/** 已经写进工作区的一张图。 */
class StoredImage(val path: String, val bytes: Int, val width: Int, val height: Int)

/** 已经存进相册的一张图。 */
class SavedImage(val savedAs: String, val uri: String, val bytes: Long)
