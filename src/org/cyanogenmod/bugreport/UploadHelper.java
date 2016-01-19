package org.cyanogenmod.bugreport;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;

public class UploadHelper {

    private static final String TAG = "UploadHelper";

    private static final boolean DEBUG = false;

    public static final String LINE_END = "\r\n";
    public static final String DASHES = "--";

    public static String uploadAndGetId(Context context, JSONObject input)
            throws IOException, JSONException {
        URL url = new URL(context.getString(R.string.config_api_url));
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        try {
            urlConnection.setRequestProperty("Accept", "application/json");
            urlConnection.setRequestProperty("Authorization", "Basic "
                    + context.getString(R.string.config_auth));
            urlConnection.setRequestProperty("Content-Type", "application/json");

            urlConnection.setReadTimeout(10000);
            urlConnection.setConnectTimeout(15000);
            urlConnection.setInstanceFollowRedirects(true);
            urlConnection.setDoInput(true);
            urlConnection.setDoOutput(true);
            urlConnection.setFixedLengthStreamingMode(input.toString().length());

            OutputStream os = urlConnection.getOutputStream();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
            writer.write(input.toString());
            writer.flush();
            writer.close();
            os.close();
            urlConnection.connect();

            String response = getResponse(urlConnection);
            Log.v(TAG, response);

            JSONObject output = new JSONObject(response);
            return output.getString("key");
        } finally {
            urlConnection.disconnect();
        }
    }

    public static boolean attachFile(Context context, File f, String issueId) throws IOException {
        Log.i(TAG, "attachFile()");
        if (f == null || !f.exists()) {
            throw new NullPointerException("can't upload a null file");
        }

        URL url;
         
        if (context.getString(R.string.config_api_url_upload).isEmpty()) {
            url = new URL(context.getString(R.string.config_api_url) + issueId + "/attachments");
        } else {
            url = new URL(context.getString(R.string.config_api_url_upload) + issueId + "/attachments");
        }

        HttpURLConnection urlConnection = null;
        urlConnection = (HttpURLConnection) url.openConnection();

        try {
            String boundry = "-------" + System.currentTimeMillis();

            String part1 = DASHES + boundry + LINE_END
                    + "Content-Disposition: form-data; name=\""
                    + "file" + "\"; filename=\"" + f.getName() + "\"" + LINE_END
                    + "Content-Type: application/octet-stream" + LINE_END
                    + "Content-Transfer-Encoding: binary" + LINE_END
                    + LINE_END;

            String part2 = LINE_END + DASHES + boundry + DASHES + LINE_END;

            final long length = part1.length() + f.length() + part2.length();

            if (DEBUG) {
                Log.d(TAG, "boundary length: : " + boundry.getBytes().length);
                Log.d(TAG, "calculated length: " + length);
            }

            urlConnection.setRequestProperty("Authorization", "Basic "
                    + context.getString(R.string.config_auth));
            urlConnection.setRequestProperty("X-Atlassian-Token", "nocheck");
            urlConnection.setRequestProperty("Content-Type", "multipart/form-data; boundary="
                    + boundry);
            urlConnection.setRequestProperty("Content-Length", String.valueOf(length));
            urlConnection.setDoOutput(true);
            urlConnection.setDoInput(true);
            urlConnection.setUseCaches(false);
            urlConnection.setInstanceFollowRedirects(true);
            urlConnection.setFixedLengthStreamingMode(length);
            urlConnection.setReadTimeout(10000);
            urlConnection.setConnectTimeout(15000);

            FileInputStream inputStream = new FileInputStream(f);
            final OutputStream outputStream = urlConnection.getOutputStream();
            final PrintWriter printWriter
                    = new PrintWriter(new OutputStreamWriter(outputStream, "utf-8"));

            printWriter.write(part1);
            printWriter.flush();

            // write contents of the file
            byte[] buffer = new byte[4096];
            int bytesRead = -1;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
            inputStream.close();

            // signal end
            printWriter.write(part2);
            printWriter.flush();
            printWriter.close();

            if (DEBUG) {
                Log.v(TAG, part1);
                Log.v(TAG, " <file>");
                Log.v(TAG, part2);
            }

            final int responseCode = urlConnection.getResponseCode();
            if (DEBUG) {
                Log.d(TAG, "response code: " + responseCode);
            }

            if (HttpURLConnection.HTTP_OK == responseCode) {
                String response = getResponse(urlConnection);

                if (DEBUG) {
                    Log.v(TAG, "server response: " + LINE_END + response);
                }

                try {
                    JSONArray jsonResponse = new JSONArray(response);
                    return jsonResponse.getJSONObject(0).get("filename").equals(f.getName());
                } catch (JSONException e) {
                    e.printStackTrace();
                    // not a proper response
                }
            }
        } finally {
            urlConnection.disconnect();
        }
        return false;
    }

    private static String getResponse(HttpURLConnection httpUrlConnection) throws IOException {
        InputStream responseStream = new BufferedInputStream(httpUrlConnection.getInputStream());

        BufferedReader responseStreamReader = new BufferedReader(
                new InputStreamReader(responseStream));
        String line = "";
        StringBuilder stringBuilder = new StringBuilder();
        while ((line = responseStreamReader.readLine()) != null) {
            stringBuilder.append(line).append("\n");
        }
        responseStreamReader.close();
        responseStream.close();

        return stringBuilder.toString();
    }
}
