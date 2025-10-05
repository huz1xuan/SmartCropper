package me.pqpo.smartcropper;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

import me.pqpo.smartcropperlib.SmartCropper;
import pub.devrel.easypermissions.EasyPermissions;

/**
 * 使用CameraX实现的相机页面Activity
 */
public class CameraActivity extends AppCompatActivity implements EasyPermissions.PermissionCallbacks {

    private static final String TAG = "CameraActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private static final String FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS";

    // UI组件
    private PreviewView mPreviewView;
    private Button mBtnCapture;
    private Button mBtnBack;
    private Button mBtnDocumentProcess;
    private Switch mSwitchManualAdjust;
    private Switch mSwitchAutoCapture;
    private TextView mTvAutoStatus;
    private TextView mTvModeStatus;
    private TextView mTvCaptureStats;

    // CameraX 相关
    private ProcessCameraProvider mCameraProvider;
    private Preview mPreview;
    private ImageCapture mImageCapture;
    private Camera mCamera;
    private CameraSelector mCameraSelector;

    // 线程相关
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    // 数据
    private File mOutputFile;
    private File mOutputDir;
    private boolean mManualAdjustEnabled = false;
    private boolean mAutoCaptureEnabled = false;
    private Handler mMainHandler = new Handler();
    private Runnable mAutoCaptureRunnable;
    
    // SSIM相似度检测相关
    private Bitmap mLastCapturedBitmap = null;
    private static final double SSIM_THRESHOLD = 0.85;
    
    // 拍照统计
    private int mCapturedCount = 0;
    private int mSkippedCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        // 初始化SmartCropper
        SmartCropper.buildImageDetector(this);

        initViews();
        setupListeners();

        // 获取输出文件路径
        mOutputFile = (File) getIntent().getSerializableExtra("output_file");
        if (mOutputFile == null) {
            mOutputFile = new File(getExternalFilesDir("img"), "camera_photo.jpg");
        }
        
        // 创建输出目录
        mOutputDir = getExternalFilesDir("img");
        if (mOutputDir != null && !mOutputDir.exists()) {
            mOutputDir.mkdirs();
        }

