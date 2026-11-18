package dev.aether.module.impl;

import dev.aether.module.*;
import dev.aether.render.EspRenderer;
import dev.aether.setting.*;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Util;
import net.minecraft.util.math.*;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.chunk.WorldChunk;
import java.util.ArrayList;
import java.util.List;

public abstract class BlockEspModule extends ClientModule {
    record Target(Box box, int rgb) {}

    protected ModeSetting renderMode, colorMode;
    protected NumberSetting lineWidth, outlineOpacity, fillOpacity, range, inflate, speed, scanInterval;
    protected BoolSetting throughWalls, fadeWithDistance;

    private volatile List<Target> targets = List.of();
    private int tick;

    protected BlockEspModule(String n, String d) { super(n, d, Category.RENDER); }

    /** Call at the end of the subclass constructor so these show below the module's own settings. */
    protected void addCommonSettings() {
        renderMode       = add(new ModeSetting("Render Mode", "Both", "Outline", "Fill"));
        outlineOpacity   = add(new NumberSetting("Outline Opacity", 100, 0, 100, 1));
        lineWidth        = add(new NumberSetting("Line Width", 2, 1, 6, 0.5));
        fillOpacity      = add(new NumberSetting("Fill Opacity", 25, 0, 100, 1));
        colorMode        = add(new ModeSetting("Color Mode", "Custom", "Rainbow", "Pulse", "Distance"));
        speed            = add(new NumberSetting("Effect Speed", 1, 0.1, 5, 0.1));
        throughWalls     = add(new BoolSetting("Through Walls", true));
        fadeWithDistance = add(new BoolSetting("Fade With Distance", false));
        range            = add(new NumberSetting("Range", 128, 16, 512, 8));
        inflate          = add(new NumberSetting("Box Inflate", 0.0, -0.2, 0.3, 0.01));
        scanInterval     = add(new NumberSetting("Scan Interval", 10, 1, 40, 1));

        outlineOpacity.visibleWhen(() -> !renderMode.is("Fill"));
        lineWidth.visibleWhen(() -> !renderMode.is("Fill"));
        fillOpacity.visibleWhen(() -> !renderMode.is("Outline"));
        speed.visibleWhen(() -> colorMode.is("Rainbow") || colorMode.is("Pulse"));
    }

    /** @return base RGB if this block entity should be highlighted, otherwise null. */
    protected abstract Integer classify(BlockEntity be);

    @Override public void onDisable() { targets = List.of(); tick = 0; }

    @Override public void onTick(MinecraftClient mc) {
        if (tick++ % scanInterval.i() != 0) return;
        ClientWorld w = mc.world;
        ChunkPos pc = mc.player.getChunkPos();
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        double r = range.get(), r2 = r * r;
        int cr = (int) Math.ceil(r / 16.0);
        List<Target> out = new ArrayList<>();

        for (int dx = -cr; dx <= cr; dx++) for (int dz = -cr; dz <= cr; dz++) {
            WorldChunk c = w.getChunkManager().getWorldChunk(pc.x + dx, pc.z + dz);
            if (c == null) continue;
            for (BlockEntity be : c.getBlockEntities().values()) {
                if (be.isRemoved()) continue;
                BlockPos p = be.getPos();
                if (p.getSquaredDistance(px, py, pz) > r2) continue;
                Integer rgb = classify(be);
                if (rgb == null) continue;
                VoxelShape s = be.getCachedState().getOutlineShape(w, p);
                Box b = (s.isEmpty() ? new Box(p) : s.getBoundingBox().offset(p)).expand(inflate.get());
                out.add(new Target(b, rgb));
            }
        }
        targets = out;
    }

    @Override public void onRender(WorldRenderContext ctx, MinecraftClient mc) {
        List<Target> list = targets;
        if (list.isEmpty()) return;

        Vec3d cam = ctx.worldState().cameraRenderState.pos;
        MatrixStack m = ctx.matrices();
        VertexConsumerProvider buf = ctx.consumers();

        boolean outline = !renderMode.is("Fill"), fill = !renderMode.is("Outline");
        long t = Util.getMeasuringTimeMs();
        double r = range.get();

        // Resolve colors once, then draw all fills, then all outlines (fewer buffer switches).
        List<EspRenderer.Item> items = new ArrayList<>();
        for (Target tg : list) {
            Vec3d c = tg.box.getCenter();
            double d = Math.sqrt(c.squaredDistanceTo(cam));
            if (d > r) continue;

            int rgb = tg.rgb;
            float mul = 1f;
            switch (colorMode.get()) {
                case "Rainbow" -> rgb = MathHelper.hsvToRgb(
                        (float) (((t * 0.0002 * speed.get()) + (c.x + c.z) * 0.01) % 1.0), 0.8f, 1f);
                case "Pulse" -> mul = (float) (0.35 + 0.65 * (0.5 + 0.5 * Math.sin(t * 0.004 * speed.get())));
                case "Distance" -> rgb = MathHelper.hsvToRgb((float) (0.33 * (1 - d / r)), 0.9f, 1f);
                default -> {}
            }
            if (fadeWithDistance.get()) mul *= (float) (1 - 0.75 * d / r);

            int oa = Math.round(outlineOpacity.f() / 100f * 255f * mul);
            int fa = Math.round(fillOpacity.f() / 100f * 255f * mul);
            items.add(new EspRenderer.Item(tg.box, rgb & 0xFFFFFF, oa, fa));
        }

        m.push();
        m.translate(-cam.x, -cam.y, -cam.z);
        if (fill) EspRenderer.fills(m, buf, items, throughWalls.get());
        if (outline) EspRenderer.outlines(m, buf, items, throughWalls.get(), lineWidth.f());
        m.pop();
    }
}
