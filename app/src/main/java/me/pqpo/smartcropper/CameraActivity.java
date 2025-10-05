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
    private Button mBtnMode;
    private TextView mTvAutoStatus;
    private TextView mTvCurrentMode;
    private TextView mTvCaptureStats;

    // 拍照模式枚举
    public enum CameraMode {
        DEFAULT("默认模式", 0),          // 直接拍照不做任何处理
        MANUAL_ADJUST("手动调整模式", 1), // 手动调整裁剪点
        AUTO_CAPTURE("自动拍照模式", 2),  // 自动拍照
        OCR_MODE("OCR识别模式", 3),      // OCR优化处理
        PRINT_DOC("打印文档模式", 4),    // 打印文档处理
        HANDWRITTEN("手写文档模式", 5),  // 手写文档处理  
        WHITEBOARD("白板模式", 6);       // 白板处理
        
        private final String displayName;
        private final int value;
        
        CameraMode(String displayName, int value) {
            this.displayName = displayName;
            this.value = value;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public int getValue() {
            return value;
        }
    }
    
    // 当前选择的模式
    private CameraMode mCurrentMode = CameraMode.DEFAULT;

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
    private boolean mAutoCaptureEnabled = false;
    private Handler mMainHandler = new Handler();
    private Runnable mAutoCaptureRunnable;
    
    // SSIM相似度检测相关
    private Bitmap mLastCapturedBitmap = null;
    private static final double SSIM_THRESHOLD = 0.50; // 50% 相似度阈值
    
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
        mBtnMode = findViewById(R.id.btn_mode);
        mTvAutoStatus = findViewById(R.id.tv_auto_status);
        mTvCurrentMode = findViewById(R.id.tv_current_mode);
        mTvCaptureStats = findViewById(R.id.tv_capture_stats);
        
        // 初始化模式显示
        updateModeDisplay();
    }

    private void setupListeners() {
        mBtnBack.setOnClickListener(v -> {
            // 如果是自动拍照模式，停止自动拍照
            if (mCurrentMode == CameraMode.AUTO_CAPTURE && mAutoCaptureEnabled) {
                stopAutoCapture();
            }
            setResult(RESULT_CANCELED);
            finish();
        });

        mBtnCapture.setOnClickListener(v -> performCaptureByMode());

        mBtnMode.setOnClickListener(v -> showModeSelectionDialog());
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

    /**
     * 显示模式选择对话框
     */
    private void showModeSelectionDialog() {
        CameraMode[] modes = CameraMode.values();
        String[] modeNames = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            modeNames[i] = modes[i].getDisplayName();
        }
        
        // 找到当前模式的索引
        int currentIndex = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == mCurrentMode) {
                currentIndex = i;
                break;
            }
        }
        
        new android.app.AlertDialog.Builder(this)
            .setTitle("选择拍照模式")
            .setSingleChoiceItems(modeNames, currentIndex, (dialog, which) -> {
                mCurrentMode = modes[which];
                updateModeDisplay();
                
                // 如果之前是自动拍照模式，先停止
                if (mAutoCaptureEnabled) {
                    stopAutoCapture();
                }
                
                // 如果切换到自动拍照模式，自动开启
                if (mCurrentMode == CameraMode.AUTO_CAPTURE) {
                    // 重置统计
                    mCapturedCount = 0;
                    mSkippedCount = 0;
                    updateCaptureStats();
                    startAutoCapture();
                }
                
                dialog.dismiss();
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    /**
     * 更新模式显示
     */
    private void updateModeDisplay() {
        if (mTvCurrentMode != null) {
            mTvCurrentMode.setText(mCurrentMode.getDisplayName());
        }
    }
    
    /**
     * 根据当前模式执行拍照
     */
    private void performCaptureByMode() {
        switch (mCurrentMode) {
            case DEFAULT:
                capturePhotoDefault();
                break;
            case MANUAL_ADJUST:
                capturePhotoForManualAdjust();
                break;
            case AUTO_CAPTURE:
                // 自动拍照模式下，点击按钮切换开关状态
                toggleAutoCapture();
                break;
            case OCR_MODE:
                captureAndProcessByMode(3); // OCR模式
                break;
            case PRINT_DOC:
                captureAndProcessByMode(4); // 打印文档模式
                break;
            case HANDWRITTEN:
                captureAndProcessByMode(5); // 手写文档模式
                break;
            case WHITEBOARD:
                captureAndProcessByMode(6); // 白板模式
                break;
        }
    }
    
    /**
     * 默认模式拍照：直接拍照不做任何处理
     */
    private void capturePhotoDefault() {
        if (mImageCapture == null) {
            Log.e(TAG, "ImageCapture is not initialized");
            return;
        }

        // 创建带时间戳的文件名
        String name = "default_" + new SimpleDateFormat(FILENAME_FORMAT, Locale.getDefault())
                .format(new Date());
        File photoFile = new File(mOutputDir, name + ".jpg");

        ImageCapture.OutputFileOptions outputFileOptions = new ImageCapture.OutputFileOptions.Builder(photoFile)
                .build();

        // 拍照
        mImageCapture.takePicture(
                outputFileOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        Log.d(TAG, "Default photo saved successfully");
                        // 直接保存到图库，不做任何处理
                        saveToGallery(photoFile);
                        runOnUiThread(() -> Toast.makeText(CameraActivity.this, "拍照成功", Toast.LENGTH_SHORT).show());
                        setResult(RESULT_OK);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Default photo capture failed: " + exception.getMessage(), exception);
                        Toast.makeText(CameraActivity.this, "拍照失败", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }
    
    /**
     * 手动调整模式拍照
     */
    private void capturePhotoForManualAdjust() {
        if (mImageCapture == null) {
            Log.e(TAG, "ImageCapture is not initialized");
            return;
        }

        // 创建带时间戳的文件名
        String name = "manual_" + new SimpleDateFormat(FILENAME_FORMAT, Locale.getDefault())
                .format(new Date());
        File photoFile = new File(mOutputDir, name + ".jpg");

        ImageCapture.OutputFileOptions outputFileOptions = new ImageCapture.OutputFileOptions.Builder(photoFile)
                .build();

        // 拍照
        mImageCapture.takePicture(
                outputFileOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        Log.d(TAG, "Manual adjust photo saved successfully");
                        // 跳转到手动裁剪页面，使用已拍摄的照片
                        Intent intent = CropActivity.getJumpIntentForExistingFile(CameraActivity.this, photoFile, photoFile);
                        startActivityForResult(intent, 300);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Manual adjust photo capture failed: " + exception.getMessage(), exception);
                        Toast.makeText(CameraActivity.this, "拍照失败", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }
    
    /**
     * 切换自动拍照状态
     */
    private void toggleAutoCapture() {
        mAutoCaptureEnabled = !mAutoCaptureEnabled;
        if (mAutoCaptureEnabled) {
            // 重置统计
            mCapturedCount = 0;
            mSkippedCount = 0;
            updateCaptureStats();
            startAutoCapture();
        } else {
            stopAutoCapture();
        }
    }
    /**
     * 通用拍照方法（用于自动拍照模式）
     */
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
     * 按指定模式拍照并处理文档
     */
    private void captureAndProcessByMode(int mode) {
        if (mImageCapture == null) {
            Log.e(TAG, "ImageCapture is not initialized");
            return;
        }

        String[] modeNames = {"", "", "", "OCR优化", "打印文档", "手写文档", "白板"};
        String name = modeNames[mode] + "_" + new SimpleDateFormat(FILENAME_FORMAT, Locale.getDefault())
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
                        Log.d(TAG, "Document photo saved successfully for mode: " + mode);
                        // 不弹出处理提示，直接处理
                        performDocumentProcessingByMode(documentFile, mode);
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
     * 处理拍照后的图片（用于自动拍照模式）
     */
    private void handleCapturedPhoto(File photoFile) {
        // 如果开启了自动拍照且是自动拍照模式，先进行SSIM相似度检测
        if (mAutoCaptureEnabled && mCurrentMode == CameraMode.AUTO_CAPTURE && shouldSkipProcessingDueToSimilarity(photoFile)) {
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
        if (mAutoCaptureEnabled && mCurrentMode == CameraMode.AUTO_CAPTURE) {
            mCapturedCount++;
            runOnUiThread(this::updateCaptureStats);
        }
        
        // 自动拍照模式下直接进行边缘检测和裁剪，不弹出提示
        performAutoProcessing(photoFile);
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
                    runOnUiThread(() -> {
                        // 自动拍照模式下不显示Toast
                        if (mCurrentMode != CameraMode.AUTO_CAPTURE || !mAutoCaptureEnabled) {
                            Toast.makeText(CameraActivity.this, "加载图片失败", Toast.LENGTH_SHORT).show();
                        }
                    });
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
                            if (mCurrentMode != CameraMode.AUTO_CAPTURE || !mAutoCaptureEnabled) {
                                Toast.makeText(CameraActivity.this, "边缘检测和裁剪完成", Toast.LENGTH_SHORT).show();
                            }
                            // 保存到图库
                            saveToGallery(originalFile);
                            setResult(RESULT_OK);
                            if (mCurrentMode != CameraMode.AUTO_CAPTURE || !mAutoCaptureEnabled) {
                                finish();
                            }
                        });
                    } else {
                        runOnUiThread(() -> {
                            // 自动拍照模式下不显示Toast
                            if (mCurrentMode != CameraMode.AUTO_CAPTURE || !mAutoCaptureEnabled) {
                                Toast.makeText(CameraActivity.this, "裁剪失败，保存原始图片", Toast.LENGTH_SHORT).show();
                            }
                            saveToGallery(originalFile);
                            setResult(RESULT_OK);
                            if (mCurrentMode != CameraMode.AUTO_CAPTURE || !mAutoCaptureEnabled) {
                                finish();
                            }
                        });
                    }
                } else {
                    // 未检测到边缘，保存原始图片
                    runOnUiThread(() -> {
                        // 自动拍照模式下不显示Toast
                        if (mCurrentMode != CameraMode.AUTO_CAPTURE || !mAutoCaptureEnabled) {
                            Toast.makeText(CameraActivity.this, "未检测到边缘，保存原始图片", Toast.LENGTH_SHORT).show();
                        }
                        saveToGallery(originalFile);
                        setResult(RESULT_OK);
                        if (mCurrentMode != CameraMode.AUTO_CAPTURE || !mAutoCaptureEnabled) {
                            finish();
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Auto processing failed", e);
                runOnUiThread(() -> {
                    // 自动拍照模式下不显示Toast
                    if (mCurrentMode != CameraMode.AUTO_CAPTURE || !mAutoCaptureEnabled) {
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 300) {
            // 从手动调整页面返回
            setResult(resultCode);
            // 手动调整模式下完成后不自动关闭
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
            // 使用改进的SSIM相似度计算
            double similarity = calculateSimpleSimilarity(mLastCapturedBitmap, currentBitmap);
            Log.d(TAG, "SSIM similarity: " + String.format("%.3f", similarity) + " (threshold: " + SSIM_THRESHOLD + ")");
            
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
     * 计算两个图片的改进SSIM相似度
     * 基于结构相似性指数的优化算法，结合多种相似度指标
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
        
        // 使用组合相似度算法：70% SSIM + 30% 直方图相似度
        double ssimScore = calculateSSIMGrayscale(bitmap1, bitmap2, width, height);
        double histogramScore = calculateHistogramSimilarity(bitmap1, bitmap2);
        
        // 加权组合，SSIM权重更高因为它更能反映结构相似性
        double combinedScore = 0.7 * ssimScore + 0.3 * histogramScore;
        
        Log.d(TAG, String.format("Similarity breakdown - SSIM: %.3f, Histogram: %.3f, Combined: %.3f", 
                ssimScore, histogramScore, combinedScore));
        
        return combinedScore;
    }
    
    /**
     * 基于灰度值的改进SSIM计算
     * 优化了采样策略和计算精度
     */
    private double calculateSSIMGrayscale(Bitmap bitmap1, Bitmap bitmap2, int width, int height) {
        // 自适应采样步长：小图片密集采样，大图片稀疏采样
        int minDimension = Math.min(width, height);
        int sampleStep;
        if (minDimension <= 100) {
            sampleStep = 1; // 小图片：每个像素都采样
        } else if (minDimension <= 300) {
            sampleStep = 2; // 中等图片：每2个像素采样一次
        } else {
            sampleStep = Math.max(2, minDimension / 150); // 大图片：自适应步长
        }
        
        double sumX = 0, sumY = 0, sumXX = 0, sumYY = 0, sumXY = 0;
        int sampleCount = 0;
        
        // 分块采样，提高局部特征捕获能力
        int blockSize = Math.max(8, sampleStep * 4);
        
        for (int blockX = 0; blockX < width; blockX += blockSize) {
            for (int blockY = 0; blockY < height; blockY += blockSize) {
                // 在每个块内进行采样
                for (int x = blockX; x < Math.min(blockX + blockSize, width); x += sampleStep) {
                    for (int y = blockY; y < Math.min(blockY + blockSize, height); y += sampleStep) {
                        if (x >= bitmap1.getWidth() || y >= bitmap1.getHeight() ||
                            x >= bitmap2.getWidth() || y >= bitmap2.getHeight()) {
                            continue;
                        }
                        
                        // 获取灰度值
                        double gray1 = getGrayscaleValue(bitmap1.getPixel(x, y));
                        double gray2 = getGrayscaleValue(bitmap2.getPixel(x, y));
                        
                        // 累计统计值
                        sumX += gray1;
                        sumY += gray2;
                        sumXX += gray1 * gray1;
                        sumYY += gray2 * gray2;
                        sumXY += gray1 * gray2;
                        sampleCount++;
                    }
                }
            }
        }
        
        if (sampleCount < 10) { // 确保有足够的采样点
            return 0.0;
        }
        
        // 计算均值
        double meanX = sumX / sampleCount;
        double meanY = sumY / sampleCount;
        
        // 计算方差和协方差
        double varX = Math.max(0, (sumXX / sampleCount) - (meanX * meanX));
        double varY = Math.max(0, (sumYY / sampleCount) - (meanY * meanY));
        double covXY = (sumXY / sampleCount) - (meanX * meanY);
        
        // 优化的SSIM常数，适应不同亮度范围
        double k1 = 0.01, k2 = 0.03;
        double L = 255.0; // 像素值范围
        double c1 = (k1 * L) * (k1 * L);
        double c2 = (k2 * L) * (k2 * L);
        
        // 计算SSIM的三个组件
        double luminance = (2 * meanX * meanY + c1) / (meanX * meanX + meanY * meanY + c1);
        double contrast = (2 * Math.sqrt(varX * varY) + c2) / (varX + varY + c2);
        double structure = (covXY + c2/2) / (Math.sqrt(varX * varY) + c2/2);
        
        // 组合SSIM分数
        double ssim = luminance * contrast * structure;
        
        // 确保SSIM值在[0,1]范围内，并应用平滑函数
        ssim = Math.max(0.0, Math.min(1.0, ssim));
        
        // 应用sigmoid平滑，增强区分度
        ssim = 1.0 / (1.0 + Math.exp(-10 * (ssim - 0.5)));
        
        return ssim;
    }
    
    /**
     * 将RGB像素转换为灰度值
     */
    private double getGrayscaleValue(int pixel) {
        int r = (pixel >> 16) & 0xff;
        int g = (pixel >> 8) & 0xff;
        int b = pixel & 0xff;
        
        // 使用标准的灰度转换公式
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }
    
    /**
     * 计算两个图片的优化直方图相似度
     * 使用多种直方图比较方法的组合
     */
    private double calculateHistogramSimilarity(Bitmap bitmap1, Bitmap bitmap2) {
        if (bitmap1 == null || bitmap2 == null) {
            return 0.0;
        }
        
        // 使用更精细的直方图分辨率
        int histSize = 64; // 减少到64个bin以提高鲁棒性
        int[] hist1 = new int[histSize];
        int[] hist2 = new int[histSize];
        
        int width = Math.min(bitmap1.getWidth(), bitmap2.getWidth());
        int height = Math.min(bitmap1.getHeight(), bitmap2.getHeight());
        
        // 自适应采样步长
        int sampleStep = Math.max(1, Math.min(width, height) / 64);
        
        int totalPixels1 = 0, totalPixels2 = 0;
        
        // 构建直方图，使用更均匀的分布
        for (int x = 0; x < width; x += sampleStep) {
            for (int y = 0; y < height; y += sampleStep) {
                if (x < bitmap1.getWidth() && y < bitmap1.getHeight()) {
                    int gray1 = (int) getGrayscaleValue(bitmap1.getPixel(x, y));
                    int bin1 = Math.min(histSize - 1, gray1 * histSize / 256);
                    hist1[bin1]++;
                    totalPixels1++;
                }
                
                if (x < bitmap2.getWidth() && y < bitmap2.getHeight()) {
                    int gray2 = (int) getGrayscaleValue(bitmap2.getPixel(x, y));  
                    int bin2 = Math.min(histSize - 1, gray2 * histSize / 256);
                    hist2[bin2]++;
                    totalPixels2++;
                }
            }
        }
        
        if (totalPixels1 == 0 || totalPixels2 == 0) {
            return 0.0;
        }
        
        // 归一化直方图
        double[] normHist1 = new double[histSize];
        double[] normHist2 = new double[histSize];
        
        for (int i = 0; i < histSize; i++) {
            normHist1[i] = (double) hist1[i] / totalPixels1;
            normHist2[i] = (double) hist2[i] / totalPixels2;
        }
        
        // 计算多种相似性指标的组合
        double bhattacharyya = calculateBhattacharyyaCoefficient(normHist1, normHist2);
        double correlation = calculateHistogramCorrelation(normHist1, normHist2);
        double intersection = calculateHistogramIntersection(normHist1, normHist2);
        
        // 加权组合多种指标：40% Bhattacharyya + 35% Correlation + 25% Intersection
        double combinedSimilarity = 0.4 * bhattacharyya + 0.35 * Math.max(0, correlation) + 0.25 * intersection;
        
        return Math.max(0.0, Math.min(1.0, combinedSimilarity));
    }
    
    /**
     * 计算Bhattacharyya系数
     */
    private double calculateBhattacharyyaCoefficient(double[] hist1, double[] hist2) {
        double coefficient = 0.0;
        for (int i = 0; i < hist1.length; i++) {
            coefficient += Math.sqrt(hist1[i] * hist2[i]);
        }
        return coefficient;
    }
    
    /**
     * 计算直方图相关系数
     */
    private double calculateHistogramCorrelation(double[] hist1, double[] hist2) {
        // 计算均值
        double mean1 = 0, mean2 = 0;
        for (int i = 0; i < hist1.length; i++) {
            mean1 += hist1[i];
            mean2 += hist2[i];
        }
        mean1 /= hist1.length;
        mean2 /= hist2.length;
        
        // 计算协方差和标准差
        double covariance = 0, variance1 = 0, variance2 = 0;
        for (int i = 0; i < hist1.length; i++) {
            double diff1 = hist1[i] - mean1;
            double diff2 = hist2[i] - mean2;
            covariance += diff1 * diff2;
            variance1 += diff1 * diff1;
            variance2 += diff2 * diff2;
        }
        
        double denominator = Math.sqrt(variance1 * variance2);
        if (denominator == 0) {
            return 1.0; // 如果两个直方图都是常数，认为相关性为1
        }
        
        return covariance / denominator;
    }
    
    /**
     * 计算直方图交集
     */
    private double calculateHistogramIntersection(double[] hist1, double[] hist2) {
        double intersection = 0.0;
        for (int i = 0; i < hist1.length; i++) {
            intersection += Math.min(hist1[i], hist2[i]);
        }
        return intersection;
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



    /**
     * 按指定模式执行文档处理
     */
    private void performDocumentProcessingByMode(File originalFile, int mode) {
        mBackgroundHandler.post(() -> {
            try {
                // 加载原始图片
                android.graphics.Bitmap originalBitmap = android.graphics.BitmapFactory.decodeFile(originalFile.getPath());
                if (originalBitmap == null) {
                    runOnUiThread(() -> Toast.makeText(CameraActivity.this, "加载图片失败", Toast.LENGTH_SHORT).show());
                    return;
                }

                String[] modeNames = {"", "", "", "OCR优化", "打印文档", "手写文档", "白板"};
                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "正在执行" + modeNames[mode] + "处理...", Toast.LENGTH_SHORT).show());

                // 进行边缘检测和裁剪
                android.graphics.Point[] detectedPoints = SmartCropper.scan(originalBitmap);
                android.graphics.Bitmap croppedBitmap = originalBitmap;
                
                if (detectedPoints != null && detectedPoints.length == 4) {
                    android.graphics.Bitmap tempCropped = SmartCropper.crop(originalBitmap, detectedPoints);
                    if (tempCropped != null) {
                        croppedBitmap = tempCropped;
                    }
                }

                // 按指定模式处理文档（带质量优化）
                android.graphics.Bitmap processedBitmap = SmartCropper.processDocumentWithQualityOptimization(croppedBitmap, mode);
                
                // 清理中间结果
                if (croppedBitmap != originalBitmap && croppedBitmap != processedBitmap) {
                    croppedBitmap.recycle();
                }

                // 保存最终结果
                saveBitmapToFile(processedBitmap, originalFile);
                processedBitmap.recycle();

                runOnUiThread(() -> {
                    // 只显示“拍照成功”消息
                    Toast.makeText(CameraActivity.this, "拍照成功", Toast.LENGTH_SHORT).show();
                    saveToGallery(originalFile);
                    setResult(RESULT_OK);
                });

            } catch (Exception e) {
                Log.e(TAG, "Document processing failed for mode: " + mode, e);
                runOnUiThread(() -> {
                    Toast.makeText(CameraActivity.this, "文档处理失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    saveToGallery(originalFile);
                });
            }
        });
    }
}