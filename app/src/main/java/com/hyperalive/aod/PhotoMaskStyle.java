package com.hyperalive.aod;

/** 参数对应 OpenAlive PhotoStyle 和各遮罩目录的 manifest.json。 */
enum PhotoMaskStyle {
    LEAVE_LIGHT("leave_light", 720, 61, 120, 483667, .70486f, 1f, 0f, 567, 530, 567),
    MOON("moon", 720, 32, 60, 516667, .986f, 2.34f, 22f, 533, 743, 553),
    DOOR("door", 720, 41, 60, 616667, .917f, 2.34f, 100f, 717, 733, 717),
    MOUNTAIN("mountain", 1080, 43, 60, 716667, .833f, 2.34f, 100f, 750, 684, 750),
    GALAXY("galaxy", 1080, 56, 60, 916667, .833f, 2.34f, 100f, 950, 800, 950);

    final String folder;
    final int size;
    final int frameCount;
    final int fps;
    final int endMicros;
    final float scale;
    final float content;
    final float blur;
    private final long openToLockMs;
    private final long closeMs;
    private final long openToHomeMs;

    PhotoMaskStyle(String folder, int size, int frameCount, int fps, int endMicros,
                   float scale, float content, float blur,
                   long openToLockMs, long closeMs, long openToHomeMs) {
        this.folder = folder;
        this.size = size;
        this.frameCount = frameCount;
        this.fps = fps;
        this.endMicros = endMicros;
        this.scale = scale;
        this.content = content;
        this.blur = blur;
        this.openToLockMs = openToLockMs;
        this.closeMs = closeMs;
        this.openToHomeMs = openToHomeMs;
    }

    long blendDuration(int transition) {
        switch (transition) {
            case 0: return openToLockMs;
            case 1:
            case 3: return closeMs;
            case 2: return openToHomeMs;
            case 4: return 800;
            default: return 400;
        }
    }

    static PhotoMaskStyle forId(int id) {
        switch (id) {
            case AodStyleSettings.STYLE_LEAVE_LIGHT: return LEAVE_LIGHT;
            case AodStyleSettings.STYLE_MOON: return MOON;
            case AodStyleSettings.STYLE_DOOR: return DOOR;
            case AodStyleSettings.STYLE_MOUNTAIN: return MOUNTAIN;
            case AodStyleSettings.STYLE_GALAXY: return GALAXY;
            default: return null;
        }
    }
}
