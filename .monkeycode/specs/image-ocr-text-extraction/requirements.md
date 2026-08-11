# 图片文字提取(OCR)需求文档

## Introduction

HyperDragShare 当前通过无障碍模式识别屏幕上的文字(节点自带文本)或图片(区域截图)。
当长按图片时,用户只能把图片作为整体分享或预览,无法提取图片中的文字。本特性为
图片拖拽链路增加 OCR 能力:长按图片后自动识别图片内文字,并把识别结果以普通文本
形式交付给既有的复制、分享和分词(大爆炸)链路。

## Glossary

- **System**: HyperDragShare 模块,负责长按捕获、预览、拖拽和分享。
- **OCR 引擎**: Google ML Kit 文本识别(Text Recognition)离线模型,本地运行。
- **文本载荷**: 与现有无障碍文字节点相同的 `CapturedContent` 文本模型。
- **预览进入动画**: 预览窗首次显示时的入场动画。
- **分享目标**: 可接收分享 Intent 的目标 Activity(复制、保存、文本分词及第三方应用)。

## Requirements

### Requirement 1: 图片长按后自动 OCR

**User Story:** 作为用户,我想在长按图片时自动得到图片中的文字,以便不离开当前手势就复制或分享文字内容。

#### Acceptance Criteria

1. WHEN 长按的载荷是图片,且内容分享对图片启用,THEN 系统 SHALL 在预览显示后启动 OCR 识别。
2. WHEN OCR 识别完成且文本非空,THEN 系统 SHALL 把识别结果转换为文本载荷并进入既有文本链路。
3. WHEN OCR 识别完成但文本为空,THEN 系统 SHALL 保持图片载荷,不显示文本结果,且不展示任何识别失败提示。
4. WHEN OCR 正在运行,THEN 系统 SHALL 不展示识别进度,识别完成后文本载荷自动就绪。
4. WHILE OCR 正在运行,THEN 系统 SHALL 保持预览窗可见且不可中断当前拖拽手势。

### Requirement 2: 识别结果的复制与分享

**User Story:** 作为用户,我想复制或分享识别出的图片文字,以便快速使用。

#### Acceptance Criteria

1. WHEN 识别文本就绪,THEN 系统 SHALL 允许用户把文本复制到剪贴板。
2. WHEN 识别文本就绪,THEN 系统 SHALL 允许用户把文本分享到其他应用。
3. WHEN 用户选择复制或分享文本,THEN 系统 SHALL 使用与现有文字节点相同的操作路径。

### Requirement 3: 识别结果接入分词(大爆炸)

**User Story:** 作为用户,我想对识别出的图片文字使用分词功能,以便逐词选择。

#### Acceptance Criteria

1. WHEN 识别文本就绪且用户触发分词,THEN 系统 SHALL 打开现有分词界面处理该文本。
2. WHEN 分词界面退出,THEN 系统 SHALL 返回到原拖拽会话状态。

### Requirement 4: OCR 性能约束

**User Story:** 作为用户,我希望 OCR 不会让预览明显卡顿。

#### Acceptance Criteria

1. WHEN OCR 启动,THEN 系统 SHALL 在非主线程执行识别。
2. WHEN 识别耗时超过 3 秒,THEN 系统 SHALL 继续允许用户以图片载荷完成拖拽与分享。
3. WHEN 图片过大,THEN 系统 SHALL 先缩放图片到适合识别的尺寸再识别。
4. WHILE OCR 未完成,THEN 用户拖拽到目标并松手 SHALL 以图片载荷完成分享,不被 OCR 阻塞。
5. WHEN OCR 识别无结果或失败,THEN 系统 SHALL 静默回退到图片载荷,不弹提示、不展示失败状态。

### Requirement 5: 离线运行与隐私

**User Story:** 作为用户,我希望文字识别在本地完成,不上传图片。

#### Acceptance Criteria

1. WHEN 执行 OCR,THEN 系统 SHALL 仅使用本地 ML Kit 模型,不发起网络请求。
2. WHEN 执行 OCR,THEN 系统 SHALL 不把图片或识别文本写入任何持久化存储(剪贴板用户主动操作除外)。

### Requirement 6: 开关控制

**User Story:** 作为用户,我想控制是否对图片启用文字识别。

#### Acceptance Criteria

1. WHEN 用户关闭"图片文字识别"开关,THEN 系统 SHALL 对图片载荷跳过 OCR,仅按图片分享。
2. WHEN 用户开启"图片文字识别"开关,THEN 系统 SHALL 恢复长按图片自动 OCR 行为。
