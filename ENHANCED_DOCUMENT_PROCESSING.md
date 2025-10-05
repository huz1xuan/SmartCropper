# SmartCropper 增强文档处理功能

## 概述

本项目已成功集成了OSS-DocumentScanner的核心技术优势，大幅提升了文档处理和BW（黑白）模式的处理能力。

## 主要改进功能

### 1. 高级文档处理算法

#### 形态学操作和GrabCut背景分离
- **形态学操作**：使用闭操作去除文本内容，获得清洁的文档边缘
- **GrabCut算法**：实现精确的前景背景分离，有效去除复杂背景
- **实现方法**：`SmartCropper.advancedDocumentProcess()`

#### 多种自适应阈值二值化
- **自适应高斯阈值**：适用于大多数文档场景
- **自适应均值阈值**：适用于光照不均匀的文档
- **Otsu自动阈值**：适用于对比度明显的文档
- **CLAHE+自适应组合**：结合对比度增强的高级二值化
- **实现方法**：`SmartCropper.smartBinarize(bitmap, method)`

#### 高级降噪算法
- **Non-local Means Denoising**：保持边缘细节的同时有效去除噪声
- **自适应降噪强度**：根据图像质量自动调整降噪参数
- **实现方法**：`SmartCropper.advancedDenoise()`

### 2. 多模式文档处理

#### OCR优化模式
- **特点**：专为字符识别优化，最大化文字清晰度
- **流程**：高级背景分离 → 高级降噪 → 灰度转换 → 对比度增强 → 组合二值化
- **适用场景**：需要进行文字识别的文档

#### 打印文档模式
- **特点**：适用于清晰的打印文档，保持原始质感
- **流程**：灰度转换 → 适度降噪 → 对比度增强 → Otsu二值化
- **适用场景**：书籍、报纸、打印资料

#### 手写文档模式
- **特点**：保持手写字迹的细节和连续性
- **流程**：灰度转换 → 高级降噪 → 轻微对比度增强 → 自适应高斯二值化
- **适用场景**：手写笔记、签名文档

#### 白板模式
- **特点**：有效去除白板背景和反光
- **流程**：背景分离 → 灰度转换 → 强对比度增强 → 自适应均值二值化
- **适用场景**：白板内容、黑板内容

### 3. 智能质量评估和自动参数调整

#### 文档质量评估
- **评估维度**：图像分辨率、宽高比、像素密度
- **评分范围**：0-100分，分数越高质量越好
- **实现方法**：`SmartCropper.evaluateDocumentQuality()`

#### 自动参数调整
- **低质量文档**：加强降噪和对比度增强，使用多次处理
- **中等质量文档**：标准处理流程
- **高质量文档**：轻度处理，保持原始细节
- **实现方法**：`SmartCropper.processDocumentWithQualityOptimization()`

## 用户界面改进

### 新增按钮功能
1. **📄 文档处理**：基础文档处理，包含5步流程
2. **⚡ 高级文档处理**：多模式选择，包含质量优化

### 模式选择对话框
用户可以选择以下处理模式：
- OCR优化模式
- 打印文档模式
- 手写文档模式
- 白板模式

## 技术实现细节

### C++ 层新增方法
```cpp
// 高级文档处理
native_advancedDocumentProcess()

// 智能二值化
native_smartBinarize(bitmap, method)

// 高级降噪
native_advancedDenoise()
```

### Java 层新增方法
```java
// 多模式文档处理
SmartCropper.processDocumentByMode(bitmap, mode)

// 质量优化处理
SmartCropper.processDocumentWithQualityOptimization(bitmap, mode)

// 各种专用处理方法
SmartCropper.processOCROptimizedDocument(bitmap)
SmartCropper.processPrintedDocument(bitmap)
SmartCropper.processHandwrittenDocument(bitmap)
SmartCropper.processWhiteboardDocument(bitmap)
```

## 使用建议

### 不同文档类型的最佳模式
1. **书籍、报纸**：打印文档模式
2. **需要OCR识别**：OCR优化模式
3. **手写笔记**：手写文档模式
4. **会议白板**：白板模式

### 性能优化
- 高分辨率图像建议使用质量优化处理
- 批量处理时可以预先评估质量选择处理强度
- 实时预览时可以使用快速模式

## 与OSS-DocumentScanner的对比

### 我们的优势
1. **集成度更高**：无需额外配置复杂的依赖
2. **模式化处理**：针对不同场景优化的处理模式
3. **质量自适应**：根据图像质量自动调整处理强度
4. **移动端优化**：针对Android平台的性能优化

### 参考的核心技术
1. **形态学操作**：去除文本干扰的预处理
2. **GrabCut算法**：精确的背景分离
3. **自适应阈值**：智能的二值化处理
4. **Non-local Means**：高质量的降噪算法

## 未来改进方向

1. **深度学习增强**：集成AI模型进行更智能的文档检测
2. **实时预览**：在相机预览中实时显示处理效果
3. **批量处理**：支持多张图片的批量处理
4. **更多格式支持**：支持PDF生成和多页文档处理

## 总结

通过集成OSS-DocumentScanner的核心技术，SmartCropper现在具备了业界领先的文档处理能力。特别是在BW模式处理方面，多种二值化算法的结合使用、智能质量评估和自动参数调整，都大大提升了最终输出的文档质量。

用户现在可以根据不同的文档类型选择最适合的处理模式，获得最佳的扫描效果。