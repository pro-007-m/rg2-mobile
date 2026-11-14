package io.github.pro007m.rg2;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Identity of the installed target, independent of the fixture's build variant. */
final class InstalledTarget {
    static JSONObject inspect(Context target, Context fixture, Bundle arguments) throws Exception {
        PackageManager pm = target.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo app = pm.getPackageInfo(target.getPackageName(), flags);
        PackageInfo test = pm.getPackageInfo(fixture.getPackageName(), flags);
        ApplicationInfo ai = target.getApplicationInfo();
        boolean debuggable = (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        boolean testOnly = (ai.flags & ApplicationInfo.FLAG_TEST_ONLY) != 0;
        String installedHash = fileSha256(ai.sourceDir);
        String fixtureHash = fileSha256(fixture.getApplicationInfo().sourceDir);
        List<String> appSigners = signers(app), testSigners = signers(test);
        String buildType = "unknown";
        try {
            buildType = String.valueOf(target.getClassLoader().loadClass(target.getPackageName() + ".BuildConfig").getField("BUILD_TYPE").get(null));
        } catch (ReflectiveOperationException ignored) { }
        long versionCode = Build.VERSION.SDK_INT >= 28 ? app.getLongVersionCode() : app.versionCode;
        String releaseArgument = arguments.getString("requireRelease", "false");
        check("true".equals(releaseArgument) || "false".equals(releaseArgument), "requireRelease must be true or false");
        boolean releaseRequired = "true".equals(releaseArgument);
        String expectedHash = hashArgument(arguments, "expectedApkSha256");
        String expectedFixtureHash = hashArgument(arguments, "expectedFixtureSha256");
        String expectedSigner = hashArgument(arguments, "expectedSignerSha256");
        String expectedVersion = arguments.getString("expectedVersionName", "");
        String expectedCode = arguments.getString("expectedVersionCode", "");
        if (releaseRequired) {
            check(!expectedHash.isEmpty() && !expectedFixtureHash.isEmpty() && !expectedSigner.isEmpty() && !expectedVersion.isEmpty() && !expectedCode.isEmpty(),
                "Release gate requires expected target/fixture APK SHA256, signer SHA256, versionName and versionCode");
            check(!debuggable, "Release gate rejected a debuggable target");
            check(!testOnly, "Release gate rejected a testOnly target");
            check("release".equals(buildType), "Release gate rejected target BuildConfig.BUILD_TYPE=" + buildType);
        }
        if (!expectedHash.isEmpty()) check(expectedHash.equals(installedHash), "Installed target APK SHA256 does not match the frozen artifact");
        if (!expectedFixtureHash.isEmpty()) check(expectedFixtureHash.equals(fixtureHash), "Installed fixture APK SHA256 does not match the selected test artifact");
        if (!expectedSigner.isEmpty()) check(appSigners.size() == 1 && appSigners.get(0).equals(expectedSigner), "Installed target signer does not match the expected certificate");
        if (!expectedVersion.isEmpty()) check(expectedVersion.equals(app.versionName), "Installed target versionName mismatch");
        if (!expectedCode.isEmpty()) check(Long.parseLong(expectedCode) == versionCode, "Installed target versionCode mismatch");
        check(appSigners.equals(testSigners) && pm.checkSignatures(target.getPackageName(), fixture.getPackageName()) == PackageManager.SIGNATURE_MATCH,
            "Target and instrumentation fixture signing certificates differ");
        return new JSONObject().put("packageName", target.getPackageName()).put("versionName", app.versionName)
            .put("versionCode", versionCode).put("buildType", buildType).put("debuggable", debuggable).put("testOnly", testOnly)
            .put("installedApkSha256", installedHash).put("signerSha256", new JSONArray(appSigners))
            .put("fixtureApkSha256", fixtureHash).put("expectedFixtureSha256", expectedFixtureHash)
            .put("fixturePackage", fixture.getPackageName()).put("fixtureSignerSha256", new JSONArray(testSigners))
            .put("releaseGateRequired", releaseRequired).put("expectedApkSha256", expectedHash).put("expectedSignerSha256", expectedSigner);
    }
    private static String hashArgument(Bundle arguments, String key) {
        String value = arguments.getString(key, "").toLowerCase(Locale.ROOT);
        check(value.isEmpty() || value.matches("[a-f0-9]{64}"), key + " must be a SHA256 hex digest");
        return value;
    }
    private static List<String> signers(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 ? info.signingInfo.getApkContentsSigners() : info.signatures;
        check(signatures != null && signatures.length > 0, "Installed package has no signing certificate");
        List<String> hashes = new ArrayList<>();
        for (Signature signature : signatures) hashes.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        Collections.sort(hashes);
        return hashes;
    }
    private static String fileSha256(String path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[128 * 1024];
        try (FileInputStream stream = new FileInputStream(path)) { int count; while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count); }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) { result.append(Character.forDigit((b >>> 4) & 15, 16)); result.append(Character.forDigit(b & 15, 16)); }
        return result.toString();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
