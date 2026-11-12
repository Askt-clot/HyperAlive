package com.qihang.aod;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 魅族「留光」(style=1) 与「星月」(style=2) 官方相框与艺术遮罩合成渲染管线。
 *
 * 1:1 还原魅族 Flyme / OpenAlive 官方 chromakey 渲染与多层遮罩合成逻辑：
 *   - 留光 (leave_light, style=1):
 *       * 63 帧 720×720 RGB 遮罩流（start.rgbz + 000..060.rgbz + end.rgbz）
 *       * R 通道: 底层倾斜衬卡（配合 63 组预计算姿态矩阵 u_rear_transform 同步扇形旋转展开）
 *       * G 通道: 顶层主相框视窗（320/720 居中取景，展开时通过 u_photo_alpha 平滑过渡到 1:1 全屏锁屏/桌面壁纸）
 *       * B 通道: 拍立得纯白相纸边框（blendNormal 叠加 vec3(1.0)）
 *   - 星月 (moon, style=2):
 *       * 34 帧 720×720 RGB 遮罩流（start.rgbz + 000..031.rgbz + end.rgbz）
 *       * R 通道: 弯月与星芒微光背景外晕（blendNormal 叠加深空群星底色 u_first_color(0.15, 0.27, 0.32)）
 *       * G 通道: 弯月内部取景主视窗（content 缩放 2.34，与锁屏壁纸融合）
 *       * B 通道: 皎洁星月轮廓高光（blendNormal 叠加纯白微光 vec3(1.0)）
 *       * 无底层倾斜相框 (u_pair=0)，严谨 0° 直立，AOD 22px 朦胧高斯绽放展开
 */
final class LiuguangPipeline {

    private static final float[] QUAD_VERTS = {
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
    };

