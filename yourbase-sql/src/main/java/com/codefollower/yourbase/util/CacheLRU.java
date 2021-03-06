/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.codefollower.yourbase.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

import com.codefollower.yourbase.constant.Constants;
import com.codefollower.yourbase.constant.SysProperties;
import com.codefollower.yourbase.message.DbException;

/**
 * A cache implementation based on the last recently used (LRU) algorithm.
 */
public class CacheLRU implements Cache {

    static final String TYPE_NAME = "LRU";

    private final CacheWriter writer;

    /**
     * Use First-In-First-Out (don't move recently used items to the front of
     * the queue).
     */
    private final boolean fifo;

    private final CacheObject head = new CacheHead();
    private final int mask;
    private CacheObject[] values;
    private int recordCount;

    /**
     * The number of cache buckets.
     */
    private final int len;

    /**
     * The maximum memory, in words (4 bytes each).
     */
    private int maxMemory;

    /**
     * The current memory used in this cache, in words (4 bytes each).
     */
    private int memory;

    CacheLRU(CacheWriter writer, int maxMemoryKb, boolean fifo) {
        this.writer = writer;
        this.fifo = fifo;
        this.setMaxMemory(maxMemoryKb);
        this.len = MathUtils.nextPowerOf2(maxMemory / 64);
        this.mask = len - 1;
        clear();
    }

    /**
     * Create a cache of the given type and size.
     *
     * @param writer the cache writer
     * @param cacheType the cache type
     * @param cacheSize the size
     * @return the cache object
     */
    public static Cache getCache(CacheWriter writer, String cacheType, int cacheSize) {
        Map<Integer, CacheObject> secondLevel = null;
        if (cacheType.startsWith("SOFT_")) {
            secondLevel = new SoftHashMap<Integer, CacheObject>();
            cacheType = cacheType.substring("SOFT_".length());
        }
        Cache cache;
        if (CacheLRU.TYPE_NAME.equals(cacheType)) {
            cache = new CacheLRU(writer, cacheSize, false);
        } else if (CacheTQ.TYPE_NAME.equals(cacheType)) {
            cache = new CacheTQ(writer, cacheSize);
        } else {
            throw DbException.getInvalidValueException("CACHE_TYPE", cacheType);
        }
        if (secondLevel != null) {
            cache = new CacheSecondLevel(cache, secondLevel);
        }
        return cache;
    }

    public void clear() {
        head.cacheNext = head.cachePrevious = head;
        // first set to null - avoiding out of memory
        values = null;
        values = new CacheObject[len];
        recordCount = 0;
        memory = len * Constants.MEMORY_POINTER;
    }

    public void put(CacheObject rec) {
        if (SysProperties.CHECK) {
            int pos = rec.getPos();
            CacheObject old = find(pos);
            if (old != null) {
                DbException.throwInternalError("try to add a record twice at pos " + pos);
            }
        }
        int index = rec.getPos() & mask;
        rec.cacheChained = values[index];
        values[index] = rec;
        recordCount++;
        memory += rec.getMemory();
        addToFront(rec);
        removeOldIfRequired();
    }

    public CacheObject update(int pos, CacheObject rec) {
        CacheObject old = find(pos);
        if (old == null) {
            put(rec);
        } else {
            if (SysProperties.CHECK) {
                if (old != rec) {
                    DbException.throwInternalError("old!=record pos:" + pos + " old:" + old + " new:" + rec);
                }
            }
            if (!fifo) {
                removeFromLinkedList(rec);
                addToFront(rec);
            }
        }
        return old;
    }

    private void removeOldIfRequired() {
        // a small method, to allow inlining
        if (memory >= maxMemory) {
            removeOld();
        }
    }

    private void removeOld() {
        int i = 0;
        ArrayList<CacheObject> changed = New.arrayList();
        int mem = memory;
        int rc = recordCount;
        boolean flushed = false;
        CacheObject next = head.cacheNext;
        while (true) {
            if (rc <= Constants.CACHE_MIN_RECORDS) {
                break;
            }
            if (changed.size() == 0) {
                if (mem <= maxMemory) {
                    break;
                }
            } else {
                if (mem * 4 <= maxMemory * 3) {
                    break;
                }
            }
            CacheObject check = next;
            next = check.cacheNext;
            i++;
            if (i >= recordCount) {
                if (!flushed) {
                    writer.flushLog();
                    flushed = true;
                    i = 0;
                } else {
                    // can't remove any record, because the records can not be removed
                    // hopefully this does not happen frequently, but it can happen
                    writer.getTrace().info("cannot remove records, cache size too small? records:" + recordCount + " memory:" + memory);
                    break;
                }
            }
            if (SysProperties.CHECK && check == head) {
                DbException.throwInternalError("try to remove head");
            }
            // we are not allowed to remove it if the log is not yet written
            // (because we need to log before writing the data)
            // also, can't write it if the record is pinned
            if (!check.canRemove()) {
                removeFromLinkedList(check);
                addToFront(check);
                continue;
            }
            rc--;
            mem -= check.getMemory();
            if (check.isChanged()) {
                changed.add(check);
            } else {
                remove(check.getPos());
            }
        }
        if (changed.size() > 0) {
            if (!flushed) {
                writer.flushLog();
            }
            Collections.sort(changed);
            int max = maxMemory;
            int size = changed.size();
            try {
                // temporary disable size checking,
                // to avoid stack overflow
                maxMemory = Integer.MAX_VALUE;
                for (i = 0; i < size; i++) {
                    CacheObject rec = changed.get(i);
                    writer.writeBack(rec);
                }
            } finally {
                maxMemory = max;
            }
            for (i = 0; i < size; i++) {
                CacheObject rec = changed.get(i);
                remove(rec.getPos());
                if (SysProperties.CHECK) {
                    if (rec.cacheNext != null) {
                        throw DbException.throwInternalError();
                    }
                }
            }
        }
    }

