package net.gotev.uploadservice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

public class SingleNotification {
    // TODO annotations

    abstract public static class Delegate {
        abstract public boolean build(Notification.Builder builder, UploadLog uploadLog);
        abstract public void buildChannel(NotificationChannel channel, boolean isCompletion);

        public boolean shouldResetStats(UploadLog uploadLog) {
            return !uploadLog.hasRunningTasks();
        }
    }

    public enum UploadStatus { WAITING, UPLOADING, COMPLETED, ERROR, CANCELLED };

    public class UploadLog {

        public class Task {
            public String uploadId;
            public long totalBytes = 0;
            public long uploadedBytes = 0;
            public long fileRangeStart = 0;
            public long runTime = 0;  // milliseconds
            public long speed = 0;  // bytes/sec
            public UploadStatus status = UploadStatus.WAITING;
        }

        public class Stats {
            public long totalBytes = 0;
            public long uploadedBytes = 0;
            public int filesUploading = 0;
            public int filesCompleted = 0;
            public int filesWaiting = 0;
            public int filesFailed = 0;
            public int filesCancelled = 0;
            public long totalSpeed = 0;  // bytes/sec
        }

        protected final LinkedList<Task> tasks = new LinkedList<Task>();

        protected boolean addOrUpdateTask(UploadTask uploadTask, UploadStatus status) {
            Task taskToUpdate = null;
            for (Task logTask : tasks) {
                if (logTask.uploadId == uploadTask.params.id) {
                    taskToUpdate = logTask;
                    break;
                }
            }
            boolean forceNotificationUpdate = taskToUpdate == null || status != taskToUpdate.status;
            if (taskToUpdate == null) {
                taskToUpdate = new Task();
                taskToUpdate.uploadId = uploadTask.params.id;
                tasks.add(taskToUpdate);
            }
            taskToUpdate.status = status;
            if (status == UploadStatus.WAITING || status == UploadStatus.UPLOADING) {
                taskToUpdate.totalBytes = uploadTask.totalBytes;
                taskToUpdate.uploadedBytes = uploadTask.uploadedBytes;
                if (uploadTask instanceof BinaryUploadTask) {
                    taskToUpdate.fileRangeStart = ((BinaryUploadTask) uploadTask).getFileRangeStart();
                }
                taskToUpdate.runTime = status == UploadStatus.UPLOADING ?
                    (new Date()).getTime() - uploadTask.startTime : 0;
                taskToUpdate.speed = taskToUpdate.runTime > 0 ?
                    taskToUpdate.uploadedBytes * 1000 / taskToUpdate.runTime : 0;
            }
            return forceNotificationUpdate;
        }

        protected void clear() {
            tasks.clear();
        }

        public LinkedList<Task> getTasks() {
            return (LinkedList<Task>) tasks.clone();
        }

        public Stats getStats() {
            Stats result = new Stats();
            for (Task task : tasks) {
                if (task.status == UploadStatus.COMPLETED
                    || task.status == UploadStatus.UPLOADING
                    || task.status == UploadStatus.WAITING) {
                    result.totalBytes += task.totalBytes + task.fileRangeStart;
                    result.uploadedBytes += task.uploadedBytes + task.fileRangeStart;
                }
                if (task.status == UploadStatus.COMPLETED) result.filesCompleted++;
                else if (task.status == UploadStatus.UPLOADING) result.filesUploading++;
                else if (task.status == UploadStatus.WAITING) result.filesWaiting++;
                else if (task.status == UploadStatus.ERROR) result.filesFailed++;
                else if (task.status == UploadStatus.CANCELLED) result.filesCancelled++;
                if (task.status == UploadStatus.UPLOADING) {
                    result.totalSpeed += task.speed;
                }
            }
            return result;
        }

        public boolean hasRunningTasks() {
            for (Task task : tasks) {
                if (task.status == UploadStatus.UPLOADING || task.status == UploadStatus.WAITING) {
                    return true;
                }
            }
            return false;
        }
    }

    private final UploadService service;
    private final Delegate delegate;
    private final UploadLog uploadLog = new UploadLog();
    private NotificationManager notificationManager = null;
    private long lastUpdateTime = 0;

    private static final int progressNotificationId = 1498;
    private static final int completeNotificationId = 1499;
    private static final int updateInterval = 1000;  // in milliseconds

