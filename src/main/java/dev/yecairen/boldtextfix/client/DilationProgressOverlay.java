package dev.yecairen.boldtextfix.client;

import dev.yecairen.boldtextfix.BoldTextFixConfig;
import dev.yecairen.boldtextfix.DilationRenderQueue;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public final class DilationProgressOverlay {
    private DilationProgressOverlay() {
    }

    public static boolean canDisplay(Minecraft client) {
        return BoldTextFixConfig.isEnabled() && BoldTextFixConfig.dilationNotifications() && client.font != null
                && client.getWindow().getGuiScaledWidth() >= 48;
    }

    public static void extract(Minecraft client, GuiRenderState state) {
        if (!canDisplay(client)) {
            return;
        }
        var progress = DilationRenderQueue.INSTANCE.snapshot();
        if (progress == null) {
            return;
        }
        var graphics = new GuiGraphicsExtractor(client, state, 0, 0);
        int wrapWidth = Math.min(300, graphics.guiWidth() - 16) - 16;
        List<FormattedCharSequence> lines = new ArrayList<>();
        Component detail = Component.translatable("notification.boldtextfix.dilation.summary", progress.total(),
                progress.completed(), DilationRenderQueue.duration(progress.elapsedNanos()));
        lines.addAll(client.font.split(detail, wrapWidth));
        if (progress.failed() > 0) {
            lines.addAll(client.font.split(Component.translatable("notification.boldtextfix.dilation.errors",
                    progress.failed()), wrapWidth));
        }
        int lineHeight = client.font.lineHeight + 2;
        // Reserve the countdown space in both states so completion does not move the text.
        int height = 21 + lines.size() * lineHeight;
        int textWidth = lines.stream().mapToInt(client.font::width).max().orElse(0);
        Bounds bounds = bounds(graphics.guiWidth(), graphics.guiHeight(), textWidth, height,
                BoldTextFixConfig.notificationCorner());
        int left = bounds.left();
        int top = bounds.top();
        int width = bounds.width();
        // A fresh stratum after screens, tooltips and toasts also isolates scissor/pose state.
        graphics.nextStratum();
        graphics.fill(left, top, left + width, top + height, 0xF01D2024);
        int accent = progress.failed() == 0 ? 0xFF78DBA9 : 0xFFFF8888;
        graphics.fill(left, top, left + 2, top + height, accent);
        int y = top + 8;
        for (FormattedCharSequence line : lines) {
            graphics.text(client.font, line, left + 8, y, 0xFFF4F4F4, false);
            y += lineHeight;
        }
        int barY = top + height - 7;
        graphics.fill(left + 8, barY, left + width - 8, barY + 2, 0xFF51575E);
        graphics.fill(left + 8, barY, left + 8 + Math.round((width - 16) * progress.remaining()), barY + 2, accent);
        DilationRenderQueue.INSTANCE.markPresented();
    }

    private static Bounds bounds(int screenWidth, int screenHeight, int textWidth, int height,
            BoldTextFixConfig.NotificationCorner corner) {
        int width = Math.min(screenWidth - 16, Math.max(32, textWidth + 16));
        int left = corner.isRight() ? screenWidth - width - 8 : 8;
        int top = corner.isBottom() ? Math.max(8, screenHeight - height - 8) : 8;
        return new Bounds(left, top, width);
    }

    private record Bounds(int left, int top, int width) {
    }
}
