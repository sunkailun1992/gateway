package com.kellen.utils.date;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * Created with IntelliJ IDEA.
 *
 * @author sunkailun
 * @DateTime 2020/7/17  10:42 上午
 * @email 376253703@qq.com
 * @phone 13777579028
 * @explain
 */
public class LocalDateTimeUtil {
    /**
     * 获取指定时间的指定格式
     * @param time
     * @param pattern
     * @return
     */
    public static String formatTime(LocalDateTime time, String pattern) {
        return time.format(DateTimeFormatter.ofPattern(pattern));
    }
    /**
     * LocalDateTime转字符串
     * @param time
     * @return
     */
    public static String formatTime(LocalDateTime time, DateStyle dateStyle) {
        if(time != null) {
            return time.format(DateTimeFormatter.ofPattern(dateStyle.getValue()));
        }else{
            return "";
        }
    }
    /**
     * 字符串转LocalDateTime
     * @param dateTime
     * @return
     */
    public static LocalDateTime stringToLocalDateTime(String dateTime,String pattern) {
        return LocalDateTime.parse(dateTime, DateTimeFormatter.ofPattern(pattern));
    }
    /**
     * 字符串转LocalDateTime
     * @param dateTime
     * @return
     */
    public static LocalDateTime stringToLocalDateTime(String dateTime,DateStyle dateStyle) {
        return LocalDateTime.parse(dateTime, DateTimeFormatter.ofPattern(dateStyle.getValue()));
    }

    /**
     * date转LocalDateTime
     * @param date
     * @return
     */
    public static LocalDateTime dateToLocalDateTime(Date date) {
        ZoneId zoneId = ZoneId.systemDefault();
        return date.toInstant().atZone(zoneId).toLocalDateTime();
    }


    /**
     * LocalDateTime转date
     * @param localDateTime
     * @return
     */
    public static Date localDateTimeToDate(LocalDateTime localDateTime) {
        ZoneId zoneId = ZoneId.systemDefault();
        ZonedDateTime zonedDateTime = localDateTime.atZone(zoneId);
        return Date.from(zonedDateTime.toInstant());
    }

    public static void main(String[] args) {
       Long  i = 1357909894224715777L;
        System.out.printf(i.toString());
    }
}
