package io.github.askt_clot.hyperalive;

import android.icu.util.Calendar;
import android.icu.util.ChineseCalendar;
import android.icu.util.TimeZone;

import java.util.Date;
import java.util.Locale;

/** 魅族时钟的农历月日显示。 */
final class LunarCalendar {
    private static final String[] MONTHS = {
            "正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二"
    };
    private static final String[] NUMBERS = {
            "一", "二", "三", "四", "五", "六", "七", "八", "九"
    };

    private LunarCalendar() {}

    static String getLunarDateString(Date date, java.util.TimeZone zone) {
        ChineseCalendar calendar = new ChineseCalendar(TimeZone.getTimeZone(zone.getID()), Locale.CHINA);
        calendar.setTime(date);
        String month = (calendar.get(Calendar.IS_LEAP_MONTH) == 1 ? "闰" : "")
                + MONTHS[calendar.get(Calendar.MONTH)] + "月";
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        if (day == 10) return month + "初十";
        if (day == 20) return month + "二十";
        if (day == 30) return month + "三十";
        String prefix = day < 10 ? "初" : day < 20 ? "十" : "廿";
        return month + prefix + NUMBERS[day % 10 - 1];
    }
}
