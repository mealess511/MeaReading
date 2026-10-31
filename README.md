# MeaReading 📚

> ## ⚠️ 关于「代码是 AI 写的」
>
> 本项目 **99% 的代码由 AI（DeepSeek）生成**，我负责提需求、给素材、提 Bug、验收。
> 包括架构重构、EPUB/MOBI/RAR 解析器、UI 重绘、图标与文档，都是 AI 在这个项目里写出来的。
> 我自己主要做了三件事：**描述想读什么书、按测试、骂它重做**。
>
> 如果你介意"AI 生成"这件事，请谨慎使用或直接 fork 自己改；如果你只是想找个能老老实实看书的 App，那它应该够用。

一款纯离线的安卓本地阅读器。**没有网络权限、没有广告、不上传任何东西**，装上就能看自己手机里的书。

- 当前版本：**1.0.2**（versionCode 3）
- 最低系统：**Android 8.0（API 26）**
- 包名：`com.Mealess.appaction`
- 安装包：见 [Releases](https://github.com/mealess511/MeaReading/releases)

---

## 💡 关于这个项目

既然是自己用的，主打的就是一个"没广告、不搞花里胡哨"。

但是吧，丑话说在前头——**本人是个纯纯的懒狗** 🐶。
这软件是我自己用着舒服就行，所以**更新频率非常随缘**。遇到 Bug 或者有什么想加的神仙功能，欢迎在 Issues 里提，
但我只能说……**"在做了在做了（新建文件夹）"**。

也正因为懒，这次干脆把架构整个推倒让 AI 重写了一遍：解析层重做成可独立测试的纯 Java 核心，UI 全部重绘，原来那些"闪退、目录乱、进度丢、图片糊"的坑顺手一起填了。

---

## ✨ 现在能干什么

### 支持的格式

| 类别 | 格式 | 说明 |
|---|---|---|
| 小说 | **TXT / MD / HTML** | 自动识别 UTF-8 / UTF-16 / GB18030(GBK) / Big5，自动分章 |
| 小说 | **EPUB** | 支持 EPUB2（NCX）与 EPUB3（nav）目录，直接读压缩包资源，不再解压 |
| 小说 | **MOBI / PRC / AZW / AZW3** | 完整 PalmDoc 解析：PalmDOC(LZ77)、HUFF/CDIC、无压缩；能读内嵌封面和作者 |
| 小说 | **FB2 / FBZ** | FictionBook，支持 zip 内嵌 |
| 小说 | **ZIP 文本包** | 压缩包里是 html/txt 章节时按小说打开 |
| 漫画 | **CBZ / ZIP** | 按文件名自然排序（2 排在 10 前面），按文件夹自动分卷 |
| 漫画 | **CBR / RAR / RAR5** | 纯 Java 解压，首次解压后缓存，第二次秒开 |
| 漫画 | **CBT / TAR** | 直接随机读取，不占额外空间 |
| 通用 | **PDF** | 系统 PdfRenderer 渲染，按屏幕宽度等比缩放 |

> 格式判断不只看后缀，会嗅探文件头（`PK` / `Rar!` / `%PDF` / `BOOKMOBI` / `<FictionBook`），所以后缀写错、甚至没有后缀的文件也能正常打开。

### 阅读器

- **两种翻页**：左右翻页 / 上下滚动（条漫模式）
- **目录**：自动章节识别 + 目录内搜索（几千章也能秒找）
- **全文搜索**：显示上下文摘要，点结果直接跳转
- **书签**：带章节名，可增删跳转
- **语音朗读**：边读边自动翻页，可随时停
- **排版**：字号、行距、页边距、正文加粗，全部实时预览并记住
- **5 种主题**：纸白 / 羊皮 / 护眼 / 夜间 / 夜蓝，22:00–07:00 可自动切换深色
- **手势**：点左/右翻页，点中间呼出工具栏；左侧边缘上下滑调亮度，右侧边缘上下滑调字号；漫画双指缩放、双击放大
- **音量键翻页**（可关）
- 页脚显示章节 / 页码 / 百分比

### 书架

- 卡片式列表：封面、进度条、收藏星标、累计阅读时长
- 分类：小说 / 漫画 / 最近阅读 / 收藏；支持搜索与四种排序
- 长按重命名、换封面、看书籍信息、删除
- **自动封面**：EPUB/漫画/PDF 自动抽封面，纯文本按书名生成渐变封面
- **文件夹批量导入**（含子目录）
- **书架备份/恢复**（JSON，换机不丢进度）
- 缓存清理、阅读统计
- 支持在文件管理器里"用其他应用打开"直接导入

### 相比 1.0.1 修掉的坑

EPUB 每次打开都解压整本书、图片路径带 `../` 就丢、目录取成书名、换字号整章重载闪白；
书架列表不复用、封面主线程解码卡顿；进度只存页号导致换字号错位；书签把第 1/10/21 页当成同一页；
"上一章/下一章"其实只翻一页；阅读时长重复累加；漫画缩放吞掉点击；PDF 渲染 OOM；文件夹导入重复写数据……（详细清单见代码注释与 Releases 说明）

---

## 📲 下载安装

1. 打开右侧 **[Releases](https://github.com/mealess511/MeaReading/releases)**，下载最新的 `MeaReading-x.x.x.apk`。
2. 安装到手机（个人开发没有上架商店，系统可能提示"未知来源"，点继续安装即可）。
3. 打开 → 右下角 **+** → 导入文件 / 导入文件夹。

> 本 App **不申请网络权限**，看不了在线书城，也不会偷偷上传任何东西。

---

## 🛠 自己编译

### 方式一：Android Studio（推荐）

用 Android Studio 打开项目根目录，等待同步完成，`Build → Build APK(s)`。
依赖只有 `app/libs/` 里的两个 jar（junrar、slf4j-api），**不需要联网下载第三方库**。

### 方式二：命令行（不装 Gradle 也行）

需要 JDK 11+ 和 Android SDK（build-tools 33.0.2 + platform android-33）：

```powershell
$env:ANDROID_SDK_ROOT = "C:\Android\Sdk"
powershell -ExecutionPolicy Bypass -File .\build_apk.ps1
# 产出 MeaReading-1.0.2.apk
```

脚本内部就是 `aapt2 → javac → d8 → zipalign → apksigner` 这一套，第一次运行会在项目下生成 `keystore/moyue.keystore`。
**这个密钥请自己保管好，以后升级必须用同一把，否则装不上。**

也可以用 Gradle Wrapper：

```bash
./gradlew assembleDebug
```

---

## 📁 目录结构

```
app/src/main/java/com/Mealess/appaction/
├── core/                纯 Java 解析核心（不依赖 Android，可单测）
│   ├── TextEngine.java  文本规范化 / 懒分页 / 章节识别 / 搜索
│   ├── TextCodec.java   编码探测（BOM / UTF-8 / UTF-16 / GB18030 / Big5）
│   ├── HtmlText.java    HTML → 纯文本、实体解码、标题提取
│   ├── EpubBook.java    EPUB 容器（OPF / spine / NCX / nav / 封面）
│   ├── MobiBook.java    PalmDoc 容器（PalmDOC + HUFF/CDIC + EXTH）
│   ├── ComicArchive.java zip / rar / tar 漫画包统一接口
│   └── ZipContent.java  zip 嗅探与文本提取
├── parser/              统一的 Parser 接口与各格式实现
├── ui/                  书架列表、可缩放漫画页
├── MainActivity.java    书架
├── ReaderActivity.java  阅读器
├── BookImporter.java    导入、格式嗅探、文件夹递归
├── Covers.java          封面提取与渐变封面生成
└── ...
```

技术细节（EPUB 零解压、RAR 缓存策略、分页算法）都写在 `README` 的详细版和代码注释里。

---

## ⚠️ 已知限制

- **DRM 加密**的书（Adobe DRM / Kindle DRM）打不开，会明确提示。
- **7z / CB7** 暂不支持，建议转成 CBZ。
- 语音朗读只支持纯文本类图书（TXT/MOBI/FB2/ZIP 文本）。
- PDF 只做整页渲染，不支持文字重排。
- 超大 CBR（几百 MB）第一次打开要解压，会慢一点，第二次就好了。

---

## 📝 更新日志

### 1.0.2
- 应用名改为 **MeaReading**，全新图标（深色底 + 打开的书 + 蓝色书签）
- 目录面板新增**章节搜索**（超过 12 章时出现）
- 阅读设置新增**正文加粗**（纯文本与 EPUB 同时生效）
- 版本号 1.0.2 / versionCode 3

### 1.0.1
- 修复若干阅读与导入问题（历史版本，见 Releases）

### 1.0.0
- 首个公开版本

---

## ⚠️ 免责声明

**代码是 AI 敲的**，Bug 是我提的。书是你自己下的，**请支持正版阅读**。
本软件不提供任何书籍内容，也不承担因使用产生的任何责任。
如果不小心闪退了，请温柔地重启，或者直接卸载。😎
