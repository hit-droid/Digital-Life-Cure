package com.maiden.pet.render;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * 自绘二次元少女渲染引擎。
 * 程序化绘制一个「活」的角色：刘海长发、渐变虹膜大眼、表情变化、
 * 呼吸/眨眼/口型/动作，全部由 Pose 状态驱动，无需外部 Live2D 模型。
 */
public class GirlRenderer {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pStro = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pShad = new Paint(Paint.ANTI_ALIAS_FLAG);

    // 单位：以短边 120 等分
    private float u;

    /** 视口内角色中心 */
    private float cx, cy;

    /** 动作/头部姿态插值 */
    private float bodyY = 0f, tilt = 0f, armR = 0f, armL = 0f;
    private float headRot = 0f, stretchY = 0f, bounceOff = 0f, sitDown = 0f, wavePhase = 0f;

    public GirlRenderer() {
        pStro.setStyle(Paint.Style.STROKE);
        pStro.setStrokeCap(Paint.Cap.ROUND);
        pStro.setColor(Color.rgb(74, 58, 90));
        pShad.setStyle(Paint.Style.FILL);
        pShad.setColor(Color.argb(40, 0, 0, 0));
    }

    public void draw(Canvas cv, float w, float h, Pose pose) {
        u = Math.min(w, h) / 120f;
        cx = w / 2f;
        cy = h * 0.56f;
        updatePose(pose);
        drawGroundShadow(cv);
        drawHairBack(cv, pose);
        drawBody(cv, pose);
        drawArms(cv, pose);
        drawHead(cv, pose);
        drawHairFront(cv, pose);
    }

    private void updatePose(Pose pose) {
        float b = (float) Math.sin(pose.breathPhase);
        bodyY = b * 1.6f * u;
        headRot = 0f;
        stretchY = 0f;
        bounceOff = 0f;
        sitDown = 0f;
        armR = 0f;
        armL = 0f;
        wavePhase = 0f;

        float k = pose.intensity;
        switch (pose.action) {
            case "wave":
                armR = 1f;
                wavePhase = (float) Math.sin(pose.breathPhase * 2.2f);
                tilt = 4f;
                break;
            case "clap":
                armR = 0.8f; armL = 0.8f;
                break;
            case "point":
                armR = 1.15f;
                tilt = -3f;
                break;
            case "tilt_head":
                headRot = 12f * k;
                break;
            case "stretch":
                stretchY = 1f;
                armR = 1.25f; armL = 1.25f;
                break;
            case "bounce":
                bounceOff = Math.abs((float) Math.sin(pose.breathPhase * 2f)) * 8f * k;
                break;
            case "sit":
                sitDown = 1f;
                break;
            default:
                tilt = b * 1.5f;
                if (pose.speaking) armL = 0.25f;
                break;
        }
        if (pose.speaking) {
            if (pose.action.equals("wave")) tilt += 2f;
        }
    }

    // ================= 地面投影 =================
    private void drawGroundShadow(Canvas cv) {
        float shW = 26f * u, shH = 5f * u;
        float sy = cy + 52f * u + bounceOff - sitDown * 16f * u;
        p.setShader(new RadialGradient(cx, sy, shW * 0.6f,
                new int[]{Color.argb(60, 0, 0, 0), Color.argb(0, 0, 0, 0)},
                null, Shader.TileMode.CLAMP));
        cv.drawOval(new RectF(cx - shW, sy - shH, cx + shW, sy + shH), p);
        p.setShader(null);
    }

    // ================= 后发 =================
    private void drawHairBack(Canvas cv, Pose pose) {
        float hx = cx + tilt * 0.6f * u;
        float hy = cy - 26f * u + bodyY + bounceOff - sitDown * 14f * u;
        p.setShader(new LinearGradient(hx, hy - 22f * u, hx, hy + 46f * u,
                Color.rgb(236, 234, 252), Color.rgb(178, 170, 216), Shader.TileMode.CLAMP));
        Path back = new Path();
        back.moveTo(hx - 17f * u, hy - 14f * u);
        back.quadTo(hx - 24f * u, hy - 20f * u, hx - 19f * u, hy + 6f * u);
        back.quadTo(hx - 16f * u, hy + 24f * u, hx - 13f * u, hy + 36f * u);
        back.quadTo(hx - 8f * u, hy + 48f * u, hx - 2f * u, hy + 44f * u);
        back.quadTo(hx + 8f * u, hy + 48f * u, hx + 13f * u, hy + 36f * u);
        back.quadTo(hx + 16f * u, hy + 24f * u, hx + 19f * u, hy + 6f * u);
        back.quadTo(hx + 24f * u, hy - 20f * u, hx + 17f * u, hy - 14f * u);
        back.close();
        cv.drawPath(back, p);
        p.setShader(null);
    }

