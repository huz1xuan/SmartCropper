package me.pqpo.smartcropperlib;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.util.Log;

import java.io.IOException;

import me.pqpo.smartcropperlib.utils.CropUtils;

/**
 * Created by qiulinmin on 8/1/17.
 */

public class SmartCropper {

    private static ImageDetector sImageDetector = null;

    public static void buildImageDetector(Context context) {
        SmartCropper.buildImageDetector(context, null);
    }

    public static void buildImageDetector(Context context, String modelFile) {
        try {
            sImageDetector = new ImageDetector(context, modelFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     *  输入图片扫描边框顶点
     * @param srcBmp 扫描图片
     * @return 返回顶点数组，以 左上，右上，右下，左下排序
     */
    public static Point[] scan(Bitmap srcBmp) {
        if (srcBmp == null) {
            throw new IllegalArgumentException("srcBmp cannot be null");
        }
        if (sImageDetector != null) {
            Bitmap bitmap = sImageDetector.detectImage(srcBmp);
            if (bitmap != null) {
                srcBmp = Bitmap.createScaledBitmap(bitmap, srcBmp.getWidth(), srcBmp.getHeight(), false);
            }
        }
        Point[] outPoints = new Point[4];
        nativeScan(srcBmp, outPoints, sImageDetector == null);
        return outPoints;
    }

    /**
     * 裁剪图片
     * @param srcBmp 待裁剪图片
     * @param cropPoints 裁剪区域顶点，顶点坐标以图片大小为准
     * @return 返回裁剪后的图片
     */
    public static Bitmap crop(Bitmap srcBmp, Point[] cropPoints) {
        if (srcBmp == null || cropPoints == null) {
            throw new IllegalArgumentException("srcBmp and cropPoints cannot be null");
        }
        if (cropPoints.length != 4) {
            throw new IllegalArgumentException("The length of cropPoints must be 4 , and sort by leftTop, rightTop, rightBottom, leftBottom");
        }

        //纠正4个点的位置，将距离(0,0)最近的点作为cropPoints[0]
        double minDistance = Integer.MAX_VALUE;
        int index = -1;
        int length = cropPoints.length;
        for (int i = 0;i < length;i++) {
            double distance2 = CropUtils.getPointsDistance(cropPoints[i],new Point(0,0));
            if(minDistance > distance2){
                minDistance = distance2;
                index = i;
            }
        }
        if(index > 0){
            Point[] resultCropPoint = new Point[length];
            for (int i = 0;i<cropPoints.length;i++) {
                resultCropPoint[i] = cropPoints[(index + i) >= length ? (index+i) - length : (index+i)];
            }
            cropPoints = resultCropPoint.clone();
        }

        Point leftTop = cropPoints[0];
        Point rightTop = cropPoints[1];
        Point rightBottom = cropPoints[2];
        Point leftBottom = cropPoints[3];

        int cropWidth = (int) ((CropUtils.getPointsDistance(leftTop, rightTop)
                + CropUtils.getPointsDistance(leftBottom, rightBottom))/2);
        int cropHeight = (int) ((CropUtils.getPointsDistance(leftTop, leftBottom)
                + CropUtils.getPointsDistance(rightTop, rightBottom))/2);

        Bitmap cropBitmap = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888);
        SmartCropper.nativeCrop(srcBmp, cropPoints, cropBitmap);
        return cropBitmap;
    }

    /**
     * 计算两个图片的SSIM相似度
     * @param bitmap1 第一张图片
     * @param bitmap2 第二张图片
     * @return SSIM值，范围0-1，值越大越相似
     */
    public static double calculateSSIM(Bitmap bitmap1, Bitmap bitmap2) {
        if (bitmap1 == null || bitmap2 == null) {
            return 0.0;
        }
        return nativeCalculateSSIM(bitmap1, bitmap2);
    }

    /**
     * 完整的文档处理流程：灰度图 -> 降噪 -> 加强对比度 -> 二值化
     * @param srcBmp 原始图片
     * @return 处理后的文档图片
     */
    public static Bitmap processDocument(Bitmap srcBmp) {
        if (srcBmp == null) {
            throw new IllegalArgumentException("srcBmp cannot be null");
        }

        // 步骤1：转换为灰度图
        Bitmap grayBitmap = convertToGrayscale(srcBmp);
        
        // 步骤2：降噪处理
        Bitmap denoisedBitmap = denoiseImage(grayBitmap);
        
        // 步骤3：加强对比度
        Bitmap contrastBitmap = enhanceContrast(denoisedBitmap);
        
        // 步骤4：二值化处理
        Bitmap binaryBitmap = binarizeImage(contrastBitmap);
        
        // 清理中间结果
        if (grayBitmap != denoisedBitmap) grayBitmap.recycle();
        if (denoisedBitmap != contrastBitmap) denoisedBitmap.recycle();
        if (contrastBitmap != binaryBitmap) contrastBitmap.recycle();
        
        return binaryBitmap;
    }

    /**
     * 转换为灰度图
     * @param srcBmp 原始图片
     * @return 灰度图
     */
    public static Bitmap convertToGrayscale(Bitmap srcBmp) {
        if (srcBmp == null) {
            throw new IllegalArgumentException("srcBmp cannot be null");
        }
        return nativeConvertToGrayscale(srcBmp);
    }

    /**
     * 图像降噪处理
     * @param srcBmp 原始图片
     * @return 降噪后的图片
     */
    public static Bitmap denoiseImage(Bitmap srcBmp) {
        if (srcBmp == null) {
            throw new IllegalArgumentException("srcBmp cannot be null");
        }
        return nativeDenoiseImage(srcBmp);
    }

    /**
     * 增强图像对比度
     * @param srcBmp 原始图片
     * @return 对比度增强后的图片
     */
    public static Bitmap enhanceContrast(Bitmap srcBmp) {
        if (srcBmp == null) {
            throw new IllegalArgumentException("srcBmp cannot be null");
        }
        return nativeEnhanceContrast(srcBmp);
    }

    /**
     * 图像二值化处理
     * @param srcBmp 原始图片
     * @return 二值化后的图片
     */
    public static Bitmap binarizeImage(Bitmap srcBmp) {
        if (srcBmp == null) {
            throw new IllegalArgumentException("srcBmp cannot be null");
        }
        return nativeBinarizeImage(srcBmp);
    }

    /**
     * 高级文档处理：包含形态学操作和GrabCut背景分离
     * @param srcBmp 原始图片
     * @return 处理后的图片
     */
    public static Bitmap advancedDocumentProcess(Bitmap srcBmp) {
        if (srcBmp == null) {
            throw new IllegalArgumentException("srcBmp cannot be null");
        }
        return nativeAdvancedDocumentProcess(srcBmp);
    }

    /**
     * 智能二值化：支持多种算法
     * @param srcBmp 原始图片
     * @param method 二值化方法 (0:自适应高斯阈值, 1:自适应均值阈值, 2:Otsu自动阈值, 3:组合方法)
     * @return 二值化后的图片
     */
    public static Bitmap smartBinarize(Bitmap srcBmp, int method) {
        if (srcBmp == null) {
            throw new IllegalArgumentException("srcBmp cannot be null");
        }
        return nativeSmartBinarize(srcBmp, method);
    }

    /**
     * 高级降噪：Non-local Means Denoising
     * @param srcBmp 原始图片
     * @return 降噪后的图片
     */
    public static Bitmap advancedDenoise(Bitmap srcBmp) {
        if (srcBmp == null) {
            throw new IllegalArgumentException("srcBmp cannot be null");
        }
        return nativeAdvancedDenoise(srcBmp);
    }

    /**
     * 完整的高级文档处理流程
     * @param srcBmp 原始图片
     * @param binarizeMethod 二值化方法
     * @return 处理后的文档图片
     */
    public static Bitmap processAdvancedDocument(Bitmap srcBmp, int binarizeMethod) {
        if (srcBmp == null) {
            throw new IllegalArgumentException("srcBmp cannot be null");
        }

        // 步骤1：高级文档处理（形态学操作+GrabCut）
        Bitmap processedBitmap = advancedDocumentProcess(srcBmp);
        
        // 步骤2：转换为灰度图
        Bitmap grayBitmap = convertToGrayscale(processedBitmap);
        
        // 步骤3：高级降噪
        Bitmap denoisedBitmap = advancedDenoise(grayBitmap);
        
        // 步骤4：增强对比度
        Bitmap contrastBitmap = enhanceContrast(denoisedBitmap);
        
        // 步骤5：智能二值化
        Bitmap binaryBitmap = smartBinarize(contrastBitmap, binarizeMethod);
        
        // 清理中间结果
        if (processedBitmap != grayBitmap) processedBitmap.recycle();
        if (grayBitmap != denoisedBitmap) grayBitmap.recycle();
        if (denoisedBitmap != contrastBitmap) denoisedBitmap.recycle();
        if (contrastBitmap != binaryBitmap) contrastBitmap.recycle();
        
        return binaryBitmap;
    }

    /**
     * 文档质量评估
     * @param srcBmp 文档图片
     * @return 质量分数 (0-100, 越高越好)
     */
    public static int evaluateDocumentQuality(Bitmap srcBmp) {
        if (srcBmp == null) {
            return 0;
        }
        
        try {
            // 简单的质量评估：基于图像清晰度和对比度
            Bitmap grayBitmap = convertToGrayscale(srcBmp);
            
            // TODO: 这里可以进一步实现更复杂的质量评估算法
            // 比如：边缘检测密度、对比度分析、噪声等级等
            
            // 基础评分：根据图像尺寸和像素密度
            int baseScore = 50;
            
            // 尺寸加分：大尺寸图像通常质量更好
            int pixels = srcBmp.getWidth() * srcBmp.getHeight();
            if (pixels > 2000000) { // 2MP+
                baseScore += 25;
            } else if (pixels > 1000000) { // 1MP+
                baseScore += 15;
            } else if (pixels > 500000) { // 0.5MP+
                baseScore += 10;
            }
            
            // 宽高比加分：文档的典型宽高比
            float aspectRatio = (float) srcBmp.getWidth() / srcBmp.getHeight();
            if (aspectRatio > 0.7f && aspectRatio < 1.5f) {
                baseScore += 10; // 接近正方形或文档比例
            }
            
            // 清理临时图像
            if (grayBitmap != srcBmp) {
                grayBitmap.recycle();
            }
            
            // 确保分数在有效范围内
            return Math.max(0, Math.min(100, baseScore));
            
        } catch (Exception e) {
            Log.e("SmartCropper", "Error evaluating document quality", e);
            return 50; // 默认中等质量
        }
    }

    /**
     * 根据文档质量自动调整处理参数
     * @param srcBmp 文档图片
     * @param targetMode 目标处理模式
     * @return 优化处理后的文档图片
     */
    public static Bitmap processDocumentWithQualityOptimization(Bitmap srcBmp, int targetMode) {
        if (srcBmp == null) {
            throw new IllegalArgumentException("srcBmp cannot be null");
        }
        
        try {
            // 评估文档质量
            int quality = evaluateDocumentQuality(srcBmp);
            Log.d("SmartCropper", "Document quality score: " + quality);
            
            // 根据质量调整处理策略
            if (quality < 30) {
                // 低质量：加强处理
                return processLowQualityDocument(srcBmp, targetMode);
            } else if (quality > 80) {
                // 高质量：轻度处理
                return processHighQualityDocument(srcBmp, targetMode);
            } else {
                // 中等质量：标准处理
                return processDocumentByMode(srcBmp, targetMode);
            }
            
        } catch (Exception e) {
            Log.e("SmartCropper", "Error in quality optimization", e);
            // 错误时使用标准处理
            return processDocumentByMode(srcBmp, targetMode);
        }
    }

    /**
     * 低质量文档处理：加强降噪和对比度增强
     */
    private static Bitmap processLowQualityDocument(Bitmap srcBmp, int targetMode) {
        // 低质量文档需要更强的处理
        Bitmap processedBitmap = advancedDocumentProcess(srcBmp); // 背景分离
        Bitmap denoisedBitmap = advancedDenoise(processedBitmap);   // 高级降噪
        Bitmap grayBitmap = convertToGrayscale(denoisedBitmap);
        
        // 增强对比度处理（多次）
        Bitmap contrastBitmap = enhanceContrast(grayBitmap);
        Bitmap enhancedBitmap = enhanceContrast(contrastBitmap);
        
        // 使用组合二值化方法
        Bitmap binaryBitmap = smartBinarize(enhancedBitmap, BinarizeMethod.COMBINED);
        
        // 清理中间结果
        if (processedBitmap != denoisedBitmap) processedBitmap.recycle();
        if (denoisedBitmap != grayBitmap) denoisedBitmap.recycle();
        if (grayBitmap != contrastBitmap) grayBitmap.recycle();
        if (contrastBitmap != enhancedBitmap) contrastBitmap.recycle();
        if (enhancedBitmap != binaryBitmap) enhancedBitmap.recycle();
        
        return binaryBitmap;
    }

    /**
     * 高质量文档处理：轻度处理保持细节
     */
    private static Bitmap processHighQualityDocument(Bitmap srcBmp, int targetMode) {
        // 高质量文档使用轻度处理
        Bitmap grayBitmap = convertToGrayscale(srcBmp);
        Bitmap denoisedBitmap = denoiseImage(grayBitmap); // 使用轻度降噪
        
        // 根据模式选择二值化方法
        int binarizeMethod;
        switch (targetMode) {
            case DocumentMode.OCR_OPTIMIZED:
                binarizeMethod = BinarizeMethod.COMBINED;
                break;
            case DocumentMode.PRINTED_DOCUMENT:
                binarizeMethod = BinarizeMethod.OTSU;
                break;
            default:
                binarizeMethod = BinarizeMethod.ADAPTIVE_GAUSSIAN;
                break;
        }
        
        Bitmap binaryBitmap = smartBinarize(denoisedBitmap, binarizeMethod);
        
        // 清理中间结果
        if (grayBitmap != denoisedBitmap) grayBitmap.recycle();
        if (denoisedBitmap != binaryBitmap) denoisedBitmap.recycle();
        
        return binaryBitmap;
    }

    /**
     * 二值化方法常量
     */
    public static class BinarizeMethod {
        public static final int ADAPTIVE_GAUSSIAN = 0;
        public static final int ADAPTIVE_MEAN = 1;
        public static final int OTSU = 2;
        public static final int COMBINED = 3;
    }

    /**
     * 文档处理模式常量
     */
    public static class DocumentMode {
        /** OCR优化模式：适用于字符识别 */
        public static final int OCR_OPTIMIZED = 0;
        /** 打印文档模式：适用于清晰的打印文档 */
        public static final int PRINTED_DOCUMENT = 1;
        /** 手写文档模式：适用于手写内容 */
        public static final int HANDWRITTEN_DOCUMENT = 2;
        /** 白板模式：适用于白板或黑板内容 */
        public static final int WHITEBOARD = 3;
    }

    /**
     * 根据指定模式处理文档
     * @param srcBmp 原始图片
     * @param mode 文档处理模式
     * @return 处理后的文档图片
     */
    public static Bitmap processDocumentByMode(Bitmap srcBmp, int mode) {
        if (srcBmp == null) {
            throw new IllegalArgumentException("srcBmp cannot be null");
        }

        switch (mode) {
            case DocumentMode.OCR_OPTIMIZED:
                return processOCROptimizedDocument(srcBmp);
            case DocumentMode.PRINTED_DOCUMENT:
                return processPrintedDocument(srcBmp);
            case DocumentMode.HANDWRITTEN_DOCUMENT:
                return processHandwrittenDocument(srcBmp);
            case DocumentMode.WHITEBOARD:
                return processWhiteboardDocument(srcBmp);
            default:
                return processAdvancedDocument(srcBmp, BinarizeMethod.COMBINED);
        }
    }

    /**
     * OCR优化模式：适用于OCR识别的文档处理
     * @param srcBmp 原始图片
     * @return 处理后的文档图片
     */
    public static Bitmap processOCROptimizedDocument(Bitmap srcBmp) {
        if (srcBmp == null) {
            throw new IllegalArgumentException("srcBmp cannot be null");
        }

        // OCR优化流程：高级背景分离 -> 高级降噪 -> 灰度转换 -> 对比度增强 -> 组合二值化
        Bitmap processedBitmap = advancedDocumentProcess(srcBmp);
        Bitmap denoisedBitmap = advancedDenoise(processedBitmap);
        Bitmap grayBitmap = convertToGrayscale(denoisedBitmap);
        Bitmap contrastBitmap = enhanceContrast(grayBitmap);
        Bitmap binaryBitmap = smartBinarize(contrastBitmap, BinarizeMethod.COMBINED);
        
        // 清理中间结果
        if (processedBitmap != denoisedBitmap) processedBitmap.recycle();
        if (denoisedBitmap != grayBitmap) denoisedBitmap.recycle();
        if (grayBitmap != contrastBitmap) grayBitmap.recycle();
        if (contrastBitmap != binaryBitmap) contrastBitmap.recycle();
        
        return binaryBitmap;
    }

    /**
     * 打印文档模式：适用于清晰的打印文档
     * @param srcBmp 原始图片
     * @return 处理后的文档图片
     */
    public static Bitmap processPrintedDocument(Bitmap srcBmp) {
        if (srcBmp == null) {
            throw new IllegalArgumentException("srcBmp cannot be null");
        }

        // 打印文档流程：灰度转换 -> 适度降噪 -> 对比度增强 -> Otsu二值化
        Bitmap grayBitmap = convertToGrayscale(srcBmp);
        Bitmap denoisedBitmap = denoiseImage(grayBitmap); // 使用较温和的降噪
        Bitmap contrastBitmap = enhanceContrast(denoisedBitmap);
        Bitmap binaryBitmap = smartBinarize(contrastBitmap, BinarizeMethod.OTSU);
        
        // 清理中间结果
        if (grayBitmap != denoisedBitmap) grayBitmap.recycle();
        if (denoisedBitmap != contrastBitmap) denoisedBitmap.recycle();
        if (contrastBitmap != binaryBitmap) contrastBitmap.recycle();
        
        return binaryBitmap;
    }

    /**
     * 手写文档模式：适用于手写内容
     * @param srcBmp 原始图片
     * @return 处理后的文档图片
     */
    public static Bitmap processHandwrittenDocument(Bitmap srcBmp) {
        if (srcBmp == null) {
            throw new IllegalArgumentException("srcBmp cannot be null");
        }

        // 手写文档流程：灰度转换 -> 高级降噪 -> 轻微对比度增强 -> 自适应高斯二值化
        Bitmap grayBitmap = convertToGrayscale(srcBmp);
        Bitmap denoisedBitmap = advancedDenoise(grayBitmap); // 使用高级降噪保持细节
        Bitmap contrastBitmap = enhanceContrast(denoisedBitmap);
        Bitmap binaryBitmap = smartBinarize(contrastBitmap, BinarizeMethod.ADAPTIVE_GAUSSIAN);
        
        // 清理中间结果
        if (grayBitmap != denoisedBitmap) grayBitmap.recycle();
        if (denoisedBitmap != contrastBitmap) denoisedBitmap.recycle();
        if (contrastBitmap != binaryBitmap) contrastBitmap.recycle();
        
        return binaryBitmap;
    }

    /**
     * 白板模式：适用于白板或黑板内容
     * @param srcBmp 原始图片
     * @return 处理后的文档图片
     */
    public static Bitmap processWhiteboardDocument(Bitmap srcBmp) {
        if (srcBmp == null) {
            throw new IllegalArgumentException("srcBmp cannot be null");
        }

        // 白板模式流程：背景分离 -> 灰度转换 -> 强对比度增强 -> 自适应均值二值化
        Bitmap processedBitmap = advancedDocumentProcess(srcBmp); // 使用GrabCut去除背景
        Bitmap grayBitmap = convertToGrayscale(processedBitmap);
        Bitmap contrastBitmap = enhanceContrast(grayBitmap);
        Bitmap binaryBitmap = smartBinarize(contrastBitmap, BinarizeMethod.ADAPTIVE_MEAN);
        
        // 清理中间结果
        if (processedBitmap != grayBitmap) processedBitmap.recycle();
        if (grayBitmap != contrastBitmap) grayBitmap.recycle();
        if (contrastBitmap != binaryBitmap) contrastBitmap.recycle();
        
        return binaryBitmap;
    }

    private static native void nativeScan(Bitmap srcBitmap, Point[] outPoints, boolean canny);

    private static native void nativeCrop(Bitmap srcBitmap, Point[] points, Bitmap outBitmap);

    private static native double nativeCalculateSSIM(Bitmap bitmap1, Bitmap bitmap2);

    private static native Bitmap nativeConvertToGrayscale(Bitmap srcBitmap);

    private static native Bitmap nativeDenoiseImage(Bitmap srcBitmap);

    private static native Bitmap nativeEnhanceContrast(Bitmap srcBitmap);

    private static native Bitmap nativeBinarizeImage(Bitmap srcBitmap);

    private static native Bitmap nativeAdvancedDocumentProcess(Bitmap srcBitmap);

    private static native Bitmap nativeSmartBinarize(Bitmap srcBitmap, int method);

    private static native Bitmap nativeAdvancedDenoise(Bitmap srcBitmap);

    static {
        System.loadLibrary("smart_cropper");
    }

}
