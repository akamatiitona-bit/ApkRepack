package com.aka.apkrepack;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** バイナリ形式の AndroidManifest.xml を読み書きして、パッケージ名とアプリ名を書き換える。 */
final class Axml {
    static final String ANDROID = "http://schemas.android.com/apk/res/android";
    static final int TYPE_STRING = 0x03;
    static final int RES_LABEL = 0x01010001;

    static final class Attr {
        int ns, name, raw, type, data;
    }

    static final class Elem {
        int line, comment, ns, name, idIdx, clsIdx, styIdx;
        List<Attr> attrs = new ArrayList<>();
    }

    static final class Chunk {
        int type;
        byte[] raw;
        Elem el;
        int nameIdx = -1;
    }

    private final List<String> pool = new ArrayList<>();
    private final Map<String, Integer> poolIdx = new HashMap<>();
    private boolean utf8;
    private int flags;
    private int styCount;
    private int[] styOffsets = new int[0];
    private byte[] styData = new byte[0];
    private int[] resMap = new int[0];
    private final List<Chunk> chunks = new ArrayList<>();

    String oldPackage;
    String splitName;

    // ---------------------------------------------------------------- 読み込み

    static Axml parse(byte[] in) {
        Axml a = new Axml();
        ByteBuffer b = ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN);
        if ((b.getShort(0) & 0xffff) != 0x0003) {
            throw new IllegalArgumentException("バイナリXMLではありません");
        }
        int total = Math.min(b.getInt(4), in.length);
        int pos = 8;
        if ((b.getShort(pos) & 0xffff) != 0x0001) {
            throw new IllegalArgumentException("文字列テーブルが見つかりません");
        }
        int poolSize = b.getInt(pos + 4);
        int sc = b.getInt(pos + 8);
        a.styCount = b.getInt(pos + 12);
        a.flags = b.getInt(pos + 16);
        int strStart = b.getInt(pos + 20);
        int styStart = b.getInt(pos + 24);
        a.utf8 = (a.flags & 0x100) != 0;
        for (int i = 0; i < sc; i++) {
            int off = b.getInt(pos + 28 + i * 4);
            String s = readStr(b, pos + strStart + off, a.utf8);
            a.pool.add(s);
            a.poolIdx.putIfAbsent(s, i);
        }
        a.styOffsets = new int[a.styCount];
        for (int i = 0; i < a.styCount; i++) {
            a.styOffsets[i] = b.getInt(pos + 28 + sc * 4 + i * 4);
        }
        if (a.styCount > 0) {
            a.styData = Arrays.copyOfRange(in, pos + styStart, pos + poolSize);
        }
        pos += poolSize;