    // ================= 身体（连衣裙） =================
    private void drawBody(Canvas cv, Pose pose) {
        float bx = cx;
        float by = cy - 26f * u + bodyY + bounceOff + stretchY * 2f * u - sitDown * 14f * u;
        // 脖子
        p.setColor(Color.rgb(255, 231, 217));
        cv.drawRoundRect(new RectF(bx - 4f * u, by + 10f * u, bx + 4f * u, by + 20f * u), 2f * u, 2f * u, p);
        // 连衣裙（钟形）
        p.setShader(new LinearGradient(bx, by + 18f * u, bx, by + 52f * u,
                Color.rgb(201, 186, 245), Color.rgb(150, 130, 220), Shader.TileMode.CLAMP));
        Path dress = new Path();
        float widen = sitDown * 8f * u;
        dress.moveTo(bx - 7f * u, by + 20f * u);
        dress.quadTo(bx - 9f * u, by + 30f * u, bx - 15f * u - widen, by + 46f * u);
        dress.quadTo(bx - 11f * u - widen, by + 54f * u, bx, by + 54f * u);
        dress.quadTo(bx + 11f * u + widen, by + 54f * u, bx + 15f * u + widen, by + 46f * u);
        dress.quadTo(bx + 9f * u, by + 30f * u, bx + 7f * u, by + 20f * u);
        dress.close();
        cv.drawPath(dress, p);
        p.setShader(null);
        // 领口蝴蝶结
        p.setColor(Color.rgb(236, 160, 184));
        cv.drawOval(new RectF(bx - 9f * u, by + 9f * u, bx - 3f * u, by + 16f * u), p);
        cv.drawOval(new RectF(bx + 3f * u, by + 9f * u, bx + 9f * u, by + 16f * u), p);
        p.setColor(Color.rgb(214, 120, 148));
        cv.drawCircle(bx, by + 12f * u, 2.2f * u, p);
    }

    // ================= 手臂 =================
    private void drawArms(Canvas cv, Pose pose) {
        float bx = cx;
        float by = cy - 24f * u + bodyY + bounceOff - sitDown * 14f * u;
        drawArm(cv, bx - 8f * u, by + 24f * u, armL, -1, pose, bx - 12f * u, by + 44f * u);
        drawArm(cv, bx + 8f * u, by + 24f * u, armR, 1, pose, bx + 12f * u, by + 44f * u);
    }

    private void drawArm(Canvas cv, float sx, float sy, float raise, int dir, Pose pose, float restX, float restY) {
        float px = restX, py = restY;
        if (raise > 0.05f) {
            float a;
            if (pose.action.equals("wave")) {
                a = (float) Math.toRadians(-110 + wavePhase * 18f);
                py = sy - 34f * u;
            } else if (pose.action.equals("clap")) {
                a = (float) Math.toRadians(-70 * dir);
                px = cx - dir * 2f * u;
                py = sy - 14f * u;
            } else if (pose.action.equals("stretch")) {
                a = (float) Math.toRadians(-165);
                py = sy - 40f * u;
                px = sx + dir * 4f * u;
            } else {
                a = (float) Math.toRadians(-100);
                py = sy - 30f * u;
                px = sx + dir * 14f * u;
            }
            float len = 22f * u;
            px = sx + (float) Math.cos(a) * len;
            py = sy + (float) Math.sin(a) * len;
        }
        // 袖子
        pStro.setStrokeWidth(7f * u);
        pStro.setColor(Color.rgb(201, 186, 245));
        cv.drawLine(sx, sy, px, py, pStro);
        pStro.setStrokeWidth(0);
        pStro.setColor(Color.rgb(74, 58, 90));
        p.setColor(Color.rgb(255, 231, 217));
        cv.drawCircle(px, py, 3.4f * u, p);
    }

