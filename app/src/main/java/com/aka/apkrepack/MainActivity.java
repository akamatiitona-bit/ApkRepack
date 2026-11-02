package com.aka.apkrepack;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final int REQ_SAVE = 2;
    private static final Pattern PKG_RE =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$");

    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText nameEt, pkgEt;
    private TextView infoTv, logTv;
    private Button buildBtn, installBtn, saveBtn;

    private List<File> inputs = new ArrayList<>();
    private List<File> outputs = new ArrayList<>();
    private String origPkg, origLabel, lastPkg;
    private boolean obbFound;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int p = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p * 2, p, p);

        Button pick = button("① APKを選ぶ（apk / apks / xapk / apkm）");
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_PICK);
        });
        root.addView(pick);

        infoTv = new TextView(this);
        infoTv.setText("ファイルが選ばれていません");
        infoTv.setPadding(0, p, 0, p);
        root.addView(infoTv);

        nameEt = new EditText(this);
        nameEt.setHint("アプリ名（例: discord）");
        nameEt.setSingleLine(true);
        root.addView(nameEt);

        pkgEt = new EditText(this);
        pkgEt.setHint("パッケージ名（例: aka → com.aka になります）");
        pkgEt.setSingleLine(true);
        pkgEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(pkgEt);

        buildBtn = button("② 別アプリ版を作る");
        buildBtn.setEnabled(false);
        buildBtn.setOnClickListener(v -> build());
        root.addView(buildBtn);

        installBtn = button("③ インストール");
        installBtn.setEnabled(false);
        installBtn.setOnClickListener(v -> install());
        root.addView(installBtn);

        saveBtn = button("ファイルとして保存");
        saveBtn.setEnabled(false);
        saveBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(outputs.size() == 1
                    ? "application/vnd.android.package-archive" : "application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE, lastPkg + (outputs.size() == 1 ? ".apk" : ".apks"));
            startActivityForResult(i, REQ_SAVE);
        });
        root.addView(saveBtn);

        logTv = new TextView(this);
        logTv.setPadding(0, p, 0, 0);
        logTv.setTextIsSelectable(true);
        root.addView(logTv);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private Button button(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        return b;
    }

    private void log(String s) {
        ui.post(() -> logTv.append(s + "\n"));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_PICK) {
            loadFile(uri);
        } else if (req == REQ_SAVE) {
            ex.execute(() -> {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (outputs.size() == 1) {
                        try (InputStream is = new FileInputStream(outputs.get(0))) {
                            copy(is, os);
                        }
                    } else {
                        try (ZipOutputStream zos = new ZipOutputStream(os)) {
                            for (File f : outputs) {
                                zos.putNextEntry(new ZipEntry(f.getName()));
                                try (InputStream is = new FileInputStream(f)) {
                                    copy(is, zos);
                                }
                                zos.closeEntry();
                            }
                        }
                    }
                    ui.post(() -> Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    log("保存失敗: " + e);
                }
            });
        }
    }

    private static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteRec(x);
        }
        f.delete();
    }

    /** 単体APKならそのまま、apks/xapk/apkmなら中のAPKを全部取り出す。 */
    private List<File> extractApks(File f) throws IOException {
        File dir = new File(getCacheDir(), "splits");
        deleteRec(dir);
        dir.mkdirs();
        List<File> res = new ArrayList<>();
        obbFound = false;
        try (ZipFile zf = new ZipFile(f)) {
            if (zf.getEntry("AndroidManifest.xml") != null) {
                res.add(f);
                return res;
            }
            List<ZipEntry> es = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName().toLowerCase();
                if (e.isDirectory()) continue;
                if (n.endsWith(".apk")) es.add(e);
                else if (n.endsWith(".obb")) obbFound = true;
            }
            List<ZipEntry> pick = new ArrayList<>();
            for (ZipEntry e : es) {
                if (baseName(e.getName()).equalsIgnoreCase("universal.apk")) pick.add(e);
            }
            if (pick.isEmpty()) {
                for (ZipEntry e : es) {
                    String n = e.getName().toLowerCase();
                    if (!n.contains("standalones/") && !n.contains("instant/")) pick.add(e);
                }
            }
            if (pick.isEmpty()) pick = es;
            int i = 0;
            for (ZipEntry e : pick) {
                String bn = baseName(e.getName()).replaceAll("[^A-Za-z0-9._-]", "_");
                File o = new File(dir, (i++) + "_" + bn);
                try (InputStream is = zf.getInputStream(e);
                     OutputStream os = new FileOutputStream(o)) {
                    copy(is, os);
                }
                res.add(o);
            }
        }
        if (res.isEmpty()) throw new IOException("APKが見つかりません（対応していない形式です）");
        return res;
    }

    /** この端末のCPUに合わないCPU用の分割ファイルかどうか。 */
    private static boolean abiOk(String split) {
        String s = split.toLowerCase().replace('-', '_');
        String abi = null;
        if (s.endsWith("arm64_v8a")) abi = "arm64-v8a";
        else if (s.endsWith("armeabi_v7a")) abi = "armeabi-v7a";
        else if (s.endsWith("x86_64")) abi = "x86_64";
        else if (s.endsWith("x86")) abi = "x86";
        if (abi == null) return true;
        for (String a : Build.SUPPORTED_ABIS) if (a.equals(abi)) return true;
        return false;
    }

    private void loadFile(Uri uri) {
        buildBtn.setEnabled(false);
        installBtn.setEnabled(false);
        saveBtn.setEnabled(false);
        logTv.setText("");
        infoTv.setText("読み込み中…");
        ex.execute(() -> {
            try {
                File f = new File(getCacheDir(), "in.bin");
                try (InputStream is = getContentResolver().openInputStream(uri);
                     OutputStream os = new FileOutputStream(f)) {
                    copy(is, os);
                }
                List<File> all = extractApks(f);
                List<File> use = new ArrayList<>();
                int skipped = 0;
                File base = null;
                String pkg = null;
                for (File a : all) {
                    Axml ax = Axml.parse(Repacker.readManifest(a));
                    ax.inspect();
                    if (pkg == null) pkg = ax.oldPackage;
                    if (ax.splitName == null) {
                        if (base == null) base = a;
                        use.add(a);
                    } else if (abiOk(ax.splitName)) {
                        use.add(a);
                    } else {
                        skipped++;
                    }
                }
                String label = pkg;
                if (base != null) {
                    try {
                        PackageInfo pi = getPackageManager()
                                .getPackageArchiveInfo(base.getAbsolutePath(), 0);
                        if (pi != null && pi.applicationInfo != null) {
                            ApplicationInfo ai = pi.applicationInfo;
                            ai.sourceDir = base.getAbsolutePath();
                            ai.publicSourceDir = base.getAbsolutePath();
                            label = String.valueOf(getPackageManager().getApplicationLabel(ai));
                        }
                    } catch (Throwable ignore) {
                    }
                }
                inputs = use;
                origPkg = pkg;
                origLabel = label;
                final int n = use.size();
                final int sk = skipped;
                final boolean noBase = base == null;
                ui.post(() -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("元のアプリ名: ").append(origLabel)
                            .append("\n元のパッケージ名: ").append(origPkg)
                            .append("\nAPKの数: ").append(n);
                    if (sk > 0) sb.append("（このスマホに合わないCPU用 ").append(sk).append("個は除外）");
                    if (noBase) sb.append("\n注意: メインのAPKが見つかりません。入らない可能性があります");
                    if (obbFound) sb.append("\n注意: obbデータ付きです。データは自動では入りません");
                    infoTv.setText(sb.toString());
                    if (nameEt.getText().length() == 0) nameEt.setText(origLabel);
                    buildBtn.setEnabled(true);
                });
            } catch (Throwable e) {
                ui.post(() -> infoTv.setText("エラー: " + e));
            }
        });
    }

    private void build() {
        String name = nameEt.getText().toString().trim();
        String pkg = pkgEt.getText().toString().trim();
        if (name.isEmpty()) {
            toast("アプリ名を入れてください");
            return;
        }
        if (pkg.isEmpty()) {
            toast("パッケージ名を入れてください");
            return;
        }
        if (pkg.indexOf('.') < 0) pkg = "com." + pkg;
        if (!PKG_RE.matcher(pkg).matches()) {
            toast("パッケージ名は英数字とドットだけ（先頭は英字）にしてください");
            return;
        }
        if (pkg.equals(origPkg)) {
            toast("元と同じパッケージ名です。違う名前にしてください");
            return;
        }
        final String fpkg = pkg;
        pkgEt.setText(fpkg);
        buildBtn.setEnabled(false);
        installBtn.setEnabled(false);
        saveBtn.setEnabled(false);
        logTv.setText("作成中…（大きいAPKは時間がかかります）\n");
        ex.execute(() -> {
            try {
                outputs = Repacker.repackAll(this, inputs, fpkg, name, this::log);
                lastPkg = fpkg;
                log("できました。③でインストールできます");
                ui.post(() -> {
                    buildBtn.setEnabled(true);
                    installBtn.setEnabled(true);
                    saveBtn.setEnabled(true);
                });
            } catch (Throwable t) {
                log("失敗: " + t);
                ui.post(() -> buildBtn.setEnabled(true));
            }
        });
    }

    private void install() {
        if (outputs.isEmpty()) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            toast("このアプリにインストールを許可してから、もう一度押してください");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        ex.execute(() -> {
            try {
                PackageInstaller pi = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int id = pi.createSession(sp);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    for (File f : outputs) {
                        try (OutputStream os = s.openWrite(f.getName(), 0, f.length());
                             InputStream is = new FileInputStream(f)) {
                            copy(is, os);
                            s.fsync(os);
                        }
                    }
                    Intent i = new Intent(this, InstallResultReceiver.class);
                    PendingIntent pend = PendingIntent.getBroadcast(this, id, i,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    s.commit(pend.getIntentSender());
                }
            } catch (Exception e) {
                log("インストール開始に失敗: " + e);
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private static void copy(InputStream is, OutputStream os) throws IOException {
        byte[] b = new byte[1 << 16];
        int r;
        while ((r = is.read(b)) > 0) os.write(b, 0, r);
    }
}
