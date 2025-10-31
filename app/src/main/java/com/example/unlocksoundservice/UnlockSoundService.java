package com.example.unlocksoundservice;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri; // NEW: 新增导入
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

public class UnlockSoundService extends Service {

    private static final String CHANNEL_ID = "UnlockSoundServiceChannel";
    private static final String TAG = "UnlockSoundService";

    public static volatile boolean isRunning = false;
    private BroadcastReceiver unlockReceiver;
    private MediaPlayer mediaPlayer;
    private AudioManager audioManager;

    private UsageStatsManager usageStatsManager;
    private Set<String> launcherPackageNames;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true; // 服务创建时，设置状态为 true
        createNotificationChannel();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        }
        launcherPackageNames = getLauncherPackageNames();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service started");

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("解锁声音服务已激活")
                .setContentText("正在监听设备解锁...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
        startForeground(1, notification);

        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction() != null && intent.getAction().equals(Intent.ACTION_USER_PRESENT)) {

                    // 这段逻辑链未更改
                    SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);

                    // 1. 检查“仅耳机”设置
                    boolean headphoneOnly = prefs.getBoolean(MainActivity.KEY_HEADPHONE_ONLY, false);
                    if (headphoneOnly && !isHeadphonesConnected()) {
                        Log.d(TAG, "解锁。条件失败：仅耳机模式开启，但未连接耳机。");
                        return;
                    }

                    // 2. 检查“无其他音频”设置
                    boolean noOtherAudio = prefs.getBoolean(MainActivity.KEY_NO_OTHER_AUDIO, false);
                    if (noOtherAudio && isOtherAudioPlaying()) {
                        Log.d(TAG, "解锁。条件失败：“无其他音频”模式开启，但有音频在播放。");
                        return;
                    }

                    // 3. 检查“仅桌面”设置
                    boolean desktopOnly = prefs.getBoolean(MainActivity.KEY_DESKTOP_ONLY, false);
                    if (desktopOnly && !isUserOnHomeScreen()) {
                        Log.d(TAG, "解锁。条件失败：“仅桌面”模式开启，但用户不在桌面上。");
                        return;
                    }

                    Log.d(TAG, "解锁。所有条件满足。播放声音。");
                    playSound();
                }
            }
        };

        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        registerReceiver(unlockReceiver, filter);

        return START_STICKY;
    }

    /**
     * 此方法从保存的 URI 播放
     */
    private void playSound() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }

        // NEW: 从 SharedPreferences 加载 URI
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        String soundUriString = prefs.getString(MainActivity.KEY_SOUND_URI, null);

        if (soundUriString == null) {
            Log.e(TAG, "没有选择声音文件。无法播放。");
            return; // 如果没有选择文件，则不执行任何操作
        }

        Uri soundUri = Uri.parse(soundUriString);

        try {
            // 这是创建播放器的新方法
            mediaPlayer = MediaPlayer.create(this, soundUri);

            if (mediaPlayer == null) {
                Log.e(TAG, "创建 MediaPlayer 失败，URI 可能无效或权限丢失。");
                return;
            }

            mediaPlayer.setOnCompletionListener(mp -> {
                mp.release();
                mediaPlayer = null;
            });
            mediaPlayer.start();

        } catch (Exception e) {
            // 这至关重要。如果用户删除了文件，我们必须捕获错误
            // 并且不能让服务崩溃。
            Log.e(TAG, "从 URI 播放声音时出错。文件可能已被删除或无法访问。");
            e.printStackTrace();
            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }
        }
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false; // NEW: 服务销毁时，设置状态为 false
        Log.d(TAG, "Service destroyed");
        if (unlockReceiver != null) {
            unregisterReceiver(unlockReceiver);
        }
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private boolean isOtherAudioPlaying() {
        if (audioManager == null) return false;
        return audioManager.isMusicActive();
    }

    private boolean isUserOnHomeScreen() {
        if (!isUsageStatsPermissionGranted()) {
            Log.w(TAG, "无法检查桌面：未授予“使用情况访问”权限。");
            return false;
        }
        if (usageStatsManager == null || launcherPackageNames == null || launcherPackageNames.isEmpty()) {
            return false;
        }
        String foregroundApp = getCurrentForegroundApp();
        if (foregroundApp != null) {
            Log.d(TAG, "当前前台应用: " + foregroundApp);
            return launcherPackageNames.contains(foregroundApp);
        }
        return false;
    }

    private String getCurrentForegroundApp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1 || usageStatsManager == null) {
            return null;
        }
        String topPackageName = null;
        long time = System.currentTimeMillis();
        List<UsageStats> stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, time - 1000 * 10, time);
        if (stats != null) {
            SortedMap<Long, UsageStats> mySortedMap = new TreeMap<>();
            for (UsageStats usageStats : stats) {
                mySortedMap.put(usageStats.getLastTimeUsed(), usageStats);
            }
            if (!mySortedMap.isEmpty()) {
                topPackageName = mySortedMap.get(mySortedMap.lastKey()).getPackageName();
            }
        }
        return topPackageName;
    }

    private Set<String> getLauncherPackageNames() {
        Set<String> packages = new HashSet<>();
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> activities = pm.queryIntentActivities(intent, 0);
        for (ResolveInfo info : activities) {
            packages.add(info.activityInfo.packageName);
        }
        Log.d(TAG, "找到的启动器: " + packages);
        return packages;
    }

    private boolean isUsageStatsPermissionGranted() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private boolean isHeadphonesConnected() {
        if (audioManager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device : devices) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    return true;
                }
            }
            return false;
        } else {
            //noinspection deprecation
            return audioManager.isWiredHeadsetOn() ||
                    audioManager.isBluetoothA2dpOn() ||
                    audioManager.isBluetoothScoOn();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Unlock Sound Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}