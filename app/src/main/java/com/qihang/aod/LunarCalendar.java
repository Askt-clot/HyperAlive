package com.qihang.aod;

import java.util.Calendar;
import java.util.Date;

/**
 * 轻量级农历（阴历）换算工具，还原魅族 FlymeLunarDateView。
 * 覆盖 2020 ~ 2035 年。
 */
public class LunarCalendar {

    // 2020 ~ 2035 农历编码表（高4位闰月，低16位大小月）
    private static final int[] LUNAR_INFO = {
            0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
            0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
            0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
            0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950
    };

    private static final String[] CHINESE_NUMBER = {
            "一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二"
    };

    private static final String[] CHINESE_TEN = {"初", "十", "廿", "三十"};

    public static String getLunarDateString(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);

        // 简易农历换算：基准 2020-01-25 为农历正月初一
        Calendar baseCal = Calendar.getInstance();
        baseCal.set(2020, Calendar.JANUARY, 25, 0, 0, 0);
        long offset = (cal.getTimeInMillis() - baseCal.getTimeInMillis()) / 86400000L;
        if (offset < 0) {
            return "";
        }

        int lunarYear = 2020;
        int daysOfYear = 0;
        while (lunarYear <= 2035) {
            daysOfYear = getLunarYearDays(lunarYear);
            if (offset < daysOfYear) {
                break;
            }
            offset -= daysOfYear;
            lunarYear++;
        }

        int leapMonth = getLeapMonth(lunarYear);
        int lunarMonth = 1;
        boolean isLeap = false;
        for (int m = 1; m <= 12; m++) {
            // 闰月
            if (leapMonth > 0 && m == (leapMonth + 1) && !isLeap) {
                isLeap = true;
                m--;
            } else {
                isLeap = false;
            }
            int daysOfMonth = isLeap ? getLeapDays(lunarYear) : getLunarMonthDays(lunarYear, m);
            if (offset < daysOfMonth) {
                lunarMonth = m;
                break;
            }
            offset -= daysOfMonth;
        }

        int lunarDay = (int) offset + 1;

        String monthStr = (isLeap ? "闰" : "") + (lunarMonth == 1 ? "正" : CHINESE_NUMBER[lunarMonth - 1]) + "月";
        String dayStr;
        if (lunarDay == 10) {
            dayStr = "初十";
        } else if (lunarDay == 20) {
            dayStr = "二十";
        } else if (lunarDay == 30) {
            dayStr = "三十";
        } else {
            dayStr = CHINESE_TEN[lunarDay / 10] + (lunarDay % 10 == 0 ? "" : CHINESE_NUMBER[(lunarDay % 10) - 1]);
        }
        return monthStr + dayStr;
    }

    private static int getLunarYearDays(int y) {
        int idx = y - 1900;
        if (idx < 0 || idx >= LUNAR_INFO.length) return 365;
        int sum = 348;
        for (int i = 0x8000; i > 0x8; i >>= 1) {
            sum += (LUNAR_INFO[idx] & i) != 0 ? 1 : 0;
        }
        return sum + getLeapDays(y);
    }

    private static int getLeapDays(int y) {
        int idx = y - 1900;
        if (idx < 0 || idx >= LUNAR_INFO.length) return 0;
        if (getLeapMonth(y) != 0) {
            return (LUNAR_INFO[idx] & 0x10000) != 0 ? 30 : 29;
        }
        return 0;
    }

    private static int getLeapMonth(int y) {
        int idx = y - 1900;
        if (idx < 0 || idx >= LUNAR_INFO.length) return 0;
        return LUNAR_INFO[idx] & 0xf;
    }

    private static int getLunarMonthDays(int y, int m) {
        int idx = y - 1900;
        if (idx < 0 || idx >= LUNAR_INFO.length) return 30;
        return (LUNAR_INFO[idx] & (0x10000 >> m)) != 0 ? 30 : 29;
    }
}