    // ================= 头 =================
    private void drawHead(Canvas cv, Pose pose) {
        float hx = cx + tilt * 0.6f * u;
        float hy = cy - 32f * u + bodyY + bounceOff - sitDown * 14f * u + stretchY * 1.5f * u;
        cv.save();
        cv.rotate(headRot, hx, hy);
        // 脸
        Path face = new Path();
        float R = 15f * u;
        face.moveTo(hx - R, hy + 2f * u);
        face.quadTo(hx - R, hy - R, hx, hy - R);
        face.quadTo(hx + R, hy - R, hx + R, hy + 2f * u);
        face.quadTo(hx + R, hy + R + 6f * u, hx + R * 0.45f, hy + R + 7f * u);
        face.quadTo(hx, hy + R + 11f * u, hx - R * 0.45f, hy + R + 7f * u);
        face.quadTo(hx - R, hy + R + 6f * u, hx - R, hy + 2f * u);
        face.close();
        p.setColor(Color.rgb(255, 234, 222));
        cv.drawPath(face, p);
        // 腮红
        p.setColor(Color.argb(90, 255, 140, 160));
        cv.drawOval(new RectF(hx - R * 0.95f, hy + 4f * u, hx - R * 0.25f, hy + 10f * u), p);
        cv.drawOval(new RectF(hx + R * 0.25f, hy + 4f * u, hx + R * 0.95f, hy + 10f * u), p);
        // 眉毛
        drawBrows(cv, hx, hy, pose);
        // 眼睛
        drawEye(cv, hx - 6.5f * u, hy + 2f * u, pose, -1);
        drawEye(cv, hx + 6.5f * u, hy + 2f * u, pose, 1);
        // 嘴
        drawMouth(cv, hx, hy, pose);
        cv.restore();
    }

    private void drawBrows(Canvas cv, float hx, float hy, Pose pose) {
        pStro.setColor(Color.rgb(120, 96, 140));
        pStro.setStrokeWidth(1.8f * u);
        float y = hy - 6f * u;
        float lx = 3.2f * u;
        float e = emotionBrow(pose.emotion);
        if (pose.emotion.equals("angry")) {
            cv.drawLine(hx - lx * 2.1f, y + 1.2f * u, hx - 0.3f * u, y - 1.6f * u, pStro);
            cv.drawLine(hx + lx * 2.1f, y + 1.2f * u, hx + 0.3f * u, y - 1.6f * u, pStro);
        } else if (pose.emotion.equals("sad")) {
            cv.drawLine(hx - lx * 2.1f, y - 0.5f * u, hx - 0.3f * u, y + 0.8f * u, pStro);
            cv.drawLine(hx + lx * 2.1f, y - 0.5f * u, hx + 0.3f * u, y + 0.8f * u, pStro);
        } else {
            cv.drawLine(hx - lx * 2.1f, y + e, hx + lx * 2.1f, y + e, pStro);
        }
        pStro.setColor(Color.rgb(74, 58, 90));
        pStro.setStrokeWidth(1f * u);
    }

    private float emotionBrow(String e) {
        switch (e) {
            case "surprised": return -2.2f * u;
            case "excited": return -1.2f * u;
            case "sleepy": return 1.2f * u;
            case "happy": return -0.6f * u;
            default: return 0f;
        }
    }

