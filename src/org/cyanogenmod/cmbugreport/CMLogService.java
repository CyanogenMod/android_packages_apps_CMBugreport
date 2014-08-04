package org.cyanogenmod.cmbugreport;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;


public class CMLogService extends IntentService {

    private final static String projectName = "11400"; // 11102 = WIKI 11400 = bugdump
    private final static String issueType = "1"; // 4 = improvement   1 = bug?
    String bugID = "";
    private final static String uNpW =     "QnVnQ29sbGVjdG9yOldlTE9WRWJ1Z3Mh"; // <--- BugCollector
    private final static String apiURL = "https://jira.cyanogenmod.org/rest/api/2/issue/";

    public final static String EXTRA_MESSAGE = "org.cyanogenmod.bugreportgrabber.MESSAGE";
    private Uri reportURI;

    private JSONObject inputJSON = new JSONObject();
    private JSONObject outputJSON;

    private int notifID = 546924;

    public CMLogService() {
        super("CMLogService");
    }

    @Override
    protected void onHandleIntent(Intent arg0) {
        Intent intent = arg0;

        ArrayList<Uri> attachments = new ArrayList<Uri>();
        attachments = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);

        for (Uri u : attachments){
            if ( u.toString().contains("txt")){
                reportURI = u;
            }
        }


        String summary = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        String description = intent.getStringExtra(Intent.EXTRA_TEXT);

        JSONObject fields = new JSONObject();
        JSONObject project = new JSONObject();
        JSONObject issuetype = new JSONObject();

        try {
        project.put("id", projectName);
        issuetype.put("id", issueType);
        fields.put("project",project);
        fields.put("summary", summary);
        fields.put("description", description);
        fields.put("issuetype", issuetype);
        inputJSON.put("fields", fields);
        } catch(JSONException e){
            Log.e("bugreportgrabber", "JSONexception: " + e.getMessage());
            notifyUploadFailed(getString(R.string.probCreating));
        }

        notifyOfUpload();
        new CallAPI().execute(inputJSON);
    }

    private void notifyOfUpload() {
        Notification.Builder mBuilder =
                new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_tab_upload)
                .setContentTitle(getString(R.string.notifName))
                .setContentText(getString(R.string.uploading));
         NotificationManager mNotificationManager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mBuilder.setProgress(0, 0, true);
        mNotificationManager.notify(notifID, mBuilder.build());
    }


    private void notifyUploadFinished(String issueNumber) {
        Notification.Builder mBuilder =
                new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.notifName))
                .setContentText(getString(R.string.thanks));
        NotificationManager mNotificationManager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(notifID, mBuilder.build());
    }
    private void notifyUploadFailed(String reason) {
        Notification.Builder mBuilder =
            new Notification.Builder(this)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notifName))
            .setContentText(R.string.uplFailed + " " + reason );
    NotificationManager mNotificationManager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(notifID, mBuilder.build());
        }
    private class CallAPI extends AsyncTask<JSONObject, Void, String> {
        String responseString = "";
        @Override
        protected String doInBackground(JSONObject...params){
            try {
                URI url = new URI(apiURL);
                DefaultHttpClient htClient = new DefaultHttpClient();
                HttpPost httpost = new HttpPost(url);
                //turn the JSONObject being passed into a stringentity for http consumption
                StringEntity se = new StringEntity(params[0].toString());
                httpost.setEntity(se);
                httpost.setHeader("Accept","application/json");
                httpost.setHeader("Authorization","Basic " + uNpW);
                httpost.setHeader("Content-Type","application/json");
                HttpResponse response = htClient.execute(httpost);
                HttpEntity entity = response.getEntity();
                responseString = EntityUtils.toString(entity);
            } catch (Exception e) {
                Log.e("bugreportgrabber", "URLexception: " + e);
                notifyUploadFailed(getString(R.string.conProblem));
                return e.getMessage();
            }
            //issue hopefully created, let's get the ID so we can attach to it (and pass that ID to the results activity)
            String jiraResponse = responseString;
            String jiraBugID = "";
            try {
                outputJSON = new JSONObject(jiraResponse);
                jiraBugID = (String)  outputJSON.get("key");
            } catch (JSONException e) {
                e.printStackTrace();
                notifyUploadFailed(getString(R.string.badResponse));
                return e.getMessage();
            }

            //now we attach the file
            if(!jiraBugID.isEmpty()){
                try {
                    URI url2 = new URI(apiURL + jiraBugID + "/attachments");
                    DefaultHttpClient uplClient = new DefaultHttpClient();
                    HttpPost httpostUpl = new HttpPost(url2);
                    httpostUpl.setHeader("Authorization","Basic " + uNpW);
                    httpostUpl.setHeader("X-Atlassian-Token","nocheck");
                    File bugreportFile = new File("/data" + reportURI.getPath());
                    File zippedBug = zip(bugreportFile);
                    MultipartEntity bugreportUploadEntity = new MultipartEntity();
                    bugreportUploadEntity.addPart("file", new FileBody(zippedBug));
                    httpostUpl.setEntity(bugreportUploadEntity);
                    HttpResponse uplResponse = uplClient.execute(httpostUpl);
                    HttpEntity entityResponse = uplResponse.getEntity();
                    responseString = EntityUtils.toString(entityResponse);
                        // Log.d("brg", "response " + responseString);
                } catch (Exception e) {
                    Log.e("bugreportgrabber", "file upload exception: " + e);
                    //pop error message for file upload"
                    notifyUploadFailed(getString(R.string.fileFail));
                }
            } else {
                // pop error message for bad response from server
                notifyUploadFailed(getString(R.string.badResponse));
            }
            return jiraBugID; //output;
        }
        private File zip(File bugreportFile) {
            String zippedFilename = "/data/bugreports/tmp.zip";
            try{
                byte[] buffer = new byte[1024];
                FileOutputStream fos = new FileOutputStream(zippedFilename);
                ZipOutputStream zos = new ZipOutputStream(fos);
                FileInputStream fis = new FileInputStream(bugreportFile);
                zos.putNextEntry(new ZipEntry(bugreportFile.getName()));
                int length;
                while ((length = fis.read(buffer)) > 0){
                    zos.write(buffer, 0, length);
                }
                zos.closeEntry();
                fis.close();
                zos.close();
            }catch (Exception e){
                Log.e("CMLogCapture", "Zipping problem", e);
                notifyUploadFailed(getString(R.string.zipFail));
            }
            return new File(zippedFilename);
        }
        protected void onPostExecute(String result){
                stopForeground(true);
                notifyUploadFinished(result);
                stopSelf();
            }
        } // end CallApi
}
