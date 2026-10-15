package ru.andrey.snt;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** «Моё СНТ»: план участков в WebView; правки хранятся в файле приложения, долги читаются из Google-таблицы. */
public class MainActivity extends Activity {

    private static final String SHEET_CSV =
            "https://docs.google.com/spreadsheets/d/1Hi5zqqP6cwQ1LnKHM1N1ibPwSX3l-x1gg4ohbEz6-mM/export?format=csv";
    private static final int REQ_IMPORT = 7;

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(0xFFF2F1EA);
            getWindow().setNavigationBarColor(0xFFF2F1EA);
            if (Build.VERSION.SDK_INT >= 23) {
                getWindow().getDecorView().setSystemUiVisibility(0x2000); // светлый статус-бар, тёмные значки
            }
        }
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return openExternal(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return openExternal(r.getUrl().toString());
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private boolean openExternal(String url) {
        if (url.startsWith("file:///android_asset/")) return false;
        try {
            Intent i = url.startsWith("tel:")
                    ? new Intent(Intent.ACTION_DIAL, Uri.parse(url))
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("Нет приложения, чтобы открыть ссылку");
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__back?window.__back():false", new android.webkit.ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if (!"true".equals(v)) MainActivity.super.onBackPressed();
            }
        });
    }

    private void toast(final String t) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, t, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private File dataFile() {
        return new File(getFilesDir(), "plots.json");
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    private boolean writeData(String json) {
        try {
            new JSONObject(json); // не пишем мусор
            File tmp = new File(getFilesDir(), "plots.json.tmp");
            FileOutputStream fo = new FileOutputStream(tmp);
            fo.write(json.getBytes("UTF-8"));
            fo.getFD().sync();
            fo.close();
            return tmp.renameTo(dataFile());
        } catch (Exception e) {
            return false;
        }
    }

    private void callJs(final String fn, final String arg) {
        final String js = fn + "(" + JSONObject.quote(arg == null ? "" : arg) + ")";
        ui.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            String json = readAll(getContentResolver().openInputStream(data.getData()));
            JSONObject o = new JSONObject(json);
            if (!o.has("plots")) throw new Exception("not a backup");
            if (writeData(o.getJSONObject("plots").toString())) {
                callJs("window.__restored", "ok");
                toast("Данные восстановлены");
            } else toast("Не удалось записать данные");
        } catch (Exception e) {
            toast("Это не файл копии «Моё СНТ»");
        }
    }

    /** Методы, доступные странице как window.Android. */
    private class Bridge {
        @JavascriptInterface
        public String load() {
            File f = dataFile();
            if (!f.exists()) return "{}";
            try {
                return readAll(new FileInputStream(f));
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public boolean save(String json) {
            return writeData(json);
        }

        @JavascriptInterface
        public void copy(String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Участок", text));
        }

        /** Скачивает первую вкладку «Взносы СНТ» как CSV и отдаёт в window.__onFees(text) или window.__feesError(msg). */
        @JavascriptInterface
        public void fetchFees() {
            new Thread(new Runnable() {
              @Override
              public void run() {
                HttpURLConnection c = null;
                try {
                    URL u = new URL(SHEET_CSV);
                    for (int hop = 0; hop < 5; hop++) {
                        c = (HttpURLConnection) u.openConnection();
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(20000);
                        c.setInstanceFollowRedirects(false);
                        int code = c.getResponseCode();
                        if (code >= 300 && code < 400) {
                            u = new URL(u, c.getHeaderField("Location"));
                            c.disconnect();
                            continue;
                        }
                        if (code != 200) throw new Exception("HTTP " + code);
                        String ct = String.valueOf(c.getContentType());
                        String body = readAll(c.getInputStream());
                        if (ct.contains("html") || body.startsWith("<")) throw new Exception("нет доступа");
                        callJs("window.__onFees", body);
                        return;
                    }
                    throw new Exception("много переадресаций");
                } catch (Exception e) {
                    callJs("window.__feesError", String.valueOf(e.getMessage()));
                } finally {
                    if (c != null) c.disconnect();
                }
              }
            }).start();
        }

        /** Отправить копию данных (JSON) в любое приложение: Telegram, Диск, почту. */
        @JavascriptInterface
        public void exportData() {
            ui.post(new Runnable() {
              @Override
              public void run() {
                try {
                    String stamp = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                    JSONObject o = new JSONObject();
                    o.put("app", "moe-snt");
                    o.put("date", stamp);
                    o.put("plots", new JSONObject(load()));
                    File dir = new File(getCacheDir(), "share");
                    dir.mkdirs();
                    File f = new File(dir, "moe-snt-" + stamp + ".json");
                    FileOutputStream fo = new FileOutputStream(f);
                    fo.write(o.toString(1).getBytes("UTF-8"));
                    fo.close();
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("application/json");
                    i.putExtra(Intent.EXTRA_SUBJECT, "Моё СНТ — копия данных " + stamp);
                    i.putExtra(Intent.EXTRA_STREAM, ShareProvider.uriFor(MainActivity.this, f));
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Сохранить копию данных"));
                } catch (Exception e) {
                    toast("Не удалось сделать копию");
                }
              }
            });
        }

        @JavascriptInterface
        public void importData() {
            ui.post(new Runnable() {
              @Override
              public void run() {
                Intent i = new Intent(Build.VERSION.SDK_INT >= 19 ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_IMPORT);
                } catch (ActivityNotFoundException e) {
                    toast("Нет приложения для выбора файла");
                }
              }
            });
        }
    }
}
