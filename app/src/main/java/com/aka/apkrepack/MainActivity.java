package com.aka.apkrepack;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

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

    private File inFile;
    private File outFile;
    private String origPkg, origLabel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int p = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p * 2, p, p);

        Button pick = button("① APKを選ぶ");
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_PICK);
        });
        root.addView(pick);

        infoTv = new TextView(this);
        infoTv.setText("APKが選ばれていません");
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

        saveBtn = button("APKとして保存");
        saveBtn.setEnabled(false);
        saveBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/vnd.android.package-archive");
            i.putExtra(Intent.EXTRA_TITLE, outFile.getName());
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
            loadApk(uri);
        } else if (req == REQ_SAVE) {
            ex.execute(() -> {
                try (InputStream is = new FileInputStream(outFile);
                     OutputStream os = getContentResolver().openOutputStream(uri)) {
                    copy(is, os);
                    ui.post(() -> Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    log("保存失敗: " + e);
                }
            });
        }
    }

    private void loadApk(Uri uri) {
        buildBtn.setEnabled(false);
        installBtn.setEnabled(false);
        saveBtn.setEnabled(false);
        logTv.setText("");
        infoTv.setText("読み込み中…");
        ex.execute(() -> {
            try {
                File f = new File(getCacheDir(), "in.apk");
                try (InputStream is = getContentResolver().openInputStream(uri);
                     OutputStream os = new FileOutputStream(f)) {
                    copy(is, os);
                }
                PackageInfo pi = getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                if (pi == null) throw new IOException("APKとして読めません（.xapk/.apks は非対応です）");
                ApplicationInfo ai = pi.applicationInfo;
                ai.sourceDir = f.getAbsolutePath();
                ai.publicSourceDir = f.getAbsolutePath();
                CharSequence l = getPackageManager().getApplicationLabel(ai);
                inFile = f;
                origPkg = pi.packageName;
                origLabel = String.valueOf(l);
                ui.post(() -> {
                    infoTv.setText("元のアプリ名: " + origLabel + "\n元のパッケージ名: " + origPkg);
                    if (nameEt.getText().length() == 0) nameEt.setText(origLabel);
                    buildBtn.setEnabled(true);
                });
            } catch (Exception e) {
                ui.post(() -> infoTv.setText("エラー: " + e.getMessage()));
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
        logTv.setText("作成中…（大きいAPKは少し時間がかかります）\n");
        ex.execute(() -> {
            try {
                File out = Repacker.repack(this, inFile, fpkg, name, this::log);
                outFile = out;
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
        if (outFile == null) return;
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
                    try (OutputStream os = s.openWrite("app.apk", 0, outFile.length());
                         InputStream is = new FileInputStream(outFile)) {
                        copy(is, os);
                        s.fsync(os);
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
