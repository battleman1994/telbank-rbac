package com.telbank.rbac.sdk.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.telbank.rbac.sdk.client.PageAction;

/**
 * 页面操作元数据本地缓存（需求 §6.9/§6.11）。
 *
 * <p>启动/变更通知/兜底轮询时全量替换，按 {@code pageCode} 分组；不做请求级回源
 * （模式 B 的数据量小，全量下发即可）。</p>
 */
public class RbacPageActionCache {

    private static final Logger log = LoggerFactory.getLogger(RbacPageActionCache.class);

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<Snapshot>(Snapshot.empty());

    private volatile boolean loaded;

    /**
     * 全量替换页面操作元数据。
     */
    public void replace(List<PageAction> pageActions) {
        List<PageAction> all = new ArrayList<PageAction>();
        Map<String, List<PageAction>> byPage = new LinkedHashMap<String, List<PageAction>>();
        if (pageActions != null) {
            for (PageAction action : pageActions) {
                if (action == null) {
                    continue;
                }
                all.add(action);
                String pageCode = action.getPageCode();
                if (pageCode == null) {
                    pageCode = "";
                }
                List<PageAction> list = byPage.get(pageCode);
                if (list == null) {
                    list = new ArrayList<PageAction>();
                    byPage.put(pageCode, list);
                }
                list.add(action);
            }
        }
        Map<String, List<PageAction>> immutableByPage = new LinkedHashMap<String, List<PageAction>>();
        for (Map.Entry<String, List<PageAction>> entry : byPage.entrySet()) {
            immutableByPage.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        snapshot.set(new Snapshot(Collections.unmodifiableList(all),
                Collections.unmodifiableMap(immutableByPage)));
        loaded = true;
        log.info("RBAC 页面操作元数据已刷新: {} 条, 页面数 {}", all.size(), immutableByPage.size());
    }

    /**
     * @return 全部页面操作（扁平数组，元素含 pageCode），不返回 null
     */
    public List<PageAction> getAll() {
        return snapshot.get().all;
    }

    /**
     * @param pageCode 页面编码；为空时返回全部
     * @return 该页面的操作列表，不存在返回空列表
     */
    public List<PageAction> get(String pageCode) {
        if (pageCode == null || pageCode.trim().isEmpty()) {
            return getAll();
        }
        List<PageAction> list = snapshot.get().byPage.get(pageCode);
        return list == null ? Collections.<PageAction>emptyList() : list;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public boolean isEmpty() {
        return snapshot.get().all.isEmpty();
    }

    public int size() {
        return snapshot.get().all.size();
    }

    public void clear() {
        snapshot.set(Snapshot.empty());
        loaded = false;
    }

    private static final class Snapshot {

        private final List<PageAction> all;

        private final Map<String, List<PageAction>> byPage;

        Snapshot(List<PageAction> all, Map<String, List<PageAction>> byPage) {
            this.all = all;
            this.byPage = byPage;
        }

        static Snapshot empty() {
            return new Snapshot(Collections.<PageAction>emptyList(),
                    Collections.<String, List<PageAction>>emptyMap());
        }
    }
}