        // 初始化CameraX
        mCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        // 检查相机权限
        checkCameraPermission();
    }

    private void initViews() {
        mPreviewView = findViewById(R.id.preview_view);
        mBtnCapture = findViewById(R.id.btn_capture);
        mBtnBack = findViewById(R.id.btn_back);
        mBtnDocumentProcess = findViewById(R.id.btn_document_process);
        mSwitchManualAdjust = findViewById(R.id.switch_manual_adjust);
        mSwitchAutoCapture = findViewById(R.id.switch_auto_capture);
        mTvAutoStatus = findViewById(R.id.tv_auto_status);
        mTvModeStatus = findViewById(R.id.tv_mode_status);
        mTvCaptureStats = findViewById(R.id.tv_capture_stats);
    }

    private void setupListeners() {
        mBtnBack.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        mBtnCapture.setOnClickListener(v -> capturePhoto());

        mBtnDocumentProcess.setOnClickListener(v -> captureAndProcessDocument());

        mSwitchManualAdjust.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mManualAdjustEnabled = isChecked;
            updateModeStatus();
            if (isChecked) {
                Toast.makeText(this, "已开启手动调整模式", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "已开启自动处理模式", Toast.LENGTH_SHORT).show();
            }
        });

        mSwitchAutoCapture.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mAutoCaptureEnabled = isChecked;
            if (isChecked) {
                // 重置统计
                mCapturedCount = 0;
                mSkippedCount = 0;
                updateCaptureStats();
                startAutoCapture();
            } else {
                stopAutoCapture();
            }
        });
        
        // 初始化模式状态
        updateModeStatus();
    }

    private void checkCameraPermission() {
        String[] permissions = {
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        };

        if (EasyPermissions.hasPermissions(this, permissions)) {
            startBackgroundThread();
            startCamera();
        } else {
            EasyPermissions.requestPermissions(
                this,
                "需要相机和存储权限来使用拍照功能",
                REQUEST_CAMERA_PERMISSION,
                permissions
            );
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        
        cameraProviderFuture.addListener(() -> {
            try {
                // Used to bind the lifecycle of cameras to the lifecycle owner
                mCameraProvider = cameraProviderFuture.get();
                
                // Preview
                mPreview = new Preview.Builder().build();
                mPreview.setSurfaceProvider(mPreviewView.getSurfaceProvider());
                
                // ImageCapture
                mImageCapture = new ImageCapture.Builder()
                    .build();
                
                // Select back camera as a default
                mCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                
                try {
                    // Unbind use cases before rebinding
                    mCameraProvider.unbindAll();
                    
                    // Bind use cases to camera
                    mCamera = mCameraProvider.bindToLifecycle(
                        (LifecycleOwner) this, mCameraSelector, mPreview, mImageCapture);
                    
                    // 优化相机亮度设置
                    optimizeCameraBrightness();
                        
                } catch (Exception exc) {
                    Log.e(TAG, "Use case binding failed", exc);
                }
                
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera provider initialization failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void capturePhoto() {
        if (mImageCapture == null) {
            Log.e(TAG, "ImageCapture is not initialized");
            return;
        }

        // 创建带时间戳的文件名
        String name = new SimpleDateFormat(FILENAME_FORMAT, Locale.getDefault())
                .format(new Date());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        
        // 创建输出文件选项
        ImageCapture.OutputFileOptions outputFileOptions;
        
        if (mOutputFile != null) {
            // 使用指定的文件
            outputFileOptions = new ImageCapture.OutputFileOptions.Builder(mOutputFile)
                    .build();
        } else {
            // 使用MediaStore
            outputFileOptions = new ImageCapture.OutputFileOptions.Builder(
                    getContentResolver(),
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues)
                    .build();
        }

        // 拍照
        mImageCapture.takePicture(
                outputFileOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        Log.d(TAG, "Photo saved successfully");
                        
                        // 获取保存的文件
                        File savedFile = mOutputFile;
                        if (savedFile == null && output.getSavedUri() != null) {
                            // 如果使用的是MediaStore，创建临时文件
                            savedFile = new File(mOutputDir, name + ".jpg");
                        }
                        
                        if (savedFile != null) {
                            handleCapturedPhoto(savedFile);
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Photo capture failed: " + exception.getMessage(), exception);
                        Toast.makeText(CameraActivity.this, "拍照失败", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }
    
    /**
     * 拍照并进行文档处理
     */
    private void captureAndProcessDocument() {
        if (mImageCapture == null) {
            Log.e(TAG, "ImageCapture is not initialized");
            return;
        }

        // 创建带时间戳的文件名
        String name = "doc_" + new SimpleDateFormat(FILENAME_FORMAT, Locale.getDefault())
                .format(new Date());
        File documentFile = new File(mOutputDir, name + ".jpg");

        ImageCapture.OutputFileOptions outputFileOptions = new ImageCapture.OutputFileOptions.Builder(documentFile)
                .build();

        // 拍照
        mImageCapture.takePicture(
                outputFileOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        Log.d(TAG, "Document photo saved successfully");
                        Toast.makeText(CameraActivity.this, "开始文档处理...", Toast.LENGTH_SHORT).show();
                        performDocumentProcessing(documentFile);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Document photo capture failed: " + exception.getMessage(), exception);
                        Toast.makeText(CameraActivity.this, "拍照失败", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    /**
     * 执行完整的文档处理流程
     */
    private void performDocumentProcessing(File originalFile) {
        mBackgroundHandler.post(() -> {
            try {
                // 加载原始图片
                android.graphics.Bitmap originalBitmap = android.graphics.BitmapFactory.decodeFile(originalFile.getPath());
                if (originalBitmap == null) {
                    runOnUiThread(() -> Toast.makeText(CameraActivity.this, "加载图片失败", Toast.LENGTH_SHORT).show());
                    return;
                }

                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "步骤1/5: 边缘检测中...", Toast.LENGTH_SHORT).show());

                // 步骤1：边缘检测和裁剪
                android.graphics.Point[] detectedPoints = SmartCropper.scan(originalBitmap);
                android.graphics.Bitmap croppedBitmap = originalBitmap;
                
                if (detectedPoints != null && detectedPoints.length == 4) {
                    android.graphics.Bitmap tempCropped = SmartCropper.crop(originalBitmap, detectedPoints);
                    if (tempCropped != null) {
                        croppedBitmap = tempCropped;
                        runOnUiThread(() -> Toast.makeText(CameraActivity.this, "步骤2/5: 转换灰度图...", Toast.LENGTH_SHORT).show());
                    }
                } else {
                    runOnUiThread(() -> Toast.makeText(CameraActivity.this, "未检测到边缘，继续处理原图", Toast.LENGTH_SHORT).show());
                }

                // 步骤2：转换为灰度图
                android.graphics.Bitmap grayBitmap = SmartCropper.convertToGrayscale(croppedBitmap);
                if (croppedBitmap != originalBitmap && croppedBitmap != grayBitmap) {
                    croppedBitmap.recycle();
                }

                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "步骤3/5: 图像降噪...", Toast.LENGTH_SHORT).show());

                // 步骤3：降噪处理
                android.graphics.Bitmap denoisedBitmap = SmartCropper.denoiseImage(grayBitmap);
                if (grayBitmap != denoisedBitmap) {
                    grayBitmap.recycle();
                }

                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "步骤4/5: 增强对比度...", Toast.LENGTH_SHORT).show());

                // 步骤4：增强对比度
                android.graphics.Bitmap contrastBitmap = SmartCropper.enhanceContrast(denoisedBitmap);
                if (denoisedBitmap != contrastBitmap) {
                    denoisedBitmap.recycle();
                }

                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "步骤5/5: 二值化处理...", Toast.LENGTH_SHORT).show());

                // 步骤5：二值化处理
                android.graphics.Bitmap binaryBitmap = SmartCropper.binarizeImage(contrastBitmap);
                if (contrastBitmap != binaryBitmap) {
                    contrastBitmap.recycle();
                }

                // 保存最终结果
                saveBitmapToFile(binaryBitmap, originalFile);
                binaryBitmap.recycle();

                runOnUiThread(() -> {
                    Toast.makeText(CameraActivity.this, "文档处理完成！", Toast.LENGTH_LONG).show();
                    saveToGallery(originalFile);
                    setResult(RESULT_OK);
                });

            } catch (Exception e) {
                Log.e(TAG, "Document processing failed", e);
                runOnUiThread(() -> {
                    Toast.makeText(CameraActivity.this, "文档处理失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    saveToGallery(originalFile);
                });
            }
        });
    }

    /**
     * 处理拍照后的图片
     */
    private void handleCapturedPhoto(File photoFile) {
        // 如果开启了自动拍照，先进行SSIM相似度检测
        if (mAutoCaptureEnabled && shouldSkipProcessingDueToSimilarity(photoFile)) {
            Log.d(TAG, "Skipping processing due to high similarity (SSIM > " + SSIM_THRESHOLD + ")");
            // 删除相似的图片文件，不进行处理
            if (photoFile.exists()) {
                photoFile.delete();
            }
            // 更新跳过统计
            mSkippedCount++;
            runOnUiThread(this::updateCaptureStats);
            return;
        }
        
        // 如果是自动拍照模式，更新拍照统计
        if (mAutoCaptureEnabled) {
            mCapturedCount++;
            runOnUiThread(this::updateCaptureStats);
        }
        
        if (mManualAdjustEnabled) {
            // 手动调整模式：跳转到手动裁剪页面
            Intent intent = CropActivity.getJumpIntent(CameraActivity.this, false, photoFile);
            startActivityForResult(intent, 300);
        } else {
            // 自动处理模式：进行边缘检测+裁剪+矯正
            performAutoProcessing(photoFile);
        }
    }

    private void saveToGallery(File file) {
        try {
            // 确保文件存在且不为空
            if (!file.exists() || file.length() == 0) {
                Log.e(TAG, "File does not exist or is empty: " + file.getAbsolutePath());
                if (!mAutoCaptureEnabled) {
                    Toast.makeText(this, "文件不存在，无法保存到图库", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            
            Log.d(TAG, "Saving file to gallery: " + file.getAbsolutePath() + ", size: " + file.length());
            
            // 首先尝试直接插入到MediaStore
            insertImageToGallery(file);
            
            // 然后使用MediaScannerConnection确保文件被扫描
            MediaScannerConnection.scanFile(this,
                    new String[]{file.getAbsolutePath()},
                    new String[]{"image/jpeg"},
                    new MediaScannerConnection.OnScanCompletedListener() {
                        @Override
                        public void onScanCompleted(String path, Uri uri) {
                            Log.d(TAG, "MediaScanner completed. Path: " + path + ", Uri: " + uri);
                            runOnUiThread(() -> {
                                // 自动拍照模式下不显示Toast
                                if (!mAutoCaptureEnabled) {
                                    Toast.makeText(CameraActivity.this, "照片已保存到图库", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    });
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to save to gallery", e);
            if (!mAutoCaptureEnabled) {
                Toast.makeText(this, "保存到图库失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 直接将文件插入到MediaStore
     */
    private void insertImageToGallery(File file) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.TITLE, "SmartCropper_" + System.currentTimeMillis());
        values.put(MediaStore.Images.Media.DISPLAY_NAME, file.getName());
        values.put(MediaStore.Images.Media.DESCRIPTION, "Photo taken by SmartCropper");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000);
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        
        // 对于Android 10及以上版本，不使用DATA字段
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.DATA, file.getAbsolutePath());
        }
        values.put(MediaStore.Images.Media.SIZE, file.length());

        try {
            ContentResolver resolver = getContentResolver();
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            
            if (uri != null) {
                Log.d(TAG, "Photo inserted to MediaStore: " + uri);
                
                // 对于Android 10及以上版本，需要将文件内容复制到MediaStore
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    copyFileToMediaStore(file, uri);
                }
                
                // 通知系统媒体库更新
                Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                mediaScanIntent.setData(Uri.fromFile(file));
                sendBroadcast(mediaScanIntent);
            } else {
                Log.e(TAG, "Failed to insert photo to MediaStore");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error inserting to MediaStore", e);
        }
    }

    /**
     * 执行自动化处理：边缘检测 + 裁剪 + 矫正
     */
    private void performAutoProcessing(File originalFile) {
        mBackgroundHandler.post(() -> {
            try {
                // 加载原始图片
                android.graphics.Bitmap originalBitmap = android.graphics.BitmapFactory.decodeFile(originalFile.getPath());
                if (originalBitmap == null) {
                    runOnUiThread(() -> Toast.makeText(CameraActivity.this, "加载图片失败", Toast.LENGTH_SHORT).show());
                    return;
                }

                // 进行边缘检测
                android.graphics.Point[] detectedPoints = SmartCropper.scan(originalBitmap);
                
                android.graphics.Bitmap processedBitmap = null;
                
                if (detectedPoints != null && detectedPoints.length == 4) {
                    // 检测到边缘，进行裁剪和矯正
                    processedBitmap = SmartCropper.crop(originalBitmap, detectedPoints);
                    
                    if (processedBitmap != null) {
                        // 保存处理后的图片到原文件
                        saveBitmapToFile(processedBitmap, originalFile);
                        
                        runOnUiThread(() -> {
                            // 自动拍照模式下不显示Toast
                            if (!mAutoCaptureEnabled) {
                                Toast.makeText(CameraActivity.this, "边缘检测和裁剪完成", Toast.LENGTH_SHORT).show();
                            }
                            // 保存到图库
                            saveToGallery(originalFile);
                            setResult(RESULT_OK);
                            if (!mAutoCaptureEnabled) {
                                finish();
                            }
                        });
                    } else {
                        runOnUiThread(() -> {
                            // 自动拍照模式下不显示Toast
                            if (!mAutoCaptureEnabled) {
                                Toast.makeText(CameraActivity.this, "裁剪失败，保存原始图片", Toast.LENGTH_SHORT).show();
                            }
                            saveToGallery(originalFile);
                            setResult(RESULT_OK);
                            if (!mAutoCaptureEnabled) {
                                finish();
                            }
                        });
                    }
                } else {
                    // 未检测到边缘，保存原始图片
                    runOnUiThread(() -> {
                        // 自动拍照模式下不显示Toast
                        if (!mAutoCaptureEnabled) {
                            Toast.makeText(CameraActivity.this, "未检测到边缘，保存原始图片", Toast.LENGTH_SHORT).show();
                        }
                        saveToGallery(originalFile);
                        setResult(RESULT_OK);
                        if (!mAutoCaptureEnabled) {
                            finish();
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Auto processing failed", e);
                runOnUiThread(() -> {
                    // 自动拍照模式下不显示Toast
                    if (!mAutoCaptureEnabled) {
                        Toast.makeText(CameraActivity.this, "自动处理失败，保存原始图片", Toast.LENGTH_SHORT).show();
                    }
                    saveToGallery(originalFile);
                });
            }
        });
    }

    /**
     * 将Bitmap保存到文件
     */
    private void saveBitmapToFile(android.graphics.Bitmap bitmap, File file) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
        } catch (IOException e) {
            Log.e(TAG, "Failed to save bitmap to file", e);
        }
    }

    private void startAutoCapture() {
        mTvAutoStatus.setText("自动拍照已开启 (1秒/张)");
        mTvAutoStatus.setVisibility(View.VISIBLE);
        mTvCaptureStats.setVisibility(View.VISIBLE);
        
        mAutoCaptureRunnable = new Runnable() {
            @Override
            public void run() {
                if (mAutoCaptureEnabled) {
                    capturePhoto();
                    mMainHandler.postDelayed(this, 1000); // 1秒后再次执行
                }
            }
        };
        
        mMainHandler.post(mAutoCaptureRunnable);
    }

    private void stopAutoCapture() {
        mTvAutoStatus.setText("自动拍照已关闭");
        mTvAutoStatus.setVisibility(View.GONE);
        mTvCaptureStats.setVisibility(View.GONE);
        
        if (mAutoCaptureRunnable != null) {
            mMainHandler.removeCallbacks(mAutoCaptureRunnable);
        }
    }
    
    /**
     * 更新拍照统计显示
     */
    private void updateCaptureStats() {
        if (mTvCaptureStats != null) {
            String statsText = String.format("拍照统计：已拍 %d 张，跳过 %d 张", mCapturedCount, mSkippedCount);
            mTvCaptureStats.setText(statsText);
        }
    }

    /**
     * 更新模式状态显示
     */
    private void updateModeStatus() {
        if (mManualAdjustEnabled) {
            mTvModeStatus.setText("手动调整模式：拍照后手动调整角点");
            mTvModeStatus.setTextColor(getResources().getColor(android.R.color.holo_orange_light));
        } else {
            mTvModeStatus.setText("自动处理模式：边缘检测+裁剪+矯正");
            mTvModeStatus.setTextColor(getResources().getColor(android.R.color.holo_blue_light));
        }
        mTvModeStatus.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 300) {
            // 从手动调整页面返回
            setResult(resultCode);
            if (!mAutoCaptureEnabled) {
                finish();
            }
        }
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (mBackgroundThread != null) {
            mBackgroundThread.quitSafely();
            try {
                mBackgroundThread.join();
                mBackgroundThread = null;
                mBackgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Failed to stop background thread", e);
            }
        }
    }



    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override
    public void onPermissionsGranted(int requestCode, List<String> perms) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            startBackgroundThread();
            startCamera();
        }
    }

    @Override
    public void onPermissionsDenied(int requestCode, List<String> perms) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            Toast.makeText(this, "需要相机权限才能使用拍照功能", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /**
     * 优化相机亮度设置
     */
    private void optimizeCameraBrightness() {
        if (mCamera != null) {
            CameraControl cameraControl = mCamera.getCameraControl();
            
            // 设置曝光补偿来提高亮度
            // 曝光补偿范围通常是-2到+2，这里设置为+1来提高亮度
            cameraControl.setExposureCompensationIndex(1);
            
            Log.d(TAG, "Camera brightness optimized with exposure compensation +1");
        }
    }
    
    /**
     * 检查是否因为相似度过高而跳过处理
     * 使用简化的像素差异算法代替复杂的SSIM
     */
    private boolean shouldSkipProcessingDueToSimilarity(File photoFile) {
        if (mLastCapturedBitmap == null) {
            // 第一次拍照，保存为参考图片
            mLastCapturedBitmap = loadAndScaleBitmap(photoFile.getAbsolutePath());
            return false;
        }
        
        // 加载当前图片
        Bitmap currentBitmap = loadAndScaleBitmap(photoFile.getAbsolutePath());
        if (currentBitmap == null) {
            return false;
        }
        
        try {
            // 使用简化的相似度计算
            double similarity = calculateSimpleSimilarity(mLastCapturedBitmap, currentBitmap);
            Log.d(TAG, "Simple similarity: " + similarity);
            
            // 如果相似度大于阈值，跳过处理
            if (similarity > SSIM_THRESHOLD) {
                currentBitmap.recycle();
                return true;
            } else {
                // 更新参考图片
                if (mLastCapturedBitmap != null && !mLastCapturedBitmap.isRecycled()) {
                    mLastCapturedBitmap.recycle();
                }
                mLastCapturedBitmap = currentBitmap;
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error calculating similarity", e);
            if (currentBitmap != null && !currentBitmap.isRecycled()) {
                currentBitmap.recycle();
            }
            // 出错时不跳过处理
            return false;
        }
    }
    
    /**
     * 加载并缩放图片以提高处理效率
     */
    private Bitmap loadAndScaleBitmap(String filePath) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 4; // 缩放到1/4大小以提高性能
            options.inPreferredConfig = Bitmap.Config.RGB_565; // 使用更少内存
            return BitmapFactory.decodeFile(filePath, options);
        } catch (Exception e) {
            Log.e(TAG, "Error loading bitmap", e);
            return null;
        }
    }
    
    /**
     * 计算两个图片的简单相似度
     * 基于像素差异的快速算法
     */
    private double calculateSimpleSimilarity(Bitmap bitmap1, Bitmap bitmap2) {
        if (bitmap1 == null || bitmap2 == null) {
            return 0.0;
        }
        
        // 确保两个图片尺寸相同
        int width = Math.min(bitmap1.getWidth(), bitmap2.getWidth());
        int height = Math.min(bitmap1.getHeight(), bitmap2.getHeight());
        
        if (width <= 0 || height <= 0) {
            return 0.0;
        }
        
        // 采样检测，不检查每个像素以提高性能
        int sampleStep = Math.max(1, width / 32); // 采样步长
        int totalSamples = 0;
        int similarSamples = 0;
        
        for (int x = 0; x < width; x += sampleStep) {
            for (int y = 0; y < height; y += sampleStep) {
                if (x >= bitmap1.getWidth() || y >= bitmap1.getHeight() ||
                    x >= bitmap2.getWidth() || y >= bitmap2.getHeight()) {
                    continue;
                }
                
                int pixel1 = bitmap1.getPixel(x, y);
                int pixel2 = bitmap2.getPixel(x, y);
                
                // 计算RGB差异
                int r1 = (pixel1 >> 16) & 0xff;
                int g1 = (pixel1 >> 8) & 0xff;
                int b1 = pixel1 & 0xff;
                
                int r2 = (pixel2 >> 16) & 0xff;
                int g2 = (pixel2 >> 8) & 0xff;
                int b2 = pixel2 & 0xff;
                
                int diff = Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2);
                
                // 如果差异小于阈值，认为相似
                if (diff < 30) { // 可调整的相似度阈值
                    similarSamples++;
                }
                totalSamples++;
            }
        }
        
        return totalSamples > 0 ? (double) similarSamples / totalSamples : 0.0;
    }
    
    /**
     * 将文件内容复制到MediaStore URI（Android 10+）
     */
    private void copyFileToMediaStore(File sourceFile, Uri targetUri) {
        try (FileOutputStream out = (FileOutputStream) getContentResolver().openOutputStream(targetUri);
             java.io.FileInputStream in = new java.io.FileInputStream(sourceFile)) {
            
            if (out != null && in != null) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
                Log.d(TAG, "File copied to MediaStore successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error copying file to MediaStore", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        // CameraX会自动管理相机生命周期
    }

    @Override
    protected void onPause() {
        stopAutoCapture();
        stopBackgroundThread();
        super.onPause();
        // CameraX会自动管理相机生命周期
    }
    
    @Override
    protected void onDestroy() {
        // 清理SSIM参考图片
        if (mLastCapturedBitmap != null && !mLastCapturedBitmap.isRecycled()) {
            mLastCapturedBitmap.recycle();
            mLastCapturedBitmap = null;
        }
        super.onDestroy();
    }
}