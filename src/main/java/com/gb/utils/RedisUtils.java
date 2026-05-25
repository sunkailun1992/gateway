package com.gb.utils;

import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 缓存设置工具类
 *
 * @author sunkailun
 * @DateTime 2020/12/27  下午4:37
 * @email 376253703@qq.com
 * @phone 13777579028
 */
public class RedisUtils {
    /**
     * 获得参数
     *
     * @param stringRedisTemplate
     * @param token               键
     * @return
     */
    public static Map<String, Object> getToken(StringRedisTemplate stringRedisTemplate, String token) {
        String t = stringRedisTemplate.opsForValue().get(token);
        if (StringUtils.isNotBlank(t)) {
            return JsonUtil.java(t, Map.class);
        } else {
            return null;
        }
    }

    /**
     * 设置参数
     *
     * @param stringRedisTemplate
     * @param k                   键
     * @param v                   值
     */
    public static void add(StringRedisTemplate stringRedisTemplate, String k, Object v) {
        stringRedisTemplate.opsForValue().set(k, JsonUtil.json(v));
    }

    /**
     * 设置参数
     *
     * @param stringRedisTemplate
     * @param k                   键
     * @param v                   值
     */
    public static void add(StringRedisTemplate stringRedisTemplate, String k, String v) {
        stringRedisTemplate.opsForValue().set(k, v);
    }

    /**
     * 设置参数和过期时间
     *
     * @param stringRedisTemplate
     * @param k                   键
     * @param v                   值
     * @param timeout             过期时间
     * @param unit                时间单位
     */
    public static void add(StringRedisTemplate stringRedisTemplate, String k, Object v, long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(k, JsonUtil.json(v));
        stringRedisTemplate.expire(k, timeout, unit);
    }

    /**
     * 设置参数和过期时间
     *
     * @param stringRedisTemplate
     * @param k                   键
     * @param v                   值
     * @param timeout             过期时间
     * @param unit                时间单位
     */
    public static void add(StringRedisTemplate stringRedisTemplate, String k, String v, long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(k, v);
        stringRedisTemplate.expire(k, timeout, unit);
    }

    /**
     * 获得参数
     *
     * @param stringRedisTemplate
     * @param k                   键
     * @return
     */
    public static String get(StringRedisTemplate stringRedisTemplate, String k) {

        return stringRedisTemplate.opsForValue().get(k);
    }

    /**
     * 设置map参数
     *
     * @param stringRedisTemplate
     * @param k                   键
     * @param map                 集合
     */
    public static void addMap(StringRedisTemplate stringRedisTemplate, String k, Map<String, Object> map) {
        for (String mapk : map.keySet()) {
            if ("ip".equals(mapk)) {
                map.put(mapk, map.get(mapk));
            } else {
                map.put(mapk, JsonUtil.json(map.get(mapk)));
            }
        }
        stringRedisTemplate.opsForHash().putAll(k, map);
    }

    /**
     * 设置map参数和过期时间
     *
     * @param stringRedisTemplate
     * @param k                   键
     * @param map                 集合
     * @param timeout             过期时间
     * @param unit                时间单位
     */
    public static void addMap(StringRedisTemplate stringRedisTemplate, String k, Map<String, Object> map, long timeout, TimeUnit unit) {
        for (String mapk : map.keySet()) {
            if (filter(mapk)) {
                map.put(mapk, map.get(mapk));
            } else {
                if (map.get(mapk) instanceof String) {
                    map.put(mapk, map.get(mapk));
                } else {
                    map.put(mapk, JsonUtil.json(map.get(mapk)));
                }
            }
        }
        stringRedisTemplate.opsForHash().putAll(k, map);
        stringRedisTemplate.expire(k, timeout, unit);
    }

    public static Boolean filter(String value) {
        if ("ip".equals(value)) {
            return true;
        } else if ("token".equals(value)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * 获得map参数
     *
     * @param stringRedisTemplate
     * @param k                   键
     * @param mapK                map参数的键
     * @return
     */
    public static Object getMap(StringRedisTemplate stringRedisTemplate, String k, String mapK) {

        return stringRedisTemplate.opsForHash().get(k, mapK);
    }

    /**
     * 删除参数
     *
     * @param stringRedisTemplate
     * @param key                 键
     */
    public static void delete(StringRedisTemplate stringRedisTemplate, String key) {
        stringRedisTemplate.delete(key);
    }


}
