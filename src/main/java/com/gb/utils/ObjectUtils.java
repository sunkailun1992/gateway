package com.gb.utils;


import com.gb.utils.date.DateStyle;
import com.gb.utils.date.DateUtil;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.text.ParseException;
import java.util.Date;

/**
 * Object的工具类
 * @author      sunkailun
 * @DateTime    2020/12/27  下午4:38
 * @email       376253703@qq.com
 * @phone       13777579028
 */
public class ObjectUtils {
    /**
     * @param o
     * @return Integer
     * @description 转换为Integer
     * @author 孙凯伦
     * @since 1.0.0
     */
    public static Integer Integer(Object o) {
        if (judge(o)) {
            return Integer.valueOf(String.valueOf(o));
        } else {
            return 0;
        }
    }

    /**
     * @param o
     * @return Long
     * @description 转换为Long
     * @author 孙凯伦
     * @since 1.0.0
     */
    public static Long Long(Object o) {
        if (judge(o)) {
            return Long.valueOf((String.valueOf(o)));
        } else {
            return null;
        }
    }

    /**
     * 判断转换
     *
     * @param o:
     * @return java.lang.Boolean
     * @author sunkailun
     * @DateTime 2018/5/10  上午11:40
     * @email 376253703@qq.com
     * @phone 13777579028
     */
    public static Boolean Boolean(Object o) {
        if (judge(o)) {
            return Boolean.valueOf(String.valueOf(o));
        } else {
            return false;
        }
    }

    /**
     * @param o
     * @return BigDecimal
     * @description 转换为BigDecimal
     * @author 孙凯伦
     * @since 1.0.0
     */
    public static BigDecimal BigDecimal(Object o) {
        if (judge(o)) {
            BigDecimal v = new BigDecimal(String.valueOf(o));
            BigDecimal one = new BigDecimal("1");
            return v.divide(one, 2, BigDecimal.ROUND_HALF_UP);
        } else {
            return new BigDecimal(0);
        }
    }

    /**
     * @param o
     * @return Double
     * @description 转换为Double
     * @author 孙凯伦
     * @since 1.0.0
     */
    public static Double Double(Object o) {
        if (judge(o)) {
            return Double.valueOf(String.valueOf(o));
        } else {
            return null;
        }
    }

    /**
     * @param o
     * @return Byte
     * @description 转换为Byte
     * @author 孙凯伦
     * @since 1.0.0
     */
    public static Byte Byte(Object o) {
        if (judge(o)) {
            return Byte.valueOf(String.valueOf(o));
        } else {
            return 0;
        }
    }

    /**
     * @param o
     * @return Date
     * @throws ParseException
     * @description String转date
     * @author 孙凯伦
     * @since 1.0.0
     */
    public static Date Date(Object o) {
        if (judge(o)) {
            return DateUtil.stringToDate(String.valueOf(o));
        } else {
            return null;
        }
    }

    /**
     * @param o
     * @return Date
     * @throws ParseException
     * @description String转date
     * @author 孙凯伦
     * @since 1.0.0
     */
    public static Date Date(Object o, DateStyle dateStyle) {
        if (judge(o)) {
            return DateUtil.stringToDate(String.valueOf(o), dateStyle);
        } else {
            return null;
        }
    }

    /**
     * @param o
     * @return boolean
     * @description 判断是否为空
     * @author 孙凯伦
     * @since 1.0.0
     */
    public static boolean judge(Object o) {
        if (o != null) {
            if (StringUtils.isNotBlank(String.valueOf(o))) {
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }


    /**
     * 对象转数组
     *
     * @param obj
     * @return
     */
    public static byte[] toByte(Object obj) {
        byte[] bytes = null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(obj);
            oos.flush();
            bytes = bos.toByteArray();
            oos.close();
            bos.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        return bytes;
    }
    /**
     * 判断是否基础类型
     * @author      sunkailun
     * @DateTime    2020/7/22  9:52 上午
     * @email       376253703@qq.com
     * @phone       13777579028
     * @param className:
     * @return      boolean
     */
    public static boolean isBaseType(Class className) {
        if (className.equals(String.class)) {
            return false;
        } else if (className.equals(Integer.class)) {
            return false;
        } else if (className.equals(int.class)) {
            return false;
        } else if (className.equals(Byte.class)) {
            return false;
        } else if (className.equals(byte.class)) {
            return false;
        } else if (className.equals(Long.class)) {
            return false;
        } else if (className.equals(long.class)) {
            return false;
        } else if (className.equals(Double.class)) {
            return false;
        } else if (className.equals(double.class)) {
            return false;
        } else if (className.equals(Float.class)) {
            return false;
        } else if (className.equals(float.class)) {
            return false;
        } else if (className.equals(char.class)) {
            return false;
        } else if (className.equals(Character.class)) {
            return false;
        } else if (className.equals(Short.class)) {
            return false;
        } else if (className.equals(short.class)) {
            return false;
        } else if (className.equals(Boolean.class)) {
            return false;
        } else if (className.equals(boolean.class)) {
            return false;
        }
        return true;
    }

}
