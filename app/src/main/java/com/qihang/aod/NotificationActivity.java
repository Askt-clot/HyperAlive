package com.qihang.aod;

/**
 * 通知光效 —— 占位页。
 *
 * <p>OpenAlive 的 {@code NotificationSettingsActivity} 有系统光效 / 光环颜色 / 显示时长等一整套，
 * 本模块这一版**尚未实现**，按约定留空（只保留入口与说明，不做假数据）。
 */
public class NotificationActivity extends UiActivity {

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            page("通知光效", "暂未实现");
            MiSettingsUi.Card card = ui.card();
            ui.row(card.body, "功能未实现", "本版不做通知光效（留空占位）", null, null);
            addCard(card);
            addStatusLog();
        } catch (Throwable t) {
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText("界面构建失败：\n" + t);
            tv.setPadding(40, 120, 40, 40);
            setContentView(tv);
        }
    }
}