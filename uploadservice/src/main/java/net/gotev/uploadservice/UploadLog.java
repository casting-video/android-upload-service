package net.gotev.uploadservice;

import java.util.LinkedList;
import java.util.Date;

public class UploadLog {
    public enum Status { WAITING, UPLOADING, COMPLETED, ERROR, CANCELLED };

    public class Task {
        public String uploadId;
        public long totalBytes = 0;
        public long uploadedBytes = 0;
        public long fileRangeStart = 0;
        public long runTime = 0;
        public Status status = Status.WAITING;
    }

    protected final LinkedList<Task> tasks = new LinkedList<Task>();

    public synchronized void addOrUpdateTask(UploadTask uploadTask, Status status) {
        Task taskToUpdate = null;
        for (Task logTask : tasks) {
            if (logTask.uploadId == uploadTask.params.id) {
                taskToUpdate = logTask;
                break;
            }
        }
        if (taskToUpdate == null) {
            taskToUpdate = new Task();
            taskToUpdate.uploadId = uploadTask.params.id;
            tasks.add(taskToUpdate);
        }
        if (taskToUpdate.status == Status.WAITING || taskToUpdate.status == Status.UPLOADING) {
            taskToUpdate.totalBytes = uploadTask.totalBytes;
            taskToUpdate.uploadedBytes = uploadTask.uploadedBytes;
            if (uploadTask instanceof  BinaryUploadTask) {
                taskToUpdate.fileRangeStart = ((BinaryUploadTask) uploadTask).getFileRangeStart();
            }
            taskToUpdate.runTime = (new Date()).getTime() - uploadTask.startTime;
            taskToUpdate.status = status;
        }
    }

    public synchronized void clear() {
        tasks.clear();
    }

    public synchronized LinkedList<Task> getTasks() {
        return (LinkedList<Task>) tasks.clone();
    }

}
