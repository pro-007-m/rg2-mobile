package io.github.pro007m.rg2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebSettingsCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android services for the packaged offline UI. No remote page receives a native bridge. */
public final class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String ORIGIN = "https://" + HOST;
    private static final String START_URL = ORIGIN + "/assets/www/index.html";
    private static final int EXPORT_REQUEST = 4101;
    private static final int IMPORT_REQUEST = 4102;
    private static final int MAX_DATA_BYTES = 8 * 1024 * 1024;
    private static final int MAX_MESSAGE_CHARS = 12 * 1024 * 1024;
    private final ExecutorService documents = Executors.newSingleThreadExecutor();
    private WebView webView;
    private FrameLayout content;
    private boolean keepAwakeRequested;
    private boolean destroyed;
    private boolean documentPickerOpen;
    private String pendingExport;
    private String resolvedTheme;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        content = new FrameLayout(this);
        content.setBackgroundColor(Color.rgb(245, 242, 235));
        webView = new WebView(this);
        content.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(content);
        configureInsets();
        configureWebView();
        applyTheme(isSystemDark());
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            new AlertDialog.Builder(this)
                .setTitle("Обновите Android System WebView")
                .setMessage("Для работы RG2 нужна современная версия Android System WebView или Chrome. Обновите её и откройте приложение снова.")
                .setPositiveButton("Закрыть", (dialog, which) -> finish())
                .setCancelable(false).show();
            return;
        }
        WebViewCompat.addWebMessageListener(webView, "RG2NativePort",
            Collections.singleton(ORIGIN), (view, message, sourceOrigin, isMainFrame, reply) -> {
                if (!isMainFrame || !isTrustedOrigin(sourceOrigin) || !isTrustedPage()) return;
                String raw = message.getData();
                if (raw == null || raw.length() > MAX_MESSAGE_CHARS) return;
                String command = "";
                try {
                    JSONObject data = new JSONObject(raw);
                    command = data.optString("type", "");
                    handleCommand(command, data);
                    reply.postMessage(new JSONObject().put("type", command).put("ok", true).toString());
                } catch (Exception error) {
                    try {
                        reply.postMessage(new JSONObject().put("type", command).put("ok", false)
                            .put("message", "Не удалось выполнить действие Android.").toString());
                    } catch (JSONException ignored) { }
                    showToast("Не удалось выполнить действие. Попробуйте ещё раз.");
                }
            });
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        webView.loadUrl(START_URL);
    }

    @SuppressWarnings("deprecation")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setTextZoom(Math.round(getResources().getConfiguration().fontScale * 100));
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false);
        }
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setGeolocationEnabled(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        WebViewAssetLoader.AssetsPathHandler assets = new WebViewAssetLoader.AssetsPathHandler(this);
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", path -> {
                WebResourceResponse response = assets.handle(path);
                if (response == null) return null;
                String lower = path.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".mjs") || lower.endsWith(".js")) response.setMimeType("text/javascript");
                else if (lower.endsWith(".json")) response.setMimeType("application/json");
                else if (lower.endsWith(".wasm")) response.setMimeType("application/wasm");
                else if (lower.endsWith(".svg")) response.setMimeType("image/svg+xml");
                else if (lower.endsWith(".woff2")) response.setMimeType("font/woff2");
                return response;
            }).build();
        webView.setWebViewClient(new WebViewClientCompat() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (isTrustedOrigin(request.getUrl())) {
                    WebResourceResponse response = loader.shouldInterceptRequest(request.getUrl());
                    if (response != null) return response;
                }
                // Every render dependency must be in the APK. Never fetch a remote subresource.
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                    Collections.singletonMap("Cache-Control", "no-store"), new ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (isTrustedOrigin(request.getUrl()) && request.getUrl().getPath() != null
                        && request.getUrl().getPath().startsWith("/assets/www/")) return false;
                if (request.isForMainFrame() && request.hasGesture()) openExternal(request.getUrl().toString());
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (isTrustedPage()) {
                    dispatchSystemTheme();
                    evaluate("if(window.RG2Mobile&&window.RG2Mobile.receiveNativeInfo){window.RG2Mobile.receiveNativeInfo({version:"
                        + JSONObject.quote(BuildConfig.VERSION_NAME) + ",sdk:" + Build.VERSION.SDK_INT + ",installer:null});}");
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                if (!isTrustedPage()) { result.cancel(); return true; }
                new AlertDialog.Builder(MainActivity.this).setTitle("RG2 Mobile").setMessage(message)
                    .setPositiveButton("Да", (dialog, which) -> result.confirm())
                    .setNegativeButton("Отмена", (dialog, which) -> result.cancel())
                    .setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                if (!isTrustedPage()) { result.cancel(); return true; }
                new AlertDialog.Builder(MainActivity.this).setTitle("RG2 Mobile").setMessage(message)
                    .setPositiveButton("ОК", (dialog, which) -> result.confirm())
                    .setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
            @Override public boolean onJsPrompt(WebView view, String url, String message, String initial, JsPromptResult result) {
                if (!isTrustedPage()) { result.cancel(); return true; }
                EditText input = new EditText(MainActivity.this);
                input.setText(initial == null ? "" : initial);
                new AlertDialog.Builder(MainActivity.this).setTitle("RG2 Mobile").setMessage(message).setView(input)
                    .setPositiveButton("ОК", (dialog, which) -> result.confirm(input.getText().toString()))
                    .setNegativeButton("Отмена", (dialog, which) -> result.cancel())
                    .setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                // Release builds do not put article/user state or JS payloads in device logs.
                return !BuildConfig.DEBUG || super.onConsoleMessage(message);
            }
        });
        webView.setDownloadListener((url, agent, disposition, mime, length) -> openExternal(url));
    }

    private void handleCommand(String command, JSONObject data) throws Exception {
        switch (command) {
            case "ready":
                dispatchSystemTheme();
                evaluate("if(window.RG2Mobile&&window.RG2Mobile.receiveNativeInfo){window.RG2Mobile.receiveNativeInfo({version:"
                    + JSONObject.quote(BuildConfig.VERSION_NAME) + ",sdk:" + Build.VERSION.SDK_INT + ",installer:null});}");
                break;
            case "clipboard": {
                String text = data.getString("text");
                if (text.length() > 1024 * 1024) throw new IllegalArgumentException();
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("RG2", text));
                break;
            }
            case "theme": {
                String theme = data.getString("resolved");
                if (!theme.equals("graphite") && !theme.equals("atelier")
                        && !theme.equals("dark") && !theme.equals("light")) throw new IllegalArgumentException();
                resolvedTheme = theme;
                applyTheme(theme.equals("graphite") || theme.equals("dark"));
                break;
            }
            case "keep-awake":
                keepAwakeRequested = data.getBoolean("enabled");
                applyKeepAwake();
                break;
            case "haptic":
                webView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                break;
            case "open-external":
                if (!openExternal(data.getString("url"))) throw new IllegalArgumentException();
                break;
            case "export-data":
                beginExport(data.getString("text"));
                break;
            case "import-data":
                beginImport();
                break;
            default: throw new IllegalArgumentException();
        }
    }

    private void beginExport(String text) throws Exception {
        if (documentPickerOpen || text.getBytes(StandardCharsets.UTF_8).length > MAX_DATA_BYTES) {
            throw new IllegalStateException();
        }
        new JSONObject(text);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "RG2-backup.json");
        pendingExport = text;
        documentPickerOpen = true;
        try { startActivityForResult(intent, EXPORT_REQUEST); }
        catch (Exception error) { pendingExport = null; documentPickerOpen = false; throw error; }
    }

    private void beginImport() {
        if (documentPickerOpen) throw new IllegalStateException();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/json", "text/plain", "application/octet-stream"});
        documentPickerOpen = true;
        try { startActivityForResult(intent, IMPORT_REQUEST); }
        catch (Exception error) { documentPickerOpen = false; throw error; }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_REQUEST && requestCode != IMPORT_REQUEST) return;
        documentPickerOpen = false;
        String exportText = pendingExport;
        pendingExport = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        documents.execute(() -> {
            try {
                if (requestCode == EXPORT_REQUEST) {
                    if (exportText == null) return;
                    try (OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
                        if (stream == null) throw new IllegalStateException();
                        stream.write(exportText.getBytes(StandardCharsets.UTF_8));
                    }
                    runOnUiThread(() -> showToast("Резервная копия сохранена."));
                } else {
                    String text;
                    try (InputStream stream = getContentResolver().openInputStream(uri)) {
                        if (stream == null) throw new IllegalStateException();
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] block = new byte[16384];
                        int count;
                        while ((count = stream.read(block)) != -1) {
                            if (bytes.size() + count > MAX_DATA_BYTES) throw new IllegalArgumentException();
                            bytes.write(block, 0, count);
                        }
                        text = bytes.toString(StandardCharsets.UTF_8.name());
                    }
                    // Import validation and explicit replacement confirmation belong to the UI.
                    String jsonText = text.startsWith("\uFEFF") ? text.substring(1) : text;
                    new JSONObject(jsonText);
                    runOnUiThread(() -> evaluate("if(window.RG2Mobile&&window.RG2Mobile.receiveImport){window.RG2Mobile.receiveImport("
                        + JSONObject.quote(jsonText) + ");}"));
                }
            } catch (Exception error) {
                runOnUiThread(() -> showToast(requestCode == IMPORT_REQUEST
                    ? "Не удалось прочитать JSON-копию. Проверьте файл (до 8 МБ)."
                    : "Не удалось сохранить резервную копию."));
            }
        });
    }

    private static boolean isTrustedOrigin(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && HOST.equalsIgnoreCase(uri.getHost())
            && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null;
    }

    private boolean isTrustedPage() {
        if (destroyed || webView == null || webView.getUrl() == null) return false;
        Uri uri = Uri.parse(webView.getUrl());
        return isTrustedOrigin(uri) && "/assets/www/index.html".equals(uri.getPath());
    }

    private boolean openExternal(String value) {
        if (value == null || value.length() > 8192) return false;
        Uri uri = Uri.parse(value);
        if ("mailto".equalsIgnoreCase(uri.getScheme())) {
            try { startActivity(new Intent(Intent.ACTION_SENDTO, uri)); return true; }
            catch (ActivityNotFoundException error) { showToast("На устройстве не найдено почтовое приложение."); return false; }
        }
        if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null
                || uri.getHost().isEmpty() || uri.getUserInfo() != null || isTrustedOrigin(uri)) return false;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException error) {
            showToast("На устройстве не найден браузер для этой ссылки.");
            return false;
        }
    }

    @Override public void onBackPressed() { handleBack(); }
    private void handleBack() {
        if (!isTrustedPage()) { moveTaskToBack(true); return; }
        webView.evaluateJavascript("(function(){try{return Boolean(window.RG2Mobile&&window.RG2Mobile.back&&window.RG2Mobile.back());}catch(e){return false;}})()",
            result -> { if (!destroyed && !"true".equals(result)) moveTaskToBack(true); });
    }

    @Override protected void onPause() {
        dispatch("rg2:pause", null);
        if (webView != null) webView.onPause();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) { webView.onResume(); applyKeepAwake(); dispatch("rg2:resume", null); }
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        webView.getSettings().setTextZoom(Math.round(config.fontScale * 100));
        if (resolvedTheme == null) applyTheme(isSystemDark());
        dispatchSystemTheme();
    }

    private void dispatchSystemTheme() {
        dispatch("rg2:system-theme", "{dark:" + isSystemDark() + "}");
    }
    private boolean isSystemDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
    private void applyKeepAwake() {
        if (keepAwakeRequested) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void dispatch(String event, String detailExpression) {
        evaluate("window.dispatchEvent(new CustomEvent(" + JSONObject.quote(event)
            + (detailExpression == null ? "" : ",{detail:" + detailExpression + "}") + "));" );
    }
    private void evaluate(String script) { if (isTrustedPage()) webView.evaluateJavascript(script, null); }

    @SuppressWarnings("deprecation")
    private void configureInsets() {
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    @SuppressWarnings("deprecation")
    private void applyTheme(boolean dark) {
        int color = dark ? Color.rgb(24, 27, 34) : Color.rgb(245, 242, 235);
        content.setBackgroundColor(color);
        webView.setBackgroundColor(color);
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(color);
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        flags = dark ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) flags = dark ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            : flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= 30 && decor.getWindowInsetsController() != null) {
            int masks = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            decor.getWindowInsetsController().setSystemBarsAppearance(dark ? 0 : masks, masks);
        }
    }

    private void showToast(String text) { if (!destroyed) Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    @Override protected void onDestroy() {
        destroyed = true;
        documents.shutdown();
        pendingExport = null;
        if (webView != null) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.removeWebMessageListener(webView, "RG2NativePort");
            }
            content.removeView(webView);
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
