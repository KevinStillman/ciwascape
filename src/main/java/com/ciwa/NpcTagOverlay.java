package com.ciwa;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.OverlayUtil;

public class NpcTagOverlay extends Overlay
{
    @Inject private Client client;

    // Not injected; plugin sets this after constructing QuestEngine
    private QuestEngine questEngine;

    public void setQuestEngine(QuestEngine engine) {
        this.questEngine = engine;
    }

    @Inject
    public NpcTagOverlay() {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(OverlayPriority.MED);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (questEngine == null || client.getNpcs() == null) return null;

        for (NPC npc : client.getNpcs())
        {
            if (npc == null) continue;
            String label = questEngine.overlayLabelFor(npc);
            if (label == null) continue;

            LocalPoint lp = npc.getLocalLocation();
            if (lp == null) continue;

            var p = Perspective.localToCanvas(client, lp, client.getPlane(), npc.getLogicalHeight() + 20);
            if (p == null) continue;

            OverlayUtil.renderTextLocation(g, p, label, Color.CYAN);
        }
        return null;
    }
}
