//
// Created by qiulinmin on 8/1/17.
//
#include <jni.h>
#include <string>
#include <android_utils.h>
#include <Scanner.h>
#include <SSIMCalculator.h>

using namespace std;

static const char* const kClassDocScanner = "me/pqpo/smartcropperlib/SmartCropper";

static struct {
    jclass jClassPoint;
    jmethodID jMethodInit;
    jfieldID jFieldIDX;
    jfieldID jFieldIDY;
} gPointInfo;

static void initClassInfo(JNIEnv *env) {
    gPointInfo.jClassPoint = reinterpret_cast<jclass>(env -> NewGlobalRef(env -> FindClass("android/graphics/Point")));
    gPointInfo.jMethodInit = env -> GetMethodID(gPointInfo.jClassPoint, "<init>", "(II)V");
    gPointInfo.jFieldIDX = env -> GetFieldID(gPointInfo.jClassPoint, "x", "I");
    gPointInfo.jFieldIDY = env -> GetFieldID(gPointInfo.jClassPoint, "y", "I");
}

static jobject createJavaPoint(JNIEnv *env, Point point_) {
    return env -> NewObject(gPointInfo.jClassPoint, gPointInfo.jMethodInit, point_.x, point_.y);
}

static void native_scan(JNIEnv *env, jclass type, jobject srcBitmap, jobjectArray outPoint_, jboolean canny) {
    if (env -> GetArrayLength(outPoint_) != 4) {
        return;
    }
    Mat srcBitmapMat;
    bitmap_to_mat(env, srcBitmap, srcBitmapMat);
    Mat bgrData(srcBitmapMat.rows, srcBitmapMat.cols, CV_8UC3);
    cvtColor(srcBitmapMat, bgrData, COLOR_RGBA2BGR);
    scanner::Scanner docScanner(bgrData, canny);
    std::vector<Point> scanPoints = docScanner.scanPoint();
    if (scanPoints.size() == 4) {
        for (int i = 0; i < 4; ++i) {
            env -> SetObjectArrayElement(outPoint_, i, createJavaPoint(env, scanPoints[i]));
        }
    }
}

static vector<Point> pointsToNative(JNIEnv *env, jobjectArray points_) {
    int arrayLength = env->GetArrayLength(points_);
    vector<Point> result;
    for(int i = 0; i < arrayLength; i++) {
        jobject point_ = env -> GetObjectArrayElement(points_, i);
        int pX = env -> GetIntField(point_, gPointInfo.jFieldIDX);
        int pY = env -> GetIntField(point_, gPointInfo.jFieldIDY);
        result.push_back(Point(pX, pY));
    }
    return result;
}

static void native_crop(JNIEnv *env, jclass type, jobject srcBitmap, jobjectArray points_, jobject outBitmap) {
    std::vector<Point> points = pointsToNative(env, points_);
    if (points.size() != 4) {
        return;
    }
    Point leftTop = points[0];
    Point rightTop = points[1];
    Point rightBottom = points[2];
    Point leftBottom = points[3];

    Mat srcBitmapMat;
    bitmap_to_mat(env, srcBitmap, srcBitmapMat);

    AndroidBitmapInfo outBitmapInfo;
    AndroidBitmap_getInfo(env, outBitmap, &outBitmapInfo);
    Mat dstBitmapMat;
    int newHeight = outBitmapInfo.height;
    int newWidth = outBitmapInfo.width;
    dstBitmapMat = Mat::zeros(newHeight, newWidth, srcBitmapMat.type());

    std::vector<Point2f> srcTriangle;
    std::vector<Point2f> dstTriangle;

    srcTriangle.push_back(Point2f(leftTop.x, leftTop.y));
    srcTriangle.push_back(Point2f(rightTop.x, rightTop.y));
    srcTriangle.push_back(Point2f(leftBottom.x, leftBottom.y));
    srcTriangle.push_back(Point2f(rightBottom.x, rightBottom.y));

    dstTriangle.push_back(Point2f(0, 0));
    dstTriangle.push_back(Point2f(newWidth, 0));
    dstTriangle.push_back(Point2f(0, newHeight));
    dstTriangle.push_back(Point2f(newWidth, newHeight));

    Mat transform = getPerspectiveTransform(srcTriangle, dstTriangle);
    warpPerspective(srcBitmapMat, dstBitmapMat, transform, dstBitmapMat.size());

    mat_to_bitmap(env, dstBitmapMat, outBitmap);
}

static jdouble native_calculateSSIM(JNIEnv *env, jclass type, jobject bitmap1, jobject bitmap2) {
    Mat mat1, mat2;
    bitmap_to_mat(env, bitmap1, mat1);
    bitmap_to_mat(env, bitmap2, mat2);
    
    double ssimValue = ssim::SSIMCalculator::calculateSSIM(mat1, mat2);
    return static_cast<jdouble>(ssimValue);
}