    private void addToFront(CacheObject rec) {
        if (SysProperties.CHECK && rec == head) {
            DbException.throwInternalError("try to move head");
        }
        rec.cacheNext = head;
        rec.cachePrevious = head.cachePrevious;
        rec.cachePrevious.cacheNext = rec;
        head.cachePrevious = rec;
    }

    private void removeFromLinkedList(CacheObject rec) {
        if (SysProperties.CHECK && rec == head) {
            DbException.throwInternalError("try to remove head");
        }
        rec.cachePrevious.cacheNext = rec.cacheNext;
        rec.cacheNext.cachePrevious = rec.cachePrevious;
        // TODO cache: mystery: why is this required? needs more memory if we
        // don't do this
        rec.cacheNext = null;
        rec.cachePrevious = null;
    }

    public boolean remove(int pos) {
        int index = pos & mask;
        CacheObject rec = values[index];
        if (rec == null) {
            return false;
        }
        if (rec.getPos() == pos) {
            values[index] = rec.cacheChained;
        } else {
            CacheObject last;
            do {
                last = rec;
                rec = rec.cacheChained;
                if (rec == null) {
                    return false;
                }
            } while (rec.getPos() != pos);
            last.cacheChained = rec.cacheChained;
        }
        recordCount--;
        memory -= rec.getMemory();
        removeFromLinkedList(rec);
        if (SysProperties.CHECK) {
            rec.cacheChained = null;
            CacheObject o = find(pos);
            if (o != null) {
                DbException.throwInternalError("not removed: " + o);
            }
        }
        return true;
    }

    public CacheObject find(int pos) {
        CacheObject rec = values[pos & mask];
        while (rec != null && rec.getPos() != pos) {
            rec = rec.cacheChained;
        }
        return rec;
    }

    public CacheObject get(int pos) {
        CacheObject rec = find(pos);
        if (rec != null) {
            if (!fifo) {
                removeFromLinkedList(rec);
                addToFront(rec);
            }
        }
        return rec;
    }

//    private void testConsistency() {
//        int s = size;
//        HashSet set = new HashSet();
//        for(int i=0; i<values.length; i++) {
//            Record rec = values[i];
//            if(rec == null) {
//                continue;
//            }
//            set.add(rec);
//            while(rec.chained != null) {
//                rec = rec.chained;
//                set.add(rec);
//            }
//        }
//        Record rec = head.next;
//        while(rec != head) {
//            set.add(rec);
//            rec = rec.next;
//        }
//        rec = head.previous;
//        while(rec != head) {
//            set.add(rec);
//            rec = rec.previous;
//        }
//        if(set.size() != size) {
//            System.out.println("size="+size+" but el.size="+set.size());
//        }
//    }

    public ArrayList<CacheObject> getAllChanged() {
//        if(Database.CHECK) {
//            testConsistency();
//        }
        ArrayList<CacheObject> list = New.arrayList();
        CacheObject rec = head.cacheNext;
        while (rec != head) {
            if (rec.isChanged()) {
                list.add(rec);
            }
            rec = rec.cacheNext;
        }
        return list;
    }

    public void setMaxMemory(int maxKb) {
        int newSize = MathUtils.convertLongToInt(maxKb * 1024L / 4);
        maxMemory = newSize < 0 ? 0 : newSize;
        // can not resize, otherwise existing records are lost
        // resize(maxSize);
        removeOldIfRequired();
    }

    public int getMaxMemory() {
        return (int) (maxMemory * 4L / 1024);
    }

    public int getMemory() {
        // CacheObject rec = head.cacheNext;
        // while (rec != head) {
            // System.out.println(rec.getMemory() + " " +
            //        MemoryFootprint.getObjectSize(rec) + " " + rec);
            // rec = rec.cacheNext;
        // }
        return (int) (memory * 4L / 1024);
    }

}
