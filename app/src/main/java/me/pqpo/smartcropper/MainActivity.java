package me.pqpo.smartcropper;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.List;

import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity implements EasyPermissions.PermissionCallbacks {

    private static final int REQUEST_STORAGE_PERMISSION = 1002;
    private static final int REQUEST_CAMERA_ACTIVITY = 100;
    private static final int REQUEST_CROP_ACTIVITY = 101;

    Button btnTake;
    Button btnSelect;

    File photoFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        btnTake = (Button) findViewById(R.id.btn_take);
        btnSelect = (Button) findViewById(R.id.btn_select);

        photoFile = new File(getExternalFilesDir("img"), "scan.jpg");

        btnTake.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, CameraActivity.class);
                intent.putExtra("output_file", photoFile);
                startActivityForResult(intent, REQUEST_CAMERA_ACTIVITY);
            }
        });

        btnSelect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkStoragePermissionAndSelectFromGallery();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 删除预览功能，直接完成操作
    }
    
    /**
     * 检查存储权限并从图库选择图片
     */
    private void checkStoragePermissionAndSelectFromGallery() {
        String[] permissions;
        
        // 根据Android版本选择合适的权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用新的媒体权限
            permissions = new String[]{
                Manifest.permission.READ_MEDIA_IMAGES
            };
        } else {
            // Android 13以下使用传统存储权限
            permissions = new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }
        
        if (EasyPermissions.hasPermissions(this, permissions)) {
            // 已有权限，直接选择图片
            selectFromGallery();
        } else {
            // 申请权限
            EasyPermissions.requestPermissions(
                this,
                "需要访问图库权限来选择图片",
                REQUEST_STORAGE_PERMISSION,
                permissions
            );
        }
    }
    
    /**
     * 从图库选择图片
     */
    private void selectFromGallery() {
        startActivityForResult(CropActivity.getJumpIntent(MainActivity.this, true, photoFile), REQUEST_CROP_ACTIVITY);
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }
    
    @Override
    public void onPermissionsGranted(int requestCode, List<String> perms) {
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            // 权限获取成功，选择图片
            selectFromGallery();
        }
    }
    
    @Override
    public void onPermissionsDenied(int requestCode, List<String> perms) {
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            Toast.makeText(this, "需要图库访问权限才能选择图片", Toast.LENGTH_LONG).show();
        }
    }
}