        while (pos + 8 <= total) {
            int type = b.getShort(pos) & 0xffff;
            int size = b.getInt(pos + 4);
            if (size < 8) break;
            Chunk c = new Chunk();
            c.type = type;
            if (type == 0x0102) {
                int base = pos + 16;
                Elem e = new Elem();
                e.line = b.getInt(pos + 8);
                e.comment = b.getInt(pos + 12);
                e.ns = b.getInt(base);
                e.name = b.getInt(base + 4);
                int attrStart = b.getShort(base + 8) & 0xffff;
                int attrSize = b.getShort(base + 10) & 0xffff;
                int cnt = b.getShort(base + 12) & 0xffff;
                e.idIdx = b.getShort(base + 14) & 0xffff;
                e.clsIdx = b.getShort(base + 16) & 0xffff;
                e.styIdx = b.getShort(base + 18) & 0xffff;
                for (int i = 0; i < cnt; i++) {
                    int p = base + attrStart + i * attrSize;
                    Attr at = new Attr();
                    at.ns = b.getInt(p);
                    at.name = b.getInt(p + 4);
                    at.raw = b.getInt(p + 8);
                    at.type = b.get(p + 15) & 0xff;
                    at.data = b.getInt(p + 16);
                    e.attrs.add(at);
                }
                c.el = e;
            } else {
                c.raw = Arrays.copyOfRange(in, pos, pos + size);
                if (type == 0x0180) {
                    int n = (size - 8) / 4;
                    a.resMap = new int[n];
                    for (int i = 0; i < n; i++) a.resMap[i] = b.getInt(pos + 8 + i * 4);
                } else if (type == 0x0103) {
                    c.nameIdx = b.getInt(pos + 20);
                }
            }
            a.chunks.add(c);
            pos += size;
        }
        return a;
    }

    private static int readLen8(ByteBuffer b, int[] p) {
        int v = b.get(p[0]) & 0xff;
        p[0]++;
        if ((v & 0x80) != 0) {
            v = ((v & 0x7f) << 8) | (b.get(p[0]) & 0xff);
            p[0]++;
        }
        return v;
    }

    private static String readStr(ByteBuffer b, int p, boolean utf8) {
        if (utf8) {
            int[] pp = {p};
            readLen8(b, pp);
            int blen = readLen8(b, pp);
            byte[] bytes = new byte[blen];
            for (int i = 0; i < blen; i++) bytes[i] = b.get(pp[0] + i);
            return new String(bytes, StandardCharsets.UTF_8);
        } else {
            int len = b.getShort(p) & 0xffff;
            p += 2;
            if ((len & 0x8000) != 0) {
                len = ((len & 0x7fff) << 16) | (b.getShort(p) & 0xffff);
                p += 2;
            }
            char[] ch = new char[len];
            for (int i = 0; i < len; i++) ch[i] = (char) (b.getShort(p + i * 2) & 0xffff);
            return new String(ch);
        }
    }

    // ---------------------------------------------------------------- 補助

    private String str(int i) {
        return (i >= 0 && i < pool.size()) ? pool.get(i) : null;
    }

    private int addString(String s) {
        Integer i = poolIdx.get(s);
        if (i != null) return i;
        pool.add(s);
        int idx = pool.size() - 1;
        poolIdx.put(s, idx);
        return idx;
    }

    private void setStr(Attr a, String s) {
        int i = addString(s);
        a.raw = i;
        a.type = TYPE_STRING;
        a.data = i;
    }

    private int resId(Attr a) {
        return (a.name >= 0 && a.name < resMap.length) ? resMap[a.name] : 0;
    }

    private static String expand(String v, String old) {
        if (v.isEmpty()) return v;
        if (v.startsWith(".")) return old + v;
        if (v.indexOf('.') < 0) return old + "." + v;
        return v;
    }

    private static String swapPrefix(String v, String old, String neu) {
        if (v.startsWith(old + ".")) return neu + v.substring(old.length());
        return v;
    }

    private static boolean isComponent(String n) {
        switch (n) {
            case "application":
            case "activity":
            case "activity-alias":
            case "service":
            case "receiver":
            case "provider":
            case "instrumentation":
                return true;
            default:
                return false;
        }
    }

    private static boolean isPermElem(String n) {
        switch (n) {
            case "permission":
            case "permission-tree":
            case "permission-group":
            case "uses-permission":
            case "uses-permission-sdk-23":
            case "uses-permission-sdk-m":
                return true;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- 書き換え

    Elem inspect() {
        Elem manifest = null;
        for (Chunk c : chunks) {
            if (c.el != null && "manifest".equals(str(c.el.name))) {
                manifest = c.el;
                break;
            }
        }
        if (manifest == null) throw new IllegalArgumentException("manifest要素がありません");
        for (Attr a : manifest.attrs) {
            if (a.ns < 0) {
                String an = str(a.name);
                if ("package".equals(an)) oldPackage = str(a.raw >= 0 ? a.raw : a.data);
                else if ("split".equals(an)) splitName = str(a.raw >= 0 ? a.raw : a.data);
            }
        }
        if (oldPackage == null) throw new IllegalArgumentException("パッケージ名が読めません");
        return manifest;
    }

    void patch(String newPkg, String newLabel) {
        int androidNs = poolIdx.containsKey(ANDROID) ? poolIdx.get(ANDROID) : -1;
        inspect();
        final String old = oldPackage;

        Elem application = null;
        Elem current = null;
        List<Elem> launchers = new ArrayList<>();

        for (Chunk c : chunks) {
            if (c.el == null) {
                if (c.type == 0x0103) {
                    String n = str(c.nameIdx);
                    if ("activity".equals(n) || "activity-alias".equals(n)) current = null;
                }
                continue;
            }
            Elem e = c.el;
            String en = str(e.name);
            if (en == null) continue;
            if (en.equals("application")) application = e;
            if (en.equals("activity") || en.equals("activity-alias")) current = e;

            for (Attr a : e.attrs) {
                String an = str(a.name);
                if (an == null) continue;

                if (a.ns < 0 && en.equals("manifest") && an.equals("package")) {
                    setStr(a, newPkg);
                    continue;
                }
                boolean droid = a.ns >= 0 && ANDROID.equals(str(a.ns));
                if (!droid || a.type != TYPE_STRING) continue;
                String v = str(a.data);
                if (v == null) continue;

                if (en.equals("category") && an.equals("name")
                        && v.equals("android.intent.category.LAUNCHER") && current != null) {
                    launchers.add(current);
                }

                switch (an) {
                    case "name":
                        if (isComponent(en)) {
                            String nv = expand(v, old);
                            if (!nv.equals(v)) setStr(a, nv);
                        } else if (isPermElem(en)) {
                            String nv = swapPrefix(v, old, newPkg);
                            if (!nv.equals(v)) setStr(a, nv);
                        }
                        break;
                    case "targetActivity":
                    case "backupAgent":
                    case "manageSpaceActivity":
                    case "parentActivityName":
                    case "appComponentFactory": {
                        String nv = expand(v, old);
                        if (!nv.equals(v)) setStr(a, nv);
                        break;
                    }
                    case "authorities": {
                        String[] parts = v.split(";");
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < parts.length; i++) {
                            if (i > 0) sb.append(';');
                            String p = parts[i];
                            if (p.startsWith(old)) sb.append(newPkg).append(p.substring(old.length()));
                            else sb.append(newPkg).append('.').append(p);
                        }
                        setStr(a, sb.toString());
                        break;
                    }
                    case "permission":
                    case "readPermission":
                    case "writePermission": {
                        String nv = swapPrefix(v, old, newPkg);
                        if (!nv.equals(v)) setStr(a, nv);
                        break;
                    }
                    case "sharedUserId":
                        setStr(a, newPkg);
                        break;
                    case "targetPackage":
                        if (en.equals("instrumentation") && v.equals(old)) setStr(a, newPkg);
                        break;
                    default:
                        break;
                }
            }
        }

        // アプリ名
        if (newLabel != null && !newLabel.isEmpty()) {
            int li = -1;
            for (int i = 0; i < resMap.length && i < pool.size(); i++) {
                if (resMap[i] == RES_LABEL) {
                    li = i;
                    break;
                }
            }
            if (li >= 0) {
                int sIdx = addString(newLabel);
                if (application != null) {
                    Attr la = findAttr(application, li);
                    if (la != null) {
                        la.raw = sIdx;
                        la.type = TYPE_STRING;
                        la.data = sIdx;
                    } else if (androidNs >= 0) {
                        Attr n = new Attr();
                        n.ns = androidNs;
                        n.name = li;
                        n.raw = sIdx;
                        n.type = TYPE_STRING;
                        n.data = sIdx;
                        int at = application.attrs.size();
                        for (int i = 0; i < application.attrs.size(); i++) {
                            if (resId(application.attrs.get(i)) > RES_LABEL) {
                                at = i;
                                break;
                            }
                        }
                        application.attrs.add(at, n);
                        if (application.idIdx != 0 && application.idIdx - 1 >= at) application.idIdx++;
                        if (application.clsIdx != 0 && application.clsIdx - 1 >= at) application.clsIdx++;
                        if (application.styIdx != 0 && application.styIdx - 1 >= at) application.styIdx++;
                    }
                }
                for (Elem l : launchers) {
                    Attr la = findAttr(l, li);
                    if (la != null) {
                        la.raw = sIdx;
                        la.type = TYPE_STRING;
                        la.data = sIdx;
                    }
                }
            }
        }
    }

    private Attr findAttr(Elem e, int nameIdx) {
        for (Attr a : e.attrs) if (a.name == nameIdx) return a;
        return null;
    }

    // ---------------------------------------------------------------- 書き出し

    byte[] build() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writePool(body);
        for (Chunk c : chunks) {
            if (c.el != null) writeElem(body, c.el);
            else body.write(c.raw, 0, c.raw.length);
        }
        byte[] b = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        i16(out, 0x0003);
        i16(out, 8);
        i32(out, b.length + 8);
        out.write(b, 0, b.length);
        return out.toByteArray();
    }

    private void writePool(ByteArrayOutputStream o) {
        int n = pool.size();
        byte[][] enc = new byte[n][];
        int[] offs = new int[n];
        int dataLen = 0;
        for (int i = 0; i < n; i++) {
            enc[i] = encode(pool.get(i));
            offs[i] = dataLen;
            dataLen += enc[i].length;
        }
        int padded = (dataLen + 3) & ~3;
        int strStart = 28 + n * 4 + styCount * 4;
        int styStart = styCount > 0 ? strStart + padded : 0;
        int size = strStart + padded + styData.length;

        i16(o, 0x0001);
        i16(o, 28);
        i32(o, size);
        i32(o, n);
        i32(o, styCount);
        i32(o, flags & ~1);
        i32(o, strStart);
        i32(o, styStart);
        for (int i = 0; i < n; i++) i32(o, offs[i]);
        for (int i = 0; i < styCount; i++) i32(o, styOffsets[i]);
        for (int i = 0; i < n; i++) o.write(enc[i], 0, enc[i].length);
        for (int i = dataLen; i < padded; i++) o.write(0);
        o.write(styData, 0, styData.length);
    }

    private byte[] encode(String s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (utf8) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            len8(o, s.length());
            len8(o, bytes.length);
            o.write(bytes, 0, bytes.length);
            o.write(0);
        } else {
            int len = s.length();
            if (len > 0x7fff) {
                i16(o, ((len >> 16) & 0x7fff) | 0x8000);
                i16(o, len & 0xffff);
            } else {
                i16(o, len);
            }
            for (int i = 0; i < len; i++) i16(o, s.charAt(i));
            i16(o, 0);
        }
        return o.toByteArray();
    }

    private static void len8(ByteArrayOutputStream o, int len) {
        if (len > 0x7f) {
            o.write(((len >> 8) & 0x7f) | 0x80);
            o.write(len & 0xff);
        } else {
            o.write(len);
        }
    }

    private void writeElem(ByteArrayOutputStream o, Elem e) {
        int n = e.attrs.size();
        i16(o, 0x0102);
        i16(o, 16);
        i32(o, 36 + 20 * n);
        i32(o, e.line);
        i32(o, e.comment);
        i32(o, e.ns);
        i32(o, e.name);
        i16(o, 20);
        i16(o, 20);
        i16(o, n);
        i16(o, e.idIdx);
        i16(o, e.clsIdx);
        i16(o, e.styIdx);
        for (Attr a : e.attrs) {
            i32(o, a.ns);
            i32(o, a.name);
            i32(o, a.raw);
            i16(o, 8);
            o.write(0);
            o.write(a.type);
            i32(o, a.data);
        }
    }

    private static void i16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
    }

    private static void i32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
        o.write((v >> 16) & 0xff);
        o.write((v >>> 24) & 0xff);
    }
}
