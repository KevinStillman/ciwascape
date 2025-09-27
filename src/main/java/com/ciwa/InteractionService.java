package com.ciwa;

import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.util.Text;

public class InteractionService
{
    private final Client client;
    private final QuestEngine engine;
    private final CiwaScapeConfig cfg;

    public InteractionService(Client client, QuestEngine engine, CiwaScapeConfig cfg) {
        this.client = client; this.engine = engine; this.cfg = cfg;
    }

    private static boolean isNpcAction(int type)
    {
        MenuAction a = MenuAction.of(type);
        switch (a) {
            case NPC_FIRST_OPTION: case NPC_SECOND_OPTION: case NPC_THIRD_OPTION:
            case NPC_FOURTH_OPTION: case NPC_FIFTH_OPTION:
            case EXAMINE_NPC: case WIDGET_TARGET_ON_NPC: case ITEM_USE_ON_NPC:
                return true;
            default: return false;
        }
    }

    public void onMenuEntryAdded(MenuEntryAdded e)
    {
        if (!cfg.enableInquire()) return;
        if (!isNpcAction(e.getType())) return;

        int idx = e.getIdentifier();
        NPC npc = npcByIndex(idx);
        if (npc == null) return;

        if (!engine.anyInterestedIn(npc)) return;

        for (MenuEntry me : client.getMenuEntries()) {
            if ("Inquire".equalsIgnoreCase(Text.removeTags(me.getOption()))) return;
        }

        client.createMenuEntry(0)
                .setOption("Inquire")
                .setTarget(e.getTarget())
                .setType(MenuAction.RUNELITE)
                .onClick(ev -> engine.handleInquire(npc));
    }

    public void onMenuOptionClicked(MenuOptionClicked e) { /* no-op, using onClick above */ }

    private NPC npcByIndex(Integer idx)
    {
        if (idx == null || client.getNpcs() == null) return null;
        for (NPC n : client.getNpcs()) if (n != null && n.getIndex() == idx) return n;
        return null;
    }
}