// 创建新的Bitmap对象的辅助函数
static jobject createBitmapFromMat(JNIEnv *env, Mat &srcMat) {
    // 获取Bitmap类和创建方法
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass bitmapConfigClass = env->FindClass("android/graphics/Bitmap$Config");
    
    jfieldID argb8888FieldID = env->GetStaticFieldID(bitmapConfigClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argb8888Obj = env->GetStaticObjectField(bitmapConfigClass, argb8888FieldID);
    
    jmethodID createBitmapMethodID = env->GetStaticMethodID(bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    
    // 创建新的Bitmap
    jobject newBitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethodID, srcMat.cols, srcMat.rows, argb8888Obj);
    
    // 将Mat数据复制到Bitmap
    mat_to_bitmap(env, srcMat, newBitmap);
    
    return newBitmap;
}

static jobject native_convertToGrayscale(JNIEnv *env, jclass type, jobject srcBitmap) {
    Mat srcMat;
    bitmap_to_mat(env, srcBitmap, srcMat);
    
    Mat grayMat;
    if (srcMat.channels() == 4) {
        cvtColor(srcMat, grayMat, COLOR_RGBA2GRAY);
    } else if (srcMat.channels() == 3) {
        cvtColor(srcMat, grayMat, COLOR_RGB2GRAY);
    } else {
        grayMat = srcMat.clone();
    }
    
    // 转换回RGBA格式以便显示
    Mat rgbaMat;
    cvtColor(grayMat, rgbaMat, COLOR_GRAY2RGBA);
    
    return createBitmapFromMat(env, rgbaMat);
}

static jobject native_denoiseImage(JNIEnv *env, jclass type, jobject srcBitmap) {
    Mat srcMat;
    bitmap_to_mat(env, srcBitmap, srcMat);
    
    Mat denoisedMat;
    // 使用高斯滤波进行降噪
    GaussianBlur(srcMat, denoisedMat, Size(5, 5), 0);
    
    return createBitmapFromMat(env, denoisedMat);
}

static jobject native_enhanceContrast(JNIEnv *env, jclass type, jobject srcBitmap) {
    Mat srcMat;
    bitmap_to_mat(env, srcBitmap, srcMat);
    
    Mat enhancedMat;
    // 使用CLAHE（对比度限制自适应直方图均衡化）增强对比度
    if (srcMat.channels() == 1) {
        Ptr<CLAHE> clahe = createCLAHE(3.0, Size(8, 8));
        clahe->apply(srcMat, enhancedMat);
        // 转换回RGBA格式
        Mat rgbaMat;
        cvtColor(enhancedMat, rgbaMat, COLOR_GRAY2RGBA);
        enhancedMat = rgbaMat;
    } else {
        // 对于彩色图像，转换到Lab色彩空间处理
        Mat labMat;
        cvtColor(srcMat, labMat, COLOR_RGBA2RGB);
        cvtColor(labMat, labMat, COLOR_RGB2Lab);
        
        std::vector<Mat> labChannels;
        split(labMat, labChannels);
        
        Ptr<CLAHE> clahe = createCLAHE(3.0, Size(8, 8));
        clahe->apply(labChannels[0], labChannels[0]);
        
        merge(labChannels, labMat);
        cvtColor(labMat, enhancedMat, COLOR_Lab2RGB);
        cvtColor(enhancedMat, enhancedMat, COLOR_RGB2RGBA);
    }
    
    return createBitmapFromMat(env, enhancedMat);
}

static jobject native_binarizeImage(JNIEnv *env, jclass type, jobject srcBitmap) {
    Mat srcMat;
    bitmap_to_mat(env, srcBitmap, srcMat);
    
    Mat grayMat;
    if (srcMat.channels() > 1) {
        cvtColor(srcMat, grayMat, COLOR_RGBA2GRAY);
    } else {
        grayMat = srcMat.clone();
    }
    
    Mat binaryMat;
    // 使用自适应阈值二值化
    adaptiveThreshold(grayMat, binaryMat, 255, ADAPTIVE_THRESH_GAUSSIAN_C, THRESH_BINARY, 11, 2);
    
    // 转换回RGBA格式
    Mat rgbaMat;
    cvtColor(binaryMat, rgbaMat, COLOR_GRAY2RGBA);
    
    return createBitmapFromMat(env, rgbaMat);
}

static JNINativeMethod gMethods[] = {

        {
                "nativeScan",
                "(Landroid/graphics/Bitmap;[Landroid/graphics/Point;Z)V",
                (void*)native_scan
        },

        {
                "nativeCrop",
                "(Landroid/graphics/Bitmap;[Landroid/graphics/Point;Landroid/graphics/Bitmap;)V",
                (void*)native_crop
        },

        {
                "nativeCalculateSSIM",
                "(Landroid/graphics/Bitmap;Landroid/graphics/Bitmap;)D",
                (void*)native_calculateSSIM
        },

        {
                "nativeConvertToGrayscale",
                "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void*)native_convertToGrayscale
        },

        {
                "nativeDenoiseImage",
                "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void*)native_denoiseImage
        },

        {
                "nativeEnhanceContrast",
                "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void*)native_enhanceContrast
        },

        {
                "nativeBinarizeImage",
                "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void*)native_binarizeImage
        }

};

extern "C"
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv *env = NULL;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_4) != JNI_OK) {
        return JNI_FALSE;
    }
    jclass classDocScanner = env->FindClass(kClassDocScanner);
    if(env -> RegisterNatives(classDocScanner, gMethods, sizeof(gMethods)/ sizeof(gMethods[0])) < 0) {
        return JNI_FALSE;
    }
    initClassInfo(env);
    return JNI_VERSION_1_4;
}