    private void drawEye(Canvas cv, float ex, float ey, Pose pose, int dir) {
        float blink = pose.blink;
        boolean happy = pose.emotion.equals("happy") || pose.emotion.equals("excited");
        boolean sad = pose.emotion.equals("sad");
        boolean surprised = pose.emotion.equals("surprised");
        boolean sleepy = pose.emotion.equals("sleepy");
        boolean shy = pose.emotion.equals("shy");

        float eW = 5.6f * u, eH = 7.4f * u;
        if (surprised) { eW *= 1.25f; eH *= 1.3f; }
        if (sleepy) eH *= 0.55f;

        float drop = blink * eH;
        if (shy) drop += 1.6f * u;

        // 眼白
        p.setColor(Color.WHITE);
        cv.drawOval(new RectF(ex - eW, ey - eH * 0.5f, ex + eW, ey + eH * 0.5f), p);

        float visible = Math.max(0f, eH - drop);
        if (visible > 1f && !happy) {
            // 虹膜
            float ir = eW * 0.82f;
            cv.save();
            cv.clipRect(ex - eW, ey - eH * 0.5f + drop * 0.4f, ex + eW, ey + eH * 0.5f);
            p.setShader(new RadialGradient(ex + dir * 1f * u, ey + 1.5f * u, ir * 1.6f,
                    new int[]{Color.rgb(154, 130, 255), Color.rgb(96, 74, 210), Color.rgb(52, 40, 140)},
                    null, Shader.TileMode.CLAMP));
            cv.drawCircle(ex + dir * 0.8f * u, ey + 1.2f * u, ir, p);
            p.setShader(null);
            // 瞳孔
            p.setColor(Color.rgb(34, 26, 90));
            cv.drawCircle(ex + dir * 0.8f * u, ey + 1.6f * u, ir * 0.5f, p);
            // 高光
            p.setColor(Color.WHITE);
            cv.drawCircle(ex - 1.6f * u + dir * 0.8f * u, ey - 1.6f * u + 1.2f * u, ir * 0.28f, p);
            cv.drawCircle(ex + 1.8f * u + dir * 0.8f * u, ey + 2.4f * u + 1.2f * u, ir * 0.16f, p);
            cv.restore();
        } else if (happy && visible > 1f) {
            // 笑眼：∩ 形
            p.setColor(Color.rgb(74, 58, 90));
            pStro.setStrokeWidth(2.2f * u);
            Path arch = new Path();
            arch.moveTo(ex - eW * 0.95f, ey - 1.6f * u);
            arch.quadTo(ex, ey + 1.4f * u, ex + eW * 0.95f, ey - 1.6f * u);
            cv.drawPath(arch, pStro);
            pStro.setStrokeWidth(1f * u);
        }

        // 上眼睑
        p.setColor(Color.rgb(74, 58, 90));
        pStro.setStrokeWidth(2.0f * u);
        Path lid = new Path();
        if (sad) {
            lid.moveTo(ex - eW * 0.98f, ey - eH * 0.2f);
            lid.quadTo(ex, ey + eH * 0.15f, ex + eW * 0.98f, ey - eH * 0.2f);
        } else if (surprised) {
            lid.moveTo(ex - eW * 0.95f, ey - eH * 0.6f);
            lid.quadTo(ex, ey - eH * 0.8f, ex + eW * 0.95f, ey - eH * 0.6f);
        } else {
            lid.moveTo(ex - eW * 0.95f, ey - eH * 0.45f + drop * 0.5f);
            lid.quadTo(ex, ey - eH * 0.62f + drop * 0.5f, ex + eW * 0.95f, ey - eH * 0.45f + drop * 0.5f);
        }
        cv.drawPath(lid, pStro);
        pStro.setStrokeWidth(1f * u);
    }

    private void drawMouth(Canvas cv, float hx, float hy, Pose pose) {
        float my = hy + 10.5f * u;
        float mw = 3.6f * u;
        String e = pose.emotion;
        pStro.setColor(Color.rgb(196, 96, 120));
        pStro.setStrokeWidth(1.6f * u);
        if (e.equals("happy") || e.equals("excited") || e.equals("surprised")) {
            float open = pose.mouth * 3.4f * u + (e.equals("surprised") ? 2.6f * u : 1.4f * u);
            if (open > 1.2f * u) {
                p.setColor(Color.rgb(196, 96, 120));
                cv.drawOval(new RectF(hx - mw, my - open * 0.5f, hx + mw, my + open * 0.5f), p);
                p.setColor(Color.rgb(150, 70, 96));
                cv.drawOval(new RectF(hx - mw * 0.6f, my + open * 0.15f, hx + mw * 0.6f, my + open * 0.42f), p);
            } else {
                Path m = new Path();
                m.moveTo(hx - mw, my);
                m.quadTo(hx, my + 3f * u, hx + mw, my);
                cv.drawPath(m, pStro);
            }
        } else if (e.equals("sad")) {
            Path m = new Path();
            m.moveTo(hx - mw, my + 0.6f * u);
            m.quadTo(hx, my - 1.4f * u, hx + mw, my + 0.6f * u);
            cv.drawPath(m, pStro);
        } else if (e.equals("angry")) {
            pStro.setStrokeWidth(2.2f * u);
            cv.drawLine(hx - mw, my + 0.8f * u, hx + mw, my + 0.8f * u, pStro);
            pStro.setStrokeWidth(1f * u);
        } else if (e.equals("sleepy")) {
            Path m = new Path();
            m.moveTo(hx - mw * 0.8f, my);
            m.quadTo(hx, my + 0.8f * u, hx + mw * 0.8f, my);
            cv.drawPath(m, pStro);
        } else {
            float open = pose.mouth * 3f * u;
            if (open > 0.8f * u) {
                p.setColor(Color.rgb(196, 96, 120));
                cv.drawOval(new RectF(hx - mw * 0.8f, my - open * 0.5f, hx + mw * 0.8f, my + open * 0.5f), p);
            } else {
                Path m = new Path();
                m.moveTo(hx - mw, my);
                m.quadTo(hx, my + 1.8f * u, hx + mw, my);
                cv.drawPath(m, pStro);
            }
        }
        pStro.setStrokeWidth(1f * u);
    }

