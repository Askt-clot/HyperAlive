/*
 * Contains code adapted from OpenAlive (org.aliveclean.WallpaperRenderer).
 * Upstream attribution and applicable license: THIRD_PARTY_NOTICES.md.
 */
package io.github.askt_clot.hyperalive;

import android.content.res.AssetManager;
import java.io.IOException;

interface WallpaperRenderer extends AutoCloseable {
    WallpaperMotion motion();
    void render();
    void close();
    default void visible(boolean value){}
    default void continuous(boolean enabled) {}
    default boolean hasAmbientMotion() { return false; }
    static WallpaperRenderer create(AssetManager assets,int variant,boolean dark,int width,int height,int scene,boolean keepLock)throws IOException {
        return create(assets,variant,dark,width,height,scene,keepLock,()->{});
    }
    static WallpaperRenderer create(AssetManager assets,int variant,boolean dark,int width,int height,int scene,boolean keepLock,Runnable invalidate)throws IOException {
        if(variant>=401&&variant<=403)return new FlymeVideoRenderer(assets,variant,dark,width,height,scene,invalidate);
        if(variant>=501&&variant<=503)return new StereoRenderer(assets,variant,dark,width,height,scene,keepLock);
        if(variant>=201&&variant<=205)return new BubbleRenderer(assets,variant,dark,width,height,scene,keepLock);
        if(variant>=301&&variant<=303)return new GradientRenderer(assets,variant,dark,width,height,scene);
        return new CosmicRenderer(assets,variant,dark,width,height,scene,keepLock);
    }
}