    // Android O notification channels. Two channels are used, #1 for in-progress notifications (silent by default), and #2 for
    // сompletion notifications (with sound by default).
    private static final String channelId1 = UploadService.NAMESPACE + ".channelProgress";
    private static final String channelId2 = UploadService.NAMESPACE + ".channelCompletion";
    private static final String channelName1 = UploadService.NAMESPACE + " (progress)";
    private static final String channelName2 = UploadService.NAMESPACE + " (completion)";

    protected SingleNotification(UploadService service, Delegate delegate) {
        this.service = service;
        this.delegate = delegate;
        notificationManager = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);

        // Create Android O notification channels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 1. channel for in-progress notifications
            if (notificationManager.getNotificationChannel(channelId1) == null) {
                NotificationChannel channel = new NotificationChannel(channelId1, channelName1, NotificationManager.IMPORTANCE_LOW);
                // call to the delegate which can set more settings
                delegate.buildChannel(channel, false);
                notificationManager.createNotificationChannel(channel);
            }

            // 2. channel for completion notifications (with sound)
            if (notificationManager.getNotificationChannel(channelId2) == null) {
                NotificationChannel channel = new NotificationChannel(channelId2, channelName2, NotificationManager.IMPORTANCE_DEFAULT);
                // sound
                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build();
                channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audioAttributes);
                // call to the delegate
                delegate.buildChannel(channel, true);
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    protected synchronized void update(UploadTask uploadTask, UploadStatus status) {
        boolean forceNotificationUpdate = uploadLog.addOrUpdateTask(uploadTask, status);
        boolean inProgress = uploadLog.hasRunningTasks();

        // update the notification not often than once in updateInterval, or when there was an important change
        long currentTime = (new Date()).getTime();
        if (forceNotificationUpdate || currentTime - lastUpdateTime >= updateInterval) {
            Notification notification = build();
            if (notification != null) {
                notificationManager.notify(
                    inProgress ? progressNotificationId : completeNotificationId,
                    notification);
            } else {
                service.stopForeground(true);
            }
            lastUpdateTime = currentTime;
        }

        if (delegate.shouldResetStats(uploadLog)) {
            uploadLog.clear();
        }
    }

    protected synchronized Notification build() {
        UploadLog.Stats stats = uploadLog.getStats();
        boolean inProgress = uploadLog.hasRunningTasks();
        boolean completionNotification = false;

        Notification.Builder builder = new Notification.Builder(service)
            .setSmallIcon(inProgress ? android.R.drawable.stat_sys_upload : android.R.drawable.stat_sys_upload_done)
            .setOngoing(inProgress);

        if (stats.filesUploading > 0) {
            int filesTotal = stats.filesCompleted + stats.filesWaiting + stats.filesUploading;
            builder.setContentTitle("Uploading " + filesTotal + " file(s)");
            if (stats.totalBytes > 0) {
                builder.setContentText(
                    String.format(Locale.US,
                        "%,.2f MB/sec",
                        (double) stats.totalSpeed / 1048576)
                );
                builder.setProgress(1000,
                    (int) ((double) stats.uploadedBytes / stats.totalBytes * 1000), false);
            } else {
                builder.setProgress(1000, 0, true);
            }

        } else if (stats.filesWaiting > 0) {
            builder.setContentTitle("Waiting for network connection");
            builder.setProgress(1000, 0, true);

        } else if (stats.filesCompleted > 0) {
            builder.setContentTitle("Upload finished");
            builder.setContentText(stats.filesCompleted + " file(s) uploaded, " + stats.filesFailed + " file(s) failed, " +
                stats.filesCancelled + " file(s) cancelled");

            // Sound (only for Android < O). For Android O, the sound is specified in the notification channel.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    // AudioAttributes are supported from API 21
                    AudioAttributes audioAttributes = new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build();
                    builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audioAttributes);
                } else {
                    builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
                }
            }

            completionNotification = true;
        }

        // Android O notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(completionNotification ? channelId2 : channelId1);
        }

        // call the delegate to modify default notification settings
        boolean show = delegate.build(builder, uploadLog);

        return show ? builder.build() : null;
    }

    protected synchronized void startForeground() {
        service.startForeground(progressNotificationId, build());
    }
}
