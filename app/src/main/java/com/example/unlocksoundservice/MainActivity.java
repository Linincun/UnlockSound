package com.example.unlocksoundservice;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor; // NEW: 新增导入
import android.net.Uri; // NEW: 新增导入
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns; // NEW: 新增导入
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView; // NEW: 新增导入
import android.widget.Toast;
import androidx.core.content.ContextCompat; // NEW: 导入 ContextCompat
import android.widget.TextView;
import com.google.android.material.switchmaterial.SwitchMaterial;

public class MainActivity extends AppCompatActivity {

    private Button btnStart, btnStop, btnGrantUsagePermission, btnSelectSound;
    private SwitchMaterial switchHeadphonesOnly, switchDesktopOnly, switchNoOtherAudio;
    private TextView tvSelectedFile;
    private TextView tvServiceStatus;

    public static final String PREFS_NAME = "UnlockPrefs";
    public static final String KEY_HEADPHONE_ONLY = "headphoneOnly";
    public static final String KEY_DESKTOP_ONLY = "desktopOnly";
    public static final String KEY_NO_OTHER_AUDIO = "noOtherAudio";
    public static final String KEY_SOUND_URI = "soundUri"; // 用于保存声音 URI 的键

    // 文件选择器的启动器
    private ActivityResultLauncher<String[]> openFileLauncher;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Toast.makeText(this, "权限已获取！请再次点击 '启动'", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "服务运行需要通知权限", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 找到所有视图
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnGrantUsagePermission = findViewById(R.id.btnGrantUsagePermission);
        switchHeadphonesOnly = findViewById(R.id.switchHeadphonesOnly);
        switchDesktopOnly = findViewById(R.id.switchDesktopOnly);
        switchNoOtherAudio = findViewById(R.id.switchNoOtherAudio);
        btnSelectSound = findViewById(R.id.btnSelectSound);
        tvSelectedFile = findViewById(R.id.tvSelectedFile);
        tvServiceStatus = findViewById(R.id.tvServiceStatus);

        // 加载所有已保存的设置
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        switchHeadphonesOnly.setChecked(prefs.getBoolean(KEY_HEADPHONE_ONLY, false));
        switchDesktopOnly.setChecked(prefs.getBoolean(KEY_DESKTOP_ONLY, false));
        switchNoOtherAudio.setChecked(prefs.getBoolean(KEY_NO_OTHER_AUDIO, false));

        // 加载并显示已保存的文件名
        String savedUriString = prefs.getString(KEY_SOUND_URI, null);
        if (savedUriString != null) {
            updateSelectedFileText(Uri.parse(savedUriString));
        } else {
            updateSelectedFileText(null);
        }

        // --- 为所有开关设置监听器 ---
        switchHeadphonesOnly.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(KEY_HEADPHONE_ONLY, isChecked).apply();
        });
        switchDesktopOnly.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(KEY_DESKTOP_ONLY, isChecked).apply();
            checkUsagePermission();
        });
        switchNoOtherAudio.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(KEY_NO_OTHER_AUDIO, isChecked).apply();
        });

        // --- 为所有按钮设置监听器 ---
        btnStart.setOnClickListener(v -> {
            startServiceWithPermissionCheck();
            // startService 是异步的，可以乐观地更新UI
            // 稍后的 onResume() 会再次同步真实状态
            tvServiceStatus.setText("服务已启动");
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_running));
        });

        btnStop.setOnClickListener(v -> {
            stopUnlockService();
            // stopService 也会触发 onDestroy，从而更新 isRunning 变量
            tvServiceStatus.setText("服务已停止");
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_stopped));
        });

        btnGrantUsagePermission.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "请找到本应用并授予权限。", Toast.LENGTH_LONG).show();
        });

        // 注册文件选择器启动器
        openFileLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) {
                // 获取持久化权限，以便服务以后可以读取此文件
                try {
                    final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    getContentResolver().takePersistableUriPermission(uri, takeFlags);

                    // 保存 URI 字符串
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putString(KEY_SOUND_URI, uri.toString())
                            .apply();

                    // 更新 UI
                    updateSelectedFileText(uri);
                    Toast.makeText(this, "文件已选择！", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "无法获取此文件的权限。", Toast.LENGTH_LONG).show();
                }
            }
        });

        // 为选择声音按钮设置监听器
        btnSelectSound.setOnClickListener(v -> {
            // 启动文件选择器，仅显示音频文件
            openFileLauncher.launch(new String[]{"audio/*"});
        });


        // 初始检查权限按钮
        checkUsagePermission();
        // 初始加载时更新服务状态UI
        updateServiceStatusUI();
    }

    /**
     * NEW: 新的辅助方法 - 从 content URI 获取文件名
     */
    private void updateSelectedFileText(Uri uri) {
        if (uri == null) {
            tvSelectedFile.setText("已选文件: 无");
            return;
        }
        // 使用 ContentResolver 获取文件名
        Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
        String fileName = "未知文件";
        if (cursor != null && cursor.moveToFirst()) {
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameIndex != -1) {
                fileName = cursor.getString(nameIndex);
            }
            cursor.close();
        }
        tvSelectedFile.setText("已选文件: " + fileName);
    }

    // 更新服务状态的 UI
    private void updateServiceStatusUI() {
        if (UnlockSoundService.isRunning) {
            tvServiceStatus.setText("服务运行中");
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_running));
        } else {
            tvServiceStatus.setText("服务已停止");
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_stopped));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次用户返回应用时，都检查权限和真实的服务状态
        checkUsagePermission();
        updateServiceStatusUI(); // 在 onResume 中更新
    }

    private void checkUsagePermission() {
        if (switchDesktopOnly.isChecked()) {
            if (!isUsageStatsPermissionGranted()) {
                btnGrantUsagePermission.setVisibility(View.VISIBLE);
            } else {
                btnGrantUsagePermission.setVisibility(View.GONE);
            }
        } else {
            btnGrantUsagePermission.setVisibility(View.GONE);
        }
    }

    private boolean isUsageStatsPermissionGranted() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void startServiceWithPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED) {
                startUnlockService();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            startUnlockService();
        }
    }

    private void startUnlockService() {
        Intent serviceIntent = new Intent(this, UnlockSoundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show();
    }

    private void stopUnlockService() {
        Intent serviceIntent = new Intent(this, UnlockSoundService.class);
        stopService(serviceIntent);
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
    }
}