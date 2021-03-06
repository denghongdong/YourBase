/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.codefollower.yourbase.value;

import java.util.HashMap;

import com.codefollower.yourbase.util.StringUtils;

/**
 * A hash map with a case-insensitive string key.
 *
 * @param <V> the value type
 */
public class CaseInsensitiveMap<V> extends HashMap<String, V> {

    private static final long serialVersionUID = 1L;

    public V get(Object key) {
        return super.get(toUpper(key));
    }

    public V put(String key, V value) {
        return super.put(toUpper(key), value);
    }

    public boolean containsKey(Object key) {
        return super.containsKey(toUpper(key));
    }

    public V remove(Object key) {
        return super.remove(toUpper(key));
    }

    private static String toUpper(Object key) {
        return key == null ? null : StringUtils.toUpperEnglish(key.toString());
    }

}