    // ================= 前发（刘海） =================
    private void drawHairFront(Canvas cv, Pose pose) {
        float hx = cx + tilt * 0.6f * u;
        float hy = cy - 32f * u + bodyY + bounceOff - sitDown * 14f * u + stretchY * 1.5f * u;
        cv.save();
        cv.rotate(headRot, hx, hy);
        p.setShader(new LinearGradient(hx, hy - 20f * u, hx, hy + 8f * u,
                Color.rgb(246, 244, 255), Color.rgb(206, 198, 240), Shader.TileMode.CLAMP));
        Path front = new Path();
        float R = 15f * u;
        front.moveTo(hx - R - 1f * u, hy - 2f * u);
        front.quadTo(hx - R - 2f * u, hy - R - 4f * u, hx, hy - R - 5f * u);
        front.quadTo(hx + R + 2f * u, hy - R - 4f * u, hx + R + 1f * u, hy - 2f * u);
        // 锯齿刘海
        front.quadTo(hx + R * 0.7f, hy - 1f * u, hx + R * 0.55f, hy + 4f * u);
        front.quadTo(hx + R * 0.42f, hy + 1f * u, hx + R * 0.25f, hy + 5f * u);
        front.quadTo(hx + R * 0.12f, hy + 1.5f * u, hx, hy + 5.5f * u);
        front.quadTo(hx - R * 0.12f, hy + 1.5f * u, hx - R * 0.25f, hy + 5f * u);
        front.quadTo(hx - R * 0.42f, hy + 1f * u, hx - R * 0.55f, hy + 4f * u);
        front.quadTo(hx - R * 0.7f, hy - 1f * u, hx - R - 1f * u, hy - 2f * u);
        front.close();
        cv.drawPath(front, p);
        p.setShader(null);
        // 发丝高光
        pStro.setColor(Color.argb(120, 255, 255, 255));
        pStro.setStrokeWidth(1.2f * u);
        Path hl = new Path();
        hl.moveTo(hx - R * 0.6f, hy - R * 0.8f);
        hl.quadTo(hx - R * 0.7f, hy - R * 0.2f, hx - R * 0.55f, hy + 1f * u);
        cv.drawPath(hl, pStro);
        // 两侧鬓发
        Path sideL = new Path();
        sideL.moveTo(hx - R - 1f * u, hy - 3f * u);
        sideL.quadTo(hx - R - 2.5f * u, hy + 6f * u, hx - R - 1.5f * u, hy + 14f * u);
        sideL.quadTo(hx - R - 0.5f * u, hy + 8f * u, hx - R * 0.3f, hy + 2f * u);
        sideL.close();
        Path sideR = new Path();
        sideR.moveTo(hx + R + 1f * u, hy - 3f * u);
        sideR.quadTo(hx + R + 2.5f * u, hy + 6f * u, hx + R + 1.5f * u, hy + 14f * u);
        sideR.quadTo(hx + R + 0.5f * u, hy + 8f * u, hx + R * 0.3f, hy + 2f * u);
        sideR.close();
        p.setColor(Color.rgb(226, 220, 246));
        cv.drawPath(sideL, p);
        cv.drawPath(sideR, p);
        cv.restore();
    }
}
