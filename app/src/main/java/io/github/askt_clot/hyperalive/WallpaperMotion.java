/*
 * Contains code adapted from OpenAlive (org.aliveclean.WallpaperMotion).
 * Upstream attribution and applicable license: THIRD_PARTY_NOTICES.md.
 */
package io.github.askt_clot.hyperalive;

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
