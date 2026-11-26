# 第三方来源与许可范围

仓库根目录的 [MIT 许可证](LICENSE) 适用于 HyperAlive 有权授权的原创贡献。上游代码、第三方素材和工具各自遵守适用条款。

## OpenAlive

感谢 OpenAlive 作者下北沢最速傳說（Maga-King）与项目贡献者。下列记录覆盖当前可确认的代码移植、实现参考和资源来源。 [OpenAlive 项目](https://github.com/Maga-King/OpenAlive)

OpenAlive 的 MIT 版权声明和许可正文随项目一同保留。调试和正式构建会从 Android assets 打包该文件。 [OpenAlive MIT 原文](app/src/main/assets/licenses/OpenAlive-MIT.txt)

### 移植与改写

| HyperAlive 文件 | OpenAlive 对应源码 |
| --- | --- |
| [AssetGl.java](app/src/main/java/com/hyperalive/aod/AssetGl.java) | [AssetGl.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/AssetGl.java) |
| [BubbleMotion.java](app/src/main/java/com/hyperalive/aod/BubbleMotion.java) | [BubbleMotion.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/BubbleMotion.java) |
| [BubbleRenderer.java](app/src/main/java/com/hyperalive/aod/BubbleRenderer.java) | [BubbleRenderer.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/BubbleRenderer.java) |
| [CosmicEgl.java](app/src/main/java/com/hyperalive/aod/CosmicEgl.java) | [CosmicEgl.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/CosmicEgl.java) |
| [CosmicMotion.java](app/src/main/java/com/hyperalive/aod/CosmicMotion.java) | [CosmicMotion.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/CosmicMotion.java) |
| [CosmicRenderer.java](app/src/main/java/com/hyperalive/aod/CosmicRenderer.java) | [CosmicRenderer.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/CosmicRenderer.java) |
| [FlymeVideoDecoder.java](app/src/main/java/com/hyperalive/aod/FlymeVideoDecoder.java) | [FlymeVideoDecoder.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/FlymeVideoDecoder.java) |
| [FlymeVideoRenderer.java](app/src/main/java/com/hyperalive/aod/FlymeVideoRenderer.java) | [FlymeVideoRenderer.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/FlymeVideoRenderer.java) |
| [GradientMotion.java](app/src/main/java/com/hyperalive/aod/GradientMotion.java) | [GradientMotion.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/GradientMotion.java) |
| [GradientRenderer.java](app/src/main/java/com/hyperalive/aod/GradientRenderer.java) | [GradientRenderer.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/GradientRenderer.java) |
| [GradientSpline.java](app/src/main/java/com/hyperalive/aod/GradientSpline.java) | [GradientSpline.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/GradientSpline.java) |
| [PhotoCropActivity.java](app/src/main/java/com/hyperalive/aod/PhotoCropActivity.java) | [PhotoCropActivity.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/PhotoCropActivity.java) |
| [PhotoViewport.java](app/src/main/java/com/hyperalive/aod/PhotoViewport.java) | [PhotoViewport.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/PhotoViewport.java) |
| [StereoMotion.java](app/src/main/java/com/hyperalive/aod/StereoMotion.java) | [StereoMotion.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/StereoMotion.java) |
| [StereoRenderer.java](app/src/main/java/com/hyperalive/aod/StereoRenderer.java) | [StereoRenderer.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/StereoRenderer.java) |
| [StereoSensor.java](app/src/main/java/com/hyperalive/aod/StereoSensor.java) | [StereoSensor.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/StereoSensor.java) |
| [WallpaperMotion.java](app/src/main/java/com/hyperalive/aod/WallpaperMotion.java) | [WallpaperMotion.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/WallpaperMotion.java) |
| [WallpaperRenderer.java](app/src/main/java/com/hyperalive/aod/WallpaperRenderer.java) | [WallpaperRenderer.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/WallpaperRenderer.java) |
| [WallpaperTransitionTime.java](app/src/main/java/com/hyperalive/aod/WallpaperTransitionTime.java) | [WallpaperTransitionTime.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/WallpaperTransitionTime.java) |

### 参数、数据与设计参考

| HyperAlive 文件 | 引用或参考内容 | OpenAlive 对应位置 |
| --- | --- | --- |
| [DynamicWallpapers.java](app/src/main/java/com/hyperalive/aod/DynamicWallpapers.java) | 内置目录、条目顺序与场景编号 | [catalog.json](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/assets/cosmic/catalog.json) |
| [EditorActivity.java](app/src/main/java/com/hyperalive/aod/EditorActivity.java) | 场景编辑结构、留光与纹理入口 | [OfficialEditor.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/OfficialEditor.java) |
| [GaussianBlur.java](app/src/main/java/com/hyperalive/aod/GaussianBlur.java) | 分级模糊流程 | [blur.rs](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/native/blur.rs) |
| [LensMatrices.java](app/src/main/java/com/hyperalive/aod/LensMatrices.java) | 启航视口、壁纸与装饰纹理矩阵 | [SceneMatrices.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/SceneMatrices.java) |
| [LensRenderer.java](app/src/main/java/com/hyperalive/aod/LensRenderer.java) | 启航、留光及多场景渲染的接口与参数 | [renderer.rs](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/native/renderer.rs) |
| [LibraryActivity.java](app/src/main/java/com/hyperalive/aod/LibraryActivity.java) | 动态壁纸图库与预览组织 | [WallpaperLibrary.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/WallpaperLibrary.java) |
| [LiuguangPipeline.java](app/src/main/java/com/hyperalive/aod/LiuguangPipeline.java) | 留光的后衬卡姿态表及相关合成逻辑 | [frame_rear.rs](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/native/frame_rear.rs) |
| [LivePreviewView.java](app/src/main/java/com/hyperalive/aod/LivePreviewView.java) | 应用内动态预览结构 | [HomeScenePreview.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/HomeScenePreview.java) |
| [MaskDecoder.java](app/src/main/java/com/hyperalive/aod/MaskDecoder.java) | RGBZ 遮罩解码约定 | [NativeScene.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/NativeScene.java) |
| [Motion.java](app/src/main/java/com/hyperalive/aod/Motion.java) | 启航、留光及其他照片样式的三态轨道和参数 | [SceneMotion.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/SceneMotion.java) |
| [PhotoMaskStyle.java](app/src/main/java/com/hyperalive/aod/PhotoMaskStyle.java) | 六种照片息屏样式的参数 | [PhotoStyle.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/PhotoStyle.java) |
| [PhotoStore.java](app/src/main/java/com/hyperalive/aod/PhotoStore.java) | 留光相框原图与默认取景 | [PhotoSources.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/PhotoSources.java) |
| [PhotoTextureMotion.java](app/src/main/java/com/hyperalive/aod/PhotoTextureMotion.java) | 照片纹理动画 | [TextureMotion.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/TextureMotion.java) |
| [PhotoTexturePipeline.java](app/src/main/java/com/hyperalive/aod/PhotoTexturePipeline.java) | 长虹、波浪、雾花的渲染与合成 | [renderer.rs](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/native/renderer.rs) |
| [UiActivity.java](app/src/main/java/com/hyperalive/aod/UiActivity.java) | 首页、图库与场景编辑入口的组织 | [MainActivity.java](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/app/java/org/aliveclean/MainActivity.java) |

## HyperAlive MIT 许可的排除范围

下表为当前仓库中的实际路径。范围也包括这些第三方文件的重编码、裁切、格式转换和衍生预览。对于混合实现，排除范围适用于其中的第三方数据或原始片段。

| 当前仓库路径 | 来源与适用范围 |
| --- | --- |
| `app/src/main/assets/masks/**` | 五组照片样式的遮罩帧和清单。文件与 OpenAlive 的对应资源一致，按上游的第三方资源说明处理。 |
| `app/src/main/assets/shader/**`、`app/src/main/assets/soundviz/**` | 渲染着色器和纹理。包含来自 OpenAlive 第三方资源目录的文件及适配版本，待按文件确认具体许可。 |
| `app/src/main/assets/bubble/**`、`app/src/main/assets/cosmic/**`、`app/src/main/assets/gradient/**` | 气泡配置、Cosmic 和 Phoenix 配色及模型、渐变配置。现有文件与 OpenAlive 的对应资源一致。 |
| `app/src/main/assets/flyme-video/**`、`app/src/main/assets/stereo/**` | Flyme 壁纸视频、立体壁纸图片和深度图，以及预览图和配套着色器。现有文件与 OpenAlive 对应资源一致。 |
| `app/src/main/assets/dynamic/thumbnails/**` | 基于 OpenAlive 预览资源生成的 WebP 缩略图，原文件及摘要见 [资源来源记录](app/src/main/assets/dynamic/source.json)。 |
| `app/src/main/assets/preview/**`、`app/src/main/assets/photo/default.jpg` | 壁纸、样式和纹理预览及示例图片。部分预览与 OpenAlive 编辑器资源一致，其余来源或分发授权待确认。 |
| `app/src/main/assets/fonts/**` | 第三方字体。FlymeNumber 和三个 SFPRO 文件的内嵌版权信息指向魅族或 Flyme。DM Serif Display 和 Six Caps 的上游版采用 OFL 1.1，仓库内二进制需核对具体发行版本及其随附声明。 |
| `LiuguangPipeline.java` 中来自第三方的姿态数据和内联着色器片段 | 姿态表经 OpenAlive 引入，混合的 Flyme 数据及原始着色器片段适用原权利人的条款。 |
| `gradle/wrapper/gradle-wrapper.jar` | Gradle Wrapper 构建工具。其上游许可独立于 HyperAlive。 |

OpenAlive 对官方着色器、壁纸、字体、遮罩等第三方资源的完整范围见上游说明。上述来源和排除信息之外，重新分发仍须具备相应授权。 [上游第三方资源说明](https://github.com/Maga-King/OpenAlive/blob/b4c3069a28e51ff6c38ee97664be8f4d6fa9262b/THIRD_PARTY.md)

Xposed API 82 是编译依赖。运行时框架由设备上的 LSPosed 提供。仓库中的 `stub/` 提供离线编译所需的接口声明。