    /**
     * 63 组底层衬卡姿态表 [cx, cy, cos, sin]（源自 OpenAlive native/frame_rear.rs）。
     * 索引 0..60 对应 000.rgbz..060.rgbz，61 对应 start.rgbz，62 对应 end.rgbz。
     */
    private static final float[][] REAR_POSES = {
            {0.53827566f, 0.49797848f, 0.99636287f, -0.08521162f},
            {0.53827566f, 0.49797848f, 0.99636287f, -0.08521162f},
            {0.53827566f, 0.49797848f, 0.99636287f, -0.08521162f},
            {0.53827566f, 0.49797848f, 0.99636287f, -0.08521162f},
            {0.53827566f, 0.49797848f, 0.99636287f, -0.08521162f},
            {0.53827566f, 0.49797848f, 0.99636287f, -0.08521162f},
            {0.53830916f, 0.49788500f, 0.99640120f, -0.08476207f},
            {0.53902820f, 0.49777684f, 0.99659130f, -0.08249725f},
            {0.53907233f, 0.49764848f, 0.99686210f, -0.07915789f},
            {0.53874810f, 0.49621520f, 0.99732125f, -0.07314585f},
            {0.53901790f, 0.49558896f, 0.99776140f, -0.06687484f},
            {0.53924900f, 0.49521902f, 0.99833155f, -0.05774208f},
            {0.53943455f, 0.49451807f, 0.99881720f, -0.04862285f},
            {0.53887320f, 0.49438840f, 0.99923700f, -0.03905639f},
            {0.53841126f, 0.49266260f, 0.99961630f, -0.02769996f},
            {0.53826284f, 0.49190020f, 0.99988410f, -0.01522164f},
            {0.53850466f, 0.49063322f, 0.99999964f, -0.00083795f},
            {0.53780560f, 0.48947710f, 0.99988620f,  0.01508657f},
            {0.53641350f, 0.48766005f, 0.99935720f,  0.03584829f},
            {0.53564880f, 0.48604986f, 0.99824930f,  0.05914635f},
            {0.53355490f, 0.48527100f, 0.99639370f,  0.08485000f},
            {0.53139110f, 0.48400337f, 0.99425733f,  0.10701561f},
            {0.52840660f, 0.48272850f, 0.99184610f,  0.12744172f},
            {0.52567340f, 0.48195186f, 0.98967475f,  0.14333151f},
            {0.52275765f, 0.48193884f, 0.98791840f,  0.15497518f},
            {0.52052120f, 0.48194890f, 0.98667500f,  0.16270343f},
            {0.51799214f, 0.48215938f, 0.98598350f,  0.16684289f},
            {0.51595050f, 0.48251500f, 0.98567330f,  0.16866611f},
            {0.51427580f, 0.48307642f, 0.98591290f,  0.16725972f},
            {0.51271470f, 0.48305196f, 0.98632747f,  0.16479725f},
            {0.51128410f, 0.48341620f, 0.98693246f,  0.16113469f},
            {0.50966585f, 0.48358563f, 0.98773460f,  0.15614218f},
            {0.50887537f, 0.48374970f, 0.98867035f,  0.15010315f},
            {0.50763850f, 0.48441080f, 0.98966050f,  0.14342980f},
            {0.50673960f, 0.48436993f, 0.99070720f,  0.13601170f},
            {0.50592643f, 0.48469952f, 0.99173240f,  0.12832320f},
            {0.50516135f, 0.48453915f, 0.99271125f,  0.12051730f},
            {0.50449395f, 0.48469648f, 0.99369970f,  0.11207537f},
            {0.50400860f, 0.48470566f, 0.99460940f,  0.10369221f},
            {0.50344545f, 0.48476055f, 0.99543250f,  0.09546816f},
            {0.50304294f, 0.48477557f, 0.99616534f,  0.08749059f},
            {0.50262743f, 0.48472542f, 0.99684435f,  0.07938079f},
            {0.50223790f, 0.48467225f, 0.99746160f,  0.07120612f},
            {0.50191630f, 0.48455070f, 0.99797726f,  0.06357218f},
            {0.50164140f, 0.48444060f, 0.99843190f,  0.05597925f},
            {0.50126400f, 0.48438810f, 0.99881697f,  0.04862809f},
            {0.50105390f, 0.48439348f, 0.99912430f,  0.04184072f},
            {0.50085100f, 0.48426706f, 0.99937550f,  0.03533491f},
            {0.50068480f, 0.48425610f, 0.99957350f,  0.02920233f},
            {0.50048040f, 0.48423180f, 0.99972147f,  0.02360040f},
            {0.50039977f, 0.48414502f, 0.99982490f,  0.01871319f},
            {0.50026363f, 0.48418275f, 0.99989986f,  0.01415303f},
            {0.50016797f, 0.48416632f, 0.99994594f,  0.01039784f},
            {0.50016160f, 0.48411033f, 0.99997485f,  0.00709075f},
            {0.50008380f, 0.48406875f, 0.99998915f,  0.00466246f},
            {0.50003770f, 0.48404998f, 0.99999660f,  0.00261493f},
            {0.50001810f, 0.48403105f, 0.99999940f,  0.00109242f},
            {0.50001810f, 0.48403105f, 0.99999940f,  0.00109242f},
            {0.50001810f, 0.48403105f, 0.99999940f,  0.00109242f},
            {0.50001810f, 0.48403105f, 0.99999940f,  0.00109242f},
            {0.50001810f, 0.48403105f, 0.99999940f,  0.00109242f},
            {0.53827566f, 0.49797848f, 0.99636287f, -0.08521162f}, // 61: start.rgbz
            {0.50001810f, 0.48403105f, 0.99999940f,  0.00109242f}, // 62: end.rgbz
    };

