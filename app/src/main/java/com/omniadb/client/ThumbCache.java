package com.omniadb.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;

/**
 * 列表縮圖的快取：記憶體 LRU ＋ 磁碟 WEBP。
 *
 * <p><b>為什麼一定要有磁碟這一層</b>：本機的縮圖可以問系統（{@code loadThumbnail}），
 * 但遠端沒有這種東西 —— SYNC 協定只有 STAT/LIST/RECV/SEND，沒有「給我縮圖」。
 * 所以遠端縮圖只能把<b>整個檔案抓回來</b>再解碼，一張 2 MB 的照片要花 0.3 秒以上。
 *
 * <p>而這個列表在每次選取、每次改排序時都會<b>整個重建</b>（見 {@code renderList}），
 * 沒有磁碟快取的話，使用者每點一下就會看到所有縮圖一起變回灰色再慢慢長回來。
 *
 * <p>key 由呼叫端負責帶上 mtime 與大小，所以檔案被改過就不會命中舊圖，
 * 這裡不必處理失效。{@code cacheDir} 由系統負責回收，40dp 的 WEBP 一張約 3 KB，
 * 一千張也才 3 MB，不需要自己清理。
 */
final class ThumbCache {

    private static final String TAG = "ThumbCache";

    /** 記憶體上限。160×160 ARGB_8888 一張約 100 KB，這裡大約放得下 80 張。 */
    private static final int MEM_BYTES = 8 * 1024 * 1024;

    private final LruCache<String, Bitmap> mem;
    private final File dir;

    ThumbCache(Context ctx) {
        dir = new File(ctx.getCacheDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        mem = new LruCache<String, Bitmap>(MEM_BYTES) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    /** 只查記憶體。主執行緒可以呼叫 —— 命中就直接畫，不必等。 */
    Bitmap peek(String key) {
        return mem.get(key);
    }

    /** 從磁碟讀回。**必須在背景執行緒呼叫**（解碼是同步 I/O）。 */
    Bitmap load(String key) {
        Bitmap hit = mem.get(key);
        if (hit != null) return hit;
        File f = fileFor(key);
        if (!f.isFile()) return null;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b != null) mem.put(key, b);
        return b;
    }

    /** 存進兩層快取。**必須在背景執行緒呼叫**。 */
    void put(String key, Bitmap bmp) {
        mem.put(key, bmp);
        File f = fileFor(key);
        File tmp = new File(f.getAbsolutePath() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            // 用有損 WEBP：縮圖不需要無損，PNG 會大十倍。透明背景在這裡不重要
            // （縮圖一律裁成方形填滿），所以不必留 alpha。
            bmp.compress(Bitmap.CompressFormat.WEBP, 82, out);
        } catch (Exception e) {
            DiagLog.w(TAG, "縮圖寫入磁碟失敗", e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /**
     * 把 key 變成一个安全的檔名。
     *
     * <p>不能用 {@code hashCode()}：兩個不同路徑撞同一個值的機率不是零，而撞到的症狀
     * 是「A 資料夾的照片出現在 B 資料夾」，比沒有縮圖還糟。用 SHA-1 取前 8 bytes。
     */
    private File fileFor(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1")
                    .digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return new File(dir, sb + ".webp");
        } catch (Exception e) {
            // 演算法一定存在，編碼一定存在；走到這裡代表環境壞了，退回長檔名。
            return new File(dir, Integer.toHexString(key.hashCode()) + ".webp");
        }
    }
}
