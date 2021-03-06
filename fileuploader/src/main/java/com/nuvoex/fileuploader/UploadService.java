package com.nuvoex.fileuploader;

import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;

import com.firebase.jobdispatcher.JobParameters;
import com.firebase.jobdispatcher.JobService;
import com.nuvoex.fileuploader.network.ApiManager;
import com.nuvoex.fileuploader.network.ApiService;
import com.nuvoex.fileuploader.utils.Constants;
import com.nuvoex.fileuploader.utils.JobList;
import com.nuvoex.fileuploader.utils.Logger;

import java.io.File;
import java.util.HashMap;
import java.util.Set;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created by dilip on 04/01/17.
 */

/**
 * Service that uploads files in the background and sends updates using broadcasts.
 */
public class UploadService extends JobService {

    private int mRemainingFiles = 0;
    private int mPendingUploads = 0;
    private FileWorkerThread mFileWorkerThread;

    /**
     * Called when the upload job starts.
     * @param jobParameters
     * @return {@code true} if there are files to upload, {@code false} otherwise.
     */
    @Override
    public boolean onStartJob(JobParameters jobParameters) {
        Logger.v("Job started");

        mFileWorkerThread = new FileWorkerThread();
        mFileWorkerThread.start();

        JobList jobList = JobList.getJobList(this);
        Set<String> uploadIds = jobList.getKeys();

        mRemainingFiles = uploadIds.size();
        mPendingUploads = mRemainingFiles;

        if (mRemainingFiles == 0) {
            Logger.v("Nothing to upload");
            return false; //nothing to upload, all done
        }

        Logger.v(mRemainingFiles + " files to upload");

        for (String uploadId : uploadIds) {
            UploadInfo uploadInfo = jobList.get(uploadId);
            uploadFile(jobParameters, uploadInfo);
        }
        return true; //still doing work
    }

    /**
     * Called when the job has been interrupted.
     * @param jobParameters
     * @return {@code true} if there are more files to be uploaded, {@code false} otherwise.
     */
    @Override
    public boolean onStopJob(JobParameters jobParameters) {
        boolean needsReschedule = (mPendingUploads > 0);
        Logger.v("Job stopped. Needs reschedule: " + needsReschedule);
        return needsReschedule;
    }

    @Override
    public void onDestroy() {
        quitWorkerThread();
        super.onDestroy();
    }

    private void quitWorkerThread() {
        if (mFileWorkerThread != null && mFileWorkerThread.isAlive())
            mFileWorkerThread.quit();
    }

    private void uploadFile(final JobParameters jobParameters, final UploadInfo uploadInfo) {
        final String filePath = uploadInfo.getFilePath();
        final String uploadUrl = uploadInfo.getUploadUrl();

        File file = new File(filePath);
        Logger.v("Uploading " + filePath + " (" + file.length() + " bytes)");

        if (!file.exists()) {
            Logger.v("Error: File not found");
            mPendingUploads--;
            mRemainingFiles--;
            JobList jobList = JobList.getJobList(this);
            jobList.remove(uploadInfo.getUploadId());
            jobList.commit();
            sendStatusBroadcast(Constants.Status.CANCELLED, uploadInfo);
            checkCompletion(jobParameters);
            return;
        }

        RequestBody body = RequestBody.create(MediaType.parse(""), file);

        ApiService service = ApiManager.getApiService();
        Call<ResponseBody> call = service.uploadFile(uploadUrl, body);
        call.enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                Logger.v(filePath);
                Logger.v(uploadUrl);
                Logger.v("Status: " + response.code());
                if (response.isSuccessful()) {
                    Logger.v("Success");
                    mPendingUploads--;
                    JobList jobList = JobList.getJobList(UploadService.this);
                    jobList.remove(uploadInfo.getUploadId());
                    jobList.commit();
                    if (uploadInfo.getDeleteOnUpload()) {
                        mFileWorkerThread.postTask(new DeleteFileTask(filePath));
                    }
                    sendStatusBroadcast(Constants.Status.COMPLETED, uploadInfo);
                } else {
                    Logger.v("Failure");
                    UploadError uploadError = new UploadError(UploadError.ERROR_RESPONSE, response.code(), response.message());
                    sendStatusBroadcast(Constants.Status.FAILED, uploadInfo, uploadError);
                }
                mRemainingFiles--;
                checkCompletion(jobParameters);
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                Logger.v(filePath);
                Logger.v("Error");
                Logger.v(t.toString());
                UploadError uploadError = new UploadError(UploadError.ERROR_NETWORK, 0, t.getLocalizedMessage());
                sendStatusBroadcast(Constants.Status.FAILED, uploadInfo, uploadError);
                mRemainingFiles--;
                checkCompletion(jobParameters);
            }
        });
        sendStatusBroadcast(Constants.Status.STARTED, uploadInfo);
    }

    /**
     * Decides whether the job can be stopped, and whether it needs to be rescheduled in case of
     * pending file uploads.
     * @param jobParameters
     */
    private void checkCompletion(JobParameters jobParameters) {
        if (!isComplete()) {
            return;
        }

        //  if any upload is not successful, reschedule job for remaining files
        boolean needsReschedule = (mPendingUploads > 0);
        Logger.v("Job finished. Pending files: " + mPendingUploads);
        jobFinished(jobParameters, needsReschedule);
    }

    // returns whether an attempt was made to upload every file at least once
    private boolean isComplete() {
        return mRemainingFiles == 0;
    }

    private void sendStatusBroadcast(int status, UploadInfo uploadInfo) {
        sendStatusBroadcast(status, uploadInfo, null);
    }

    private void sendStatusBroadcast(int status, UploadInfo uploadInfo, UploadError uploadError) {
        Intent intent = new Intent(Constants.Actions.STATUS_CHANGE);
        intent.addCategory(getPackageName() + ".CATEGORY_UPLOAD");
        intent.putExtra(Intent.EXTRA_UID, uploadInfo.getUploadId());
        intent.putExtra(Constants.Keys.EXTRA_UPLOAD_STATUS, status);
        intent.putExtra(Constants.Keys.EXTRA_UPLOAD_ERROR, uploadError);
        intent.putExtra(Constants.Keys.EXTRA_EXTRAS, (HashMap<String, String>) uploadInfo.getExtras());
        sendBroadcast(intent);
    }

    private class DeleteFileTask implements Runnable {

        private String filePath;

        private DeleteFileTask(String filePath) {
            this.filePath = filePath;
        }

        @Override
        public void run() {
            File file = new File(filePath);
            File parentFolder = file.getParentFile();
            //If file delete is successful
            if (file.delete()) {
                Logger.v("File " + file.getName() + " is deleted!");
                //if the folder is empty, then delete it
                if (parentFolder.list().length == 0) {
                    //Delete the folder
                    parentFolder.delete();
                }
            }
        }
    }

    /**
     * This {@link HandlerThread} takes care of deleting files asynchronously.
     */
    private class FileWorkerThread extends HandlerThread {

        private Handler mWorkerHandler;

        public FileWorkerThread() {
            super("FileWorkerThread");
        }

        @Override
        protected void onLooperPrepared() {
            mWorkerHandler = new Handler(getLooper());
        }

        public void postTask(Runnable task) {
            mWorkerHandler.post(task);
        }

    }
}
