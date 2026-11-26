/*
 * Contains code adapted from OpenAlive (org.aliveclean.WallpaperTransitionTime).
 * Upstream attribution and applicable license: THIRD_PARTY_NOTICES.md.
 */
package io.github.askt_clot.hyperalive;

/** Elapsed scene time on a monotonic clock, with optional pause and resume. */
final class WallpaperTransitionTime {
    private long last=-1,elapsed;
    void restart(){elapsed=0;last=-1;}
    void pause(){last=-1;}
    float advance(long now){
        if(last>=0)elapsed+=Math.max(0,now-last);
        last=now;
        return elapsed/1_000_000f;
    }
}