    private static final String VS =
            "#version 300 es\n" +
            "in vec2 a_pos;\n" +
            "in vec2 a_uv;\n" +
            "uniform mat4 u_modelMatrix;\n" +
            "uniform mat4 u_contentMatrix;\n" +
            "out vec2 v_snapshot_texCoord;\n" +
            "out vec2 v_content_texCoord;\n" +
            "void main() {\n" +
            "    gl_Position = u_modelMatrix * vec4(a_pos, 0.0, 1.0);\n" +
            "    v_snapshot_texCoord = a_uv;\n" +
            "    v_content_texCoord = (u_contentMatrix * vec4(a_uv, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String FS =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "in vec2 v_snapshot_texCoord;\n" +
            "in vec2 v_content_texCoord;\n" +
            "out vec4 fragColor;\n" +
            "uniform sampler2D u_photo;\n" +
            "uniform sampler2D u_home_photo;\n" +
            "uniform sampler2D u_mask;\n" +
            "uniform sampler2D u_frame_photo;\n" +
            "uniform mat3 u_frame_crop;\n" +
            "uniform mat3 u_rear_transform;\n" +
            "uniform float u_photo_alpha;\n" +
            "uniform float u_home_ratio;\n" +
            "uniform int u_pair;\n" +
            "uniform vec3 u_first_color;\n" +
            "\n" +
            "vec3 blendNormal(vec3 base, vec3 blend, float opacity) {\n" +
            "    return blend * opacity + base * (1.0 - opacity);\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    vec3 videoColor = texture(u_mask, v_snapshot_texCoord).rgb;\n" +
            "    vec3 finalColor = vec3(0.0);\n" +
            "    if (u_pair == 1) {\n" +
            "        if (videoColor.r > 0.001) {\n" +
            "            vec2 rearUv = (u_frame_crop * u_rear_transform * vec3(v_snapshot_texCoord, 1.0)).xy;\n" +
            "            vec3 rearColor = texture(u_frame_photo, clamp(rearUv, 0.0, 1.0)).rgb;\n" +
            "            finalColor = blendNormal(finalColor, rearColor, videoColor.r);\n" +
            "        }\n" +
            "    } else {\n" +
            "        if (videoColor.r > 0.001) {\n" +
            "            finalColor = blendNormal(finalColor, u_first_color, videoColor.r);\n" +
            "        }\n" +
            "    }\n" +
            "    vec2 fullUv = clamp(v_content_texCoord, 0.0, 1.0);\n" +
            "    vec3 fullColor = texture(u_photo, fullUv).rgb;\n" +
            "    if (u_home_ratio > 0.0001) {\n" +
            "        vec3 homeColor = texture(u_home_photo, fullUv).rgb;\n" +
            "        fullColor = mix(fullColor, homeColor, clamp(u_home_ratio, 0.0, 1.0));\n" +
            "    }\n" +
            "    vec3 contentColor = fullColor;\n" +
            "    if (u_pair == 1) {\n" +
            "        vec2 frameUv = (u_frame_crop * vec3(v_snapshot_texCoord - vec2(0.5, 348.5 / 720.0), 1.0)).xy;\n" +
            "        vec3 frameColor = texture(u_frame_photo, clamp(frameUv, 0.0, 1.0)).rgb;\n" +
            "        contentColor = mix(fullColor, frameColor, clamp(u_photo_alpha, 0.0, 1.0));\n" +
            "    }\n" +
            "    finalColor = blendNormal(finalColor, contentColor, videoColor.g);\n" +
            "    finalColor = blendNormal(finalColor, vec3(1.0), videoColor.b);\n" +
            "    fragColor = vec4(finalColor, 1.0);\n" +
            "}\n";

    private final Context context;
    private int activeStyle;
    private int program;
    private int vao;
    private int vbo;
    private int maskTexture;
    private MaskFrames maskFrames;

    private int uPhoto;
    private int uHomePhoto;
    private int uMask;
    private int uFramePhoto;
    private int uModelMatrix;
    private int uContentMatrix;
    private int uFrameCrop;
    private int uRearTransform;
    private int uPhotoAlpha;
    private int uHomeRatio;
    private int uPair;
    private int uFirstColor;

    private final float[] model = new float[16];
    private final float[] scratch = new float[16];
    private final float[] modelMatrix = new float[16];
    private final float[] contentMatrix = new float[16];
    private final float[] frameCropMatrix = new float[9];
    private final float[] rearTransformMatrix = new float[9];

    LiuguangPipeline(Context context) {
        this(context, AodStyleSettings.STYLE_LEAVE_LIGHT);
    }

    LiuguangPipeline(Context context, int initialStyle) {
        this.context = context.getApplicationContext();
        this.activeStyle = initialStyle;
        this.maskFrames = MaskFrames.forStyle(this.context, this.activeStyle);
        initGl();
    }

    void setStyle(int style) {
        if (this.activeStyle != style) {
            this.activeStyle = style;
            if (maskFrames != null) {
                maskFrames.close();
            }
            maskFrames = MaskFrames.forStyle(context, style);
        }
    }

