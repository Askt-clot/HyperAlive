package com.hyperalive.aod;

interface WallpaperMotion {
    void change(int scene,boolean animate);
    void advance(long time,float speed);
    void follow(float x,float y);
    void pause();
    default void pause(boolean freezeScene) { pause(); }
    default void advanceScene(long nowNanos) {}
    default void finishScene() {}
    boolean active();
}
