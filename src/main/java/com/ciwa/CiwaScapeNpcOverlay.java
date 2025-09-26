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

public class CiwaScapeNpcOverlay extends Overlay
{
    @Inject private Client client;
    private final CiwaScapePlugin plugin;

    @Inject
    public CiwaScapeNpcOverlay(CiwaScapePlugin plugin)
    {
        this.plugin = plugin;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(OverlayPriority.MED);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (client.getNpcs() == null) return null;

        for (NPC npc : client.getNpcs())
        {
            if (npc == null) continue;

            String assigned = plugin.getAssignedName(npc);
            if (assigned == null) continue;

            LocalPoint lp = npc.getLocalLocation();
            if (lp == null) continue;

            var textLoc = Perspective.localToCanvas(
                    plugin.getClient(), lp, plugin.getClient().getPlane(), npc.getLogicalHeight() + 20);
            if (textLoc == null) continue;

            OverlayUtil.renderTextLocation(g, textLoc, assigned, Color.CYAN);
        }
        return null;
    }
}
