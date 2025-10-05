package me.pqpo.smartcropper;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import me.pqpo.smartcropperlib.SmartCropper;
import me.pqpo.smartcropperlib.view.CropImageView;
import pub.devrel.easypermissions.EasyPermissions;

public class CropActivity extends AppCompatActivity implements EasyPermissions.PermissionCallbacks{

    private static final String EXTRA_FROM_ALBUM = "extra_from_album";
    private static final String EXTRA_CROPPED_FILE = "extra_cropped_file";
    private static final String EXTRA_USE_EXISTING_FILE = "extra_use_existing_file";
    private static final int REQUEST_CODE_TAKE_PHOTO = 100;
    private static final int REQUEST_CODE_SELECT_ALBUM = 200;

    CropImageView ivCrop;
    Button btnCancel;
    Button btnOk;

    boolean mFromAlbum;
    boolean mUseExistingFile;
    File mCroppedFile;

    File tempFile;

    public static Intent getJumpIntent(Context context, boolean fromAlbum, File croppedFile) {
        Intent intent = new Intent(context, CropActivity.class);
        intent.putExtra(EXTRA_FROM_ALBUM, fromAlbum);
        intent.putExtra(EXTRA_CROPPED_FILE, croppedFile);
        return intent;
    }
    
    /**
     * 获取跳转到裁剪页面的Intent（使用已存在的文件）
     * @param context 上下文
     * @param existingFile 已存在的图片文件
     * @param croppedFile 裁剪后保存的文件
     * @return Intent
     */
    public static Intent getJumpIntentForExistingFile(Context context, File existingFile, File croppedFile) {
        Intent intent = new Intent(context, CropActivity.class);
        intent.putExtra(EXTRA_FROM_ALBUM, false);
        intent.putExtra(EXTRA_CROPPED_FILE, croppedFile);
        intent.putExtra(EXTRA_USE_EXISTING_FILE, true);
        intent.putExtra("existing_file", existingFile);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);
        ivCrop = (CropImageView) findViewById(R.id.iv_crop);
        btnCancel = (Button) findViewById(R.id.btn_cancel);
        btnOk = (Button) findViewById(R.id.btn_ok);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });
        btnOk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                if (ivCrop.canRightCrop()) {
                    Bitmap crop = ivCrop.crop();
                    if (crop != null) {
                        saveImage(crop, mCroppedFile);
                        Toast.makeText(CropActivity.this, "拍照成功", Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                    } else {
                        Toast.makeText(CropActivity.this, "裁剪失败", Toast.LENGTH_SHORT).show();
                        setResult(RESULT_CANCELED);
                    }
                    finish();
                } else {
                    Toast.makeText(CropActivity.this, "裁剪区域不合法，请重新调整", Toast.LENGTH_SHORT).show();
                }
            }
        });
        mFromAlbum = getIntent().getBooleanExtra(EXTRA_FROM_ALBUM, true);
        mUseExistingFile = getIntent().getBooleanExtra(EXTRA_USE_EXISTING_FILE, false);
        mCroppedFile = (File) getIntent().getSerializableExtra(EXTRA_CROPPED_FILE);
        if (mCroppedFile == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        tempFile = new File(getExternalFilesDir("img"), "temp.jpg");
        
        // 如果使用已存在的文件，直接加载
        if (mUseExistingFile) {
            File existingFile = (File) getIntent().getSerializableExtra("existing_file");
            if (existingFile != null && existingFile.exists()) {
                loadExistingFile(existingFile);
                return;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            EasyPermissions.requestPermissions(
                    CropActivity.this,
                    "申请权限",
                    0,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA);
        }else{
            selectPhoto();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Forward results to EasyPermissions
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override
    public void onPermissionsGranted(int requestCode, List<String> list) {
        // Some permissions have been granted
        // ...
        selectPhoto();
    }

    @Override
    public void onPermissionsDenied(int requestCode, List<String> list) {
        // Some permissions have been denied
        // ...
    }

    /**
     * 加载已存在的图片文件
     */
    private void loadExistingFile(File existingFile) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(existingFile.getAbsolutePath(), options);
            options.inJustDecodeBounds = false;
            options.inSampleSize = calculateSampleSize(options);
            Bitmap selectedBitmap = BitmapFactory.decodeFile(existingFile.getAbsolutePath(), options);
            
            if (selectedBitmap != null) {
                ivCrop.setImageToCrop(selectedBitmap);
            } else {
                Toast.makeText(this, "加载图片失败", Toast.LENGTH_SHORT).show();
                setResult(RESULT_CANCELED);
                finish();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "加载图片失败", Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private void selectPhoto() {
        if (mFromAlbum) {
            // 使用更具体的Intent来选择图片，确保关联到系统图库
            Intent selectIntent = new Intent(Intent.ACTION_PICK);
            selectIntent.setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
            
            // 备用方案：使用ACTION_GET_CONTENT
            if (selectIntent.resolveActivity(getPackageManager()) == null) {
                selectIntent = new Intent(Intent.ACTION_GET_CONTENT);
                selectIntent.setType("image/*");
                selectIntent.addCategory(Intent.CATEGORY_OPENABLE);
            }
            
            if (selectIntent.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(Intent.createChooser(selectIntent, "选择图片"), REQUEST_CODE_SELECT_ALBUM);
            } else {
                Toast.makeText(this, "未找到图片选择器", Toast.LENGTH_SHORT).show();
                setResult(RESULT_CANCELED);
                finish();
            }
        } else {
            Intent startCameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(this, "me.pqpo.smartcropper.fileProvider", tempFile);
            } else {
                uri = Uri.fromFile(tempFile);
            }
            startCameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            if (startCameraIntent.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(startCameraIntent, REQUEST_CODE_TAKE_PHOTO);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return super.onTouchEvent(event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        Bitmap selectedBitmap = null;
        if (requestCode == REQUEST_CODE_TAKE_PHOTO && tempFile.exists()) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tempFile.getPath(), options);
            options.inJustDecodeBounds = false;
            options.inSampleSize = calculateSampleSize(options);
            selectedBitmap = BitmapFactory.decodeFile(tempFile.getPath(), options);
        } else if (requestCode == REQUEST_CODE_SELECT_ALBUM && data != null && data.getData() != null) {
            ContentResolver cr = getContentResolver();
            Uri bmpUri = data.getData();
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(cr.openInputStream(bmpUri), new Rect(), options);
                options.inJustDecodeBounds = false;
                options.inSampleSize = calculateSampleSize(options);
                selectedBitmap = BitmapFactory.decodeStream(cr.openInputStream(bmpUri), new Rect(), options);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
        if (selectedBitmap != null) {
            ivCrop.setImageToCrop(selectedBitmap);
        }
    }


    private void saveImage(Bitmap bitmap, File saveFile) {
        try {
            FileOutputStream fos = new FileOutputStream(saveFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            fos.flush();
            fos.close();
            
            // 保存到图库
            saveToGallery(saveFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 保存图片到系统图库（双重保障机制）
     */
    private void saveToGallery(File file) {
        try {
            // 确保文件存在且不为空
            if (!file.exists() || file.length() == 0) {
                return;
            }
            
            // 主方法：使用MediaScannerConnection扫描文件
            android.media.MediaScannerConnection.scanFile(this,
                    new String[]{file.getAbsolutePath()},
                    new String[]{"image/jpeg"},
                    new android.media.MediaScannerConnection.OnScanCompletedListener() {
                        @Override
                        public void onScanCompleted(String path, android.net.Uri uri) {
                            // 主方法成功
                        }
                    });
            
            // 备用方法：直接插入MediaStore并发送广播
            insertImageToMediaStore(file);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 备用方法：直接将文件插入到MediaStore
     */
    private void insertImageToMediaStore(File file) {
        try {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Images.Media.TITLE, "SmartCropper_" + System.currentTimeMillis());
            values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, file.getName());
            values.put(android.provider.MediaStore.Images.Media.DESCRIPTION, "Photo processed by SmartCropper");
            values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(android.provider.MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000);
            values.put(android.provider.MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
            values.put(android.provider.MediaStore.Images.Media.SIZE, file.length());
            
            // 对于Android 10以下版本，使用DATA字段
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                values.put(android.provider.MediaStore.Images.Media.DATA, file.getAbsolutePath());
            }
            
            android.content.ContentResolver resolver = getContentResolver();
            android.net.Uri uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            
            if (uri != null) {
                // 对于Android 10及以上版本，需要复制文件内容
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    copyFileToMediaStore(file, uri);
                }
                
                // 发送广播通知系统媒体库更新
                Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                mediaScanIntent.setData(android.net.Uri.fromFile(file));
                sendBroadcast(mediaScanIntent);
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 复制文件内容到MediaStore URI（Android 10+）
     */
    private void copyFileToMediaStore(File sourceFile, android.net.Uri targetUri) {
        try (java.io.FileOutputStream out = (java.io.FileOutputStream) getContentResolver().openOutputStream(targetUri);
             java.io.FileInputStream in = new java.io.FileInputStream(sourceFile)) {
            
            if (out != null && in != null) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private int calculateSampleSize(BitmapFactory.Options options) {
        int outHeight = options.outHeight;
        int outWidth = options.outWidth;
        int sampleSize = 1;
        int destHeight = 1000;
        int destWidth = 1000;
        if (outHeight > destHeight || outWidth > destHeight) {
            if (outHeight > outWidth) {
                sampleSize = outHeight / destHeight;
            } else {
                sampleSize = outWidth / destWidth;
            }
        }
        if (sampleSize < 1) {
            sampleSize = 1;
        }
        return sampleSize;
    }
}
