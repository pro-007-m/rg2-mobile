package io.github.pro007m.rg2;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private Bundle testArguments = new Bundle();
    private JSONObject installedTarget;
    private JSONObject virtualDatabaseBackup;
    private boolean virtualPuzzles;
    private boolean virtualDatabaseRestored;
    private boolean reviewMode;
    private JSONObject reviewDatabaseBackup;
    private boolean reviewDatabaseRestored;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null) {
            testArguments = new Bundle(arguments);
            String virtualArgument = arguments.getString("virtualPuzzles", "false");
            require("true".equals(virtualArgument) || "false".equals(virtualArgument), "virtualPuzzles must be true or false");
            virtualPuzzles = "true".equals(virtualArgument);
            String reviewArgument = arguments.getString("reviewMode", "false");
            require("true".equals(reviewArgument) || "false".equals(reviewArgument), "reviewMode must be true or false");
            reviewMode = "true".equals(reviewArgument);
            long maximum = reviewMode ? 240000 : virtualPuzzles ? 180000 : 90000;
            try { limit = Math.max(40000, Math.min(maximum, Long.parseLong(arguments.getString("timeoutMs", String.valueOf(maximum))))); }
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
            installedTarget = InstalledTarget.inspect(getTargetContext(), getContext(), testArguments);
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
            storageBackup = evalObject("Object.fromEntries(Object.keys(localStorage).filter(k=>k.startsWith('rg2.')||k.startsWith('twistforge.')).map(k=>[k,localStorage.getItem(k)]))");
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
            if (virtualPuzzles) runVirtualChecks();
            if (reviewMode) runReviewChecks();
        } catch (Throwable error) {
            failure = error;
            try { screenshot("failure"); } catch (Throwable ignored) { }
        } finally {
            if (webView != null && storageBackup != null) {
                try {
                    final String snapshot = storageBackup.toString();
                    evalObject("(()=>{RG2App?.navigate({type:'settings'});return {navigated:true}})()");
                    if (virtualDatabaseBackup != null) {
                        final String databaseSnapshot = virtualDatabaseBackup.toString();
                        runJs("const snapshot=" + databaseSnapshot + ";const db=await new Promise((ok,bad)=>{const r=indexedDB.open('rg2-puzzle-games',1);r.onupgradeneeded=()=>r.result.createObjectStore('sessions');r.onsuccess=()=>ok(r.result);r.onerror=()=>bad(r.error)});await new Promise((ok,bad)=>{const t=db.transaction('sessions','readwrite'),s=t.objectStore('sessions');s.clear();for(const e of snapshot.entries)s.put(e.value,e.key);t.oncomplete=ok;t.onerror=()=>bad(t.error)});db.close();return {restored:true};", 10000);
                        virtualDatabaseRestored = true;
                    }
                    if (reviewDatabaseBackup != null) {
                        final String reviewSnapshot = reviewDatabaseBackup.toString();
                        runJs("const snapshot=" + reviewSnapshot + ";const db=await new Promise((ok,bad)=>{const r=indexedDB.open('twistforge-review',1);r.onupgradeneeded=()=>r.result.createObjectStore('workspace');r.onsuccess=()=>ok(r.result);r.onerror=()=>bad(r.error)});await new Promise((ok,bad)=>{const t=db.transaction('workspace','readwrite'),s=t.objectStore('workspace');s.clear();for(const e of snapshot.entries)s.put(e.value,e.key);t.oncomplete=ok;t.onerror=()=>bad(t.error)});db.close();return {restored:true};", 10000);
                        reviewDatabaseRestored = true;
                    }
                    evalObject("(()=>{for(const k of Object.keys(localStorage))if(k.startsWith('rg2.')||k.startsWith('twistforge.'))localStorage.removeItem(k);const old=" + snapshot + ";for(const [k,v]of Object.entries(old))localStorage.setItem(k,v);return {restored:true}})()");
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
                .put("installedTarget", installedTarget == null ? JSONObject.NULL : installedTarget)
                .put("testedBuildType", installedTarget == null ? "unknown" : installedTarget.optString("buildType", "unknown"))
                .put("virtualPuzzlesRequested", virtualPuzzles).put("virtualDatabaseRestored", virtualDatabaseRestored)
                .put("reviewModeRequested", reviewMode).put("reviewDatabaseRestored", reviewDatabaseRestored)
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

    /** Optional review extension preserves both historical smoke-test contracts. */
    private void runReviewChecks() throws Exception {
        String appLabel = getTargetContext().getPackageManager().getApplicationLabel(getTargetContext().getApplicationInfo()).toString();
        require("TwistForge".equals(appLabel), "Visible Android application name was not renamed");
        waitFor("review controller", "Boolean(window.TwistForgeReview)", 10000);
        runJs("await TwistForgeReview.ready;return {ready:true};", 10000);
        reviewDatabaseBackup = runJs("const db=await new Promise((ok,bad)=>{const r=indexedDB.open('twistforge-review',1);r.onupgradeneeded=()=>r.result.createObjectStore('workspace');r.onsuccess=()=>ok(r.result);r.onerror=()=>bad(r.error)});const entries=await new Promise((ok,bad)=>{const entries=[],r=db.transaction('workspace').objectStore('workspace').openCursor();r.onsuccess=()=>{const c=r.result;if(c){entries.push({key:c.key,value:c.value});c.continue()}else ok(entries)};r.onerror=()=>bad(r.error)});db.close();return {entries};", 10000);
        // Reset only the local convenience gate in the isolated QA profile. The
        // unlock itself below uses five real touch events and the actual form.
        evalObject("(localStorage.removeItem('twistforge.developer.enabled.v1'),window.__reviewReload=true,{reset:true})");
        runOnMainSync(() -> webView.reload());
        SystemClock.sleep(150);
        waitFor("review reload", "Boolean(!window.__reviewReload&&window.TwistForgeReview)", 10000);
        JSONObject build = runJs("await RG2App.ready;await TwistForgeReview.ready;RG2App.navigate({type:'settings'});await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));const e=document.querySelector('#app-version');if(!e||!e.classList.contains('tf-build-number'))throw Error('build number not bound');e.scrollIntoView({block:'center'});await new Promise(r=>requestAnimationFrame(r));if(document.querySelector('.tf-dev-challenge'))throw Error('challenge appears without five taps');const q=e.getBoundingClientRect();return {left:q.left,top:q.top,width:q.width,height:q.height,innerWidth};", 5000);
        float[] position = screenPoint(build, .5f, .5f);
        for (int i = 0; i < 4; i++) { touchTap(position[0], position[1]); SystemClock.sleep(110); }
        require(!evalObject("({shown:Boolean(document.querySelector('.tf-dev-challenge'))})").getBoolean("shown"), "Challenge appeared before fifth tap");
        touchTap(position[0], position[1]);
        waitFor("developer challenge", "Boolean(document.querySelector('.tf-dev-challenge'))", 3000);
        runJs("const c=document.querySelector('.tf-dev-challenge');if(c.querySelector('h3').textContent!=='Что ты здесь делаешь?!?')throw Error('challenge title');c.querySelector('input').value='неверный код';c.querySelector('button').click();if(c.querySelector('input').getAttribute('aria-invalid')!=='true'||localStorage.getItem('twistforge.developer.enabled.v1')==='true')throw Error('wrong code accepted');c.querySelector('input').value='Я знал, что так будет';c.querySelector('button').click();return {wrongCodeRejected:true};", 3000);
        waitFor("developer unlock", "localStorage.getItem('twistforge.developer.enabled.v1')==='true'&&!document.querySelector('.tf-unlock-flight')&&Boolean(document.querySelector('.tf-review-launcher:not([hidden])'))", 6000);
        record("review-developer-unlock", runJs("return {fiveNativeTaps:true,wrongCodeRejected:true,enabled:localStorage.getItem('twistforge.developer.enabled.v1')==='true',launcher:!document.querySelector('.tf-review-launcher').hidden,icon:document.querySelector('.tf-review-launcher img')?.getAttribute('src')};", 2000).put("applicationLabel", appLabel));
        screenshot("11-review-developer-unlocked");
        runReviewEditChecks();
        runReviewDocumentChecks();
    }

    private void touchTap(float x, float y) {
        long down = SystemClock.uptimeMillis();
        sendTouch(down, MotionEvent.ACTION_DOWN, new float[][] {{x, y}});
        SystemClock.sleep(40);
        sendTouch(down, MotionEvent.ACTION_UP, new float[][] {{x, y}});
    }

    private JSONObject reviewCase;
    private void runReviewEditChecks() throws Exception {
        runJs("const item=[...RG2App.items.values()].find(i=>i.kind==='article'&&!/^ABOUT/.test(i.id)&&i.html?.length>50);window.__reviewCase={lessonId:item.id,marker:'TwistForge QA '+Date.now(),source:item.html};RG2App.openItem(item.id);return {lessonId:item.id};", 3000);
        waitFor("review lesson context", "Boolean(TwistForgeReview.debug().contextReady)", 5000);
        runJs("document.querySelector('.tf-review-launcher').click();await new Promise(r=>setTimeout(r,100));document.querySelector('[data-tf-action=\"rewrite\"]').click();return {opened:true};", 2000);
        waitFor("rewrite editor", "Boolean(document.querySelector('.tf-review-rich-editor'))", 3000);
        runJs("const e=document.querySelector('.tf-review-rich-editor');e.innerHTML='<p>'+window.__reviewCase.marker+'</p><p>Алгоритм: R U R&#39; U&#39;</p>';e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText'}));document.querySelector('[data-tf-action=\"save-composer\"]').click();return {edited:true};", 2000);
        waitFor("review saved", "Boolean(TwistForgeReview.debug().composer?.noteId)&&!TwistForgeReview.debug().composer.dirty&&!TwistForgeReview.debug().dirty", 5000);
        reviewCase = runJs("window.__reviewCase.noteId=TwistForgeReview.debug().composer.noteId;window.__reviewCase.count=TwistForgeReview.debug().notes;document.querySelector('[data-tf-action=\"composer-preview\"]').click();return {...window.__reviewCase};", 2000);
        waitFor("rewritten preview", "TwistForgeReview.debug().preview&&document.querySelector('#article-body').textContent.includes(window.__reviewCase.marker)", 5000);
        runJs("if(RG2App.items.get(window.__reviewCase.lessonId).html!==window.__reviewCase.source)throw Error('source lesson mutated');if(TwistForgeReview.debug().conflicts.length)throw Error('unexpected preview conflict');document.querySelector('[data-tf-action=\"close-composer\"]').click();return {sourceUntouched:true};", 2000);
        screenshot("12-review-preview-portrait");
        evalObject("(window.__reviewPersistenceReloadMarker=true,{marked:true})");
        runOnMainSync(() -> webView.reload());
        SystemClock.sleep(200);
        waitFor("review persisted reload", "Boolean(!window.__reviewPersistenceReloadMarker&&window.TwistForgeReview&&window.RG2App?.catalog)", 10000);
        final String lesson = JSONObject.quote(reviewCase.getString("lessonId"));
        runJs("await RG2App.ready;await TwistForgeReview.ready;RG2App.openItem(" + lesson + ");return {ready:true};", 5000);
        waitFor("persisted lesson context", "Boolean(TwistForgeReview.debug().contextReady)", 5000);
        runJs("if(!TwistForgeReview.debug().unlocked)throw Error('developer gate lost on reload');document.querySelector('.tf-review-launcher').click();await new Promise(r=>setTimeout(r,100));document.querySelector('[data-tf-action=\"preview-result\"]').click();return {requested:true};", 2000);
        String marker = JSONObject.quote(reviewCase.getString("marker"));
        waitFor("persisted preview", "document.querySelector('#article-body').textContent.includes(" + marker + ")", 5000);
        runJs("const view=document.querySelector('[data-tf-action=\"view-lesson\"]');if(!view)throw Error('full lesson preview control missing');view.click();return {clicked:true};", 2000);
        waitFor("unobstructed mobile preview", "!TwistForgeReview.debug().panelOpen&&TwistForgeReview.debug().preview", 3000);
        runJs("document.querySelector('#article-body').scrollIntoView({block:'center'});await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));return {visible:true};", 2000);
        record("review-edit-preview-persistence", evalObject("({lessonId:" + lesson + ",noteId:" + JSONObject.quote(reviewCase.getString("noteId")) + ",notes:TwistForgeReview.debug().notes,persisted:true,preview:TwistForgeReview.debug().preview,fullLessonVisible:!TwistForgeReview.debug().panelOpen,sourceUnchanged:!RG2App.items.get(" + lesson + ").html.includes(" + marker + ")})"));
        screenshot("13-review-persisted-preview");
        runJs("document.querySelector('.tf-review-launcher').click();await new Promise(r=>setTimeout(r,100));return {panelOpen:TwistForgeReview.debug().panelOpen};", 2000);
    }

    private void runReviewDocumentChecks() throws Exception {
        // Substitute a QA-owned selection for the system picker, then let the
        // actual trusted WebMessage bridge and ContentResolver do all I/O.
        File document = new File(output, "review-roundtrip-" + SystemClock.elapsedRealtime() + ".json");
        Intent result = new Intent().setData(Uri.fromFile(document));
        IntentFilter exportFilter = new IntentFilter(Intent.ACTION_CREATE_DOCUMENT); exportFilter.addDataType("application/json"); exportFilter.addCategory(Intent.CATEGORY_OPENABLE);
        ActivityMonitor exportMonitor = addMonitor(exportFilter, new ActivityResult(Activity.RESULT_OK, result), true);
        try {
            runJs("document.querySelector('[data-tf-action=\"export\"]').click();return {exportClicked:true};", 2000);
            long stop = SystemClock.elapsedRealtime() + remaining(5000);
            while ((!document.isFile() || document.length() == 0 || nativeDocumentBusy()) && SystemClock.elapsedRealtime() < stop) SystemClock.sleep(80);
            require(exportMonitor.getHits() == 1 && document.isFile() && document.length() > 0, "Native review export did not reach the document picker and chosen file");
        } finally { removeMonitor(exportMonitor); }
        String exported = new String(Files.readAllBytes(document.toPath()), StandardCharsets.UTF_8);
        JSONObject packageData = new JSONObject(exported);
        require("twistforge-review".equals(packageData.optString("schema")) && packageData.getInt("version") == 1, "Native export schema mismatch");
        require(exported.getBytes(StandardCharsets.UTF_8).length <= 8 * 1024 * 1024 && exported.contains(reviewCase.getString("marker")), "Native export lost review text or exceeded limit");
        final String noteId = JSONObject.quote(reviewCase.getString("noteId"));
        runJs("const b=[...document.querySelectorAll('[data-tf-action=\"delete-note\"]')].find(e=>e.dataset.noteId===" + noteId + ");if(!b)throw Error('saved note not listed');b.click();return {removed:true};", 2000);
        int expected = reviewCase.getInt("count");
        waitFor("note removed before import", "TwistForgeReview.debug().notes===" + (expected - 1), 4000);
        IntentFilter importFilter = new IntentFilter(Intent.ACTION_OPEN_DOCUMENT); importFilter.addDataType("*/*"); importFilter.addCategory(Intent.CATEGORY_OPENABLE);
        ActivityMonitor importMonitor = addMonitor(importFilter, new ActivityResult(Activity.RESULT_OK, result), true);
        try {
            runJs("document.querySelector('[data-tf-action=\"import\"]').click();return {importClicked:true};", 2000);
            waitFor("native review import callback", "TwistForgeReview.debug().notes===" + expected, 5000);
            require(importMonitor.getHits() == 1, "Native review import did not use the document picker");
        } finally { removeMonitor(importMonitor); }
        require(!nativeDocumentBusy(), "Native document operation did not release its guard");
        record("review-native-document-roundtrip", new JSONObject().put("nativeExport", true).put("nativeImport", true).put("systemPickerIntents", true)
            .put("selection", "instrumentation-selected QA-owned file").put("bytes", document.length()).put("schema", packageData.getString("schema"))
            .put("lessons", packageData.getJSONArray("lessons").length()).put("notes", packageData.getJSONArray("notes").length()).put("restoredDeletedNote", true));
    }

    private boolean nativeDocumentBusy() {
        AtomicReference<Boolean> busy = new AtomicReference<>(false);
        runOnMainSync(() -> { try { java.lang.reflect.Field field = MainActivity.class.getDeclaredField("documentPickerOpen"); field.setAccessible(true); busy.set(field.getBoolean(activity)); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); } });
        return busy.get();
    }

    /** The optional extension preserves the original 13-check contract. */
    private void runVirtualChecks() throws Exception {
        virtualDatabaseBackup = runJs("""
            const db=await new Promise((ok,bad)=>{const r=indexedDB.open('rg2-puzzle-games',1);r.onupgradeneeded=()=>r.result.createObjectStore('sessions');r.onsuccess=()=>ok(r.result);r.onerror=()=>bad(r.error)});
            const entries=await new Promise((ok,bad)=>{const entries=[],r=db.transaction('sessions').objectStore('sessions').openCursor();r.onsuccess=()=>{const c=r.result;if(c){entries.push({key:c.key,value:c.value});c.continue()}else ok(entries)};r.onerror=()=>bad(r.error)});db.close();return {entries};
            """, 10000);
        evalObject("(RG2App.navigate({type:'tool',id:'virtual-puzzles'}),{navigated:true})");
        waitFor("virtual game mounts", "Boolean(document.querySelector('#tool-host')?.virtualPuzzle)", 10000);
        record("virtual-game-boot", runJs("""
            const h=document.querySelector('#tool-host'),t=h.virtualPuzzle;
            if(!await t.ready)throw Error('virtual tool did not become ready');
            await t.newGame({kind:'cube',size:3});
            const speed=h.querySelector('[data-setting="speed"]');speed.value='4';speed.dispatchEvent(new Event('input',{bubbles:true}));
            const pause=h.querySelector('[data-setting="pause-choice"]');pause.checked=true;pause.dispatchEvent(new Event('change',{bubbles:true}));
            const canvas=h.querySelector('[data-role="scene"] canvas');canvas.scrollIntoView({block:'center'});await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
            const d=t.debug(),rect=canvas.getBoundingClientRect(),gl=canvas.getContext('webgl2');
            if(d.spec.kind!=='cube'||d.spec.size!==3||d.state.length!==54||d.state[31]!==0||!d.solved)throw Error('initial cube/frame invalid');
            if(!gl||gl.isContextLost()||d.visual.contextLost||!d.visual.opaqueCaps||d.visual.caps!==54||rect.width<100||rect.height<100)throw Error('virtual WebGL2 cube unavailable');
            if(d.visual.state.some((v,i)=>v!==d.state[i]))throw Error('virtual renderer disagrees with logical state');
            if(h.querySelectorAll('[data-setting="kind"] option').length!==3)throw Error('three puzzle families missing');
            return {spec:d.spec,whiteDown:true,webgl2:true,opaqueCaps:d.visual.opaqueCaps,quality:d.visual.quality,canvas:{width:canvas.width,height:canvas.height}};
            """, 10000));
        screenshot("07-virtual-cube-portrait");

        record("virtual-nxn50", runJs("""
            const h=document.querySelector('#tool-host'),t=h.virtualPuzzle;
            await t.newGame({kind:'cube',size:50});const initial=t.debug().initial;
            await t.dispatch("25Rw U2 2R'");
            for(let i=0;i<120&&(t.debug().running||t.debug().queued||t.debug().cursor!==3);i++)await new Promise(r=>setTimeout(r,50));
            const d=t.debug(),core=await import(new URL('./puzzles/core.mjs',location.href).href),expected=core.applyAlg(initial,50,"25Rw U2 2R'");
            if(d.spec.size!==50||d.state.length!==15000||d.cursor!==3||d.running||d.queued||d.solved)throw Error('50x50 wide/inner turn did not complete');
            if(expected.some((v,i)=>v!==d.state[i])||d.visual.state.some((v,i)=>v!==d.state[i])||d.visual.caps!==15000||!d.visual.opaqueCaps)throw Error('50x50 replay/renderer mismatch');
            const canvas=h.querySelector('[data-role="scene"] canvas');canvas.scrollIntoView({block:'center'});await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
            if(canvas.getContext('webgl2').isContextLost())throw Error('50x50 WebGL context lost');
            return {size:50,facelets:d.state.length,moves:d.history,verifiedReplay:true,quality:d.visual.quality,triangles:d.visual.triangles,drawCalls:d.visual.drawCalls};
            """, 18000));
        screenshot("08-virtual-cube50");

        record("virtual-polyhedra", runJs("""
            const t=document.querySelector('#tool-host').virtualPuzzle,poly=await import(new URL('./puzzles/poly-core.mjs',location.href).href),results=[];
            for(const kind of ['pyramid','dodecahedron'])for(const size of [2,20]){
              await t.newGame({kind,size});const before=t.debug().initial,p=poly.createPolyPuzzle(kind,size),move=kind==='pyramid'?'U':'A';
              await t.dispatch(move);
              for(let i=0;i<100&&(t.debug().running||t.debug().queued||t.debug().cursor!==1);i++)await new Promise(r=>setTimeout(r,50));
              const d=t.debug(),expected=poly.applyPolyAlg(before,p,move);
              if(d.cursor!==1||d.state.some((v,i)=>v!==expected[i])||d.visual.state.some((v,i)=>v!==d.state[i])||!d.visual.opaqueCaps||d.visual.contextLost)throw Error(kind+size+' replay/opaque model failed');
              if(d.visual.caps!==p.facelets.filter(f=>!f.hidden&&!f.black).length)throw Error(kind+size+' colored cap count mismatch');
              results.push({kind,size,facelets:d.state.length,coloredCaps:d.visual.caps,hiddenCaps:d.visual.hiddenCaps,quality:d.visual.quality});
            }
            document.querySelector('[data-role="scene"] canvas').scrollIntoView({block:'center'});await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));return {results};
            """, 25000));
        screenshot("09-virtual-dodeca20");

        record("virtual-ortega-worker", runJs("""
            const h=document.querySelector('#tool-host'),t=h.virtualPuzzle,core=await import(new URL('./puzzles/core.mjs',location.href).href);
            const OriginalWorker=window.Worker,packets=[];
            window.Worker=class extends OriginalWorker{constructor(url,options){super(url,options);this.qaPuzzle=String(url).includes('solver-general-worker.mjs')}postMessage(data,...rest){if(this.qaPuzzle)packets.push({keys:Object.keys(data),spec:data.spec,stateLength:data.state?.length,hasHistory:'history' in data||'moves' in data||'scramble' in data});return super.postMessage(data,...rest)}};
            try {
            await t.newGame({kind:'cube',size:2});await t.dispatch("R U F2 R'");
            for(let i=0;i<120&&(t.debug().running||t.debug().queued||t.debug().cursor!==4);i++)await new Promise(r=>setTimeout(r,50));
            if(t.debug().cursor!==4||t.debug().solved)throw Error('Ortega test position unavailable');
            const phases=[];
            for(let phase=0;phase<4&&!t.debug().solved;phase++){
              const before=t.debug().state;h.querySelector('[data-action="hint"]').click();
              for(let i=0;i<160&&(t.debug().searching||!t.debug().candidates.length);i++)await new Promise(r=>setTimeout(r,50));
              const d=t.debug(),candidate=d.candidates[0];if(!candidate||d.searching)throw Error('state-only Ortega worker returned no stage');
              if(!['D_FACE','OLL','PBL'].includes(candidate.stage))throw Error('wrong Ortega stage '+candidate.stage);
              const expected=core.applyAlg(before,2,candidate.moves);
              if(expected.some((v,i)=>v!==candidate.state[i]))throw Error('Ortega candidate replay invalid');
              const choice=h.querySelector('[data-choice="0"]');if(!choice)throw Error('visible algorithm choice missing');choice.click();
              for(let i=0;i<240&&(t.debug().running||t.debug().queued);i++)await new Promise(r=>setTimeout(r,50));
              const after=t.debug();if(after.running||after.queued||expected.some((v,i)=>v!==after.state[i])||after.visual.state.some((v,i)=>v!==after.state[i]))throw Error('chosen Ortega animation mismatch');
              phases.push({stage:candidate.stage,moves:candidate.moves,verified:true});
            }
            const final=t.debug();if(!final.solved||!final.assisted||!core.isSolved(final.state,2)||!phases.length)throw Error('Ortega pipeline did not solve cube');
            if(!packets.length||packets.some(p=>p.stateLength!==24||p.hasHistory||!p.keys.includes('spec')))throw Error('Ortega worker did not receive state-only puzzle requests');
            const workerClient=await import(new URL('./puzzles/solver-general.mjs',location.href).href),poly=await import(new URL('./puzzles/poly-core.mjs',location.href).href),workerImports=[];
            for(const spec of [{kind:'cube',size:4},{kind:'pyramid',size:3},{kind:'dodecahedron',size:3},{kind:'pyramid',size:20},{kind:'dodecahedron',size:20}]){
              const queryStarted=performance.now(),puzzle=spec.kind==='cube'?null:poly.createPolyPuzzle(spec.kind,spec.size);
              const initial=puzzle?puzzle.solvedState():core.applyAlg(core.solvedState(spec.size),spec.size,'x2'),move=spec.kind==='cube'?'2R':spec.kind==='pyramid'?'U':'A';
              const input=puzzle?poly.applyPolyAlg(initial,puzzle,move):core.applyAlg(initial,spec.size,move),snapshot=[...input],packetStart=packets.length;
              if(puzzle?poly.isPolySolved(input,puzzle):core.isSolved(input,spec.size))throw Error('worker import probe must start unsolved');
              const result=await workerClient.suggestPuzzle({spec,state:input,goal:initial},{maxMs:18000,method:'reduction'}),candidate=result.candidates?.[0];
              if(!candidate?.moves?.length||!candidate.state)throw Error(spec.kind+spec.size+' worker returned no real algorithm');
              if(snapshot.some((v,i)=>v!==input[i]))throw Error('worker probe mutated input');
              const actual=puzzle?poly.applyPolyAlg(input,puzzle,candidate.moves):core.applyAlg(input,spec.size,candidate.moves);
              if(actual.some((v,i)=>v!==candidate.state[i])||actual.every((v,i)=>v===input[i]))throw Error(spec.kind+spec.size+' candidate did not replay independently');
              if(spec.kind==='cube'){
                const reduction=await import(new URL('./puzzles/solver-nxn.mjs',location.href).href);
                if(candidate.stage!=='REDUCTION_CENTERS'||!reduction.extractVirtual3(actual,spec.size).centers)throw Error('4x4 worker did not solve the center phase');
              }else{
                if(!candidate.fixedIndices?.length||candidate.fixedIndices.some(i=>actual[i]!==puzzle.facelets[i].color))throw Error(spec.kind+spec.size+' claimed group is not fixed');
                if(spec.size===3&&!candidate.stage.startsWith('SGS_'))throw Error('standard polyhedron did not use SGS');
              }
              const observed=packets.slice(packetStart);if(observed.length!==1||observed[0].stateLength!==input.length||observed[0].hasHistory||observed[0].spec.kind!==spec.kind||observed[0].spec.size!==spec.size)throw Error('worker import probe was not state-only');
              workerImports.push({spec,stage:candidate.stage,method:result.method||candidate.method,moves:candidate.moves.length,facelets:input.length,independentReplay:true,stateOnlyWorker:true,elapsedMs:Math.round(performance.now()-queryStarted)});
            }
            return {phases,workerPackets:packets,workerImports,stateOnlyWorker:true,solved:true,assisted:final.assisted};
            } finally {window.Worker=OriginalWorker;}
            """, 140000));
        screenshot("10-virtual-ortega");

        JSONObject touchLayout = runJs("""
            const t=document.querySelector('#tool-host').virtualPuzzle;await t.newGame({kind:'cube',size:3});
            const c=document.querySelector('[data-role="scene"] canvas');c.scrollIntoView({block:'center'});await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
            const r=c.getBoundingClientRect();return {left:r.left,top:r.top,width:r.width,height:r.height,innerWidth,innerHeight,cursor:t.debug().cursor};
            """, 10000);
        float[] touch = screenPoint(touchLayout, .57f, .60f);
        float[] end = screenPoint(touchLayout, .78f, .46f);
        touchSwipe(touch[0], touch[1], end[0], end[1]);
        waitFor("native layer swipe", "document.querySelector('#tool-host').virtualPuzzle.debug().cursor===1&&!document.querySelector('#tool-host').virtualPuzzle.debug().running", 6000);
        JSONObject beforePinch = evalObject("(()=>{const d=document.querySelector('#tool-host').virtualPuzzle.debug();return {cursor:d.cursor,distance:d.visual.distance,theta:d.visual.theta,phi:d.visual.phi}})()");
        float[] pinchCenter = screenPoint(touchLayout, .50f, .55f);
        float scale = touchLayout.getInt("innerWidth") == 0 ? 1 : webView.getWidth() / (float) touchLayout.getInt("innerWidth");
        touchPinch(pinchCenter[0], pinchCenter[1], 36 * scale, 75 * scale);
        final String beforePinchJson = beforePinch.toString();
        JSONObject touchEvidence = runJs("""
            await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));const before=BEFORE,d=document.querySelector('#tool-host').virtualPuzzle.debug();
            if(d.cursor!==before.cursor||d.running||d.visual.distance>=before.distance-.05)throw Error('native pinch changed state or did not zoom');
            const core=await import(new URL('./puzzles/core.mjs',location.href).href),expected=core.applyAlg(d.initial,3,d.history);
            if(expected.some((v,i)=>v!==d.state[i])||d.visual.state.some((v,i)=>v!==d.state[i]))throw Error('native touch replay mismatch');
            return {swipeNative:true,pinchNative:true,logicalMoves:d.cursor,pinchAddedMoves:0,distanceBefore:before.distance,distanceAfter:d.visual.distance,state:d.state,cursor:d.cursor};
            """.replace("BEFORE", beforePinchJson), 5000);
        runOnMainSync(() -> callActivityOnPause(activity));
        SystemClock.sleep(200);
        runOnMainSync(() -> callActivityOnResume(activity));
        waitFor("native pause callback", "document.querySelector('#tool-host').virtualPuzzle.debug().paused", 5000);
        final String expectedTouch = touchEvidence.toString();
        runJs("""
            const expected=EXPECTED,model=await import(new URL('./puzzles/model.mjs',location.href).href),saved=await model.loadGame();
            if(!saved||saved.cursor!==expected.cursor||saved.state.some((v,i)=>v!==expected.state[i]))throw Error('pause did not preserve IndexedDB game');
            window.__virtualReloadMarker=true;return {indexedDBSaved:true};
            """.replace("EXPECTED", expectedTouch), 6000);
        runOnMainSync(() -> webView.reload());
        waitFor("virtual reload boot", "!window.__virtualReloadMarker&&Boolean(window.RG2App?.catalog)&&Boolean(document.querySelector('#tool-host')?.virtualPuzzle)", 10000);
        JSONObject restored = runJs("""
            const expected=EXPECTED,t=document.querySelector('#tool-host').virtualPuzzle;if(!await t.ready)throw Error('virtual reload failed');
            const d=t.debug();if(d.cursor!==expected.cursor||d.state.some((v,i)=>v!==expected.state[i])||d.visual.state.some((v,i)=>v!==d.state[i]))throw Error('IndexedDB reload changed the game');
            document.querySelector('[data-role="scene"] canvas').scrollIntoView({block:'center'});await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
            return {nativePauseResumeCallbacks:true,indexedDBRestored:true,cursor:d.cursor,opaqueCaps:d.visual.opaqueCaps};
            """.replace("EXPECTED", expectedTouch), 10000);
        JSONObject fullscreen = runJs("""
            const root=document.querySelector('.puz'),t=document.querySelector('#tool-host').virtualPuzzle,before=t.debug(),route=location.hash;
            document.querySelector('[data-action="fullscreen"]').click();await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
            if(!root.classList.contains('puz-fullscreen'))throw Error('mobile fullscreen did not open');
            const bounds=root.getBoundingClientRect();
            if(bounds.top<-.5||bounds.left<-.5||Math.abs(bounds.width-innerWidth)>2||Math.abs(bounds.height-innerHeight)>2)throw Error('mobile fullscreen does not fill WebView');
            return {cursor:before.cursor,state:before.state,route,opened:true,width:bounds.width,height:bounds.height};
            """, 5000);
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitFor("native Back exits puzzle fullscreen", "!document.querySelector('.puz')?.classList.contains('puz-fullscreen')", 5000);
        JSONObject fullscreenBack = runJs("""
            const before=BEFORE,t=document.querySelector('#tool-host')?.virtualPuzzle;if(!t||location.hash!==before.route)throw Error('native Back left the game');
            const d=t.debug();if(d.cursor!==before.cursor||d.state.some((v,i)=>v!==before.state[i]))throw Error('native Back changed the game');
            if(document.querySelector('[data-action="fullscreen"]').textContent!=='Полный экран')throw Error('fullscreen label was not restored');
            document.querySelector('[data-role="scene"] canvas').scrollIntoView({block:'center'});await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
            return {opened:before.opened,backKeyNative:true,exited:true,routePreserved:true,statePreserved:true,width:before.width,height:before.height};
            """.replace("BEFORE", fullscreen.toString()), 5000);
        touchEvidence.remove("state");touchEvidence.put("restore", restored).put("fullscreen", fullscreenBack);
        record("virtual-native-touch-and-restore", touchEvidence);
        screenshot("11-virtual-touch-restored");
    }

    private float[] screenPoint(JSONObject layout, float horizontal, float vertical) throws Exception {
        int[] position = new int[2];
        runOnMainSync(() -> webView.getLocationOnScreen(position));
        float scale = webView.getWidth() / (float) layout.getDouble("innerWidth");
        return new float[] {position[0] + (float) (layout.getDouble("left") + layout.getDouble("width") * horizontal) * scale,
            position[1] + (float) (layout.getDouble("top") + layout.getDouble("height") * vertical) * scale};
    }

    private void touchSwipe(float startX, float startY, float endX, float endY) {
        long down = SystemClock.uptimeMillis();
        sendTouch(down, MotionEvent.ACTION_DOWN, new float[][] {{startX, startY}});
        for (int i = 1; i <= 12; i++) {
            SystemClock.sleep(16);
            sendTouch(down, MotionEvent.ACTION_MOVE, new float[][] {{startX + (endX - startX) * i / 12, startY + (endY - startY) * i / 12}});
        }
        sendTouch(down, MotionEvent.ACTION_UP, new float[][] {{endX, endY}});
    }

    private void touchPinch(float centerX, float centerY, float startRadius, float endRadius) {
        long down = SystemClock.uptimeMillis();
        sendTouch(down, MotionEvent.ACTION_DOWN, new float[][] {{centerX - startRadius, centerY}});
        sendTouch(down, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new float[][] {{centerX - startRadius, centerY}, {centerX + startRadius, centerY}});
        for (int i = 1; i <= 12; i++) {
            SystemClock.sleep(16);
            float radius = startRadius + (endRadius - startRadius) * i / 12;
            sendTouch(down, MotionEvent.ACTION_MOVE, new float[][] {{centerX - radius, centerY}, {centerX + radius, centerY}});
        }
        sendTouch(down, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new float[][] {{centerX - endRadius, centerY}, {centerX + endRadius, centerY}});
        sendTouch(down, MotionEvent.ACTION_UP, new float[][] {{centerX - endRadius, centerY}});
    }

    private void sendTouch(long downTime, int action, float[][] points) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[points.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[points.length];
        for (int i = 0; i < points.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();properties[i].id = i;properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();coordinates[i].x = points[i][0];coordinates[i].y = points[i][1];coordinates[i].pressure = 1;coordinates[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, points.length, properties, coordinates, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { sendPointerSync(event); } finally { event.recycle(); }
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