    private void initGl() {
        program = buildProgram(VS, FS);
        uPhoto = GLES30.glGetUniformLocation(program, "u_photo");
        uHomePhoto = GLES30.glGetUniformLocation(program, "u_home_photo");
        uMask = GLES30.glGetUniformLocation(program, "u_mask");
        uFramePhoto = GLES30.glGetUniformLocation(program, "u_frame_photo");
        uModelMatrix = GLES30.glGetUniformLocation(program, "u_modelMatrix");
        uContentMatrix = GLES30.glGetUniformLocation(program, "u_contentMatrix");
        uFrameCrop = GLES30.glGetUniformLocation(program, "u_frame_crop");
        uRearTransform = GLES30.glGetUniformLocation(program, "u_rear_transform");
        uPhotoAlpha = GLES30.glGetUniformLocation(program, "u_photo_alpha");
        uHomeRatio = GLES30.glGetUniformLocation(program, "u_home_ratio");
        uPair = GLES30.glGetUniformLocation(program, "u_pair");
        uFirstColor = GLES30.glGetUniformLocation(program, "u_first_color");

        FloatBuffer buf = ByteBuffer.allocateDirect(QUAD_VERTS.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(QUAD_VERTS).position(0);

        int[] ids = new int[2];
        GLES30.glGenVertexArrays(1, ids, 0);
        vao = ids[0];
        GLES30.glBindVertexArray(vao);

        GLES30.glGenBuffers(1, ids, 0);
        vbo = ids[0];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, QUAD_VERTS.length * 4, buf, GLES30.GL_STATIC_DRAW);

        int aPos = GLES30.glGetAttribLocation(program, "a_pos");
        int aUv = GLES30.glGetAttribLocation(program, "a_uv");
        GLES30.glEnableVertexAttribArray(aPos);
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 16, 0);
        GLES30.glEnableVertexAttribArray(aUv);
        GLES30.glVertexAttribPointer(aUv, 2, GLES30.GL_FLOAT, false, 16, 8);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glBindVertexArray(0);

        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        maskTexture = tex[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTexture);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
    }

    void render(int photoTex, int homeTex, int frameTex, float homeRatio, Motion motion, int width, int height) {
        render(photoTex, homeTex, frameTex, homeRatio, motion, width, height, activeStyle);
    }

    void render(int photoTex, int homeTex, int frameTex, float homeRatio, Motion motion, int width, int height, int style) {
        if (program == 0 || maskTexture == 0 || photoTex == 0 || motion == null || width <= 0 || height <= 0) {
            return;
        }

        setStyle(style);

        long micros = motion.frameMicros();
        int frameIdx = maskFrames.getFrameIndex(micros);
        maskFrames.upload(frameIdx, maskTexture);

        // 1. 计算官方 u_modelMatrix 与 u_contentMatrix（对齐 FrameMotion.packet）
        float x = motion.frameX();
        float y = motion.frameY();
        float scale = motion.frameScale();
        float rotation = motion.frameRotation();
        float content = Math.max(0.001f, motion.frameContent());
        float aspect = (float) height / (float) width;

        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, width * x, -height * y, 0f);
        Matrix.scaleM(model, 0, scale, scale, 1f);
        Matrix.rotateM(model, 0, rotation, 0f, 0f, 1f);
        Matrix.setIdentityM(scratch, 0);
        Matrix.scaleM(scratch, 0, 2f / width, -2f / height, 1f);
        Matrix.multiplyMM(modelMatrix, 0, scratch, 0, model, 0);
        float half = Math.min(width, height) / 2f;
        Matrix.scaleM(modelMatrix, 0, half, half, 1f);

        Matrix.setIdentityM(contentMatrix, 0);
        Matrix.translateM(contentMatrix, 0, 0.5f, 0.5f, 0f);
        Matrix.scaleM(contentMatrix, 0, aspect / content, 1f / content, 1f);
        Matrix.translateM(contentMatrix, 0, -0.5f, -0.5f, 0f);

        boolean isPaired = (activeStyle == AodStyleSettings.STYLE_LEAVE_LIGHT);

        // 2. 计算相框正方形取景矩阵 u_frame_crop（严格对齐 PhotoCropActivity 留光相框 320/720 取景参考框）
        float cropX = 1.0f;
        float cropY = (float) width / (float) height;
        frameCropMatrix[0] = cropX; frameCropMatrix[1] = 0f;    frameCropMatrix[2] = 0f;
        frameCropMatrix[3] = 0f;    frameCropMatrix[4] = cropY; frameCropMatrix[5] = 0f;
        frameCropMatrix[6] = 0.5f;  frameCropMatrix[7] = 348.5f / 720f;  frameCropMatrix[8] = 1f;

        // 3. 计算底层倾斜衬卡逆变换矩阵 u_rear_transform (留光专属)
        if (isPaired) {
            int poseIdx = (frameIdx >= 0 && frameIdx < REAR_POSES.length) ? frameIdx : 61;
            float[] pose = REAR_POSES[poseIdx];
            float cx = pose[0], cy = pose[1], c = pose[2], s = pose[3];
            rearTransformMatrix[0] = c;   rearTransformMatrix[1] = s;   rearTransformMatrix[2] = 0f;
            rearTransformMatrix[3] = -s;  rearTransformMatrix[4] = c;   rearTransformMatrix[5] = 0f;
            rearTransformMatrix[6] = -cx * c + cy * s;
            rearTransformMatrix[7] = -cx * s - cy * c;
            rearTransformMatrix[8] = 1f;
        } else {
            rearTransformMatrix[0] = 1f; rearTransformMatrix[1] = 0f; rearTransformMatrix[2] = 0f;
            rearTransformMatrix[3] = 0f; rearTransformMatrix[4] = 1f; rearTransformMatrix[5] = 0f;
            rearTransformMatrix[6] = 0f; rearTransformMatrix[7] = 0f; rearTransformMatrix[8] = 1f;
        }

        GLES30.glViewport(0, 0, width, height);
        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);

        GLES30.glUseProgram(program);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, photoTex);
        GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_LOD, 0f);
        GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LOD, 0f);
        GLES30.glUniform1i(uPhoto, 0);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTexture);
        GLES30.glUniform1i(uMask, 1);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, homeTex != 0 ? homeTex : photoTex);
        GLES30.glUniform1i(uHomePhoto, 2);

        int fTex = frameTex != 0 ? frameTex : photoTex;
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fTex);
        GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_LOD, 0f);
        GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LOD, 0f);
        GLES30.glUniform1i(uFramePhoto, 3);

        GLES30.glUniformMatrix4fv(uModelMatrix, 1, false, modelMatrix, 0);
        GLES30.glUniformMatrix4fv(uContentMatrix, 1, false, contentMatrix, 0);
        GLES30.glUniformMatrix3fv(uFrameCrop, 1, false, frameCropMatrix, 0);
        GLES30.glUniformMatrix3fv(uRearTransform, 1, false, rearTransformMatrix, 0);
        GLES30.glUniform1f(uPhotoAlpha, motion.framePhotoAlpha());
        GLES30.glUniform1f(uHomeRatio, homeTex != 0 ? homeRatio : 0f);
        GLES30.glUniform1i(uPair, isPaired ? 1 : 0);
        GLES30.glUniform3f(uFirstColor, 0.15f, 0.27f, 0.32f);

        GLES30.glBindVertexArray(vao);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glBindVertexArray(0);
    }

    void release() {
        if (maskFrames != null) {
            maskFrames.close();
            maskFrames = null;
        }
        if (maskTexture != 0) {
            GLES30.glDeleteTextures(1, new int[]{maskTexture}, 0);
            maskTexture = 0;
        }
        if (vbo != 0) {
            GLES30.glDeleteBuffers(1, new int[]{vbo}, 0);
            vbo = 0;
        }
        if (vao != 0) {
            GLES30.glDeleteVertexArrays(1, new int[]{vao}, 0);
            vao = 0;
        }
        if (program != 0) {
            GLES30.glDeleteProgram(program);
            program = 0;
        }
    }

    private static int buildProgram(String vs, String fs) {
        int v = compileShader(GLES30.GL_VERTEX_SHADER, vs);
        int f = compileShader(GLES30.GL_FRAGMENT_SHADER, fs);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        GLES30.glDeleteShader(v);
        GLES30.glDeleteShader(f);
        if (ok[0] == 0) {
            String log = GLES30.glGetProgramInfoLog(p);
            GLES30.glDeleteProgram(p);
            throw new IllegalStateException("Photo mask program link failed: " + log);
        }
        return p;
    }

    private static int compileShader(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(s);
            GLES30.glDeleteShader(s);
            throw new IllegalStateException("Photo mask shader compile failed: " + log);
        }
        return s;
    }
}
