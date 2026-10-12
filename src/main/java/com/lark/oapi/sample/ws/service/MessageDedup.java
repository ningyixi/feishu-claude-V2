package com.lark.oapi.sample.ws.service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FIFO + TTL 的消息去重表（参考 larksuite/openclaw-lark 的 inbound/dedup.ts）。
 * 消息 id 写一次查一次，没有冷热之分，所以按插入序淘汰最老的即可；清理从最老的开始，遇到未过期就停。
 */
public class MessageDedup {

    private final long ttlMs;
    private final int maxEntries;
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();

    public MessageDedup(long ttlMs, int maxEntries) {
        this.ttlMs = ttlMs;
        this.maxEntries = maxEntries;
    }

    /** true = 第一次见到（并登记）；false = 重复 */
    public synchronized boolean firstSeen(String key) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() > ttlMs || seen.size() >= maxEntries) it.remove(); else break;
        }
        if (seen.containsKey(key)) return false;
        seen.put(key, now);
        return true;
    }

    public synchronized int size() { return seen.size(); }
}
