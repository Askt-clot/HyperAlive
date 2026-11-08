package com.qihang.aod;

/**
 * **桌面槽位**壁纸：与 {@link QihangWallpaper} 完全同一套实现（直接继承），
 * 但使用**独立的 ComponentName**。
 *
 * <p>为什么需要它：实测澎湃 OS 在「设置一侧动态壁纸」时会把另一侧重置
 * （日志 {@code WallpaperManagerServiceImpl: static wallpaper null, use miui default preset wallpaper}），
 * 且当两侧指向**同一个组件**时系统最终只保留一侧。两侧用不同组件才能同时留住，
 * 这样解锁时桌面槽位的 Engine 才能从 AOD 走 <b>tr2（熄屏→桌面）</b>的圆扩散，
 * 而不是 tr4（锁屏→桌面，没有扩散）。
 *
 * <p>两个 Engine 会收到同一条广播；锁屏期间桌面 Engine 不可见，由
 * {@link QihangWallpaper} 里「不可见时忽略 mode=1」的规则把锁屏指令挡掉。
 */
public class QihangHomeWallpaper extends QihangWallpaper {
}