package com.dsh.screenwatch;

import java.net.HttpURLConnection;
import java.net.URL;

/** Lightweight latency probe: TCP/TLS + first response time to a cloud service home page. */
final class NetProbe {

    static final class Endpoint {
        final String key;
        final String name;
        final String url;

        Endpoint(String key, String name, String url) {
            this.key = key;
            this.name = name;
            this.url = url;
        }
    }

    static final Endpoint[] ENDPOINTS = {
            new Endpoint("huawei", "华为云", "https://www.huaweicloud.com/"),
            new Endpoint("aliyun", "阿里云", "https://www.aliyun.com/"),
            new Endpoint("tencent", "腾讯云", "https://cloud.tencent.com/"),
            new Endpoint("ustc", "中科大", "https://mirrors.ustc.edu.cn/"),
            new Endpoint("tuna", "清华", "https://mirrors.tuna.tsinghua.edu.cn/"),
            new Endpoint("baidu", "百度", "https://www.baidu.com/"),
    };

    private NetProbe() {
    }

    static Endpoint endpoint(String key) {
        for (Endpoint e : ENDPOINTS) {
            if (e.key.equals(key)) {
                return e;
            }
        }
        return ENDPOINTS[1];
    }

    /** Returns round-trip milliseconds, or -1 on failure/timeout. */
    static int latencyMs(String url) {
        long start = System.nanoTime();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)");
            int code = conn.getResponseCode();
            long ms = (System.nanoTime() - start) / 1_000_000L;
            return code > 0 ? (int) Math.min(ms, 9999) : -1;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
