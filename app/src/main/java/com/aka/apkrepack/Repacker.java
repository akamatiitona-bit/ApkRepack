package com.aka.apkrepack;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.android.apksig.ApkSigner;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.security.auth.x500.X500Principal;

final class Repacker {
    interface Log {
        void log(String s);
    }

    private static final String ALIAS = "apkrepack_key";

    /** 単体APKでも、分割APK(複数ファイル)でも、全部同じパッケージ名・同じ署名にして返す。 */
    static List<File> repackAll(Context ctx, List<File> ins, String newPkg, String newLabel, Log log)
            throws Exception {
        File dir = new File(ctx.getCacheDir(), "work");
        deleteRec(dir);
        dir.mkdirs();
        File outDir = new File(dir, "out");
        outDir.mkdirs();

        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator g = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setKeySize(2048)
                    .setCertificateSubject(new X500Principal("CN=ApkRepack"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(new Date(System.currentTimeMillis() - 86400000L))
                    .setCertificateNotAfter(new Date(2524608000000L))
                    .build());
            g.generateKeyPair();
        }
        PrivateKey key = (PrivateKey) ks.getKey(ALIAS, null);
        X509Certificate cert = (X509Certificate) ks.getCertificate(ALIAS);

        boolean multi = ins.size() > 1;
        List<File> outs = new ArrayList<>();
        for (int i = 0; i < ins.size(); i++) {
            File in = ins.get(i);
            File unsigned = new File(dir, i + ".unsigned");
            File out = new File(outDir, multi ? in.getName() : newPkg + ".apk");
            log.log("(" + (i + 1) + "/" + ins.size() + ") " + in.getName());

            try (ZipFile zf = new ZipFile(in)) {
                ZipEntry me = zf.getEntry("AndroidManifest.xml");
                if (me == null) throw new IOException(in.getName() + " にAndroidManifest.xmlがありません");
                byte[] manifest;
                try (InputStream is = zf.getInputStream(me)) {
                    manifest = readAll(is);
                }
                Axml ax = Axml.parse(manifest);
                ax.inspect();
                // アプリ名はメインのAPK(splitではないもの)にだけ付ける
                ax.patch(newPkg, ax.splitName == null ? newLabel : null);
                if (i == 0) log.log("元のパッケージ名: " + ax.oldPackage + " → " + newPkg);
                writeAligned(zf, unsigned, ax.build());
            }

            ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder(
                    "repack", key, Collections.singletonList(cert)).build();
            new ApkSigner.Builder(Collections.singletonList(sc))
                    .setInputApk(unsigned)
                    .setOutputApk(out)
                    .setMinSdkVersion(26)
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .setCreatedBy("ApkRepack")
                    .build()
                    .sign();
            unsigned.delete();
            outs.add(out);
        }
        log.log("署名完了（" + outs.size() + "個）");
        return outs;
    }

    static byte[] readManifest(File apk) throws IOException {
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry me = zf.getEntry("AndroidManifest.xml");
            if (me == null) throw new IOException("AndroidManifest.xml がありません");
            try (InputStream is = zf.getInputStream(me)) {
                return readAll(is);
            }
        }
    }

    private static void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteRec(x);
        }
        f.delete();
    }

    private static boolean isSig(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String u = name.toUpperCase();
        return u.equals("META-INF/MANIFEST.MF") || u.endsWith(".SF") || u.endsWith(".RSA")
                || u.endsWith(".DSA") || u.endsWith(".EC");
    }

    private static void writeAligned(ZipFile zf, File dst, byte[] manifest) throws IOException {
        try (CountingOut co = new CountingOut(
                new BufferedOutputStream(new FileOutputStream(dst), 1 << 16));
             ZipOutputStream zos = new ZipOutputStream(co)) {
            Set<String> seen = new HashSet<>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            byte[] buf = new byte[1 << 16];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory() || !seen.add(name) || isSig(name)) continue;

                if (name.equals("AndroidManifest.xml")) {
                    ZipEntry ne = new ZipEntry(name);
                    ne.setMethod(ZipEntry.DEFLATED);
                    zos.putNextEntry(ne);
                    zos.write(manifest);
                    zos.closeEntry();
                    continue;
                }

                ZipEntry ne = new ZipEntry(name);
                if (e.getMethod() == ZipEntry.STORED) {
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(e.getSize());
                    ne.setCompressedSize(e.getSize());
                    ne.setCrc(e.getCrc());
                    int align = name.endsWith(".so") ? 16384 : 4;
                    int nameLen = name.getBytes(StandardCharsets.UTF_8).length;
                    long pos = co.count + 30 + nameLen;
                    int pad = (int) ((align - (pos % align)) % align);
                    if (pad > 0 && pad < 4) pad += align;
                    if (pad > 0) {
                        byte[] ex = new byte[pad];
                        ex[0] = (byte) 0x35;
                        ex[1] = (byte) 0xD9;
                        ex[2] = (byte) ((pad - 4) & 0xff);
                        ex[3] = (byte) (((pad - 4) >> 8) & 0xff);
                        ne.setExtra(ex);
                    }
                } else {
                    ne.setMethod(ZipEntry.DEFLATED);
                }
                zos.putNextEntry(ne);
                try (InputStream is = zf.getInputStream(e)) {
                    int r;
                    while ((r = is.read(buf)) > 0) zos.write(buf, 0, r);
                }
                zos.closeEntry();
            }
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int r;
        while ((r = is.read(b)) > 0) o.write(b, 0, r);
        return o.toByteArray();
    }

    private static final class CountingOut extends OutputStream {
        private final OutputStream o;
        long count = 0;

        CountingOut(OutputStream o) {
            this.o = o;
        }

        @Override
        public void write(int b) throws IOException {
            o.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            o.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            o.flush();
        }

        @Override
        public void close() throws IOException {
            o.close();
        }
    }
}
