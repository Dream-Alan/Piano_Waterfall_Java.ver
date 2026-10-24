package com.piano.waterfall.render;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 触键瞬间的霓虹粒子：短促爆发 + 长音持续微光。
 *
 * <p>粒子使用 ADD 混合模式叠加，重叠处会自然变亮。</p>
 */
public class ParticleSystem {

    private static final double GRAVITY = 190.0;
    private static final int MAX_PARTICLES = 1800;

    private static class Particle {
        double x;
        double y;
        double vx;
        double vy;
        final double size;
        double life;
        final double decay;
        final Color color;

        Particle(double x, double y, double vx, double vy,
                 double size, double decay, Color color) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.size = size;
            this.life = 1.0;
            this.decay = decay;
            this.color = color;
        }
    }

    private final List<Particle> particles = new ArrayList<>(MAX_PARTICLES);

    /** 触键瞬间的强力爆发飞溅。 */
    public void emitBurst(double centerX, double hitLineY, double keyWidth, Color baseColor) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int count = random.nextInt(10, 16);
        for (int i = 0; i < count; i++) {
            double spawnX = centerX + random.nextDouble(-keyWidth * 0.4, keyWidth * 0.4);
            double angle = Math.toRadians(random.nextDouble(-145, -35));
            double speed = random.nextDouble(65, 220);
            double vx = Math.cos(angle) * speed;
            double vy = Math.sin(angle) * speed;
            double size = random.nextDouble(2.5, 6.2);
            double decay = random.nextDouble(1.2, 2.4);
            Color particleColor = baseColor.deriveColor(
                    random.nextDouble(-10, 10), 0.9, 1.0, 1.0);
            add(new Particle(spawnX, hitLineY, vx, vy, size, decay, particleColor));
        }
    }

    /** 长音按住期间的持续微光火星。 */
    public void emitContinuous(double centerX, double hitLineY, double keyWidth, Color baseColor) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextDouble() > 0.45) {
            return;
        }
        double spawnX = centerX + random.nextDouble(-keyWidth * 0.45, keyWidth * 0.45);
        double vx = random.nextDouble(-35, 35);
        double vy = random.nextDouble(-115, -45);
        double size = random.nextDouble(1.8, 4.2);
        double decay = random.nextDouble(1.5, 2.8);
        add(new Particle(spawnX, hitLineY, vx, vy, size, decay, baseColor));
    }

    private void add(Particle particle) {
        if (particles.size() >= MAX_PARTICLES) {
            particles.remove(0);
        }
        particles.add(particle);
    }

    /** 更新物理状态并渲染所有存活粒子。 */
    public void updateAndRender(GraphicsContext gc, double dt) {
        if (particles.isEmpty()) {
            return;
        }
        gc.save();
        gc.setGlobalBlendMode(BlendMode.ADD);
        Iterator<Particle> iterator = particles.iterator();
        while (iterator.hasNext()) {
            Particle p = iterator.next();
            p.life -= p.decay * dt;
            if (p.life <= 0) {
                iterator.remove();
                continue;
            }

            p.vx *= 0.985;
            p.vy += GRAVITY * dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;

            double currentSize = p.size * p.life;
            double alpha = Math.max(0.0, Math.min(1.0, p.life));

            gc.setFill(p.color.deriveColor(0, 1.0, 1.0, alpha * 0.65));
            gc.fillOval(p.x - currentSize, p.y - currentSize,
                    currentSize * 2, currentSize * 2);

            gc.setFill(Color.WHITE.deriveColor(0, 1.0, 1.0, alpha * 0.9));
            double coreSize = currentSize * 0.45;
            gc.fillOval(p.x - coreSize, p.y - coreSize, coreSize * 2, coreSize * 2);
        }
        gc.setGlobalBlendMode(BlendMode.SRC_OVER);
        gc.restore();
    }

    public void clear() {
        particles.clear();
    }
}
