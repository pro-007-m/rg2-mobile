package io.github.pro007m.rg2;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** No-JUnit device smoke test. Run only on an isolated QA app/profile.
 * adb shell am instrument -w -e timeoutMs 90000 \
 * io.github.pro007m.rg2.test/io.github.pro007m.rg2.OfflineSmokeInstrumentation
 * adb pull /sdcard/Android/data/io.github.pro007m.rg2/files/qa
 * This drives the packaged WebView through its actual public app/UI actions.
 */
public final class OfflineSmokeInstrumentation extends Instrumentation {
    private static final String PACKAGE = "io.github.pro007m.rg2";
    private final JSONArray checks = new JSONArray();
    private final JSONArray screenshots = new JSONArray();
    private Activity activity;
    private WebView webView;
    private File output;
    private JSONObject storageBackup;
    private long started;
    private long deadline;
    private long limit = 90000;
    private int sequence;
    private String targetVersionName;
    private long targetVersionCode = -1;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null) {
            try { limit = Math.max(40000, Math.min(90000, Long.parseLong(arguments.getString("timeoutMs", "90000")))); }
            catch (NumberFormatException ignored) { }
        }
        start();
    }

    @Override public void onStart() {
        super.onStart();
        started = SystemClock.elapsedRealtime();
        deadline = started + limit;
        Throwable failure = null;
        boolean restored = false;
        try {
            output = new File(getTargetContext().getExternalFilesDir(null), "qa");
            require(output.isDirectory() || output.mkdirs(), "Cannot create private QA output directory");
            PackageInfo info = getTargetContext().getPackageManager().getPackageInfo(PACKAGE, PackageManager.GET_PERMISSIONS);
            targetVersionName = info.versionName;
            targetVersionCode = android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            if (info.requestedPermissions != null) for (String permission : info.requestedPermissions) {
                require(!"android.permission.INTERNET".equals(permission), "Offline target unexpectedly requests INTERNET permission");
            }
            record("native-offline-permissions", new JSONObject().put("internetPermission", false));
            Intent launch = new Intent().setClassName(PACKAGE, PACKAGE + ".MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = startActivitySync(launch);
            require(activity != null, "Target activity did not start");
            runOnMainSync(() -> webView = findWebView(activity.getWindow().getDecorView()));
            require(webView != null, "Target WebView not found");
            waitFor("app exposed", "Boolean(window.RG2App)", 10000);
            record("boot", runJs("""
                const a=window.RG2App,ok=await a.ready;
                if(!ok||a.state.bootError)throw Error('boot: '+a.state.bootError);
                if(location.origin!=='https://appassets.androidplatform.net')throw Error('wrong asset origin');
                return {ready:ok,origin:location.origin,bootError:a.state.bootError,userAgent:navigator.userAgent};
                """, 10000));
            storageBackup = evalObject("Object.fromEntries(Object.keys(localStorage).filter(k=>k.startsWith('rg2.')).map(k=>[k,localStorage.getItem(k)]))");
            record("catalog-and-local-resources", runJs("""
                const a=RG2App,c=a.catalog;
                if(c.phases.length!==60||a.items.size!==560)throw Error('catalog counts '+c.phases.length+'/'+a.items.size);
                const manifest=await fetch('original-assets-manifest.json').then(r=>{if(!r.ok)throw Error('manifest HTTP '+r.status);return r.json()});
                if(manifest.length!==2063)throw Error('asset manifest count '+manifest.length);
                const checked=[];
                for(const i of [0,1,517,1031,1547,2062]){
                  const asset=manifest[i],url=new URL('assets/'+asset.path,location.href),r=await fetch(url);
                  if(!r.ok||url.origin!==location.origin)throw Error('local image '+asset.path+' HTTP '+r.status);
                  const bytes=await r.arrayBuffer();if(bytes.byteLength!==asset.bytes)throw Error('image length '+asset.path);
                  const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),b=>b.toString(16).padStart(2,'0')).join('');
                  if(hash!==asset.sha256)throw Error('image hash '+asset.path);checked.push(asset.path);
                }
                const modules=[];
                for(const path of ['trainer-data.json','solver/core.mjs','solver/tool.mjs','solver/worker.mjs','solver/vendor/three.module.js']){
                  const r=await fetch(path);if(!r.ok)throw Error(path+' HTTP '+r.status);
                  const type=r.headers.get('content-type')||'',text=await r.text();
                  if(!text.length||(/\\.(mjs|js)$/.test(path)&&!/(javascript|ecmascript)/i.test(type)))throw Error('module MIME '+path+': '+type);
                  modules.push({path,type,bytes:text.length});
                }
                return {phases:c.phases.length,items:a.items.size,assetManifest:manifest.length,sampledImageSha256:checked,modules};
                """, 16000));

            record("home-route", runJs("""
                RG2App.navigate({type:'phase',id:RG2App.catalog.defaultPhase||'MAIN3X3'});
                if(RG2App.state.route.type!=='phase'||!document.querySelector('.menu-grid'))throw Error('home route did not render');
                return {route:RG2App.state.route,title:document.querySelector('.page-title')?.textContent};
                """, 3000));
            screenshot("01-home-portrait");
            record("article-favorite", runJs("""
                const item=[...RG2App.items.values()].find(i=>i.kind==='article'&&i.html?.includes('<img'));
                if(!item)throw Error('article with diagram missing');window.__smokeArticle=item.id;
                RG2App.openItem(item.id);
                const body=document.querySelector('#article-body'),button=document.querySelector('.article-actions [data-favorite]');
                if(!body?.textContent.trim()||!button)throw Error('article not rendered');
                if(button.getAttribute('aria-pressed')!=='true')button.click();
                const images=[...body.querySelectorAll('img')];await Promise.all(images.slice(0,3).map(img=>new Promise((ok,bad)=>{if(img.complete)return img.naturalWidth?ok():bad(Error('diagram failed'));img.onload=ok;img.onerror=()=>bad(Error('diagram failed'))})));
                if(!RG2App.state.favorites.includes(item.id))throw Error('favorite click not persisted');
                return {id:item.id,title:item.title,images:images.length,favorites:RG2App.state.favorites.length};
                """, 6000));
            screenshot("02-article-portrait");
            record("native-back-route", runJs("""
                document.querySelector('#sidebar')?.classList.remove('expanded');
                const handled=RG2Mobile.back();
                for(let i=0;i<20&&RG2App.state.route.type==='item';i++)await new Promise(r=>setTimeout(r,50));
                if(!handled||RG2App.state.route.type!=='phase')throw Error('native back did not return from article');
                return {handled,route:RG2App.state.route};
                """, 2000));
            record("tools-route", runJs("""
                RG2App.navigate({type:'tools'});
                if(document.querySelectorAll('[data-tool]').length<5)throw Error('tools missing');
                RG2App.navigate({type:'tool',id:'timer'});
                if(!document.querySelector('#tool-host')?.children.length)throw Error('timer did not mount');
                RG2App.navigate({type:'settings'});
                for(const theme of ['graphite','atelier','graphite']){
                  const input=document.querySelector('input[name="theme"][value="'+theme+'"]');
                  if(!input)throw Error('theme radio missing');input.checked=true;input.dispatchEvent(new Event('change',{bubbles:true}));
                  if(document.documentElement.dataset.theme!==theme||localStorage.getItem('rg2.theme')!==theme)throw Error('theme failed '+theme);
                }
                return {route:RG2App.state.route,theme:RG2App.state.effectiveTheme,storedTheme:localStorage.getItem('rg2.theme')};
                """, 3000));
            screenshot("03-settings-graphite-portrait");
            JSONObject persistence = evalObject("({article:window.__smokeArticle,theme:localStorage.getItem('rg2.theme')})");
            evalObject("(window.__smokeReloadMarker=true,{marked:true})");
            runOnMainSync(() -> webView.reload());
            SystemClock.sleep(200);
            waitFor("reload app", "Boolean(!window.__smokeReloadMarker&&window.RG2App&&window.RG2App.catalog&&!window.RG2App.state.bootError)", 10000);
            String favoriteId = JSONObject.quote(persistence.getString("article"));
            record("localStorage-survives-reload", runJs("""
                const ok=await RG2App.ready;
                if(!ok||RG2App.state.bootError||RG2App.state.themePreference!=='graphite'||!RG2App.state.favorites.includes(FAVORITE_ID))throw Error('settings/favorite lost after reload');
                return {theme:RG2App.state.themePreference,favorite:FAVORITE_ID,reloaded:true};
                """.replace("FAVORITE_ID", favoriteId), 5000));

            evalObject("(RG2App.navigate({type:'tool',id:'cfop-solver'}),{navigated:true})");
            waitFor("solver mounts", "Boolean(document.querySelector('#tool-host')?.cfopSolver)", 8000);
            record("solver-WebGL2", runJs("""
                const host=document.querySelector('#tool-host'),d=host.cfopSolver.debug(),canvas=host.querySelector('.cfop-cube canvas');
                canvas?.scrollIntoView({block:'center'});await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
                const r=canvas?.getBoundingClientRect(),gl=canvas?.getContext('webgl2');
                if(!canvas||r.width<=0||r.height<=0||r.bottom<=0||r.top>=innerHeight||!gl||gl.isContextLost())throw Error('WebGL2 canvas unavailable/not visible');
                if(d.visual?.caps!==54||d.visual?.state?.length!==54||!d.visual.opaqueCaps)throw Error('renderer 54 opaque facelets unavailable');
                const pause=host.querySelector('[data-setting="pauseForChoice"]');pause.checked=true;pause.dispatchEvent(new Event('change',{bubbles:true}));
                const speed=host.querySelector('[data-setting="speed"]');speed.value='4';speed.dispatchEvent(new Event('change',{bubbles:true}));
                return {canvas:{width:canvas.width,height:canvas.height,cssWidth:r.width,cssHeight:r.height},renderer:d.visual};
                """, 4000));
            screenshot("04-solver-portrait");
            record("random-scramble", runJs("""
                const h=document.querySelector('#tool-host');h.querySelector('[data-action="demo"]').click();
                const d=h.cfopSolver.debug(),core=await import(new URL('./solver/core.mjs',location.href).href);
                if(d.active||d.searching||d.running||d.scramble?.moves?.length!==20||!core.validate(d.input).valid)throw Error('new scramble state invalid/auto-started');
                const base=Uint8Array.from({length:54},(_,i)=>d.scramble.centers[Math.floor(i/9)]),replay=core.applyAlg(base,d.scramble.moves);
                if(replay.some((v,i)=>v!==d.input[i]))throw Error('20-move scramble replay mismatch');
                h.querySelector('[data-action="solve"]').click();
                return {moves:d.scramble.moves,validInput:true,noAutoSolve:true};
                """, 4000));
            waitFor("worker recommendations", "Boolean(document.querySelector('#tool-host')?.cfopSolver?.debug().candidates.length)&&!document.querySelector('#tool-host').cfopSolver.debug().searching", 32000);
            record("worker-candidate-and-playback", runJs("""
                const h=document.querySelector('#tool-host'),d=h.cfopSolver.debug(),core=await import(new URL('./solver/core.mjs',location.href).href);
                const candidate=d.candidates.find(c=>c.moves.length>=3);
                if(!candidate)throw Error('no 3-move cross candidate within bounded search');
                const final=core.applyAlg(candidate.baseState||d.state,candidate.moves);
                if(!core.isCross(final)||final.some((v,i)=>v!==candidate.state[i]))throw Error('worker candidate does not solve cross');
                window.__smokeChosen={id:candidate.id,moves:candidate.moves};
                const button=[...h.querySelectorAll('[data-choose]')].find(b=>b.dataset.choose===candidate.id);
                if(!button||button.disabled)throw Error('candidate action unavailable');
                button.click();h.querySelector('[data-action="pause"]').click();
                return {candidates:d.candidates.length,chosen:window.__smokeChosen,verifiedCross:true};
                """, 4000));
            waitFor("first move paused", "Boolean(document.querySelector('#tool-host')?.cfopSolver?.debug().paused)&&!document.querySelector('#tool-host').cfopSolver.debug().running&&document.querySelector('#tool-host').cfopSolver.debug().executed.length>=1", 4000);
            for (int move = 2; move <= 3; move++) {
                evalObject("(document.querySelector('#tool-host [data-action=\"next\"]').click(),{clicked:true})");
                waitFor("single move " + move, "!document.querySelector('#tool-host').cfopSolver.debug().running&&document.querySelector('#tool-host').cfopSolver.debug().executed.length>=" + move, 4000);
            }
            record("paused-state-replays-exactly", runJs("""
                const h=document.querySelector('#tool-host'),d=h.cfopSolver.debug(),core=await import(new URL('./solver/core.mjs',location.href).href),orientation=await import(new URL('./solver/orientation.mjs',location.href).href);
                const base=orientation.orientForCross(d.input,d.frame.color).state,expected=core.applyAlg(base,d.executed);
                if(!d.paused||d.running||d.executed.length<3||!core.validate(d.state).valid)throw Error('paused state invalid');
                if(expected.some((v,i)=>v!==d.state[i])||d.visual.state.some((v,i)=>v!==d.state[i]))throw Error('controller/renderer/replay mismatch');
                if(d.state[31]!==d.frame.color)throw Error('selected cross color is not down');
                return {executed:d.executed,queue:d.queue.length,stage:d.stage,paused:d.paused,validState:true,rendererMatches:true,traceSegments:d.trace.segments.length};
                """, 4000));
            runOnMainSync(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            boolean landscape = waitForOptional("innerWidth>innerHeight", 3500);
            runJs("document.querySelector('.cfop-cube canvas')?.scrollIntoView({block:'center'});await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));return {scrolledToCube:true};", 2000);
            record("landscape-layout", evalObject("({landscape:innerWidth>innerHeight,width:innerWidth,height:innerHeight,canvasWidth:document.querySelector('.cfop-cube canvas')?.getBoundingClientRect().width,orientationRequested:true})").put("rotationObserved", landscape));
            screenshot(landscape ? "05-solver-landscape" : "05-solver-orientation-unchanged");
            runOnMainSync(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            waitForOptional("innerHeight>innerWidth", 3500);
            runJs("document.querySelector('.cfop-cube canvas')?.scrollIntoView({block:'center'});await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));return {scrolledToCube:true};", 2000);
            screenshot("06-solver-portrait-paused");
        } catch (Throwable error) {
            failure = error;
            try { screenshot("failure"); } catch (Throwable ignored) { }
        } finally {
            if (webView != null && storageBackup != null) {
                try {
                    final String snapshot = storageBackup.toString();
                    evalObject("(()=>{RG2App?.navigate({type:'settings'});for(const k of Object.keys(localStorage))if(k.startsWith('rg2.'))localStorage.removeItem(k);const old=" + snapshot + ";for(const [k,v]of Object.entries(old))localStorage.setItem(k,v);return {restored:true}})()");
                    restored = true;
                } catch (Throwable error) { if (failure == null) failure = error; }
            }
            if (activity != null) runOnMainSync(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
        }
        Bundle result = new Bundle();
        boolean passed = failure == null;
        try {
            JSONObject report = new JSONObject().put("passed", passed).put("durationMs", SystemClock.elapsedRealtime() - started)
                .put("targetPackage", PACKAGE).put("api", android.os.Build.VERSION.SDK_INT).put("device", android.os.Build.MODEL)
                .put("versionName", targetVersionName).put("versionCode", targetVersionCode)
                .put("checks", checks).put("screenshots", screenshots).put("storageRestored", restored)
                .put("offlineEvidence", "No INTERNET permission; only APK-local same-origin resource requests")
                .put("error", failure == null ? JSONObject.NULL : failure.toString());
            if (output != null) {
                File reportFile = new File(output, "offline-smoke-result.json");
                try (FileOutputStream stream = new FileOutputStream(reportFile)) { stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
                result.putString("reportPath", reportFile.getAbsolutePath());
            }
            result.putString("report", report.toString());
            result.putString("screenshots", screenshots.toString());
        } catch (Exception error) { passed = false; result.putString("reportError", error.toString()); }
        result.putBoolean("passed", passed);
        if (failure != null) result.putString("error", failure.toString());
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private static WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { WebView found = findWebView(group.getChildAt(i)); if (found != null) return found; }
        }
        return null;
    }
    private void record(String name, JSONObject details) throws Exception {
        checks.put(new JSONObject().put("name", name).put("passed", true).put("elapsedMs", SystemClock.elapsedRealtime() - started).put("details", details));
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private long remaining(long requested) {
        long available = deadline - SystemClock.elapsedRealtime();
        require(available > 0, "Global smoke-test time budget exceeded");
        return Math.min(requested, available);
    }
    private JSONObject evalObject(String expression) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        runOnMainSync(() -> webView.evaluateJavascript("JSON.stringify(" + expression + ")", value -> { result.set(value); latch.countDown(); }));
        require(latch.await(remaining(5000), TimeUnit.MILLISECONDS), "evaluateJavascript callback timed out");
        Object outer = new JSONTokener(result.get() == null ? "null" : result.get()).nextValue();
        require(outer instanceof String, "JavaScript did not return serialized object: " + result.get());
        return new JSONObject((String) outer);
    }
    private JSONObject runJs(String body, long timeout) throws Exception {
        String key = "step" + (++sequence), quoted = JSONObject.quote(key);
        evalObject("(()=>{const s=window.__rg2Smoke||(window.__rg2Smoke={});s[" + quoted + "]={done:false};(async()=>{" + body + "})().then(value=>s[" + quoted + "]={done:true,value},error=>s[" + quoted + "]={done:true,error:String(error),stack:error.stack});return {started:true}})()");
        long stop = SystemClock.elapsedRealtime() + remaining(timeout);
        do {
            JSONObject result = evalObject("(window.__rg2Smoke?.[" + quoted + "]||{done:false})");
            if (result.optBoolean("done")) {
                require(!result.has("error"), result.optString("error") + " " + result.optString("stack"));
                return result.getJSONObject("value");
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < stop);
        throw new AssertionError("Async JavaScript step timed out: " + key);
    }
    private void waitFor(String name, String expression, long timeout) throws Exception {
        require(waitForOptional(expression, timeout), name + " timed out; expression: " + expression);
    }
    private boolean waitForOptional(String expression, long timeout) throws Exception {
        long stop = SystemClock.elapsedRealtime() + remaining(timeout);
        do {
            JSONObject result = evalObject("(()=>{try{return {ok:Boolean(" + expression + ")}}catch(e){return {ok:false,error:String(e)}}})()");
            if (result.optBoolean("ok")) return true;
            SystemClock.sleep(125);
        } while (SystemClock.elapsedRealtime() < stop);
        return false;
    }
    private void screenshot(String name) throws Exception {
        require(output != null, "QA directory unavailable");
        waitForIdleSync();
        if (webView != null) runOnMainSync(() -> webView.postInvalidateOnAnimation());
        SystemClock.sleep(250);
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        require(bitmap != null, "UiAutomation screenshot unavailable");
        File path = new File(output, name + ".png");
        try (FileOutputStream stream = new FileOutputStream(path)) { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream), "PNG encoding failed"); }
        screenshots.put(new JSONObject().put("name", name).put("path", path.getAbsolutePath()).put("width", bitmap.getWidth()).put("height", bitmap.getHeight()));
        bitmap.recycle();
    }
}
